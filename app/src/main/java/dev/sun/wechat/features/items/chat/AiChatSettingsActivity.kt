package dev.sun.wechat.features.items.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import dev.sun.wechat.ui.content.miuix.IosDivider
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import dev.sun.wechat.ui.content.miuix.MiuixRadioButtonWidget
import dev.sun.wechat.ui.utils.theme.MiuixModuleTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.content.Intent
import dev.sun.wechat.features.items.chat_mood.AgentLlm
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.MultimodalSettingsActivity
import kotlinx.coroutines.launch

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
                            title = "AI 群聊",
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
                title = "AI 群聊",
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
        var modelBusy by remember { mutableStateOf(false) }
        var modelResult by remember { mutableStateOf("") }
        val modelScope = rememberCoroutineScope()
        var cooldown by remember { mutableStateOf(AiChat.cooldownSeconds.toString()) }
        var daily by remember { mutableStateOf(AiChat.dailyLimit.toString()) }
        var chars by remember { mutableStateOf(AiChat.maxContextChars.toString()) }
        var mediaContext by remember { mutableStateOf(AiChat.mediaContext) }
        var voiceContext by remember { mutableStateOf(AiChat.voiceContext) }
        var liveIdentify by remember { mutableStateOf(AiChat.liveIdentify) }
        var whitelistCount by remember { mutableIntStateOf(AiChat.whitelist.size) }
        var showAdvanced by remember { mutableStateOf(false) }
        Block("模型", "AI 群聊生成回复用的模型来源") {
            Text(
                if (MultimodalEngine.textUsingVisionFallback()) {
                    "文字接口未配置，借用「视觉接口」的模型"
                } else {
                    "跟随「多模态」→「文字接口」"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                MultimodalEngine.textModelOrVision().ifBlank { "未配置：请到「多模态」填好接口地址、Key 和模型" },
                style = MiuixTheme.textStyles.title3,
            )
            Text(
                MultimodalEngine.textEndpointBase().ifBlank { "接口：未配置" },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    startActivity(Intent(this@AiChatSettingsActivity, MultimodalSettingsActivity::class.java))
                }, Modifier.weight(1f)) { Text("多模态设置") }
                Button({
                    if (modelBusy) return@Button
                    modelBusy = true
                    modelResult = "测试中…"
                    modelScope.launch {
                        modelResult = runCatching { AgentLlm.complete("请只回复四个字：连接成功") }
                            .fold(
                                onSuccess = { "连接成功：${it.trim().take(60)}" },
                                onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" },
                            )
                        modelBusy = false
                    }
                }, Modifier.weight(1f)) { Text(if (modelBusy) "测试中…" else "测试连接") }
            }
            if (modelResult.isNotBlank()) {
                Text(modelResult, style = MiuixTheme.textStyles.footnote1)
            }
        }
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
                "现场识别图片/表情/视频",
                "只影响图片/表情/视频：开启后实时调用视觉接口识别最近几条，按量计费每识别一条都会产生费用；语音不受这个开关控制，只要配好语音接口就一律现场识别",
                liveIdentify,
            ) { liveIdentify = it; AiChat.liveIdentify = it }
            Button({ AiChat.clearContext() }, Modifier.fillMaxWidth()) { Text("清除全部上下文") }
        }
        Block("高级设置", "人设、采样参数、监控等 8 项") {
            Button({ showAdvanced = true }, Modifier.fillMaxWidth()) { Text("打开高级设置") }
        }
        if (showAdvanced) AdvancedDialog { showAdvanced = false }
    }

    @Composable private fun Edit(label: String, value: String, onChange: (String) -> Unit) =
        MiuixCompatTextField(value, onChange, Modifier.fillMaxWidth(), label = label)

    /** 高级设置弹窗：把 AiChat 里没有入口的参数集中在一处，分组卡片 + 右侧数值输入。 */
    @Composable
    private fun AdvancedDialog(onDismiss: () -> Unit) {
        var systemPrompt by remember { mutableStateOf(AiChat.systemPrompt) }
        var maxReplyChars by remember { mutableStateOf(AiChat.groupMaxReplyChars.toString()) }
        var backfill by remember { mutableStateOf(AiChat.historyBackfillCount.toString()) }
        var delayInput by remember { mutableStateOf(AiChat.replyDelayMs.toString()) }
        var rounds by remember { mutableStateOf(AiChat.contextRounds.toString()) }
        var tokens by remember { mutableStateOf(AiChat.maxTokens.toString()) }
        var tempInput by remember { mutableStateOf(AiChat.temperature.toString()) }
        var monitorOnly by remember { mutableStateOf(AiChat.monitorOnly) }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.86f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MiuixTheme.colorScheme.background),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("高级设置", style = MiuixTheme.textStyles.title3)
                        Text(
                            "人设与采样参数，改完立即生效",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Button(onClick = onDismiss) { Text("完成") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AdvancedSection("人设") {
                        val personaKey = remember(systemPrompt) { personaKeyOf(systemPrompt) }
                        val personaChoices = remember(systemPrompt) { personaOptions() }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            personaChoices.forEach { (key, label) ->
                                PersonaChip(label, selected = key == personaKey) {
                                    val prompt = personaPromptOf(key)
                                    if (prompt.isNotEmpty()) {
                                        systemPrompt = prompt
                                        AiChat.systemPrompt = prompt
                                    }
                                }
                            }
                        }
                        IosDivider(startInset = 0.dp)
                        MiuixCompatTextField(
                            systemPrompt,
                            { systemPrompt = it; AiChat.systemPrompt = it },
                            Modifier.fillMaxWidth().height(132.dp),
                            label = "系统提示词",
                            placeholder = { Text("选内置人设会自动填进来，也可以自己改") },
                        )
                    }
                    AdvancedSection("回复") {
                        AdvancedNumberRow("群聊回复字数上限", "100 – 4000", maxReplyChars) {
                            maxReplyChars = it
                            it.toIntOrNull()?.let { n -> AiChat.groupMaxReplyChars = n.coerceIn(100, 4000) }
                        }
                        IosDivider(startInset = 0.dp)
                        AdvancedNumberRow("历史回填条数", "1 – 50，每轮从数据库取几条历史", backfill) {
                            backfill = it
                            it.toIntOrNull()?.let { n -> AiChat.historyBackfillCount = n.coerceIn(1, 50) }
                        }
                        IosDivider(startInset = 0.dp)
                        AdvancedNumberRow("回复延迟", "毫秒，0 – 60000", delayInput) {
                            delayInput = it
                            it.toLongOrNull()?.let { n -> AiChat.replyDelayMs = n.coerceIn(0L, 60_000L) }
                        }
                    }
                    AdvancedSection("模型采样") {
                        AdvancedNumberRow("上下文轮数", "0 – 20，越大越贵", rounds) {
                            rounds = it
                            it.toIntOrNull()?.let { n -> AiChat.contextRounds = n.coerceIn(0, 20) }
                        }
                        IosDivider(startInset = 0.dp)
                        AdvancedNumberRow("单次输出上限", "maxTokens，1 – 32768", tokens) {
                            tokens = it
                            it.toIntOrNull()?.let { n -> AiChat.maxTokens = n.coerceIn(1, 32768) }
                        }
                        IosDivider(startInset = 0.dp)
                        AdvancedNumberRow("温度", "0 – 2，越大越随机", tempInput) {
                            tempInput = it
                            it.toFloatOrNull()?.let { v -> AiChat.temperature = v.coerceIn(0f, 2f) }
                        }
                    }
                    AdvancedSection("监控") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("仅监控模式")
                                Text(
                                    "只记录命中的会话与内容，不发送任何消息",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Switch(monitorOnly, { monitorOnly = it; AiChat.monitorOnly = it })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    /** 高级设置里的一组：小标题 + 圆角卡片，卡片内行与行之间用 0.5dp 分隔线。 */
    @Composable
    private fun AdvancedSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 6.dp),
            )
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }

    /** 内置 8 个 + 自建人设 + 「自定义」，供人设胶囊行使用。 */
    private fun personaOptions(): List<Pair<String, String>> =
        AI_PERSONAS.map { it.key to it.name } +
            loadCustomPersonas().map { CUSTOM_PERSONA_PREFIX + it.name to it.name + "（自建）" } +
            (PERSONA_CUSTOM to "自定义")

    /** 人设 key 对应的提示词；「自定义」返回空串，表示保留用户当前文本不动。 */
    private fun personaPromptOf(key: String): String = when {
        key == PERSONA_CUSTOM -> ""
        key.startsWith(CUSTOM_PERSONA_PREFIX) -> {
            val name = key.removePrefix(CUSTOM_PERSONA_PREFIX)
            loadCustomPersonas().firstOrNull { it.name == name }?.prompt.orEmpty()
        }
        else -> AI_PERSONAS.firstOrNull { it.key == key }?.prompt.orEmpty()
    }

    /** 人设胶囊：选中实心，未选出描边；横向一排，不占高度。 */
    @Composable
    private fun PersonaChip(title: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .clip(shape)
                .background(if (selected) Color(0xFF6750A4) else Color.Transparent)
                .border(1.dp, if (selected) Color.Transparent else Color(0x33888888), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                title,
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    /** 一行「标题 + 灰色说明 + 右侧窄输入框」，数字项的统一样式。 */
    @Composable
    private fun AdvancedNumberRow(title: String, hint: String, value: String, onChange: (String) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(
                    hint,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(12.dp))
            MiuixCompatTextField(value, onChange, Modifier.width(104.dp), label = "")
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

}
