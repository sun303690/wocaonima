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
import dev.sun.wechat.features.items.chat_mood.ReplyMessageSupport
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
    /** 一次回填里最多现场调用几次语音接口；命中的会写盘缓存，下一轮直接复用。 */
    private const val MAX_LIVE_VOICE_CALLS = 10
    /** 一次回填里最多反射读几条微信内存转写（binder 调用有开销，只做兜底）。 */
    private const val MAX_VOICE_REFLECT = 3

    /** 语音消息 content 里可能直接带文件名（`msg_xxx.amr`），历史消息 imgPath 为空时靠它兜底。 */
    private val VOICE_NAME_IN_CONTENT = Regex("""msg_[0-9A-Za-z_\-]+\.(?:amr|silk)""")

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

    /**
     * 出厂默认人设：直接取内置「可爱妹妹」模板的提示词，
     * 这样界面上「人设」下拉能反查到它（[personaKeyOf] 按 prompt 原文比对）。
     */
    private val DEFAULT_SYSTEM_PROMPT: String =
        AI_PERSONAS.firstOrNull { it.key == "cute_sister" }?.prompt.orEmpty()

    /** 老版本装过、人设仍是空串时补一次默认值，之后用户自己清空不再重置。 */
    private const val PERSONA_SEEDED_KEY = "ai_chat_persona_seeded"

    // ---- 配置（键名与 Nuke 的 AIChatConfig 字段一一对应） ----
    internal var systemPrompt by KvStore.prefOption("ai_chat_system_prompt", DEFAULT_SYSTEM_PROMPT)
    var temperature by KvStore.prefOption("ai_chat_temperature", 0.7f)
    var maxTokens by KvStore.prefOption("ai_chat_max_tokens", 512)
    var contextRounds by KvStore.prefOption("ai_chat_context_rounds", 6)
    var replyDelayMs by KvStore.prefOption("ai_chat_reply_delay_ms", 0L)
    internal var listMode by KvStore.prefOption("ai_chat_list_mode", LIST_WHITELIST)
    internal var maxContextChars by KvStore.prefOption("ai_chat_max_context_chars", 12000)
    internal var monitorAuthorizedChats by KvStore.prefOption("ai_chat_monitor_authorized_chats", true)
    internal var historyBackfillCount by KvStore.prefOption("ai_chat_history_backfill_count", 10)

    /** 上下文里的图片/表情/动图/视频是否送视觉模型识别（默认关，按张计费）。 */
    internal var mediaContext by KvStore.prefOption("ai_chat_media_context", false)
    internal var voiceContext by KvStore.prefOption("ai_chat_voice_context", true)
    /** 是否允许回填上下文时现场调用视觉/语音接口识别；默认关闭，避免产生接口费用。 */
    internal var liveIdentify by KvStore.prefOption("ai_chat_live_identify", false)
    internal var groupMaxReplyChars by KvStore.prefOption("ai_chat_group_max_reply_chars", 500)
    /** 语音回复开关统一在「文字转语音」功能里配，AI 群聊只读它的值。 */
    private val voiceReplyEnabled: Boolean
        get() = TextToSpeech.voiceReplyEnabled

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
        val maxContextChars: Int,
        val monitorAuthorizedChats: Boolean,
        val historyBackfillCount: Int,
        val groupMaxReplyChars: Int,
        val mediaContext: Boolean,
        val voiceContext: Boolean,
        val liveIdentify: Boolean,
    )

    private fun snapshot(): Config = Config(
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens.coerceIn(1, MAX_OUTPUT_TOKENS),
        contextRounds = contextRounds.coerceIn(0, MAX_CONTEXT_ROUNDS),
        replyDelayMs = replyDelayMs.coerceIn(0L, MAX_REPLY_DELAY_MS),
        listMode = listMode,
        targets = if (listMode == LIST_BLACKLIST) blacklist else whitelist,
        maxContextChars = maxContextChars.coerceIn(1000, 100000),
        monitorAuthorizedChats = monitorAuthorizedChats,
        historyBackfillCount = historyBackfillCount.coerceIn(1, 50),
        groupMaxReplyChars = groupMaxReplyChars.coerceIn(100, 4000),
        mediaContext = mediaContext,
        voiceContext = voiceContext,
        liveIdentify = liveIdentify,
    )

    /** 白名单：命中才回；黑名单：命中就跳过（空名单=全部处理）。 */
    private fun Config.allowsTalker(talker: String): Boolean =
        if (listMode == LIST_WHITELIST) targets.contains(talker) else !targets.contains(talker)

    // ---- 运行时 ----
    private val configVersion = AtomicLong(0)
    private val memories = ConcurrentHashMap<String, MutableList<LlmMessage>>()
    private val handledIds = ConcurrentHashMap<String, Boolean>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val pendingGroupMessages = ConcurrentHashMap<String, String>()
    @Volatile private var lastReceiveAt = 0L
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastFailure: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    override fun onEnable() {
        // 人设播种只影响文案，出错也必须把消息监听挂上，否则整个功能静默失效
        runCatching { seedDefaultPersona() }
            .onFailure { WeLogger.w(TAG, "seed default persona failed: ${it.message}") }
        WeDatabaseListenerApi.addListener(this)
    }

    private fun seedDefaultPersona() {
        if (KvStore.getStringOrDef(PERSONA_SEEDED_KEY, "").isNotEmpty()) return
        if (KvStore.getStringOrDef("ai_chat_system_prompt", "").isBlank()) {
            KvStore.putString("ai_chat_system_prompt", DEFAULT_SYSTEM_PROMPT)
            WeLogger.i(TAG, "seeded default persona: cute_sister")
        }
        KvStore.putString(PERSONA_SEEDED_KEY, "1")
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memories.clear()
        inFlight.clear()
        pendingGroupMessages.clear()
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
        if (messageType?.isText != true && !isVoice && !isMultimodal) {
            WeLogger.d(TAG, "AI insert ignored: unsupported type=$type talker=${values.getAsString("talker")}")
            return
        }
        val isSend = values.getAsInteger("isSend") ?: return
        if (isSend != 0) return
        val talker = values.getAsString("talker").orEmpty()
        if (talker.isBlank() || talker.startsWith("gh_")) return
        WeLogger.d(TAG, "AI insert accepted: type=$type talker=$talker voice=$isVoice multimodal=$isMultimodal")

        // 微信同一条消息可能插入多次，按 msgSvrId/localId 去重
        val dedupeKey = values.getAsString("msgSvrId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: values.getAsInteger("localId")?.toString()
        if (dedupeKey != null && handledIds.putIfAbsent(dedupeKey, true) != null) return
        pruneHandledIds()

        if (isVoice) {
            // 转写文本只活在微信内存 bean（不落数据库），由消息行绑定（MessageSniffer）
            // 记进 VoiceTranscriptStore。这里延迟等自动转写落地再取；取不到（没开
            // 自动转写/一直没转完）就放弃这条语音，绝不拿 voicemsg 的 XML 当正文瞎回复。
            // 微信转写缓存（VoiceTranscriptStore）是用 field_msgId 记的，消息表的 localId 是另一列，
            // 两个都查一遍才拿得到历史语音的转写文本；都缺时才放弃这条。
            val localId = values.getAsInteger("localId")?.toLong()
                ?: values.getAsInteger("msgId")?.toLong()
                ?: return
            val msgIdKey = values.getAsInteger("msgId")?.toLong()
            scope.launch {
                var transcript: String? = null
                for (attempt in 0 until 16) {
                    transcript = VoiceTranscriptStore.get(localId)
                        ?: msgIdKey?.let { VoiceTranscriptStore.get(it) }
                    if (!transcript.isNullOrBlank()) break
                    delay(500)
                }
                // 微信没给转写文本时，用模块自己接的 ASR 识别一遍（失败就放弃这条语音）
                if (transcript.isNullOrBlank()) {
                    // imgPath 偶发为空，退到 content（有的版本语音名只在 content 里）。
                    val encPath = values.getAsString("imgPath")?.takeIf { it.isNotBlank() }
                        ?: values.getAsString("content")?.takeIf { it.startsWith("msg_") }
                    // 这里同样只看语音接口自身是否配好，不受「多模态识别」总开关（默认关）影响
                    if (AsrEngine.ready() && !encPath.isNullOrBlank()) {
                        WeLogger.i(TAG, "语音无微信转写，改用 ASR：talker=$talker, localId=$localId")
                        transcript = runCatching {
                            withContext(Dispatchers.IO) { AsrEngine.transcribeEncPath(encPath) }
                        }.getOrElse {
                            WeLogger.w(TAG, "ASR 识别失败：${it.message}")
                            null
                        }
                        transcript?.takeIf { it.isNotBlank() }?.let {
                            VoiceTranscriptStore.put(localId, it)
                            msgIdKey?.let { id -> VoiceTranscriptStore.put(id, it) }
                        }
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

    /** 新消息（文字原文、语音转写文本或多模态描述）统一入口：名单过滤/观察/冷却/限流后交给模型。 */
    private fun dispatchNewMessage(talker: String, content: String) {
        val config = snapshot()
        if (!config.allowsTalker(talker)) {
            WeLogger.i(TAG, "AI reply skipped: talker not in list, talker=$talker, mode=${if (config.listMode == LIST_WHITELIST) "whitelist" else "blacklist"}, size=${config.targets.size}")
            return
        }
        val isGroup = isGroupTalker(talker)
        if (config.monitorAuthorizedChats && isGroup) appendObservedMessage(talker, content, isSend = false)
        // 白名单/黑名单已决定哪些会话会进来；群聊不再按 @/关键词/前缀 过滤，进来就回
        val triggeredContent = content
        val now = System.currentTimeMillis()
        if (inFlight.putIfAbsent(talker, true) != null) {
            if (isGroup) pendingGroupMessages[talker] = triggeredContent
            WeLogger.i(TAG, "AI reply queued: busy, talker=$talker")
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
                "AI context prepared: talker=$talker, " +
                    "persona=${activePersona?.id ?: "default"}, memories=${persistentMemories.size}, " +
                    "backfilled=$backfilledCount, contextMessages=$contextMessageCount, contextChars=$contextCharCount",
            )

            WeLogger.i(
                TAG,
                "AI request start: talker=$talker, model=${MultimodalEngine.textModelOrVision()}, " +
                    "endpoint=${MultimodalEngine.textEndpointBase()}, messages=${messages.size}, " +
                    "maxTokens=${config.maxTokens}, temperature=${config.temperature}",
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
            if (rawText.isEmpty()) {
                // 模型返回空文本时原来静默退出，日志里只停在 context prepared，看不出卡在哪
                WeLogger.w(
                    TAG,
                    "AI reply empty: talker=$talker, model=${MultimodalEngine.textModelOrVision()}, " +
                        "endpoint=${MultimodalEngine.textEndpointBase()}",
                )
                lastFailure = "模型返回空文本"
                return
            }
            val text = if (isGroupTalker(talker)) sanitizeGroupReply(rawText, config.groupMaxReplyChars) else rawText
            val useVoice = voiceReplyEnabled
            val sent = if (useVoice) sendReplyAsVoice(talker, text) else WeMessageApi.sendText(talker, text)
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
            WeLogger.i(
                TAG,
                "AI replied to talker=$talker, replyChars=${text.length}, " +
                    "truncated=${text.length < rawText.length}, voice=$useVoice",
            )
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

    /** 从数据库回填最近若干条消息做上下文（默认 10，条数可在「高级设置」里调）；群聊和私聊都走这里。 */
    private fun refreshRecentGroupContext(talker: String, history: MutableList<LlmMessage>, config: Config): Int {
        if (!WeDatabaseApi.isReady) return 0
        val rows = runCatching { WeDatabaseApi.getMessages(talker, 1, config.historyBackfillCount) }
            .getOrElse { WeLogger.w(TAG, "failed to refresh recent group context for $talker", it); emptyList() }
            .asReversed()
            .filter { row ->
                val type = MessageType.fromCode(row.typeCode)
                (type?.isText == true && row.content.isNotBlank()) ||
                    type?.code == MessageType.VOICE.code ||
                    (config.mediaContext && MediaVision.isVisual(type))
            }
            .takeLast(config.historyBackfillCount)
        // 语音走「语音接口」：只要语音接口配好了，语音消息一律现场识别（识别结果会写盘缓存，
        // 同一条只会请求一次）。图片/表情/视频体积大、单价高，仍由「现场识别」开关控制。
        var voiceLiveBudget = MAX_LIVE_VOICE_CALLS
        var reflectBudget = MAX_VOICE_REFLECT
        var liveMediaBudget = if (config.liveIdentify) MAX_LIVE_MEDIA_DESCRIPTIONS else 0
        var mediaDescribed = 0
        var transcribed = 0
        var voiceSkipped = 0
        var asrNotReady = 0
        synchronized(history) {
            history.clear()
            rows.forEach { message ->
                val type = MessageType.fromCode(message.typeCode)
                if (type?.code == MessageType.VOICE.code) {
                    // 历史语音的 imgPath 常为空（微信只把文件名写进 content 的 XML 或压根没下载），
                    // 所以按 imgPath → content 里的文件名 → 空 三级兜底。
                    val encPath = message.imgPath?.trim()?.takeIf { it.isNotEmpty() }
                        ?: VOICE_NAME_IN_CONTENT.find(message.content.orEmpty())?.value
                    val transcript = when {
                        // 语音识别用的是多模态「语音接口」那一页的配置（AsrEngine，含它自己的开关）。
                        // 这里只看语音接口本身是否可用，不再挂在「多模态识别」总开关上——
                        // 总开关默认关闭，会让配好语音接口的人以为功能坏了。
                        !config.voiceContext -> {
                            voiceSkipped++
                            null
                        }
                        !AsrEngine.ready() -> {
                            asrNotReady++
                            null
                        }
                        encPath == null -> {
                            voiceSkipped++
                            null
                        }
                        else -> {
                            // 取文字的顺序：① 语音接口 ② 微信自带转写 ③ 占位符。
                            // ① 语音接口：先用模块识别过的磁盘缓存，没命中就现场请求（每轮有预算，
                            //    识别成功会写盘，同一条只请求一次）。
                            val fromAsr = runCatching { AsrEngine.cachedTranscript(encPath) }.getOrNull()
                                ?: if (voiceLiveBudget > 0) {
                                    voiceLiveBudget--
                                    runCatching { AsrEngine.transcriptForContext(encPath, VOICE_WAIT_MS) }
                                        .onFailure {
                                            WeLogger.w(TAG, "voice transcript for context failed: ${it.message}")
                                        }
                                        .getOrNull()
                                } else null
                            // ② 语音接口没结果（未下载 / 接口失败 / 预算用完）才退到微信自带转写：
                            //    内存里已记下的，或反射去微信内存 bean 里读这条的（含之前转写过的历史语音）。
                            val fromWeChat = if (fromAsr.isNullOrBlank()) {
                                VoiceTranscriptStore.get(message.msgId)
                                    ?: if (reflectBudget > 0 && message.msgSvrId > 0) {
                                        reflectBudget--
                                        runCatching {
                                            val info = MessageInfo(
                                                WeMessageApi.getMsgInfoInstanceByMsgSvrId(message.msgSvrId, talker)
                                            )
                                            ReplyMessageSupport.voiceTranscript(info)
                                        }.getOrNull()?.also { VoiceTranscriptStore.put(message.msgId, it) }
                                    } else null
                            } else null
                            val best = fromAsr ?: fromWeChat
                            if (best.isNullOrBlank()) voiceSkipped++
                            best
                        }
                    }
                    if (!transcript.isNullOrBlank()) transcribed++
                    val body = when {
                        !transcript.isNullOrBlank() -> "[语音] $transcript"
                        else -> "[语音]"
                    }
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
        if (transcribed > 0) WeLogger.i(TAG, "voice messages transcribed for context: talker=$talker, count=$transcribed, skipped=$voiceSkipped")
        else if (voiceSkipped > 0 || asrNotReady > 0) WeLogger.i(TAG, "voice messages not transcribed for context: talker=$talker, skipped=$voiceSkipped, asrNotReady=$asrNotReady（未配置语音接口 / 文件未下载 / 预算用尽）")
        if (mediaDescribed > 0) WeLogger.i(TAG, "media messages described for context: talker=$talker, count=$mediaDescribed")
        return rows.size
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

}
