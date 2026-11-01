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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        var whitelistCount by remember { mutableIntStateOf(AiChat.whitelist.size) }
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
            Button({ AiChat.clearContext() }, Modifier.fillMaxWidth()) { Text("清除全部上下文") }
        }
    }

    @Composable private fun Edit(label: String, value: String, onChange: (String) -> Unit) =
        MiuixCompatTextField(value, onChange, Modifier.fillMaxWidth(), label = label)

    @Composable private fun VoicePage() = Page {
        var voiceEnabled by remember { mutableStateOf(AiChat.voiceReplyEnabled) }
        var backend by remember { mutableStateOf(TextToSpeech.backend) }
        var breezeKey by remember { mutableStateOf(TextToSpeech.breezeKey) }
        var cookie by remember { mutableStateOf(TextToSpeech.doubaoCookie) }
        var mofaKey by remember { mutableStateOf(TextToSpeech.apiKey) }
        var voiceId by remember { mutableStateOf(currentVoiceId()) }
        var emotion by remember { mutableStateOf(TextToSpeech.selectedEmotion) }
        val doubaoTts = backend == TextToSpeech.BACKEND_DOUBAO
        val breezeTts = backend == TextToSpeech.BACKEND_BREEZE

        Block("自动文字转语音", "对方发来消息，AI 生成回复后自动转成语音发送") {
            ToggleRow("自动转语音发送", "开启后 AI 回复以语音消息发出，不再发送文字", voiceEnabled) { voiceEnabled = it; AiChat.voiceReplyEnabled = it }
        }
        Block("语音参数", "与「文字转语音」「帮忙回」共用同一套设置") {
            MiuixRadioButtonWidget(
                title = "Breeze Blue",
                description = "音色丰富，支持克隆音色",
                selected = breezeTts,
                onSelect = {
                    backend = TextToSpeech.BACKEND_BREEZE
                    TextToSpeech.backend = TextToSpeech.BACKEND_BREEZE
                    voiceId = currentVoiceId()
                },
            )
            MiuixRadioButtonWidget(
                title = "豆包",
                description = "需要豆包 Cookie",
                selected = doubaoTts,
                onSelect = {
                    backend = TextToSpeech.BACKEND_DOUBAO
                    TextToSpeech.backend = TextToSpeech.BACKEND_DOUBAO
                    voiceId = currentVoiceId()
                },
            )
            MiuixRadioButtonWidget(
                title = "配音魔方",
                description = "旧魔方接口",
                selected = backend == TextToSpeech.BACKEND_MOFA,
                onSelect = {
                    backend = TextToSpeech.BACKEND_MOFA
                    TextToSpeech.backend = TextToSpeech.BACKEND_MOFA
                    voiceId = currentVoiceId()
                },
            )
            if (breezeTts) {
                TextFieldDialogWidget(
                    title = "Breeze API Key",
                    value = breezeKey,
                    onValueChange = { breezeKey = it.trim(); TextToSpeech.breezeKey = breezeKey },
                    dialogTitle = "设置 Breeze API Key",
                    confirmLabel = "确认",
                    dismissLabel = "取消",
                    valueHint = "在 breezeblue.ai 的 Developer → API Keys 创建",
                    password = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("音色")
                        Text(TextToSpeech.breezeVoiceName.ifBlank { voiceId }, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Button({
                        showBreezeVoicePicker(this@AiChatSettingsActivity, voiceId, breezeKey) { id, name ->
                            TextToSpeech.breezeVoice = id
                            TextToSpeech.breezeVoiceName = name
                            voiceId = id
                        }
                    }) { Text("选择音色") }
                }
            } else if (doubaoTts) {
                TextFieldDialogWidget(
                    title = "豆包 Cookie",
                    value = cookie,
                    onValueChange = { cookie = it.trim(); TextToSpeech.doubaoCookie = cookie },
                    dialogTitle = "设置豆包 Cookie",
                    confirmLabel = "确认",
                    dismissLabel = "取消",
                    valueHint = "需包含 sessionid / sid_guard / uid_tt",
                    password = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("音色")
                        Text(doubaoSpeakerLabel(voiceId), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Button({
                        showDoubaoVoicePicker(this@AiChatSettingsActivity, voiceId, cookie) { id, name ->
                            TextToSpeech.doubaoSpeaker = id
                            TextToSpeech.doubaoSpeakerName = name
                            voiceId = id
                        }
                    }) { Text("选择音色") }
                }
            } else {
                TextFieldDialogWidget(
                    title = "魔方 API Key",
                    value = mofaKey,
                    onValueChange = { mofaKey = it.trim(); TextToSpeech.apiKey = mofaKey },
                    dialogTitle = "设置魔方 API Key",
                    confirmLabel = "确认",
                    dismissLabel = "取消",
                    valueHint = "在 peiyinmofang.com 创建",
                    password = true,
                )
                MiuixDropDownMenuWidget(
                    iconPlaceholder = false,
                    title = "音色",
                    description = null,
                    value = voiceId,
                    options = TextToSpeech.DEFAULT_VOICES.map { DropdownOption(it.voiceId, it.label) },
                    onValueChange = { voiceId = it; TextToSpeech.selectedVoice = it },
                )
            }
            MiuixDropDownMenuWidget(
                iconPlaceholder = false,
                title = "情绪",
                description = "生成语音时使用的情绪语气",
                value = emotion,
                options = TextToSpeech.EMOTIONS.map { DropdownOption(it.first, it.first) },
                enabled = !doubaoTts,
                onValueChange = { emotion = it; TextToSpeech.selectedEmotion = it },
            )
        }
    }
}

private fun currentVoiceId(): String = when (TextToSpeech.backend) {
    TextToSpeech.BACKEND_DOUBAO -> TextToSpeech.customVoiceId.ifBlank { TextToSpeech.doubaoSpeaker.ifBlank { TextToSpeech.DOUBAO_VOICES.first().id } }
    TextToSpeech.BACKEND_BREEZE -> TextToSpeech.customVoiceId.ifBlank { TextToSpeech.breezeVoice }
    else -> TextToSpeech.customVoiceId.ifBlank { TextToSpeech.selectedVoice }
}

private fun doubaoSpeakerLabel(id: String): String =
    if (TextToSpeech.doubaoSpeakerName.isNotBlank() && TextToSpeech.doubaoSpeaker == id) TextToSpeech.doubaoSpeakerName
    else TextToSpeech.DOUBAO_VOICES.firstOrNull { it.id == id }?.label ?: id