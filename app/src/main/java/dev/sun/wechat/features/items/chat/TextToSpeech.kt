package dev.sun.wechat.features.items.chat

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Graphic_eq
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Settings
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.ui.utils.MicIcon
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.AudioUtils
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TextToSpeech :
    ClickableFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider,
    WeChatInputBarMenuApi.IActionItemsProvider {

    override val technicalId = "文字转语音"
    override val nameRes = R.string.feature_text_to_speech_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_text_to_speech_description

    private const val TAG = "TextToSpeech"
    private const val API_BASE = "https://peiyinmofang.com"

    const val BACKEND_MOFA = 0
    const val BACKEND_DOUBAO = 1

    // 豆包网页端协议 (Samantha 音频通道) 整体下沉到 DoubaoSamanthaTts:
    // 握手期鉴权、参数拼接、音频接收、30 秒超时、8 秒节流与 180 秒风控冷却都在那边

    var apiKey by prefOption("tts_api_key", "")
    var selectedVoice by prefOption("tts_voice_id", "琅琊榜-梅长苏")
    var selectedEmotion by prefOption("tts_emotion", "平静")
    /** 自定义音色 ID；非空时优先于 [selectedVoice] / [doubaoSpeaker]。 */
    var customVoiceId by prefOption("tts_custom_voice_id", "")

    var backend by prefOption("tts_backend", BACKEND_MOFA)
    var doubaoCookie by prefOption("tts_doubao_cookie", "")
    var doubaoSpeaker by prefOption("tts_doubao_speaker", "zh_female_shuangkuai_emo_v3_wvae_bigtts")
    var doubaoSpeakerName by prefOption("tts_doubao_speaker_name", "爽快 · 女声")

    /** AI 回复是否带语音（AI 回复配置页与自动回复共用）。 */
    var voiceReplyEnabled by prefOption("ai_reply_tts_enabled", true)

    /** 入口一: 长按聊天消息气泡的菜单 */
    private var entryBubble by prefOption("tts_entry_bubble", false)

    /** 入口二: 长按输入栏加号/发送按钮弹出的菜单 */
    private var entryInputBar by prefOption("tts_entry_input_bar", false)

    private val mh = Handler(Looper.getMainLooper())
    private var lastWavPath: String? = null

    data class TtsVoice(val voiceId: String, val label: String)

    val DEFAULT_VOICES = listOf(
        TtsVoice("琅琊榜-梅长苏", "琅琊榜-梅长苏"),
        TtsVoice("琅琊榜-靖王", "琅琊榜-靖王"),
        TtsVoice("甄嬛传-甄嬛", "甄嬛传-甄嬛"),
    )

    data class DoubaoVoice(val id: String, val label: String)

    /**
     * 内置 9 个音色：由 [DoubaoSamanthaTts.VOICES] 派生，和设置页试听列表、WebSocket 客户端
     * 共用同一份表，避免音色 id 两处漂移。这些 id 都不在豆包推荐接口里，所以只有名字，
     * 没有头像和分类标签。
     */
    val DOUBAO_VOICES: List<DoubaoVoice> = DoubaoSamanthaTts.VOICES.map { (id, label) ->
        DoubaoVoice(id, label)
    }

    val EMOTIONS = listOf(
        "平静" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "开心" to floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "悲伤" to floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "愤怒" to floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f),
        "惊讶" to floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
        "害怕" to floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f),
        "温柔" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f),
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
        WeChatInputBarMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        WeChatInputBarMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    fun isSupported(msgInfo: MessageInfo): Boolean =
        msgInfo.type == MessageType.TEXT || msgInfo.type == MessageType.QUOTE

    private val micImageVector = MaterialSymbols.Outlined.Graphic_eq

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        if (!entryBubble) return emptyList()

        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777025,
                localizedChatString(R.string.chat_tts_menu),
                MicIcon,
                micImageVector,
                isSupported = ::isSupported,
                onClick = { view, _, msgInfo ->
                    val text = msgInfo.humanReadableRepr.trim()
                    if (text.isEmpty()) {
                        showToast(view.context, "该消息没有可转语音的文本")
                        return@MenuItem
                    }
                    showMainDialog(view.context, msgInfo.talker, text)
                }
            )
        )
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> {
        if (!entryInputBar) return emptyList()

        return listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "text_to_speech",
                icon = micImageVector,
                label = localizedChatString(R.string.chat_tts_menu),
                onClick = { context, chatFooter ->
                    showMainDialog(context, WeCurrentConversationApi.value, chatFooter.lastText.trim())
                }
            )
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var bubbleEntry by remember { mutableStateOf(entryBubble) }
            var inputBarEntry by remember { mutableStateOf(entryInputBar) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_text_to_speech_name)) },
                text = {
                    SegmentedColumn(title = stringResource(R.string.tts_entry_group_title)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.tts_entry_bubble),
                                description = stringResource(R.string.tts_entry_bubble_description),
                                checked = bubbleEntry,
                                onCheckedChange = {
                                    bubbleEntry = it
                                    entryBubble = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.tts_entry_input_bar),
                                description = stringResource(R.string.tts_entry_input_bar_description),
                                checked = inputBarEntry,
                                onCheckedChange = {
                                    inputBarEntry = it
                                    entryInputBar = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showMainDialog(context: android.content.Context, talker: String, initialText: String) {
        // 点弹窗外面不关（避免误触丢掉已生成的语音），但返回键仍可关；显式出口是底部「关闭」
        showComposeDialog(context) {
            var inputText by remember { mutableStateOf(initialText) }
            var backendMode by remember { mutableIntStateOf(backend) }
            val isDoubao = backendMode == BACKEND_DOUBAO
            var voiceId by remember { mutableStateOf(if (isDoubao) doubaoSpeaker else selectedVoice) }
            var emotion by remember {
                mutableStateOf(
                    selectedEmotion.takeIf { e -> EMOTIONS.any { it.first == e } }
                        ?: EMOTIONS.first().first
                )
            }
            var voices by remember {
                mutableStateOf(
                    if (DEFAULT_VOICES.any { it.voiceId == selectedVoice }) DEFAULT_VOICES
                    else DEFAULT_VOICES + TtsVoice(selectedVoice, selectedVoice)
                )
            }
            var customVoices by remember { mutableStateOf(emptyList<TtsVoice>()) }
            var generating by remember { mutableStateOf(false) }
            var credentialsRevision by remember { mutableIntStateOf(0) }

            LaunchedEffect(backendMode, credentialsRevision) {
                // 魔方音色列表按后端重新拉取: 从豆包切回魔方时不能沿用旧快照;
                // apiKey 是裸 MMKV 委托、不可观察, 所以填完 Key 由设置弹窗递增 revision 来触发重拉
                if (backendMode != BACKEND_MOFA) return@LaunchedEffect
                Thread {
                    val fetched = fetchVoices()
                    val custom = fetchUserVoices()
                    mh.post {
                        // 异步结果可能晚于用户再次切换到达, 只在仍处于魔方模式时回填
                        if (backend != BACKEND_MOFA) return@post
                        voices = fetched
                        customVoices = custom
                        val inSystem = fetched.any { it.voiceId == voiceId }
                        val inCustom = custom.any { it.voiceId == voiceId }
                        // 当前所选不在魔方列表里 (含被豆包音色污染的残留值) 时归位
                        if (!inSystem && !inCustom && fetched.isNotEmpty()) {
                            voiceId = fetched.first().voiceId
                            selectedVoice = voiceId
                        }
                    }
                }.start()
            }

            AlertDialogContent(
                title = { Text("文字转语音") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text("输入要转成语音的文本") },
                        )

                        Spacer(Modifier.height(8.dp))

                        val mainOptions = voices.map { DropdownOption(it.voiceId, it.label) }
                        val customOptions = customVoices.map { DropdownOption(it.voiceId, it.label) }
                        val customCurrent = customOptions.any { it.value == voiceId }

                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                if (isDoubao) {
                                    BaseWidget(
                                        title = "豆包音色",
                                        description = doubaoSpeakerName,
                                        onClick = {
                                            showDoubaoVoicePicker(context, voiceId, doubaoCookie) { id, name ->
                                                voiceId = id
                                                doubaoSpeaker = id
                                                doubaoSpeakerName = name
                                            }
                                        },
                                        trailingContent = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                                contentDescription = null,
                                            )
                                        },
                                    )
                                } else {
                                    DropDownMenuWidget(
                                        title = "系统音色",
                                        description = null,
                                        value = voiceId,
                                        options = if (mainOptions.any { it.value == voiceId }) mainOptions
                                            else mainOptions + DropdownOption(voiceId, voiceId),
                                        onValueChange = {
                                            voiceId = it
                                            selectedVoice = it
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                DropDownMenuWidget(
                                    title = "自定义音色",
                                    description = if (customCurrent) null else "未选择",
                                    value = voiceId,
                                    options = if (customCurrent) customOptions
                                        else customOptions + DropdownOption(voiceId, ""),
                                    onValueChange = { voiceId = it; selectedVoice = it },
                                    enabled = !isDoubao && customVoices.isNotEmpty(),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        DropDownMenuWidget(
                            title = "语气",
                            description = if (isDoubao) emotion else null,
                            value = emotion,
                            options = if (isDoubao) listOf(DropdownOption(emotion, emotion))
                                else EMOTIONS.map { DropdownOption(it.first, it.first) },
                            onValueChange = { emotion = it; selectedEmotion = it },
                            enabled = !isDoubao,
                        )

                        Spacer(Modifier.height(12.dp))

                        if (generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (inputText.trim().isEmpty()) {
                                        showToast(context, "请输入文本")
                                        return@Button
                                    }
                                    if (isDoubao) {
                                        if (doubaoCookie.isBlank()) {
                                            showToast(context, "请先在设置中填写豆包 Cookie")
                                            showSettingsDialog(context)
                                            return@Button
                                        }
                                        generating = true
                                        generateVoiceDoubao(inputText.trim(), voiceId) { path, err ->
                                            generating = false
                                            if (path != null) {
                                                showPreviewDialog(context, talker, path)
                                            } else {
                                                showToast(context, "生成失败：$err")
                                            }
                                        }
                                        return@Button
                                    }
                                    if (apiKey.isBlank()) {
                                        showToast(context, "请先在设置中填写 API Key")
                                        showSettingsDialog(context) { credentialsRevision++ }
                                        return@Button
                                    }
                                    generating = true
                                    generateVoice(
                                        inputText.trim(),
                                        voiceId,
                                        EMOTIONS.first { it.first == emotion }.second,
                                    ) { wavPath, err ->
                                        generating = false
                                        if (wavPath != null) {
                                            showPreviewDialog(context, talker, wavPath)
                                        } else {
                                            showToast(context, "生成失败：$err")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("生成")
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    val wav = lastWavPath
                                    if (wav == null || !File(wav).exists()) {
                                        showToast(context, "请先生成语音")
                                        return@Button
                                    }
                                    sendVoiceTo(talker, wav) { ok ->
                                        if (ok) {
                                            showToast(context, "语音已发送")
                                            onDismiss()
                                        } else {
                                            showToast(context, "发送失败")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("发送语音")
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                if (backendMode == BACKEND_MOFA) {
                                    selectedVoice = voiceId
                                    backendMode = BACKEND_DOUBAO
                                    voiceId = doubaoSpeaker
                                } else {
                                    doubaoSpeaker = voiceId
                                    backendMode = BACKEND_MOFA
                                    voiceId = selectedVoice
                                }
                                backend = backendMode
                            }) {
                                Icon(
                                    MaterialSymbols.Outlined.Compare_arrows,
                                    contentDescription = "切换后端",
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (backendMode == BACKEND_MOFA) "魔方" else "豆包")
                            }
                            Row {
                                TextButton(onClick = { showSettingsDialog(context) { credentialsRevision++ } }) {
                                    Icon(
                                        MaterialSymbols.Outlined.Settings,
                                        contentDescription = "设置",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("打开设置")
                                }
                                TextButton(onClick = { onDismiss() }) {
                                    Text("关闭")
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    /** 供「AI 回复配置」等处直接打开语音（TTS）配置弹窗。 */
    fun showVoiceConfig(context: android.content.Context, onSaved: () -> Unit = {}) {
        showSettingsDialog(context, onSaved)
    }

    private fun showSettingsDialog(context: android.content.Context, onApiKeySaved: () -> Unit = {}) {
        showComposeDialog(context) {
            var apiKeyState by remember { mutableStateOf(apiKey) }
            var cookieState by remember { mutableStateOf(doubaoCookie) }
            var doubaoVoiceState by remember { mutableStateOf(doubaoSpeaker) }
            var auditioningId by remember { mutableStateOf<String?>(null) }
            var playingId by remember { mutableStateOf<String?>(null) }
            var player by remember { mutableStateOf<MediaPlayer?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    runCatching { player?.stop() }
                    runCatching { player?.release() }
                    player = null
                }
            }

            fun stopAudition() {
                runCatching { player?.stop() }
                runCatching { player?.release() }
                player = null
                playingId = null
            }

            fun pickDoubaoVoice(id: String, label: String) {
                doubaoVoiceState = id
                doubaoSpeaker = id
                doubaoSpeakerName = label
            }

            /** 试听：8 秒最小间隔 + 180 秒风控冷却由 DoubaoSamanthaTts 统一管，错误文案带剩余秒数。 */
            fun auditionDoubaoVoice(id: String) {
                if (playingId == id) {
                    stopAudition()
                    return
                }
                if (cookieState.isBlank()) {
                    showToast(context, "请先填写豆包 Cookie")
                    return
                }
                stopAudition()
                auditioningId = id
                DoubaoSamanthaTts.synthesize(
                    DoubaoSamanthaTts.AUDITION_TEXT,
                    id,
                    cookieState,
                ) { path, error ->
                    auditioningId = null
                    if (path == null) {
                        showToast(context, error)
                        return@synthesize
                    }
                    val next = MediaPlayer()
                    player = next
                    playingId = id
                    next.setOnPreparedListener { it.start() }
                    next.setOnCompletionListener { playingId = null }
                    next.setOnErrorListener { _, _, _ ->
                        playingId = null
                        showToast(context, "试听播放失败，音频文件可能已损坏")
                        true
                    }
                    runCatching {
                        next.setDataSource(path)
                        next.prepareAsync()
                    }.onFailure {
                        playingId = null
                        showToast(context, "试听播放失败：${it.message}")
                    }
                }
            }

            AlertDialogContent(
                title = { Text("文字转语音设置") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(title = "配音魔方") {
                            item {
                                TextFieldDialogWidget(
                                    title = "API Key",
                                    value = apiKeyState,
                                    onValueChange = {
                                        apiKeyState = it
                                        apiKey = it
                                        onApiKeySaved()
                                    },
                                    dialogTitle = "设置 API Key",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，请前往 peiyinmofang.com 获取",
                                    password = true,
                                )
                            }
                        }
                        Text(
                            "用于调用配音魔方 TTS 接口，在官网「API Key 管理」中创建",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SegmentedColumn(title = "豆包") {
                            item {
                                TextFieldDialogWidget(
                                    title = "Cookie",
                                    value = cookieState,
                                    onValueChange = {
                                        cookieState = it
                                        doubaoCookie = it
                                    },
                                    dialogTitle = "设置豆包 Cookie",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，需包含 sessionid / sid_guard / uid_tt",
                                    password = true,
                                )
                            }
                            // 9 个内置音色：点整行选中，点右侧图标用当前 Cookie 现场合成一句试听
                            DoubaoSamanthaTts.VOICES.forEach { (id, label) ->
                                item(key = id) {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = label,
                                        description = if (doubaoVoiceState == id) "当前音色 · $id" else id,
                                        selected = doubaoVoiceState == id,
                                        enabled = auditioningId != id,
                                        onClick = { pickDoubaoVoice(id, label) },
                                        onTrailingClick = { auditionDoubaoVoice(id) },
                                        trailingContent = {
                                            when {
                                                auditioningId == id -> CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                )

                                                playingId == id -> Icon(
                                                    MaterialSymbols.Outlined.Pause,
                                                    contentDescription = "停止试听",
                                                    modifier = Modifier.size(20.dp),
                                                )

                                                else -> Icon(
                                                    MaterialSymbols.Outlined.Play_arrow,
                                                    contentDescription = "试听",
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            "9 个音色走豆包网页端 Samantha 通道，逐个试听按 8 秒最小间隔排队；" +
                                "约 6 次快速请求会触发服务端风控 (710022002)，届时自动冷却 180 秒",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "在已登录的 doubao.com 开发者工具 (Application → Cookies) 中复制三个字段, 形如 sessionid=…; sid_guard=…; uid_tt=…, 有效期约 30 天",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text("完成") } }
            )
        }
    }

    fun showPreviewDialog(context: android.content.Context, talker: String, wavPath: String) {
        showComposeDialog(context) {
            var playing by remember { mutableStateOf(false) }
            var durationMs by remember { mutableIntStateOf(0) }
            var positionMs by remember { mutableIntStateOf(0) }
            var player by remember { mutableStateOf<MediaPlayer?>(null) }

            DisposableEffect(Unit) {
                val p = MediaPlayer().apply {
                    setDataSource(wavPath)
                    prepare()
                    durationMs = duration
                    setOnCompletionListener { mh.post { playing = false } }
                }
                player = p
                onDispose {
                    runCatching { p.stop() }
                    p.release()
                }
            }

            LaunchedEffect(playing) {
                while (playing) {
                    player?.let { positionMs = it.currentPosition }
                    delay(200)
                }
            }

            AlertDialogContent(
                title = { Text("试听语音") },
                text = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Slider(
                            value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                            onValueChange = {
                                positionMs = it.toInt()
                                player?.seekTo(it.toInt())
                            },
                            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(positionMs))
                            Text(formatDuration(durationMs))
                        }
                        IconButton(
                            onClick = {
                                val p = player ?: return@IconButton
                                if (playing) {
                                    p.pause()
                                    playing = false
                                } else {
                                    p.seekTo(positionMs)
                                    p.start()
                                    playing = true
                                }
                            },
                        ) {
                            Icon(
                                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                contentDescription = if (playing) "暂停" else "播放",
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        player?.let { runCatching { it.stop() }.onFailure { } }
                        player?.release()
                        player = null
                        sendVoiceTo(talker, wavPath) { ok ->
                            if (ok) {
                                showToast(context, "语音已发送")
                                onDismiss()
                            } else {
                                showToast(context, "发送失败")
                            }
                        }
                    }) { Text("发送语音") }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) { Text("关闭") }
                }
            )
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun generateVoice(
        text: String,
        voiceId: String,
        emoVec: FloatArray,
        key: String = apiKey,
        cb: (String?, String) -> Unit,
    ) {
        Thread {
            var wavPath: String? = null
            var error = ""
            try {
                val body = JSONObject().apply {
                    put("voiceId", voiceId)
                    put("text", text)
                    put("emoVec", JSONArray(emoVec.map { it.toDouble() }))
                }.toString()
                val resp = httpPostJson("$API_BASE/api/open/v1/tts/simple-generate", body, key)
                if (resp.isEmpty()) {
                    error = "接口无响应 (HTTP 非 200 或网络异常)"
                } else {
                    val root = JSONObject(resp)
                    val audioUrl = root.optJSONObject("data")?.optString("audio").orEmpty()
                    if (audioUrl.isEmpty()) {
                        val msg = root.optString("msg").ifEmpty { root.optString("message") }
                        error = "code=${root.opt("code")} ${msg.ifEmpty { "未返回音频地址" }}"
                    } else {
                        val dir = File(KnownPaths.moduleCache.toFile(), "wcs_tts").apply { mkdirs() }
                        val dest = File(dir, "tts_${System.currentTimeMillis()}.wav")
                        if (download(audioUrl, dest)) {
                            wavPath = dest.absolutePath
                            lastWavPath = wavPath
                        } else {
                            error = "音频下载失败"
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "exception"
                WeLogger.w(TAG, "generate voice failed: $error")
            }
            val result = wavPath
            val reason = error
            mh.post { cb(result, reason) }
        }.start()
    }

    /**
     * 豆包合成：转交 [DoubaoSamanthaTts]（Samantha WebSocket 通道，替代旧 HTTP / 裸 WS 实现）。
     * 签名与旧实现完全一致，[VoiceReply]、[AiReplyConfigActivity]、[AiReplyCandidates] 等调用方无需改动。
     */
    fun generateVoiceDoubao(
        text: String,
        speaker: String,
        cookie: String = doubaoCookie,
        cb: (String?, String) -> Unit,
    ) {
        DoubaoSamanthaTts.synthesize(text, speaker, cookie) { path, error ->
            if (path != null) lastWavPath = path
            cb(path, error)
        }
    }

    fun sendVoiceTo(talker: String, wavPath: String, cb: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val silkPath = wavPath.substringBeforeLast('.') + ".silk"
                if (!AudioUtils.anyToSilk(wavPath, silkPath)) return@runCatching false
                val durationMs = AudioUtils.getDurationMs(silkPath).toInt()
                WeMessageApi.sendVoice(talker, silkPath, durationMs)
            }.getOrDefault(false)
            mh.post { cb(ok) }
        }.start()
    }

    fun fetchVoices(key: String = apiKey): List<TtsVoice> {
        if (key.isBlank()) return DEFAULT_VOICES
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/voices", key)
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return DEFAULT_VOICES
            val list = DEFAULT_VOICES.toMutableList()
            for (i in 0 until data.length()) {
                val group = data.getJSONObject(i)
                val title = group.optString("title")
                val chars = group.optJSONArray("characters") ?: continue
                for (j in 0 until chars.length()) {
                    val c = chars.getJSONObject(j)
                    val vid = c.optString("voice_id")
                    if (vid.isEmpty()) continue
                    if (list.any { it.voiceId == vid }) continue
                    val name = c.optString("name")
                    val actor = c.optString("actor")
                    val label = if (title.isNotEmpty()) "$title-$name" else name
                    list.add(TtsVoice(vid, if (actor.isNotEmpty()) "$label($actor)" else label))
                }
            }
            list
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch voices failed: ${e.message}")
            DEFAULT_VOICES
        }
    }

    fun fetchUserVoices(key: String = apiKey): List<TtsVoice> {
        if (key.isBlank()) return emptyList()
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/user-voices", key)
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val vid = item.optString("voice_id")
                    if (vid.isEmpty()) continue
                    val name = item.optString("name")
                    add(TtsVoice(vid, if (name.isEmpty()) vid else name))
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch user voices failed: ${e.message}")
            emptyList()
        }
    }

    private fun httpGet(urlStr: String, key: String = apiKey): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $key")
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String, key: String = apiKey): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun download(urlStr: String, dest: File): Boolean {
        return runCatching {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            c.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            c.disconnect()
            true
        }.getOrElse {
            WeLogger.w(TAG, "download wav failed: ${it.message}")
            false
        }
    }
}
