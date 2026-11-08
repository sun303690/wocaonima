package dev.sun.wechat.features.items.chat

import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.utils.AudioUtils
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 语音识别（ASR）：把微信语音消息转成文字，喂给 AI 回复。
 *
 * 微信自己的「转文字」是可选、而且常常不可用（对方没开、服务端不给、只活在内存 bean 里），
 * 所以这里自带一条 OpenAI 兼容的识别通道：`POST {baseUrl}/audio/transcriptions`（multipart，
 * 字段 model + file，返回 {"text": "..."}）。硅基流动 SenseVoice、OpenAI whisper、Groq、
 * 各类中转站都是这个形状，填地址 + Key + 模型名即可。
 *
 * 流程：微信语音是 SILK → `AudioUtils.silkToPcm` → `AudioUtils.pcmToMp3` → 上传 → 文本。
 * 结果按 encPath 缓存在 cacheDir/asr 下，同一条语音不会重复花钱。
 */
object AsrEngine {

    private const val TAG = "AsrEngine"
    private const val DEFAULT_MODEL = "whisper-1"

    /** 设置面板里的总开关：关掉就不调接口（微信自带转写照旧可用）。 */
    var enabled by prefOption("ai_reply_asr_enabled", true)

    /** 接口根地址，例如 https://api.siliconflow.cn/v1 ；也接受直接填完整的 .../audio/transcriptions。 */
    var baseUrl by prefOption("asr_base_url", "")
    var apiKey by prefOption("asr_api_key", "")
    var model by prefOption("asr_model", DEFAULT_MODEL)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 是否具备自动识别能力（开关打开且地址、Key 都填了）。 */
    fun ready(): Boolean = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()

    /**
     * 识别一条语音消息，回调在后台线程。成功回 (文本, null)，失败回 (null, 原因)。
     */
    fun transcribeMessage(message: MessageInfo, cb: (String?, String?) -> Unit) {
        if (message.type?.code != MessageType.VOICE.code) {
            cb(null, "不是语音消息"); return
        }
        if (!ready()) {
            cb(null, "未配置语音识别接口"); return
        }
        val encPath = message.imagePath
        if (encPath.isNullOrBlank()) {
            cb(null, "拿不到语音文件路径"); return
        }
        thread(name = "wekit-asr") {
            runCatching { transcribeEncPath(encPath) }
                .onSuccess { cb(it, null) }
                .onFailure { cb(null, it.message ?: "识别失败") }
        }
    }

    /**
     * 同步识别一个微信语音 encPath（阻塞，调用方自己放 IO 线程）。
     * @return 识别文本；失败抛异常。
     */
    fun transcribeEncPath(encPath: String): String {
        val key = cacheKey(encPath)
        val dir = File(HostInfo.application.cacheDir, "asr").apply { mkdirs() }
        val textCache = File(dir, "$key.txt")
        textCache.takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val silkPath = WeMessageApi.getVoiceFullPath(encPath)
        if (silkPath.isBlank()) throw IllegalStateException("语音文件还没下载")
        val pcm = File(dir, "$key.pcm")
        if (!AudioUtils.silkToPcm(silkPath, pcm.absolutePath)) {
            throw IllegalStateException("SILK 转 PCM 失败")
        }
        val mp3 = File(dir, "$key.mp3")
        val ok = AudioUtils.pcmToMp3(pcm.absolutePath, mp3.absolutePath) &&
            mp3.isFile && mp3.length() > 0
        pcm.delete()
        if (!ok) throw IllegalStateException("PCM 转 MP3 失败")

        val text = request(mp3)
        mp3.delete()
        if (text.isEmpty()) throw IllegalStateException("接口没返回文字")
        runCatching { textCache.writeText(text) }
        WeLogger.i(TAG, "识别成功 key=$key len=${text.length}")
        return text
    }

    private fun request(file: File): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model.trim().ifBlank { DEFAULT_MODEL })
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(endpoint())
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(form)
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            WeLogger.w(TAG, "ASR 请求失败：${it.message}")
            throw IllegalStateException("网络失败：${it.message}")
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException("接口返回 ${it.code}：${body.take(160)}")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
            // OpenAI 形状是 {"text": "..."}；个别中转站包了一层 {"data": {...}}
            return json.optString("text").trim()
                .ifEmpty { json.optJSONObject("data")?.optString("text").orEmpty().trim() }
        }
    }

    /** 允许用户直接填完整端点；否则拼上标准路径。 */
    private fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
    }

    /** 同一条语音（encPath 相同）只识别一次，缓存文件名用它的哈希。 */
    private fun cacheKey(encPath: String): String =
        Integer.toHexString(encPath.hashCode())

    /** 清掉所有识别缓存（设置里「清空识别缓存」用）。 */
    fun clearCache() {
        runCatching { File(HostInfo.application.cacheDir, "asr").deleteRecursively() }
    }
}
