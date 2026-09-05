package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.MultiEngineTtsClient
import dev.ujhhgtg.wekit.utils.TiaxTtsClient
import dev.ujhhgtg.wekit.utils.TtsEngine
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.toPath
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 智能语音助手：DeepSeek AI 自动回复（触发词 + 对话记忆 + 仅语音回复）。
 *
 * 收到以触发词（默认 `*`）开头的消息 → 调用 DeepSeek 对话接口 → 用多引擎 TTS
 * 合成语音 → 转 silk 发送回聊天窗口。
 *
 * 设置面板为卡片式（API Key / 模型 / 触发词 / 人设 / 记忆轮数 / 各引擎音色）。
 */
object AiVoiceAssistant : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "AI语音助手"
    override val nameRes = R.string.feature_ai_voice_assistant_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_voice_assistant_description

    private const val TAG = "AiVoiceAssistant"

    override val noSwitchWidget = true

    // ---- 配置（deepseek + 触发词 + 记忆） ----
    private var apiUrl by WePrefs.prefOption("aivoice_api_url", "https://api.deepseek.com")
    private var apiKey by WePrefs.prefOption("aivoice_api_key", "")
    private var model by WePrefs.prefOption("aivoice_model", "deepseek-chat")
    private var prompt by WePrefs.prefOption("aivoice_prompt", "你是一个乐于助人的AI助手")
    private var enabled by WePrefs.prefOption("aivoice_enabled", false)
    private var triggerWord by WePrefs.prefOption("aivoice_trigger", "*")
    private var memoryRounds by WePrefs.prefOption("aivoice_memory_rounds", 5)
    private var voiceOnly by WePrefs.prefOption("aivoice_voice_only", true)

    // ---- TTS 引擎配置 ----
    var engine by WePrefs.prefOption("aivoice_engine", "fishaudio")
    var fishKey by WePrefs.prefOption("aivoice_fish_key", "")
    var fishVoice by WePrefs.prefOption("aivoice_fish_voice", "")
    var yxKey by WePrefs.prefOption("aivoice_yx_key", "")
    var yxVoice by WePrefs.prefOption("aivoice_yx_voice", "")
    var bvKey by WePrefs.prefOption("aivoice_bv_key", "")
    var bvVoice by WePrefs.prefOption("aivoice_bv_voice", "")
    var vocuKey by WePrefs.prefOption("aivoice_vocu_key", "")
    var vocuVoice by WePrefs.prefOption("aivoice_vocu_voice", "")
    var tiaxKey by WePrefs.prefOption("aivoice_tiax_key", "")
    var tiaxVoice by WePrefs.prefOption("aivoice_tiax_voice", "")

    // ---- 对话记忆：talker -> 消息列表(JSON) ----
    private val memories = ConcurrentHashMap<String, MutableList<JSONObject>>()

    // ---- 协程 ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient by lazy { OkHttpClient() }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        loadMemories()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memories.clear()
    }

    override fun onClick(context: ComponentActivity) {
        // 设置面板（卡片式），在 Compose 中实现
        dev.ujhhgtg.wekit.features.items.chat.AiVoiceSettingsDialog.show(context)
    }

    // ================= 消息监听：触发 AI 回复 =================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (!enabled) return
        if (apiKey.isBlank()) return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0) return
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return

        val tg = triggerWord.ifBlank { "*" }
        // 兼容群聊 "昵称:\n内容" 前缀
        val clean = if (content.contains(":\n")) {
            val idx = content.indexOf(":\n")
            if (idx in 1..50) content.substring(idx + 2).trim() else content.trim()
        } else content.trim()
        if (!clean.startsWith(tg)) return

        val question = clean.substring(tg.length).trim().ifEmpty { clean }
        scope.launch {
            handleAiReply(talker, question)
        }
    }

    private suspend fun handleAiReply(talker: String, question: String) {
        try {
            val reply = askDeepSeek(talker, question)
            if (reply.startsWith("[")) {
                // 错误
                showToastSuspend(reply)
                return
            }
            if (voiceOnly) {
                sendAiVoice(talker, reply)
            } else {
                WeMessageApi.sendText(talker, reply)
            }
        } catch (error: Exception) {
            WeLogger.e(TAG, "AI reply failed", error)
            showToastSuspend("AI处理异常: ${error.message}")
        }
    }

    // ================= DeepSeek 对话 =================

    private suspend fun askDeepSeek(talker: String, question: String): String =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl.trimEnd('/') + "/v1/chat/completions"
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 2000)
                    put("messages", buildMessages(talker, question))
                }
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(jsonMedia))
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        return@use "[err:${resp.code}] $err"
                    }
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val choices = json.optJSONArray("choices")
                    val reply = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    if (reply.isBlank()) "[err:空回复]" else {
                        rememberMessage(talker, "user", question)
                        rememberMessage(talker, "assistant", reply)
                        reply
                    }
                }
            } catch (error: Exception) {
                "[fail:${error.message}]"
            }
        }

    private fun buildMessages(talker: String, question: String): JSONArray {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", prompt))
        memories[talker]?.takeLast(memoryRounds.coerceIn(1, 999) * 2)?.forEach { msgs.put(it) }
        msgs.put(JSONObject().put("role", "user").put("content", question))
        return msgs
    }

    private fun rememberMessage(talker: String, role: String, content: String) {
        val list = memories.getOrPut(talker) { mutableListOf() }
        list.add(JSONObject().put("role", role).put("content", content))
        val max = memoryRounds.coerceIn(1, 999) * 2
        while (list.size > max) list.removeAt(0)
        saveMemories()
    }

    // ================= 语音发送 =================

    private suspend fun sendAiVoice(talker: String, text: String) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // 1. 用多引擎 TTS 合成 mp3
                val mp3 = synthesizeMp3(text) ?: return@runCatching null
                // 2. mp3 -> silk
                val silk = File(File(mp3).parentFile, "ai_${System.nanoTime()}.silk").absolutePath
                if (!AudioUtils.anyToSilk(mp3, silk)) return@runCatching null
                // 3. 时长
                val duration = AudioUtils.getDurationMs(silk).toInt()
                // 4. 发送
                WeMessageApi.sendVoice(talker, silk, duration)
                silk
            }.getOrNull()
        }
        if (result != null) {
            showToastSuspend("AI语音已回复")
        } else {
            showToastSuspend("AI语音合成失败，请检查引擎配置")
        }
    }

    /** 按当前引擎配置合成语音，返回 mp3 路径。 */
    private suspend fun synthesizeMp3(text: String): String? {
        val cacheDir = dev.ujhhgtg.wekit.utils.HostInfo.application.cacheDir
        val out = java.io.File(cacheDir, "ai_voice_${System.nanoTime()}.mp3").toPath()
        val result = when (engine) {
            "fishaudio" -> MultiEngineTtsClient.synthesizeToMp3(
                TtsEngine.FISH_AUDIO, text, out, fishVoice, fishKey)
            "yx520" -> MultiEngineTtsClient.synthesizeToMp3(
                TtsEngine.YX520, text, out, yxVoice, yxKey)
            "bv" -> MultiEngineTtsClient.synthesizeToMp3(
                TtsEngine.BYTE_DANCE, text, out, bvVoice, bvKey)
            "vocu" -> MultiEngineTtsClient.synthesizeToMp3(
                TtsEngine.VOCU, text, out, vocuVoice, vocuKey)
            "tiax" -> TiaxTtsClient.synthesizeToMp3(
                text, out, tiaxVoice.toIntOrNull() ?: 0, tiaxKey)
            else -> null
        }
        return result.getOrNull()?.toString()
    }

    // ================= 记忆持久化 =================

    private fun memoryFile(): File {
        val dir = File(dev.ujhhgtg.wekit.utils.HostInfo.application.cacheDir, "aivoice")
        return File(dir, "ai_memory.json")
    }

    private fun loadMemories() {
        runCatching {
            val f = memoryFile()
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            root.keys().forEach { tk ->
                val arr = root.optJSONArray(tk) ?: return@forEach
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(it) }
                }
                if (list.isNotEmpty()) memories[tk] = list
            }
        }.onFailure { WeLogger.e(TAG, "load memory failed", it) }
    }

    private fun saveMemories() {
        runCatching {
            val root = JSONObject()
            memories.forEach { (tk, list) ->
                if (list.isNotEmpty()) root.put(tk, JSONArray(list))
            }
            val f = memoryFile()
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
        }.onFailure { WeLogger.e(TAG, "save memory failed", it) }
    }

    fun clearAllMemories() {
        memories.clear()
        memoryFile().delete()
    }

    fun clearTalkerMemory(talker: String) {
        memories.remove(talker)
        saveMemories()
    }
}
