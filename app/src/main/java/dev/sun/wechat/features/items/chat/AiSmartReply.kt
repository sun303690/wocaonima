package dev.sun.wechat.features.items.chat

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import dev.sun.wechat.ui.content.miuix.MiuixFilterChip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.core.models.WeMessage
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.items.chat_mood.ReplyMessageSupport
import dev.sun.wechat.features.items.chat_mood.VoiceReply
import dev.sun.wechat.features.items.multimodal.MediaVision
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.VisionClient
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.VectorPathDrawable
import dev.sun.wechat.ui.utils.ShowComposeDialogScope
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 帮我回（原「智能回复」，并入情绪分析）：
 * 快捷选择语气/关系预设 → 按预设生成多条可编辑的回复候选 → 发送。
 *  - 语气预设：智能全能/高情商/…/委婉拒绝
 *  - 关系预设：亲人/朋友/同事/恋人/长辈/弟弟妹妹/暗恋对象/暧昧对象
 *  - 参考上下文条数（默认10，取该会话最近N条）；生成备选数（默认20）
 *  AI 调用走「多模态」设置里的文本模型（未配文本模型时退回视觉模型）。
 */
object AiSmartReply : WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiSmartReply"

    private const val MENU_ID = 777042

    private const val IMAGE_PROMPT = "请用中文一句话概括这张图片的内容，只输出概括结果。"
    private const val STICKER_PROMPT = "请用中文一句话说明这个表情包想表达的情绪或意思，只输出结果。"
    private const val ANIMATION_PROMPT = "这是一段动图的四连拍拼图，请用中文一句话概括这段动画在做什么，只输出结果。"
    private const val VIDEO_PROMPT = "请用中文一句话概括这个视频的主要内容，只输出概括结果。"

    /** 同一条消息的媒体识别结果在整个进程里只算一次（key = serverId-typeCode）。 */
    private val mediaCache = ConcurrentHashMap<String, String>()

    /** 一轮上下文里最多现场转写几条语音；命中 ASR 缓存的不算。 */
    private const val MAX_LIVE_VOICE_TRANSCRIPTS = 1

    private fun stylePromptKey(name: String) = "asr_style_prompt_$name"

    /** 风格提示词: 优先用户改过的, 否则内置默认 */
    private fun currentPromptFor(name: String): String =
        KvStore.getStringOrDef(
            stylePromptKey(name),
            STYLES.firstOrNull { it.first == name }?.second ?: "",
        )
    var contextLimit by prefOption("ai_reply_context_limit", 10)

    /** 生成回复时是否把历史窗口里的图片 / 表情 / 视频也读成文字，喂给模型当上下文。 */
    var mediaContext by prefOption("ai_reply_media_context", false)
    var replyCount by prefOption("ai_reply_count", 20)
    private var lastSelectedStyle by prefOption("ai_reply_last_selected_style", "智能全能")

    /** 语气预设 name -> prompt（与 WeKit 一致，含关系预设） */
    val STYLES = listOf(
        "智能全能" to "分析当前对话氛围，给出最得体、自然的回复。",
        "高情商" to "说话非常有艺术，能够化解尴尬，照顾对方感受，充满智慧。",
        "轻松闲聊" to "语气随性自然，带一点点幽默感，不要官方和生硬。",
        "严谨正式" to "语气礼貌、专业、客观，适用于职场或正式商务沟通。",
        "幽默/阴阳" to "说话风趣，带点俏皮甚至一点点阴阳怪气，非常有意思。",
        "同理/安慰" to "语气非常温柔，站在对方立场思考，给予对方情感上的支撑。",
        "客气周到" to "非常有礼貌，多使用敬语，保持一定的礼貌距离。",
        "霸道/冷酷" to "言简意赅，语气带有一点压迫感和冷酷的霸总风格。",
        "可爱/萌化" to "说话活泼，多用呀、哒、呢，增加适量颜文字，非常可爱。",
        "委婉拒绝" to "礼貌地拒绝对方的要求，不让对方感到难堪，语气委婉。",
        // ===== 关系预设（帮我回按对方身份调整语气）=====
        "亲人" to "对方是亲人。语气温暖、直接、日常化，关心具体事情，亲近而不客套。",
        "朋友" to "对方是朋友。自然平等、接住话题，语气轻松，熟悉程度以聊天为准。",
        "同事" to "对方是同事。友好、清楚、简洁，就事论事，边界明确。",
        "恋人" to "对方是恋人。可亲近、简短、有生活感，称呼和撒娇程度沿用实际聊天。",
        "长辈" to "对方是长辈。尊重、清楚、亲切，用词礼貌，称呼依据聊天。",
        "弟弟妹妹" to "对方是弟弟妹妹。亲近平等、关心具体事情，不居高临下。",
        "暗恋对象" to "对方是我暗恋的人，不代表对方也喜欢我。自然表达关注，轻松而有分寸，不默认暧昧。",
        "暧昧对象" to "以轻松、有来有往的口吻交流，调侃需结合对方实际回应。",
    )

    @Composable
    private fun ShowComposeDialogScope.SettingsDialogContent(context: android.content.Context) {
        var contextInput by remember { mutableStateOf(contextLimit.toString()) }
        var countInput by remember { mutableStateOf(replyCount.toString()) }
        var mediaContextInput by remember { mutableStateOf(mediaContext) }
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_context_limit), style = MiuixTheme.textStyles.footnote1)
                                TextField(
                                    value = contextInput,
                                    onValueChange = { contextInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_count), style = MiuixTheme.textStyles.footnote1)
                                TextField(
                                    value = countInput,
                                    onValueChange = { countInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                        item {
                            MiuixSwitchWidget(
                                title = stringResource(R.string.smart_reply_media_context),
                                checked = mediaContextInput,
                                onCheckedChange = { mediaContextInput = it },
                                summary = stringResource(R.string.smart_reply_media_context_summary),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    contextLimit = contextInput.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    replyCount = countInput.toIntOrNull()?.coerceIn(1, 20) ?: 20
                    mediaContext = mediaContextInput
                    onDismiss()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    /**
     * 帮我回对话框里有多行可编辑输入框（风格提示词 + 每条候选回复）。
     * 共享的 showComposeDialog 默认带 SOFT_INPUT_STATE_ALWAYS_HIDDEN：每次输入框获得焦点都会
     * 先强制收键盘、再重新弹出，配合第三方输入法就会出现「一打开就乱跳」。
     * 这里只针对本对话框的窗口关掉 ALWAYS_HIDDEN，仅保留 ADJUST_RESIZE + 面板内 imePadding()
     * 平滑跟随软键盘（不动共享 helper，避免影响其它对话框）。
     */
    private fun tuneImeForEditing(scope: ShowComposeDialogScope) {
        scope.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showSmartReplyDialog(activity: Activity, msgInfo: MessageInfo) {
        // 视频消息：先抽帧识别画面内容，再拿识别结果生成回复（识别不到就退回 [视频] 占位）
        val replyType = msgInfo.type
        val isVideoReply = replyType?.code == MessageType.VIDEO.code ||
            replyType?.code == MessageType.MICRO_VIDEO.code
        if (isVideoReply && MediaVision.available()) {
            showToast(activity, "正在识别视频…")
            Thread {
                val described = describeRepliedVideo(msgInfo)
                activity.runOnUiThread {
                    val text = if (described.isNullOrBlank()) msgMessageText(msgInfo) else "视频内容：$described"
                    openSmartReplyDialog(activity, msgInfo, text)
                }
            }.start()
            return
        }
        // 语音消息：微信自带转写拿不到时，先用模块接的 ASR 识别，再拿识别结果生成回复
        val isVoice = msgInfo.type?.code == MessageType.VOICE.code
        val needAsr = isVoice && ReplyMessageSupport.voiceTranscript(msgInfo).isNullOrBlank() &&
            AsrEngine.ready()
        if (needAsr) {
            showToast(activity, "正在识别语音…")
            AsrEngine.transcribeMessage(msgInfo) { text, error ->
                activity.runOnUiThread {
                    if (text.isNullOrBlank()) showToast(activity, error ?: "语音识别失败")
                    openSmartReplyDialog(activity, msgInfo, text ?: msgMessageText(msgInfo))
                }
            }
            return
        }
        openSmartReplyDialog(activity, msgInfo, msgMessageText(msgInfo))
    }

    private fun openSmartReplyDialog(activity: Activity, msgInfo: MessageInfo, content: String) {
        showComposeDialog(activity) {
            tuneImeForEditing(this)
            SmartReplyDialogContent(msgInfo.talker, content)
        }
    }

    /** 从聊天输入栏（+ 面板）打开智能回复：针对当前会话生成，未选中具体消息。 */
    fun openSmartReply(activity: Activity, talker: String) {
        showComposeDialog(activity) {
            tuneImeForEditing(this)
            SmartReplyDialogContent(talker, "")
        }
    }

    // ===== 长按消息菜单入口（WeChatMessageContextMenuApi）=====
    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = MENU_ID,
            text = "AI 代回",
            drawable = AiSmartReplyIcon,
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            // 文字、语音、表情包都可以 AI 回复
            isSupported = { msg -> ReplyMessageSupport.isReplyEligible(msg) },
        ) { view, ctx, msgInfo ->
            showSmartReplyDialog(ctx.activity, msgInfo)
        },
    )

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ShowComposeDialogScope.SmartReplyDialogContent(talker: String, text: String) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectedStyle by remember {
            mutableStateOf(lastSelectedStyle.takeIf { saved -> STYLES.any { it.first == saved } } ?: "智能全能")
        }
        var stylePromptInput by remember(selectedStyle) { mutableStateOf(currentPromptFor(selectedStyle)) }
        var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun generate() {
            if (loading) return
            scope.launch {
                loading = true
                error = null
                // 保存该风格的提示词修改
                KvStore.putString(stylePromptKey(selectedStyle), stylePromptInput.trim())
                // 换一批: 先清空旧候选, 生成中显示思考态
                candidates = emptyList()
                val result = generateCandidates(talker, text, selectedStyle, stylePromptInput.trim())
                candidates = result.getOrDefault(emptyList())
                loading = false
                error = result.exceptionOrNull()?.let {
                    "生成失败：" + (it.message ?: it.javaClass.simpleName).take(160)
                } ?: if (candidates.isEmpty()) "模型没有返回内容，请换个模型再试" else null
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
                    // 快捷选择语气预设
                    Text(stringResource(R.string.smart_reply_style), style = MiuixTheme.textStyles.title3)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        STYLES.forEach { (name, _) ->
                            MiuixFilterChip(
                                selected = selectedStyle == name,
                                onClick = {
                                    selectedStyle = name
                                    lastSelectedStyle = name
                                },
                                label = { Text(name) },
                            )
                        }
                    }

                    // 选中风格的介绍(提示词), 可直接编辑, 生成时生效
                    TextField(
                        value = stylePromptInput,
                        onValueChange = { stylePromptInput = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = stringResource(R.string.asr_style_prompt_label),
                        minLines = 2,
                        maxLines = 4,
                    )

                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.asr_thinking))
                        }
                        error != null -> Text(error!!, color = MiuixTheme.colorScheme.error)
                        candidates.isEmpty() -> Text(stringResource(R.string.smart_reply_tap_generate), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        else -> candidates.forEach { cand ->
                            var editable by remember(cand) { mutableStateOf(cand) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                TextField(
                                    value = editable,
                                    onValueChange = { editable = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 5,
                                )
                                // 每条候选给两个按钮：发文字 / 发语音。发送成功后自动关掉面板，
                                // 不用再手动点「取消」。
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            val finalText = editable.trim()
                                            if (finalText.isEmpty()) { showToast(context, context.getString(R.string.ama_tts_empty_v2)); return@Button }
                                            val ok = WeMessageApi.sendText(talker, finalText)
                                            showToast(context, context.getString(if (ok) R.string.ama_sent else R.string.ama_send_failed))
                                            if (ok) onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text(stringResource(R.string.ama_send)) }
                                    if (VoiceReply.enabled()) {
                                        Button(
                                            onClick = {
                                                val finalText = editable.trim()
                                                if (finalText.isEmpty()) { showToast(context, context.getString(R.string.ama_tts_empty_v2)); return@Button }
                                                if (!VoiceReply.ready()) {
                                                    showToast(context, "语音未配置：请到「设置 → AI 回复配置」填写 API Key / Cookie")
                                                    return@Button
                                                }
                                                showToast(context, "正在合成语音…")
                                                // 合成是异步的，回调到了再关面板
                                                VoiceReply.send(talker, finalText) { sent ->
                                                    if (sent) onDismiss()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("🔊 语音") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { generate() },
                    enabled = !loading,
                ) {
                    Text(
                        stringResource(
                            if (candidates.isEmpty()) R.string.ama_generate
                            else R.string.asr_regenerate
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    /** 被回复的这条是视频：交给 MediaVision 抽帧识别，失败返回 null。 */
    private fun describeRepliedVideo(msg: MessageInfo): String? = runCatching {
        MediaVision.describe(
            msg = WeMessage(
                msgId = 0L,
                msgSvrId = msg.serverId,
                talker = msg.talker,
                content = "",
                typeCode = msg.type?.code ?: MessageType.VIDEO.code,
                createTime = 0L,
                isSend = 0,
                imgPath = null,
            ),
            cacheDir = File(HostInfo.application.cacheDir, "media-vision"),
            imagePrompt = IMAGE_PROMPT,
            stickerPrompt = STICKER_PROMPT,
            animationPrompt = ANIMATION_PROMPT,
            videoPrompt = VIDEO_PROMPT,
        )
    }
        .onFailure { WeLogger.w(TAG, "describe replied video failed: ${it.message}") }
        .getOrNull()

    private fun msgMessageText(msg: MessageInfo): String =
        // 文字用原文；语音用微信转写文本（没有则 [语音]）；表情用 [表情]
        ReplyMessageSupport.replyText(msg) ?: ""

    private suspend fun loadRecentContext(talker: String, limit: Int): String {
        if (talker.isEmpty()) return ""
        return try {
            val now = System.currentTimeMillis()
            val rows = WeDatabaseApi.getMessagesInRange(talker, now - 7L * 86400000L, now)
                .filter {
                    it.content.isNotBlank() ||
                        MediaVision.isVisual(it.type) ||
                        it.typeCode == MessageType.VOICE.code
                }
                .takeLast(limit)
            val withMedia = mediaContext && MediaVision.available()
            val cacheDir = File(HostInfo.application.cacheDir, "media-vision")
            withContext(Dispatchers.IO) {
                var liveVoiceBudget = MAX_LIVE_VOICE_TRANSCRIPTS
                // 语音消息的 content 是 voicemsg XML，直接拼进上下文只会污染提示词，改成缓存转写或现场识别。
                fun voiceLine(msg: WeMessage): String {
                    val path = msg.imgPath
                    if (path.isNullOrBlank() || !AsrEngine.ready()) return "[语音]"
                    val cached = runCatching { AsrEngine.cachedTranscript(path) }.getOrNull()
                    if (!cached.isNullOrBlank()) return "[语音] $cached"
                    if (!withMedia || liveVoiceBudget <= 0) return "[语音]"
                    liveVoiceBudget--
                    val fresh = runCatching { AsrEngine.transcriptForContext(path) }
                        .onFailure { WeLogger.w(TAG, "voice transcript for context failed: ${it.message}") }
                        .getOrNull()
                    return if (fresh.isNullOrBlank()) "[语音]" else "[语音] $fresh"
                }
                rows.joinToString("\n") { msg ->
                    val head = if (msg.isSend != 0) "我：" else "对方："
                    if (msg.typeCode == MessageType.VOICE.code) {
                        head + voiceLine(msg)
                    } else if (!withMedia || !MediaVision.isVisual(msg.type)) {
                        head + msg.content
                    } else {
                        val label = msg.type?.displayName.orEmpty()
                        val key = "${msg.msgSvrId}-${msg.typeCode}"
                        var text = mediaCache[key]
                        if (text == null) {
                            text = MediaVision.describe(
                                msg = msg,
                                cacheDir = cacheDir,
                                imagePrompt = IMAGE_PROMPT,
                                stickerPrompt = STICKER_PROMPT,
                                animationPrompt = ANIMATION_PROMPT,
                                videoPrompt = VIDEO_PROMPT,
                            ).orEmpty()
                            mediaCache[key] = text
                        }
                        if (text.isBlank()) "$head[$label]" else "$head[$label 分析] $text"
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "load context failed", e); ""
        }
    }

    private suspend fun generateCandidates(talker: String, content: String, styleName: String, promptOverride: String? = null): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                // 模型统一取「多模态」设置里的纯文本模型（没配就退回视觉模型）
                if (!MultimodalEngine.textUsable()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            HostInfo.application.getString(R.string.ai_reply_err_multimodal_unset),
                        ),
                    )
                }

                val count = replyCount.coerceIn(1, 20)
                val stylePrompt = promptOverride?.takeIf { it.isNotBlank() }
                    ?: STYLES.firstOrNull { it.first == styleName }?.second
                    ?: "回复自然得体，像正常人一样交流。"
                val contextText = loadRecentContext(talker, contextLimit.coerceIn(1, 50))
                val systemPrompt = buildString {
                    append("你是微信聊天助手。语气要求：$stylePrompt\n")
                    append("根据对方最后一条消息和最近聊天记录，生成${count}条可以直接发出去的回复。\n")
                    append("每条 15–45 字，要有实质内容：先接住对方说的具体内容，再带一点自己的态度、关心、补充或追问；\n")
                    append("不要出现只回“嗯”“好的”“在”“收到”“哈哈”这类一两个字的敷衍回复，也不要写成书面语、长段作文或分点说明。\n")
                    append("每条单独一行，不要序号，不要多余解释。")
                    if (contextText.isNotBlank()) append("\n\n最近聊天记录参考：\n$contextText")
                }
                // 从输入栏打开时没有选中具体消息，content 为空；空 content 会被服务端以
                // "user content required" 拒绝，这里补一句明确的指令并把聊天记录带上。
                val userContent = content.takeIf { it.isNotBlank() }
                    ?: buildString {
                        append("请根据下面的最近聊天记录，生成${count}条我可以直接发送的回复。")
                        if (contextText.isNotBlank()) append("\n\n最近聊天记录：\n").append(contextText)
                    }
                val sb = StringBuilder(
                    VisionClient.streamChat(
                        system = systemPrompt,
                        user = userContent,
                        maxTokens = 2048,
                    ),
                )
                Result.success(
                    sb.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .filter { !it.matches(Regex("^\\d+[.、．]?.*")) }
                        .filter { it.isNotBlank() }.take(count)
                        .ifEmpty { listOf(sb.toString().trim()).filter { it.isNotBlank() } }
                )
            } catch (e: Exception) {
                WeLogger.e(TAG, "generate failed: ${e.message}", e); Result.failure(e)
            }
        }
}

private object AiSmartReplyIcon : VectorPathDrawable(
    "M19,8l-4,4h3c0,3.31 -2.69,6 -6,6c-1.01,0 -1.97,-0.25 -2.8,-0.7l-1.46,1.46C8.97,19.54 10.43,20 12,20c4.42,0 8,-3.58 8,-8h3L19,8zM6,12c0,-3.31 2.69,-6 6,-6c1.01,0 1.97,0.25 2.8,0.7l1.46,-1.46C15.03,4.46 13.57,4 12,4c-4.42,0 -8,3.58 -8,8H1l4,4l4,-4H6z"
)