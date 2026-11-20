package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.sun.wechat.ui.content.miuix.MiuixAlertDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Save
import dev.sun.wechat.R
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.items.multimodal.MediaVision
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.multimodal.VisionClient
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.MultimodalFeature
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.data.WeKitDatabase
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixIntNumberPickerWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import kotlin.math.roundToInt
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import androidx.lifecycle.lifecycleScope
import dev.sun.wechat.ui.utils.showComposeDialog
import kotlin.time.Duration.Companion.milliseconds

/**
 * AI 聊天（移植 Nuke 的 `ai_chat` 模块）：让 AI 对名单内收到的每条文字消息进行连续对话回复。
 *
 * 与 Nuke 对齐的行为（逆向自 `a0.G(q42, long)`）：
 *  - 只处理收到的纯文本消息；`会话名单模式` 决定白名单(只回选中)或黑名单(跳过选中)
 *  - 回复前先等 `回复延迟`；等待后与生成后各校验一次配置版本号，配置变了就丢弃这次回复
 *    （对应 Nuke 日志 "AI reply discarded because configuration changed"）
 *  - 每个会话独立维护上下文，按 `上下文轮数` 取最近 N 轮（一轮 = 一问一答，即 2N 条消息）
 *  - 群聊消息带 `[WeChat sender: 昵称]\n` 前缀，让模型知道是谁在说话
 *  - 接口/模型走 WeKit 自己的 WeAgent 模型库（Nuke 是自带的 Base URL + API Key）
 */
