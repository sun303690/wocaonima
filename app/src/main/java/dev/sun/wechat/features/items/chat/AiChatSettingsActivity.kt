package dev.sun.wechat.features.items.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.content.miuix.MiuixRadioButtonWidget
import dev.sun.wechat.ui.utils.theme.MiuixModuleTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AiChatSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixModuleTheme {
                var tab by remember { mutableIntStateOf(0) }
                // 小窗卡片：不再占满整个屏幕，背景压暗，中间一个圆角面板
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.8f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MiuixTheme.colorScheme.background),
                    ) {
                        dev.sun.wechat.ui.content.miuix.MiuixSecondaryBar(
                            title = "AI 设置",
                            onBack = { finish() },
                            actions = {
                                Button(onClick = { AiChat.clearContext(); Toast.makeText(this@AiChatSettingsActivity, "AI 上下文已清除", Toast.LENGTH_SHORT).show() }) { Text("•••") }
                            },
                        )
                        TabRow(tabs = listOf("回复", "语音"), selectedTabIndex = tab, onTabSelected = { tab = it })
                        when (tab) {
                            0 -> ReplyPage()
                            else -> VoicePage()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AiSettingsScreen() {
        var tab by remember { mutableIntStateOf(0) }
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {
            dev.sun.wechat.ui.content.miuix.MiuixSecondaryBar(
                title = "AI 设置",
                onBack = { finish() },
                actions = {
                    Button(onClick = { AiChat.clearContext(); Toast.makeText(this@AiChatSettingsActivity, "AI 上下文已清除", Toast.LENGTH_SHORT).show() }) { Text("•••") }
                },
            )
            TabRow(tabs = listOf("回复", "语音"), selectedTabIndex = tab, onTabSelected = { tab = it })
            when (tab) {
                0 -> ReplyPage()
                else -> VoicePage()
            }
        }
    }

    @Composable private fun Page(content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

    @Composable private fun Block(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit = {}) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3)
            if (subtitle.isNotBlank()) Text(subtitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            content()
        }}
    }

    @Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(title); Text(subtitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Switch(checked, onChange)
        }
    }

    @Composable private fun ReplyPage() = Page {
        var enabled by remember { mutableStateOf(AiChat.isEnabled) }
        var cooldown by remember { mutableStateOf(AiChat.cooldownSeconds.toString()) }
        var daily by remember { mutableStateOf(AiChat.dailyLimit.toString()) }
        var chars by remember { mutableStateOf(AiChat.maxContextChars.toString()) }
        var mediaContext by remember { mutableStateOf(AiChat.mediaContext) }
        var voiceContext by remember { mutableStateOf(AiChat.voiceContext) }
        var liveIdentify by remember { mutableStateOf(AiChat.liveIdentify) }
        var whitelistCount by remember { mutableIntStateOf(AiChat.whitelist.size) }
        var showAdvanced by remember { mutableStateOf(false) }
        Block("自动回复", "只有授权会话会进入模型") { ToggleRow("启用 AI 自动回复", "关闭后不接收、不请求、不发送", enabled) { enabled = it; AiChat.isEnabled = it } }
        Block("授权会话", "白名单 $whitelistCount 个 · 黑名单 ${AiChat.blacklist.size} 个") {
            Button({
                AiChat.showAuthorizedChatsPicker(this@AiChatSettingsActivity, AiChat.whitelist) {
                    whitelistCount = it.size
                }
            }, Modifier.fillMaxWidth()) { Text("选择联系人或群聊") }
        }
        Block("频率与上下文") {
            Edit("冷却秒数", cooldown) { cooldown = it; it.toIntOrNull()?.let { n -> AiChat.cooldownSeconds = n.coerceIn(0, 3600) } }
            Edit("单会话每日上限，0 不限制", daily) { daily = it; it.toIntOrNull()?.let { n -> AiChat.dailyLimit = n.coerceIn(0, 10000) } }
            Edit("上下文字符上限", chars) { chars = it; it.toIntOrNull()?.let { n -> AiChat.maxContextChars = n.coerceIn(1000, 100000) } }
            ToggleRow(
                "上下文识别图片/表情",
                "把最近消息里的图片、表情包、动图、视频送去视觉模型识别后写进上下文；按张计费，同一张只识别一次",
                mediaContext,
            ) { mediaContext = it; AiChat.mediaContext = it }
            ToggleRow(
                "上下文识别语音",
                "把最近消息里的语音转成文字再写进上下文；需要先在多模态里配好语音接口。语音文件没下载过（没在微信里点开过）的会跳过",
                voiceContext,
            ) { voiceContext = it; AiChat.voiceContext = it }
            ToggleRow(
                "现场识别图片/语音",
                "开启后回填上下文时会实时调用视觉/语音接口识别最近几条消息，按量计费的接口每识别一条都会产生费用；关闭时只使用已经识别过的结果，其余留占位符",
                liveIdentify,
            ) { liveIdentify = it; AiChat.liveIdentify = it }
            Button({ AiChat.clearContext() }, Modifier.fillMaxWidth()) { Text("清除全部上下文") }
        }
        Block("高级设置", "人设提示词、群聊触发方式、采样参数、仅监控等 11 项") {
            Button({ showAdvanced = true }, Modifier.fillMaxWidth()) { Text("打开高级设置") }
        }
        if (showAdvanced) AdvancedDialog { showAdvanced = false }
    }

    @Composable private fun Edit(label: String, value: String, onChange: (String) -> Unit) =
        MiuixCompatTextField(value, onChange, Modifier.fillMaxWidth(), label = label)

    /** 高级设置弹窗：把 AiChat 里一直没有入口的 11 项集中放一处，点开就能改，不用就地折叠。 */
    @Composable
    private fun AdvancedDialog(onDismiss: () -> Unit) {
        var systemPrompt by remember { mutableStateOf(AiChat.systemPrompt) }
        var trigger by remember { mutableIntStateOf(AiChat.groupTriggerMode) }
        var keywords by remember { mutableStateOf(AiChat.groupKeywords) }
        var prefix by remember { mutableStateOf(AiChat.groupPrefix) }
        var maxReplyChars by remember { mutableStateOf(AiChat.groupMaxReplyChars.toString()) }
        var rounds by remember { mutableStateOf(AiChat.contextRounds.toString()) }
        var tokens by remember { mutableStateOf(AiChat.maxTokens.toString()) }
        var tempInput by remember { mutableStateOf(AiChat.temperature.toString()) }
        var delayInput by remember { mutableStateOf(AiChat.replyDelayMs.toString()) }
        var backfill by remember { mutableStateOf(AiChat.historyBackfillCount.toString()) }
        var monitorOnly by remember { mutableStateOf(AiChat.monitorOnly) }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.84f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MiuixTheme.colorScheme.background),
            ) {
                dev.sun.wechat.ui.content.miuix.MiuixSecondaryBar(title = "高级设置", onBack = onDismiss)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Block("人设", "写清 AI 是谁、用什么语气说话；留空就用内置默认人设") {
                        MiuixCompatTextField(
                            systemPrompt,
                            { systemPrompt = it; AiChat.systemPrompt = it },
                            Modifier.fillMaxWidth().height(140.dp),
                            label = "系统提示词",
                        )
                    }
                    Block("群聊触发", "群里什么消息才会让 AI 开口") {
                        MiuixDropDownMenuWidget(
                            title = "触发方式",
                            description = "关键词模式只看关键词，前缀模式只看指令前缀",
                            value = trigger,
                            options = TRIGGER_OPTIONS,
                            onValueChange = { trigger = it; AiChat.groupTriggerMode = it },
                        )
                        if (trigger == TRIGGER_KEYWORD) {
                            Edit("关键词，多个用逗号分隔", keywords) { keywords = it; AiChat.groupKeywords = it }
                        }
                        if (trigger == TRIGGER_PREFIX) {
                            Edit("指令前缀，例如 /ai", prefix) { prefix = it; AiChat.groupPrefix = it }
                        }
                        Edit("群聊回复字数上限（100–4000）", maxReplyChars) {
                            maxReplyChars = it
                            it.toIntOrNull()?.let { n -> AiChat.groupMaxReplyChars = n.coerceIn(100, 4000) }
                        }
                    }
                    Block("上下文", "调大更聪明，也更费 token") {
                        Edit("上下文轮数（0–20）", rounds) {
                            rounds = it
                            it.toIntOrNull()?.let { n -> AiChat.contextRounds = n.coerceIn(0, 20) }
                        }
                        Edit("单次输出上限 maxTokens（1–32768）", tokens) {
                            tokens = it
                            it.toIntOrNull()?.let { n -> AiChat.maxTokens = n.coerceIn(1, 32768) }
                        }
                        Edit("温度 0–2，越大越随机", tempInput) {
                            tempInput = it
                            it.toFloatOrNull()?.let { v -> AiChat.temperature = v.coerceIn(0f, 2f) }
                        }
                        Edit("回复延迟毫秒（0–60000）", delayInput) {
                            delayInput = it
                            it.toLongOrNull()?.let { n -> AiChat.replyDelayMs = n.coerceIn(0L, 60_000L) }
                        }
                        Edit("历史回填条数（1–50）", backfill) {
                            backfill = it
                            it.toIntOrNull()?.let { n -> AiChat.historyBackfillCount = n.coerceIn(1, 50) }
                        }
                    }
                    Block("监控", "只记录不回复，先用它确认触发条件对不对") {
                        ToggleRow(
                            "仅监控模式",
                            "开启后不发送任何消息，只在日志里记录命中的会话与内容",
                            monitorOnly,
                        ) { monitorOnly = it; AiChat.monitorOnly = it }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }


    @Composable private fun VoicePage() = Page {
        var voiceEnabled by remember { mutableStateOf(AiChat.voiceReplyEnabled) }
        var summary by remember { mutableStateOf(TextToSpeech.voiceSummary()) }

        Block("自动文字转语音", "对方发来消息，AI 生成回复后自动转成语音发送") {
            ToggleRow("自动转语音发送", "开启后 AI 回复以语音消息发出，不再发送文字", voiceEnabled) { voiceEnabled = it; AiChat.voiceReplyEnabled = it }
        }
        Block("语音", "后端、Key 与音色都在「文字转语音」功能页里配置，这里只挑用哪个") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择语音")
                    Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Button({
                    TextToSpeech.showVoiceConfig(this@AiChatSettingsActivity) { summary = TextToSpeech.voiceSummary() }
                }) { Text("去配置") }
            }
        }

    }

    private companion object {
        /** 与 AiChat 里的私有常量对齐：0 全部 / 1 @我 / 2 关键词 / 3 前缀。 */
        private const val TRIGGER_ALL = 0
        private const val TRIGGER_MENTION = 1
        private const val TRIGGER_KEYWORD = 2
        private const val TRIGGER_PREFIX = 3
        private val TRIGGER_OPTIONS = listOf(
            DropdownOption(TRIGGER_ALL, "所有消息"),
            DropdownOption(TRIGGER_MENTION, "@我"),
            DropdownOption(TRIGGER_KEYWORD, "关键词"),
            DropdownOption(TRIGGER_PREFIX, "指令前缀"),
        )
    }
}
