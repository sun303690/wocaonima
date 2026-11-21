package dev.sun.wechat.activity.ai

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.MultimodalSettingsActivity
import dev.sun.wechat.features.items.chat.TextToSpeech
import dev.sun.wechat.features.items.chat.doubaoVoiceLabel
import dev.sun.wechat.features.items.chat.showBreezeVoicePicker
import dev.sun.wechat.features.items.chat.showDoubaoVoicePicker
import dev.sun.wechat.features.items.chat_mood.AgentLlm
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import kotlinx.coroutines.launch

/** 「AI 回复配置」独立页面：模型配置 + 语音配置（移植自 WCS 的语音配置弹窗，并统一到本模块）。 */
class AiReplyConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                val dark = ThemeSettings.themeMode.resolve()
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        AiReplyConfigScreen(onClose = { finish() })
                    }
                }
            }
        }
    }
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

private fun playWav(path: String) {
    runCatching {
        MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { it.release() }
            prepare()
            start()
        }
    }
}

@Composable
private fun AiReplyConfigScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelResult by remember { mutableStateOf("") }
    var modelBusy by remember { mutableStateOf(false) }

    var voiceReply by remember { mutableStateOf(TextToSpeech.voiceReplyEnabled) }
    var voicePanelSummary by remember { mutableStateOf(TextToSpeech.voiceSummary()) }
    var backend by remember { mutableStateOf(TextToSpeech.backend) }
    // 配音魔方与 Breeze Blue 是两套凭据，之前共用 apiKey 并把值写进 breezeKey，
    // 导致 TextToSpeech.apiKey 永远为空，VoiceReply.ready() 判定未配置。
    var mofaKey by remember { mutableStateOf(TextToSpeech.apiKey) }
    var breezeKeyState by remember { mutableStateOf(TextToSpeech.breezeKey) }
    var cookie by remember { mutableStateOf(TextToSpeech.doubaoCookie) }
    var mofaVoice by remember { mutableStateOf(TextToSpeech.selectedVoice) }
    var breezeVoice by remember { mutableStateOf(TextToSpeech.breezeVoice) }
    var breezeVoiceName by remember { mutableStateOf(TextToSpeech.breezeVoiceName) }
    var doubaoVoice by remember { mutableStateOf(TextToSpeech.doubaoSpeaker) }
    var emotion by remember { mutableStateOf(TextToSpeech.selectedEmotion) }
    var customVoice by remember { mutableStateOf(TextToSpeech.customVoiceId) }
    var showMofaPicker by remember { mutableStateOf(false) }
    var showEmotionPicker by remember { mutableStateOf(false) }
    var editField by remember { mutableStateOf<String?>(null) }

    val isBreeze = backend == TextToSpeech.BACKEND_BREEZE
    val apiKey = if (isBreeze) breezeKeyState else mofaKey

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 回复配置", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("关闭") }
        }

        Text("模型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (MultimodalEngine.textUsingVisionFallback()) {
                        "模型来源：文字接口未配置，使用「多模态」→「视觉接口」的模型"
                    } else {
                        "模型来源：跟随「多模态」→「文字接口」"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    MultimodalEngine.textModelOrVision()
                        .ifBlank { "未配置：请先到「多模态」里填好接口地址、Key 和模型" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (MultimodalEngine.textReady()) {
                        "接口：${MultimodalEngine.textEndpointBase()} · Key 已填"
                    } else if (MultimodalEngine.textUsingVisionFallback()) {
                        "接口：${MultimodalEngine.textEndpointBase()}（视觉接口承接） · Key 已填"
                    } else {
                        "接口：未配置（缺少地址或 Key）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, MultimodalSettingsActivity::class.java))
                    }) { Text("多模态设置") }
                    Button(
                        enabled = !modelBusy,
                        onClick = {
                            modelBusy = true
                            modelResult = "测试中…"
                            scope.launch {
                                val text = runCatching { AgentLlm.complete("请只回复四个字：连接成功") }
                                    .fold(
                                        onSuccess = { "连接成功：${it.trim().take(80)}" },
                                        onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" },
                                    )
                                modelResult = text
                                modelBusy = false
                            }
                        },
                    ) { Text(if (modelBusy) "测试中…" else "测试连接") }
                }
                if (modelResult.isNotBlank()) {
                    Text(
                        modelResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("语音配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.padding(end = 8.dp)) {
                        Text("开启语音回复", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "在候选结果中生成并发送语音",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = voiceReply,
                        onCheckedChange = {
                            voiceReply = it
                            TextToSpeech.voiceReplyEnabled = it
                        },
                    )
                }
                HorizontalDivider()
                SettingsRow(
                    title = "语音设置",
                    value = voicePanelSummary,
                    actionText = "去配置",
                ) {
                    TextToSpeech.showVoiceConfig(context) {
                        voicePanelSummary = TextToSpeech.voiceSummary()
                    }
                }
            }
        }
    }

    val field = editField
    if (field != null) {
        var buffer by remember(field) {
            mutableStateOf(
                when (field) {
                    "key" -> apiKey
                    "cookie" -> cookie
                    else -> customVoice
                },
            )
        }
        AlertDialog(
            onDismissRequest = { editField = null },
            title = {
                Text(
                    when (field) {
                        "key" -> "语音 API Key"
                        "cookie" -> "豆包 Cookie"
                        else -> "自定义音色 ID"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = buffer,
                        onValueChange = { buffer = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when (field) {
                            "key" -> if (isBreeze) "breezeblue.ai 控制台「API Key 管理」里创建，用于合成语音"
                            else "peiyinmofang.com「API Key 管理」里创建，用于合成语音"
                            "cookie" -> "doubao.com 开发者工具里复制 sessionid / sid_guard / uid_tt 三段"
                            else -> "留空则用预设音色；魔方与豆包共用此值"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = buffer.trim()
                        when (field) {
                            "key" -> if (isBreeze) {
                                breezeKeyState = v
                                TextToSpeech.breezeKey = v
                            } else {
                                mofaKey = v
                                TextToSpeech.apiKey = v
                            }

                            "cookie" -> {
                                cookie = v
                                TextToSpeech.doubaoCookie = v
                            }

                            else -> {
                                customVoice = v
                                TextToSpeech.customVoiceId = v
                            }
                        }
                        editField = null
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editField = null }) { Text("取消") } },
        )
    }

    if (showMofaPicker) {
        AlertDialog(
            onDismissRequest = { showMofaPicker = false },
            confirmButton = { TextButton(onClick = { showMofaPicker = false }) { Text("取消") } },
            title = { Text("选择魔方音色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextToSpeech.DEFAULT_VOICES.forEach { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = voice.voiceId == mofaVoice,
                                onClick = {
                                    mofaVoice = voice.voiceId
                                    customVoice = ""
                                    TextToSpeech.customVoiceId = ""
                                    TextToSpeech.selectedVoice = voice.voiceId
                                    showMofaPicker = false
                                },
                            )
                            Text(voice.label)
                        }
                    }
                }
            },
        )
    }

    if (showEmotionPicker) {
        AlertDialog(
            onDismissRequest = { showEmotionPicker = false },
            confirmButton = { TextButton(onClick = { showEmotionPicker = false }) { Text("取消") } },
            title = { Text("选择语气") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextToSpeech.EMOTIONS.forEach { e ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = e.first == emotion,
                                onClick = {
                                    emotion = e.first
                                    TextToSpeech.selectedEmotion = e.first
                                    showEmotionPicker = false
                                },
                            )
                            Text(e.first)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun BackendRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    actionText: String? = "选择",
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionText != null) {
            Text(actionText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