object AiChat : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "AI聊天"
    override val nameRes = R.string.feature_ai_chat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_chat_description

    private const val TAG = "AiChat"

    private const val MAX_CONTEXT_ROUNDS = 20
    private const val MAX_OUTPUT_TOKENS = 32768
    /** 一次历史上下文里最多现场补几条语音转写（每条都要调一次语音识别接口，慢且花钱）。 */
    private const val MAX_LIVE_VOICE_TRANSCRIPTS = 1

    /** 一轮上下文里最多现场识别几条媒体；其余先放占位符，等下一轮命中缓存再补全。窗口只有 10 条，所以不会超过 10。 */
    private const val MAX_LIVE_MEDIA_DESCRIPTIONS = 8

    private const val MEDIA_PROMPT_IMAGE = "请用中文一句话概括这张图片的内容，只输出概括结果。"
    private const val MEDIA_PROMPT_STICKER = "请用中文一句话说明这个表情包表达的意思，只输出概括结果。"
    private const val MEDIA_PROMPT_ANIMATION = "请用中文一句话概括这个动图的内容，只输出概括结果。"
    private const val MEDIA_PROMPT_VIDEO = "请用中文一句话概括这个视频的内容，只输出概括结果。"

    /** 媒体识别结果缓存，key = msgSvrId-typeCode；命中就不再请求，省钱也省时间。 */
    private val mediaDescriptions = ConcurrentHashMap<String, String>()

    private fun mediaCacheDir(): File = File(HostInfo.application.cacheDir, "ai-chat-media").apply { mkdirs() }
    /** 现场补语音转写时的最长等待，超时先用「[语音]」占位，识别结果仍会进缓存。 */
    private const val VOICE_WAIT_MS = 8_000L
    private const val MAX_REPLY_DELAY_MS = 60_000L

    private const val LIST_WHITELIST = 0
    private const val LIST_BLACKLIST = 1
    private const val GROUP_TRIGGER_ALL = 0
    private const val GROUP_TRIGGER_MENTION = 1
    private const val GROUP_TRIGGER_KEYWORD = 2
    private const val GROUP_TRIGGER_PREFIX = 3

    // ---- 配置（键名与 Nuke 的 AIChatConfig 字段一一对应） ----
    internal var systemPrompt by KvStore.prefOption("ai_chat_system_prompt", "")
    var temperature by KvStore.prefOption("ai_chat_temperature", 0.7f)
    var maxTokens by KvStore.prefOption("ai_chat_max_tokens", 512)
    var contextRounds by KvStore.prefOption("ai_chat_context_rounds", 6)
    var replyDelayMs by KvStore.prefOption("ai_chat_reply_delay_ms", 0L)
    internal var listMode by KvStore.prefOption("ai_chat_list_mode", LIST_WHITELIST)
    internal var groupTriggerMode by KvStore.prefOption("ai_chat_group_trigger_mode", GROUP_TRIGGER_MENTION)
    internal var groupKeywords by KvStore.prefOption("ai_chat_group_keywords", "")
    internal var groupPrefix by KvStore.prefOption("ai_chat_group_prefix", "/ai")
    internal var cooldownSeconds by KvStore.prefOption("ai_chat_cooldown_seconds", 15)
    internal var dailyLimit by KvStore.prefOption("ai_chat_daily_limit", 100)
    internal var maxContextChars by KvStore.prefOption("ai_chat_max_context_chars", 12000)
    internal var monitorAuthorizedChats by KvStore.prefOption("ai_chat_monitor_authorized_chats", true)
    internal var monitorOnly by KvStore.prefOption("ai_chat_monitor_only", false)
    internal var historyBackfillCount by KvStore.prefOption("ai_chat_history_backfill_count", 10)

    /** 上下文里的图片/表情/动图/视频是否送视觉模型识别（默认关，按张计费）。 */
    internal var mediaContext by KvStore.prefOption("ai_chat_media_context", false)
    internal var groupMaxReplyChars by KvStore.prefOption("ai_chat_group_max_reply_chars", 500)
    var voiceReplyEnabled by KvStore.prefOption("ai_chat_voice_reply_enabled", false)

    internal var whitelist: Set<String>
        get() = KvStore.getStringSetOrDef("ai_chat_whitelist", emptySet())
        set(value) = KvStore.putStringSet("ai_chat_whitelist", value)

    internal var blacklist: Set<String>
        get() = KvStore.getStringSetOrDef("ai_chat_blacklist", emptySet())
        set(value) = KvStore.putStringSet("ai_chat_blacklist", value)

    /** 配置快照；用于「生成期间配置变了就丢弃」的判定（Nuke 用 AIChatConfig.equals）。 */
    private data class Config(
        val systemPrompt: String,
        val temperature: Float,
        val maxTokens: Int,
        val contextRounds: Int,
        val replyDelayMs: Long,
        val listMode: Int,
        val targets: Set<String>,
        val groupTriggerMode: Int,
        val groupKeywords: Set<String>,
        val groupPrefix: String,
        val cooldownMs: Long,
        val dailyLimit: Int,
        val maxContextChars: Int,
        val monitorAuthorizedChats: Boolean,
        val monitorOnly: Boolean,
        val historyBackfillCount: Int,
        val groupMaxReplyChars: Int,
        val mediaContext: Boolean,
    )

    private fun snapshot(): Config = Config(
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens.coerceIn(1, MAX_OUTPUT_TOKENS),
        contextRounds = contextRounds.coerceIn(0, MAX_CONTEXT_ROUNDS),
        replyDelayMs = replyDelayMs.coerceIn(0L, MAX_REPLY_DELAY_MS),
        listMode = listMode,
        targets = if (listMode == LIST_BLACKLIST) blacklist else whitelist,
        groupTriggerMode = groupTriggerMode,
        groupKeywords = groupKeywords.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        groupPrefix = groupPrefix.trim(),
        cooldownMs = cooldownSeconds.coerceIn(0, 3600) * 1000L,
        dailyLimit = dailyLimit.coerceIn(0, 10000),
        maxContextChars = maxContextChars.coerceIn(1000, 100000),
        monitorAuthorizedChats = monitorAuthorizedChats,
        monitorOnly = monitorOnly,
        historyBackfillCount = 10,
        groupMaxReplyChars = groupMaxReplyChars.coerceIn(100, 4000),
        mediaContext = mediaContext,
    )

    /** 白名单：命中才回；黑名单：命中就跳过（空名单=全部处理）。 */
    private fun Config.allowsTalker(talker: String): Boolean =
        if (listMode == LIST_WHITELIST) targets.contains(talker) else !targets.contains(talker)

    // ---- 运行时 ----
    private val configVersion = AtomicLong(0)
    private val memories = ConcurrentHashMap<String, MutableList<LlmMessage>>()
    private val handledIds = ConcurrentHashMap<String, Boolean>()
    private val lastReplyAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val pendingGroupMessages = ConcurrentHashMap<String, String>()
    private val dailyCounts = ConcurrentHashMap<String, Int>()
    @Volatile private var lastReceiveAt = 0L
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastFailure: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memories.clear()
        lastReplyAt.clear()
        inFlight.clear()
        pendingGroupMessages.clear()
        dailyCounts.clear()
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(android.content.Intent(context, AiChatSettingsActivity::class.java))
    }

    // ---------------- 消息入口 ----------------

    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        val messageType = MessageType.fromCode(type)
        // 语音也放行：对方语音经微信转写后（VoiceTranscriptStore）同样能自动回复
        val isVoice = messageType == MessageType.VOICE
        // 多模态也放行：图片/表情/卡片/链接/文件/视频，经多模态识别转成文字后同样能自动回复
        val isMultimodal = MultimodalFeature.isEnabled && messageType != null && (
            messageType == MessageType.IMAGE ||
                messageType.isSticker ||
                messageType == MessageType.FILE ||
                messageType.isLink ||
                messageType == MessageType.APP ||
                messageType == MessageType.VIDEO ||
                messageType == MessageType.MICRO_VIDEO
            )
        if (messageType?.isText != true && !isVoice && !isMultimodal) return
        val isSend = values.getAsInteger("isSend") ?: return
        if (isSend != 0) return
        val talker = values.getAsString("talker").orEmpty()
        if (talker.isBlank() || talker.startsWith("gh_")) return

        // 微信同一条消息可能插入多次，按 msgSvrId/localId 去重
        val dedupeKey = values.getAsString("msgSvrId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: values.getAsInteger("localId")?.toString()
        if (dedupeKey != null && handledIds.putIfAbsent(dedupeKey, true) != null) return
        pruneHandledIds()

        if (isVoice) {
            // 转写文本只活在微信内存 bean（不落数据库），由消息行绑定（MessageSniffer）
            // 记进 VoiceTranscriptStore。这里延迟等自动转写落地再取；取不到（没开
            // 自动转写/一直没转完）就放弃这条语音，绝不拿 voicemsg 的 XML 当正文瞎回复。
            val localId = values.getAsInteger("localId")?.toLong()
            if (localId == null) return
            scope.launch {
                var transcript: String? = null
                repeat(16) {
                    transcript = VoiceTranscriptStore.get(localId)
                    if (!transcript.isNullOrBlank()) return@repeat
                    delay(500)
                }
                // 微信没给转写文本时，用模块自己接的 ASR 识别一遍（失败就放弃这条语音）
                if (transcript.isNullOrBlank()) {
                    // imgPath 偶发为空，退到 content（有的版本语音名只在 content 里）。
                    val encPath = values.getAsString("imgPath")?.takeIf { it.isNotBlank() }
                        ?: values.getAsString("content")?.takeIf { it.startsWith("msg_") }
                    if (MultimodalFeature.isEnabled && AsrEngine.ready() && !encPath.isNullOrBlank()) {
                        WeLogger.i(TAG, "语音无微信转写，改用 ASR：talker=$talker, localId=$localId")
                        transcript = runCatching {
                            withContext(Dispatchers.IO) { AsrEngine.transcribeEncPath(encPath) }
                        }.getOrElse {
                            WeLogger.w(TAG, "ASR 识别失败：${it.message}")
                            null
                        }
                        transcript?.takeIf { it.isNotBlank() }?.let { VoiceTranscriptStore.put(localId, it) }
                    }
                }
                if (transcript.isNullOrBlank()) {
                    WeLogger.i(TAG, "AI reply skipped: voice without transcript, talker=$talker, localId=$localId")
                    return@launch
                }
                dispatchNewMessage(talker, transcript!!)
            }
            return
        }

        if (isMultimodal) {
            // 图片/表情/卡片/链接/文件/视频：先用多模态识别把消息转成文字，
            // 有缓存/可同步解析（卡片类）就直接分发；首次图片/表情先后台识别，
            // 识别完成拿到文字后再补一条分发（识别失败就放弃这条）。
            val info = runCatching { MessageInfo.fromContentValues(values) }.getOrNull()
            if (info == null) return
            val text = MultimodalEngine.textFor(info)
            if (!text.isNullOrBlank()) {
                dispatchNewMessage(talker, text)
                return
            }
            MultimodalEngine.prefetch(info) { result ->
                if (!result.isNullOrBlank()) {
                    scope.launch { dispatchNewMessage(talker, result) }
                } else {
                    WeLogger.i(TAG, "AI reply skipped: multimodal without text, talker=$talker")
                }
            }
            return
        }

        val content = values.getAsString("content").orEmpty()
        if (content.isBlank()) return
        dispatchNewMessage(talker, content)
    }

    /** 新消息（文字原文或语音转写文本）统一入口：观察/群触发/冷却/限流后交给模型。 */
    private fun dispatchNewMessage(talker: String, content: String) {
        val config = snapshot()
        if (!config.allowsTalker(talker)) return
        val isGroup = isGroupTalker(talker)
        if (config.monitorAuthorizedChats && isGroup) appendObservedMessage(talker, content, isSend = false)
        if (config.monitorOnly && isGroup) return
        val triggeredContent = applyGroupTrigger(talker, content, config) ?: return
        val now = System.currentTimeMillis()
        if (!isGroup && config.cooldownMs > 0 && now - (lastReplyAt[talker] ?: 0L) < config.cooldownMs) return
        if (!isGroup && config.dailyLimit > 0 && (dailyCounts[dailyKey(talker)] ?: 0) >= config.dailyLimit) return
        if (inFlight.putIfAbsent(talker, true) != null) {
            if (isGroup) pendingGroupMessages[talker] = triggeredContent
            return
        }
        lastReceiveAt = now

        scope.launch {
            var next: String? = triggeredContent
            try {
                while (next != null) {
                    processMessage(talker, next, snapshot(), configVersion.get())
                    next = pendingGroupMessages.remove(talker)
                }
            } finally {
                inFlight.remove(talker)
                pendingGroupMessages.remove(talker)?.let { queued -> onQueuedGroupMessage(talker, queued) }
            }
        }
    }

    private fun onQueuedGroupMessage(talker: String, content: String) {
        if (inFlight.putIfAbsent(talker, true) != null) {
            pendingGroupMessages[talker] = content
            return
        }
        scope.launch {
            var next: String? = content
            try {
                while (next != null) {
                    processMessage(talker, next, snapshot(), configVersion.get())
                    next = pendingGroupMessages.remove(talker)
                }
            } finally {
                inFlight.remove(talker)
            }
        }
    }

    private suspend fun processMessage(talker: String, rawContent: String, config: Config, version: Long) {
        runCatching {
            val effectiveDelay = if (isGroupTalker(talker)) 10_000L else config.replyDelayMs
            if (effectiveDelay > 0) delay(effectiveDelay.milliseconds)
            if (configVersion.get() != version) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }

            val (sender, body) = splitGroupSender(talker, rawContent)
            val userText = sender?.let { "[WeChat sender: $it]\n$body" } ?: body

            val history = memories.getOrPut(talker) { mutableListOf() }
            val backfilledCount = refreshRecentGroupContext(talker, history, config)
            val activePersona = personaForTalker(talker)
            val persistentMemories = runCatching {
                WeKitDatabase.instance.aiChatMemoryDao().forPrompt(
                    talker = talker,
                    personaId = activePersona?.id.orEmpty(),
                    isGroup = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"),
                ).filter { activePersona == null || activePersona.shareMemory || it.talker == talker }
            }.getOrElse {
                WeLogger.w(TAG, "failed to load persistent memories for $talker", it)
                emptyList()
            }
            val messages = buildList {
                val memoryPrompt = persistentMemories.asReversed().joinToString("\n") { "- ${it.content}" }
                val effectiveSystemPrompt = buildString {
                    append(resolvePersona(talker, config.systemPrompt))
                    if (isGroupTalker(talker)) {
                        if (isNotEmpty()) append("\n\n")
                        append("Group-chat reply rules: Answer the latest message in its actual conversational context. Do not guess or expand an unclear abbreviation into a named entity. If the meaning is ambiguous, ask one brief clarification question. Reply naturally and concisely in the language used by the group. Avoid headings, numbered analysis, generic background introductions, closing sales questions, and emoji unless explicitly requested. Keep the answer within ${config.groupMaxReplyChars} characters.")
                    }
                    if (memoryPrompt.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Known user facts (use only when relevant):\n")
                        append(memoryPrompt)
                    }
                }
                if (effectiveSystemPrompt.isNotBlank()) add(LlmMessage(LlmRole.SYSTEM, effectiveSystemPrompt))
                if (config.contextRounds > 0) {
                    synchronized(history) {
                        addAll(trimContext(history.takeLast(config.contextRounds * 2), config.maxContextChars))
                    }
                }
                add(LlmMessage(LlmRole.USER, userText))
            }
            val contextMessageCount = messages.count { it.role != LlmRole.SYSTEM } - 1
            val contextCharCount = messages.sumOf { it.content?.length ?: 0 }
            WeLogger.i(
                TAG,
                "AI context prepared: talker=$talker, trigger=${triggerName(config.groupTriggerMode)}, " +
                    "persona=${activePersona?.id ?: "default"}, memories=${persistentMemories.size}, " +
                    "backfilled=$backfilledCount, contextMessages=$contextMessageCount, contextChars=$contextCharCount",
            )

            val reply = requestModel(config, messages)

            // 生成期间配置可能已改（换了名单/模型/轮数），此时不再发出这条回复
            if (configVersion.get() != version) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }
            val latest = snapshot()
            if (latest != config || !latest.allowsTalker(talker)) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }
            if (reply.isFailure) {
                lastFailure = reply.exceptionOrNull()?.message ?: "模型请求失败"
                WeLogger.e(
                    TAG,
                    "AI completion failed: talker=$talker, model=${MultimodalEngine.textModel}, reason=${reply.exceptionOrNull()?.message}",
                )
                return
            }
            val rawText = reply.getOrThrow().trim()
            if (rawText.isEmpty()) return
            val text = if (isGroupTalker(talker)) sanitizeGroupReply(rawText, config.groupMaxReplyChars) else rawText
            val sent = if (voiceReplyEnabled) sendReplyAsVoice(talker, text) else WeMessageApi.sendText(talker, text)
            if (!sent) {
                lastFailure = "微信发送 API 返回失败"
                WeLogger.e(TAG, "failed to send AI reply to talker=$talker")
                return
            }
            synchronized(history) {
                history += LlmMessage(LlmRole.USER, userText)
                history += LlmMessage(LlmRole.ASSISTANT, text)
                val keep = MAX_CONTEXT_ROUNDS * 2
                while (history.size > keep) history.removeAt(0)
            }
            lastSendAt = System.currentTimeMillis()
            lastFailure = null
            lastReplyAt[talker] = lastSendAt
            dailyCounts.merge(dailyKey(talker), 1, Int::plus)
            WeLogger.i(TAG, "AI replied to talker=$talker, replyChars=${text.length}, truncated=${text.length < rawText.length}")
        }.onFailure {
            lastFailure = it.message ?: it.javaClass.simpleName
            WeLogger.e(TAG, "AI chat processing failed", it)
        }
    }

    /** 语音发送：按「文字转语音」里选的后端合成（Breeze/豆包/魔方），转 silk 后发语音。
     *  生成失败或没配好凭据时自动降级为发文字，保证 AI 回复不丢。 */
    private suspend fun sendReplyAsVoice(talker: String, text: String): Boolean {
        val audio = suspendCancellableCoroutine<String?> { cont ->
            TextToSpeech.synthesizeByBackend(text) { path, _ -> cont.resume(path) }
        }
        if (audio.isNullOrEmpty()) return WeMessageApi.sendText(talker, text)
        return suspendCancellableCoroutine { cont ->
            TextToSpeech.sendVoiceTo(talker, audio) { ok -> cont.resume(ok) }
        }
    }

    private fun isGroupTalker(talker: String): Boolean =
        talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")

    private fun appendObservedMessage(talker: String, content: String, isSend: Boolean) {
        if (content.isBlank()) return
        val (sender, body) = splitGroupSender(talker, content)
        val text = when {
            isSend -> "[WeChat sender: self]\n$body"
            sender != null -> "[WeChat sender: $sender]\n$body"
            else -> body
        }
        val history = memories.getOrPut(talker) { mutableListOf() }
        synchronized(history) {
            history += LlmMessage(if (isSend) LlmRole.ASSISTANT else LlmRole.USER, text)
            while (history.size > MAX_CONTEXT_ROUNDS * 4) history.removeAt(0)
        }
    }

    /** 从数据库回填最近 10 条消息做上下文；群聊和私聊都走这里（私聊原来只累积文本框，媒体消息会被丢掉）。 */
    private fun refreshRecentGroupContext(talker: String, history: MutableList<LlmMessage>, config: Config): Int {
        if (!WeDatabaseApi.isReady) return 0
        val rows = runCatching { WeDatabaseApi.getMessages(talker, 1, 10) }
            .getOrElse { WeLogger.w(TAG, "failed to refresh recent group context for $talker", it); emptyList() }
            .asReversed()
            .filter { row ->
                val type = MessageType.fromCode(row.typeCode)
                (type?.isText == true && row.content.isNotBlank()) ||
                    type?.code == MessageType.VOICE.code ||
                    (config.mediaContext && MediaVision.isVisual(type))
            }
            .takeLast(10)
        // 语音进上下文要花钱：缓存没命中时只现场识别 1 条（限时 8 秒），其余先放占位符，下一轮命中缓存再补全。
        var liveBudget = MAX_LIVE_VOICE_TRANSCRIPTS
        var liveMediaBudget = MAX_LIVE_MEDIA_DESCRIPTIONS
        var mediaDescribed = 0
        var transcribed = 0
        synchronized(history) {
            history.clear()
            rows.forEach { message ->
                val type = MessageType.fromCode(message.typeCode)
                if (type?.code == MessageType.VOICE.code) {
                    val encPath = message.imgPath
                    val transcript = when {
                        // 多模态识别总开关管全部：关掉时语音一律不识别，只留占位符
                        !MultimodalFeature.isEnabled -> null
                        encPath == null -> null
                        else -> {
                            val cached = runCatching { AsrEngine.cachedTranscript(encPath) }.getOrNull()
                            if (cached != null) {
                                cached
                            } else if (liveBudget > 0) {
                                liveBudget--
                                runCatching { AsrEngine.transcriptForContext(encPath, VOICE_WAIT_MS) }
                                    .onFailure { WeLogger.w(TAG, "voice transcript for context failed: ${it.message}") }
                                    .getOrNull()
                            } else null
                        }
                    }
                    if (!transcript.isNullOrBlank()) transcribed++
                    val body = if (transcript.isNullOrBlank()) "[语音]" else "[语音] $transcript"
                    val text = if (message.isSend != 0) "[WeChat sender: self]\n$body" else body
                    history += LlmMessage(if (message.isSend != 0) LlmRole.ASSISTANT else LlmRole.USER, text)
                    return@forEach
                }
                if (config.mediaContext && MediaVision.isVisual(type)) {
                    val key = "${message.msgSvrId}-${message.typeCode}"
                    val cached = mediaDescriptions[key]
                    val described = when {
                        cached != null -> cached
                        !MediaVision.available() -> null
                        liveMediaBudget > 0 -> {
                            liveMediaBudget--
                            runCatching {
                                MediaVision.describe(
                                    message,
                                    mediaCacheDir(),
                                    MEDIA_PROMPT_IMAGE,
                                    MEDIA_PROMPT_STICKER,
                                    MEDIA_PROMPT_ANIMATION,
                                    MEDIA_PROMPT_VIDEO,
                                )
                            }
                                .onFailure { WeLogger.w(TAG, "media describe for context failed: ${it.message}") }
                                .getOrNull()
                                ?.also { mediaDescriptions[key] = it }
                        }
                        else -> null
                    }
                    if (!described.isNullOrBlank()) mediaDescribed++
                    val label = when {
                        type?.isSticker == true -> "表情"
                        type?.code == MessageType.VIDEO.code -> "视频"
                        else -> "图片"
                    }
                    val body = if (described.isNullOrBlank()) "[$label]" else "[$label 分析] $described"
                    // 群聊里媒体消息的 content 同样带 "wxid:\n" 前缀，按文本分支的规则标出发送者，模型才知道是谁发的图。
                    val (mediaSender, _) = splitGroupSender(talker, message.content)
                    val text = when {
                        message.isSend != 0 -> "[WeChat sender: self]\n$body"
                        mediaSender != null -> "[WeChat sender: $mediaSender]\n$body"
                        else -> body
                    }
                    history += LlmMessage(if (message.isSend != 0) LlmRole.ASSISTANT else LlmRole.USER, text)
                    return@forEach
                }
                val (sender, body) = splitGroupSender(talker, message.content)
                val text = when {
                    message.isSend != 0 -> "[WeChat sender: self]\n$body"
                    sender != null -> "[WeChat sender: $sender]\n$body"
                    else -> body
                }
                history += LlmMessage(if (message.isSend != 0) LlmRole.ASSISTANT else LlmRole.USER, text)
            }
        }
        if (transcribed > 0) WeLogger.i(TAG, "voice messages transcribed for context: talker=$talker, count=$transcribed")
        if (mediaDescribed > 0) WeLogger.i(TAG, "media messages described for context: talker=$talker, count=$mediaDescribed")
        return rows.size
    }

    private fun applyGroupTrigger(talker: String, content: String, config: Config): String? {
        if (!talker.endsWith("@chatroom") && !talker.endsWith("@im.chatroom")) return content
        val (_, body) = splitGroupSender(talker, content)
        return when (config.groupTriggerMode) {
            GROUP_TRIGGER_ALL -> content
            GROUP_TRIGGER_MENTION -> if (body.contains("@${dev.sun.wechat.features.api.core.WeApi.selfWxId}", true)) content else null
            GROUP_TRIGGER_KEYWORD -> if (config.groupKeywords.any { body.contains(it, true) }) content else null
            GROUP_TRIGGER_PREFIX -> if (config.groupPrefix.isNotEmpty() && body.startsWith(config.groupPrefix)) {
                val stripped = body.removePrefix(config.groupPrefix).trimStart()
                val sender = splitGroupSender(talker, content).first
                sender?.let { "$it:\n$stripped" } ?: stripped
            } else null
            else -> null
        }
    }

    private fun triggerName(mode: Int): String = when (mode) {
        GROUP_TRIGGER_ALL -> "all"
        GROUP_TRIGGER_MENTION -> "mention"
        GROUP_TRIGGER_KEYWORD -> "keyword"
        GROUP_TRIGGER_PREFIX -> "prefix"
        else -> "unknown"
    }

    private fun sanitizeGroupReply(text: String, maxChars: Int): String {
        val cleaned = text
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace("**", "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
            .replace(Regex("(?m)^\\s*\\d+[.、]\\s*"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return limitReply(cleaned, maxChars)
    }

    private fun limitReply(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val safeEnd = text.substring(0, maxChars).lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?'))
            .takeIf { it >= maxChars / 2 } ?: maxChars
        return text.substring(0, safeEnd).trimEnd() + "\n…（回复已精简）"
    }

    private fun trimContext(messages: List<LlmMessage>, maxChars: Int): List<LlmMessage> {
        var chars = 0
        return messages.asReversed().takeWhile {
            val size = it.content?.length ?: 0
            if (chars + size > maxChars) false else { chars += size; true }
        }.asReversed()
    }

    private fun dailyKey(talker: String): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date()) + ':' + talker

    internal fun clearContext(talker: String? = null) {
        if (talker == null) memories.clear() else memories.remove(talker)
        configVersion.incrementAndGet()
    }

    /** 群聊 content 形如 "昵称:\n正文"，拆出发送者；单聊返回 null。 */
    private fun splitGroupSender(talker: String, content: String): Pair<String?, String> {
        if (!isGroupTalker(talker)) return null to content
        val index = content.indexOf(":\n")
        if (index !in 1..64) return null to content
        return content.substring(0, index) to content.substring(index + 2)
    }

    /**
     * 模型统一取「多模态」设置里的文本模型；多轮上下文压成一段带说话人前缀的文本，
     * system 单独发，保证 OpenAI 兼容服务端都能吃。
     */
    private suspend fun requestModel(config: Config, messages: List<LlmMessage>): Result<String> = runCatching {
        if (!MultimodalEngine.textUsable()) error("no text model configured")
        val systemText = messages.lastOrNull { it.role == LlmRole.SYSTEM }?.content
        val dialogue = messages
            .filter { it.role != LlmRole.SYSTEM }
            .joinToString("\n") { message ->
                val speaker = if (message.role == LlmRole.ASSISTANT) "我" else "对方"
                "$speaker：${message.content}"
            }
        withContext(Dispatchers.IO) {
            VisionClient.streamChat(
                system = systemText,
                user = dialogue,
                maxTokens = config.maxTokens,
                temperature = config.temperature.toDouble(),
            )
        }
    }

    private fun pruneHandledIds() {
        if (handledIds.size > 500) {
            val drop = handledIds.keys.take(handledIds.size / 2)
            drop.forEach(handledIds::remove)
        }
    }

    // ---------------- 配置界面（对齐 Nuke 的「AI 聊天设置」） ----------------

    internal fun showAuthorizedChatsPicker(
        context: ComponentActivity,
        initial: Set<String> = whitelist,
        onSaved: (Set<String>) -> Unit = {},
    ) {
        context.lifecycleScope.launch(Dispatchers.IO) {
            val contacts = WeDatabaseApi.getContacts()
            withContext(Dispatchers.Main) {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = "选择授权会话",
                        contacts = contacts,
                        initialSelectedWxIds = initial,
                        onDismiss = onDismiss,
                        onConfirm = { selected ->
                            whitelist = selected
                            configVersion.incrementAndGet()
                            onSaved(selected)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }

    internal fun showConfigDialog(context: ComponentActivity) {
        dev.sun.wechat.ui.utils.showComposeDialog(context, directlyDismissable = false) {
            val promptInput = remember { mutableStateOf(systemPrompt) }
            val personaKeyInput = remember { mutableStateOf(personaKeyOf(systemPrompt)) }
            val temperatureTenInput = remember { mutableStateOf((temperature * 10).roundToInt().coerceIn(0, 20)) }
            val tokensInput = remember { mutableStateOf(maxTokens.coerceIn(1, MAX_OUTPUT_TOKENS)) }
            val roundsInput = remember { mutableStateOf(contextRounds.coerceIn(0, MAX_CONTEXT_ROUNDS)) }
            val delayInput = remember { mutableStateOf(replyDelayMs.coerceIn(0L, MAX_REPLY_DELAY_MS).toInt()) }
            val modeInput = remember { mutableStateOf(listMode) }
            val groupTriggerInput = remember { mutableStateOf(groupTriggerMode) }
            val groupKeywordsInput = remember { mutableStateOf(groupKeywords) }
            val groupPrefixInput = remember { mutableStateOf(groupPrefix) }
            val cooldownInput = remember { mutableStateOf(cooldownSeconds.coerceIn(0, 3600)) }
            val dailyLimitInput = remember { mutableStateOf(dailyLimit.coerceIn(0, 10000)) }
            val contextCharsInput = remember { mutableStateOf(maxContextChars.coerceIn(1000, 100000)) }
            val monitorInput = remember { mutableStateOf(monitorAuthorizedChats) }
            val monitorOnlyInput = remember { mutableStateOf(monitorOnly) }
            val backfillInput = remember { mutableStateOf(historyBackfillCount.coerceIn(0, 200)) }
            val whitelistInput = remember { mutableStateOf(whitelist) }
            val blacklistInput = remember { mutableStateOf(blacklist) }
            val customPersonasInput = remember { mutableStateOf(loadCustomPersonas()) }
            val showSavePersonaDialog = remember { mutableStateOf(false) }
            val personaNameInput = remember { mutableStateOf("") }
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            val composeContext = LocalContext.current
            val exportPersonasLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri != null) runCatching {
                    composeContext.contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use {
                        it.write(exportPersonas())
                    }
                }.onSuccess { showToast("人设已导出") }
                    .onFailure { showToast("导出失败: ${it.message}") }
            }
            val importPersonasLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) runCatching {
                    val text = composeContext.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    importPersonas(text)
                    customPersonasInput.value = loadCustomPersonas()
                }.onSuccess { showToast("人设已导入") }
                    .onFailure { showToast("导入失败: ${it.message}") }
            }

            fun openPicker() {
                val useWhitelist = modeInput.value == LIST_WHITELIST
                val current = if (useWhitelist) whitelistInput.value else blacklistInput.value
                // 不关掉设置框, 否则配完名单回来时其它未保存的输入会丢
                dev.sun.wechat.ui.utils.showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(
                            if (useWhitelist) R.string.ai_chat_select_whitelist_title else R.string.ai_chat_select_blacklist_title,
                        ),
                        contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups(),
                        initialSelectedWxIds = current,
                        onDismiss = onDismiss,
                    ) { selected ->
                        if (useWhitelist) whitelistInput.value = selected else blacklistInput.value = selected
                        showToast(localizedContext.getString(R.string.ai_chat_selected_count, selected.size))
                        onDismiss()
                    }
                }
            }

            AlertDialogContent(
                icon = { Icon(MaterialSymbols.Outlined.Auto_awesome, contentDescription = null) },
                title = { Text(stringResource(R.string.ai_chat_config_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MiuixDropDownMenuWidget(
                            iconPlaceholder = false,
                            title = "人物设定",
                            description = "选一个人物设定自动填入系统人设，之后可手动微调",
                            value = personaKeyInput.value,
                            options = listOf(DropdownOption(PERSONA_CUSTOM, "自定义")) +
                                AI_PERSONAS.map { DropdownOption(it.key, it.name) } +
                                customPersonasInput.value.map {
                                    DropdownOption(CUSTOM_PERSONA_PREFIX + it.name, it.name)
                                },
                            onValueChange = { key ->
                                personaKeyInput.value = key
                                when {
                                    key == PERSONA_CUSTOM -> { /* 自定义：保持当前 prompt 不变 */ }
                                    key.startsWith(CUSTOM_PERSONA_PREFIX) -> {
                                        val name = key.removePrefix(CUSTOM_PERSONA_PREFIX)
                                        customPersonasInput.value.firstOrNull { it.name == name }?.let {
                                            promptInput.value = it.prompt
                                        }
                                    }
                                    else -> AI_PERSONAS.firstOrNull { it.key == key }?.let {
                                        promptInput.value = it.prompt
                                    }
                                }
                            },
                        )
                        MiuixBaseWidget(
                            iconPlaceholder = false,
                            title = "导出全部人设",
                            description = "导出自定义、联系人和群聊专属人设（不包含 API Key）",
                            onClick = { exportPersonasLauncher.launch("wekit-ai-personas.json") },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Save, null) },
                        )
                        MiuixBaseWidget(
                            iconPlaceholder = false,
                            title = "导入人设",
                            description = "导入 WeKit 人设 JSON；同名项目会被文件内容替换",
                            onClick = { importPersonasLauncher.launch(arrayOf("application/json", "text/plain")) },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                        )
                        FieldRow(
                            label = stringResource(R.string.ai_chat_system_prompt),
                            value = promptInput.value,
                            onValueChange = {
                                promptInput.value = it
                                personaKeyInput.value = personaKeyOf(it)
                            },
                            description = stringResource(R.string.ai_chat_system_prompt_description),
                            singleLine = false,
                        )
                        if (personaKeyInput.value == PERSONA_CUSTOM && promptInput.value.isNotBlank()) {
                            MiuixBaseWidget(
                                iconPlaceholder = false,
                                title = "保存为我的设定",
                                description = "把当前系统人设定个名字存起来，下次直接从下拉里选",
                                onClick = {
                                    personaNameInput.value = ""
                                    showSavePersonaDialog.value = true
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Save,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                            )
                        }
                        if (personaKeyInput.value.startsWith(CUSTOM_PERSONA_PREFIX)) {
                            MiuixBaseWidget(
                                iconPlaceholder = false,
                                title = "删除此设定",
                                description = "从我的设定里移除「" +
                                    personaKeyInput.value.removePrefix(CUSTOM_PERSONA_PREFIX) + "」",
                                onClick = {
                                    val name = personaKeyInput.value.removePrefix(CUSTOM_PERSONA_PREFIX)
                                    deleteCustomPersona(name)
                                    customPersonasInput.value = loadCustomPersonas()
                                    personaKeyInput.value = PERSONA_CUSTOM
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_temperature),
                            description = "0~20，数值 ÷10 即温度（0.0~2.0），越大越有创造性",
                            value = temperatureTenInput.value,
                            startInt = 0,
                            endInt = 20,
                            stepSize = 1,
                            valueSuffix = "",
                            onValueChange = { temperatureTenInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_max_tokens),
                            description = stringResource(R.string.ai_chat_max_tokens_description),
                            value = tokensInput.value,
                            startInt = 256,
                            endInt = MAX_OUTPUT_TOKENS,
                            stepSize = 256,
                            valueSuffix = " tokens",
                            onValueChange = { tokensInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_context_rounds),
                            description = stringResource(R.string.ai_chat_context_rounds_description),
                            value = roundsInput.value,
                            startInt = 0,
                            endInt = MAX_CONTEXT_ROUNDS,
                            stepSize = 1,
                            valueSuffix = " 轮",
                            onValueChange = { roundsInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_reply_delay),
                            description = stringResource(R.string.ai_chat_reply_delay_description),
                            value = delayInput.value,
                            startInt = 0,
                            endInt = MAX_REPLY_DELAY_MS.toInt(),
                            stepSize = 1000,
                            valueSuffix = " ms",
                            onValueChange = { delayInput.value = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        MiuixDropDownMenuWidget(
                            iconPlaceholder = false,
                            title = "群聊触发方式",
                            description = "仅影响群聊；私聊仍按授权名单回复",
                            value = groupTriggerInput.value,
                            options = listOf(
                                DropdownOption(GROUP_TRIGGER_ALL, "全部消息"),
                                DropdownOption(GROUP_TRIGGER_MENTION, "被 @ 时"),
                                DropdownOption(GROUP_TRIGGER_KEYWORD, "包含关键词"),
                                DropdownOption(GROUP_TRIGGER_PREFIX, "指定前缀"),
                            ),
                            onValueChange = { groupTriggerInput.value = it },
                        )
                        if (groupTriggerInput.value == GROUP_TRIGGER_KEYWORD) FieldRow(
                            "群聊关键词", groupKeywordsInput.value,
                            { groupKeywordsInput.value = it }, "使用逗号或换行分隔", false,
                        )
                        if (groupTriggerInput.value == GROUP_TRIGGER_PREFIX) FieldRow(
                            "群聊触发前缀", groupPrefixInput.value,
                            { groupPrefixInput.value = it }, "例如 /ai，发送给模型前自动移除",
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false, title = "单会话冷却时间",
                            description = "避免连续消息触发刷屏", value = cooldownInput.value,
                            startInt = 0, endInt = 3600, stepSize = 5, valueSuffix = " 秒",
                            onValueChange = { cooldownInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false, title = "单会话每日上限",
                            description = "0 表示不限制", value = dailyLimitInput.value,
                            startInt = 0, endInt = 10000, stepSize = 10, valueSuffix = " 条",
                            onValueChange = { dailyLimitInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false, title = "上下文字符上限",
                            description = "超出时优先保留最近消息", value = contextCharsInput.value,
                            startInt = 1000, endInt = 100000, stepSize = 1000, valueSuffix = " 字符",
                            onValueChange = { contextCharsInput.value = it },
                        )
                        MiuixIntNumberPickerWidget(
                            iconPlaceholder = false, title = "历史消息回读条数",
                            description = "每次自动回复前固定读取微信群最近 10 条文本消息", value = 10,
                            startInt = 10, endInt = 10, stepSize = 1, valueSuffix = " 条",
                            onValueChange = { },
                        )
                        MiuixDropDownMenuWidget(
                            iconPlaceholder = false, title = "授权群聊上下文监控",
                            description = "监听授权群聊消息并持续加入现场上下文",
                            value = if (monitorInput.value) 1 else 0,
                            options = listOf(DropdownOption(1, "开启"), DropdownOption(0, "关闭")),
                            onValueChange = { monitorInput.value = it == 1 },
                        )
                        MiuixDropDownMenuWidget(
                            iconPlaceholder = false, title = "群聊仅监控不回复",
                            description = "开启后只收集授权群聊上下文，不触发模型回复",
                            value = if (monitorOnlyInput.value) 1 else 0,
                            options = listOf(DropdownOption(1, "开启"), DropdownOption(0, "关闭")),
                            onValueChange = { monitorOnlyInput.value = it == 1 },
                        )
                        MiuixBaseWidget(
                            iconPlaceholder = false,
                            title = "AI 自动回复诊断",
                            description = buildString {
                                append("消息 Hook: ")
                                append(if (WeMessageApi.methodMsgInfoStorageInsertMessage.isAvailable) "可用" else "不可用")
                                append(" · 模型: ")
                                append(if (MultimodalEngine.textUsable()) MultimodalEngine.textModel.ifBlank { MultimodalEngine.visionModel } else "请先在多模态设置里填地址、Key 和模型")
                                append("\n收件: ").append(if (lastReceiveAt == 0L) "暂无" else java.util.Date(lastReceiveAt))
                                append(" · 发送: ").append(if (lastSendAt == 0L) "暂无" else java.util.Date(lastSendAt))
                                append("\n处理中: ").append(inFlight.size)
                                lastFailure?.let { append(" · 最近失败: ").append(it) }
                            },
                            onClick = {
                                val target = whitelistInput.value.firstOrNull()
                                if (target == null) showToast("请先在白名单中选择一个测试联系人")
                                else if (WeMessageApi.sendText(target, "WeKit AI 发送链路测试")) showToast("测试消息已提交")
                                else showToast("测试消息发送失败")
                            },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                        )
                        MiuixBaseWidget(
                            iconPlaceholder = false, title = "清除全部 AI 上下文",
                            description = "立即丢弃当前内存中的所有会话上下文",
                            onClick = { clearContext(); showToast("AI 上下文已清除") },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Delete, null) },
                        )
                        Spacer(Modifier.height(8.dp))
                        MiuixDropDownMenuWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_list_mode),
                            description = stringResource(
                                if (modeInput.value == LIST_WHITELIST) R.string.ai_chat_whitelist_description
                                else R.string.ai_chat_blacklist_description,
                            ),
                            value = modeInput.value,
                            options = listOf(
                                DropdownOption(LIST_WHITELIST, stringResource(R.string.ai_chat_list_whitelist)),
                                DropdownOption(LIST_BLACKLIST, stringResource(R.string.ai_chat_list_blacklist)),
                            ),
                            onValueChange = { modeInput.value = it },
                        )
                        MiuixBaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(
                                if (modeInput.value == LIST_WHITELIST) R.string.ai_chat_configure_whitelist
                                else R.string.ai_chat_configure_blacklist,
                            ),
                            description = stringResource(
                                R.string.ai_chat_selected_count,
                                (if (modeInput.value == LIST_WHITELIST) whitelistInput.value else blacklistInput.value).size,
                            ),
                            onClick = { openPicker() },
                            trailingContent = {
                                Icon(
                                    MaterialSymbols.Outlined.Chevron_right,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val previous = snapshot()
                        systemPrompt = promptInput.value.trim()
                        temperature = (temperatureTenInput.value / 10f).coerceIn(0f, 2f)
                        maxTokens = tokensInput.value.coerceIn(1, MAX_OUTPUT_TOKENS)
                        contextRounds = roundsInput.value.coerceIn(0, MAX_CONTEXT_ROUNDS)
                        replyDelayMs = delayInput.value.toLong().coerceIn(0L, MAX_REPLY_DELAY_MS)
                        listMode = modeInput.value
                        groupTriggerMode = groupTriggerInput.value
                        groupKeywords = groupKeywordsInput.value.trim()
                        groupPrefix = groupPrefixInput.value.trim()
                        cooldownSeconds = cooldownInput.value
                        dailyLimit = dailyLimitInput.value
                        maxContextChars = contextCharsInput.value
                        monitorAuthorizedChats = monitorInput.value
                        monitorOnly = monitorOnlyInput.value
                        historyBackfillCount = 10
                        whitelist = whitelistInput.value
                        blacklist = blacklistInput.value

                        val now = snapshot()
                        // 名单/轮数等变化后，旧上下文不再适用：清空并让在途回复作废
                        if (now.contextRounds != previous.contextRounds ||
                            now.listMode != previous.listMode ||
                            now.targets != previous.targets
                        ) {
                            memories.clear()
                        }
                        configVersion.incrementAndGet()
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
            if (showSavePersonaDialog.value) {
                MiuixAlertDialog(
                    onDismissRequest = { showSavePersonaDialog.value = false },
                    icon = { Icon(MaterialSymbols.Outlined.Save, contentDescription = null) },
                    title = { Text("保存人物设定") },
                    text = {
                        TextField(
                            value = personaNameInput.value,
                            onValueChange = { personaNameInput.value = it },
                            label = "设定名称（如：我的分身）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val name = personaNameInput.value.trim()
                            if (name.isNotEmpty()) {
                                saveCustomPersona(name, promptInput.value)
                                customPersonasInput.value = loadCustomPersonas()
                                personaKeyInput.value = CUSTOM_PERSONA_PREFIX + name
                                showSavePersonaDialog.value = false
                            }
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePersonaDialog.value = false }) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        description: String,
        singleLine: Boolean = true,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.title3,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
