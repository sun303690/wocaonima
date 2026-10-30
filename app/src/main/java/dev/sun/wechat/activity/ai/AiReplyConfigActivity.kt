package dev.sun.wechat.activity.ai

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import dev.sun.wechat.activity.agent.WeAgentSettingsActivity
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.features.items.chat.TextToSpeech
import dev.sun.wechat.features.items.chat.doubaoVoiceLabel
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

    var modelId by remember { mutableStateOf<String?>(null) }
    var modelResult by remember { mutableStateOf("") }
    var modelBusy by remember { mutableStateOf(false) }

    var voiceReply by remember { mutableStateOf(TextToSpeech.voiceReplyEnabled) }
    var backend by remember { mutableStateOf(TextToSpeech.backend) }
    var apiKey by remember { mutableStateOf(TextToSpeech.apiKey) }
    var cookie by remember { mutableStateOf(TextToSpeech.doubaoCookie) }
    var mofaVoice by remember { mutableStateOf(TextToSpeech.selectedVoice) }
    var doubaoVoice by remember { mutableStateOf(TextToSpeech.doubaoSpeaker) }
    var emotion by remember { mutableStateOf(TextToSpeech.selectedEmotion) }
    var showMofaPicker by remember { mutableStateOf(false) }
    var showEmotionPicker by remember { mutableStateOf(false) }
    var voiceBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        modelId = runCatching { WeAgentRepository.resolvedDefaultModelId() }.getOrNull()
    }

    val isDoubao = backend == TextToSpeech.BACKEND_DOUBAO
    val voiceLabel = if (isDoubao) doubaoVoiceLabel(doubaoVoice) else mofaVoice

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

        Text("模型配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 回复使用的模型", style = MaterialTheme.typography.bodyLarge)
                Text(
                    modelId ?: "未配置：请先到「WeAgent 模型设置」添加服务商与模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, WeAgentSettingsActivity::class.java))
                    }) { Text("模型设置") }
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

                BackendRow(
                    title = "魔方（配音魔方）",
                    desc = "需要单独填写 API Key",
                    selected = !isDoubao,
                ) {
                    backend = TextToSpeech.BACKEND_MOFA
                    TextToSpeech.backend = TextToSpeech.BACKEND_MOFA
                }
                BackendRow(
                    title = "豆包",
                    desc = "需要已登录的豆包 Cookie",
                    selected = isDoubao,
                ) {
                    backend = TextToSpeech.BACKEND_DOUBAO
                    TextToSpeech.backend = TextToSpeech.BACKEND_DOUBAO
                }
                HorizontalDivider()

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        TextToSpeech.apiKey = it
                    },
                    label = { Text("语音 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = {
                        cookie = it
                        TextToSpeech.doubaoCookie = it
                    },
                    label = { Text("豆包 Cookie") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SettingsRow("音色", voiceLabel) {
                    if (isDoubao) {
                        showDoubaoVoicePicker(context, doubaoVoice, cookie) { id, name ->
                            doubaoVoice = id
                            TextToSpeech.doubaoSpeaker = id
                            TextToSpeech.doubaoSpeakerName = name
                        }
                    } else {
                        showMofaPicker = true
                    }
                }
                SettingsRow(
                    title = "语气",
                    value = if (isDoubao) "豆包音色自带语气" else emotion,
                    actionText = if (isDoubao) null else "选择",
                ) { showEmotionPicker = true }

                Button(
                    enabled = !voiceBusy,
                    onClick = {
                        val sample = "这是一段语音测试，听到就说明配置可用。"
                        voiceBusy = true
                        if (isDoubao) {
                            if (cookie.isBlank()) {
                                voiceBusy = false
                                toast(context, "请先填写豆包 Cookie")
                            } else {
                                TextToSpeech.generateVoiceDoubao(sample, doubaoVoice, cookie) { path, err ->
                                    voiceBusy = false
                                    if (path != null) playWav(path) else toast(context, "合成失败：$err")
                                }
                            }
                        } else {
                            if (apiKey.isBlank()) {
                                voiceBusy = false
                                toast(context, "请先填写语音 API Key")
                            } else {
                                val vec = TextToSpeech.EMOTIONS.firstOrNull { it.first == emotion }?.second
                                    ?: TextToSpeech.EMOTIONS.first().second
                                TextToSpeech.generateVoice(sample, mofaVoice, vec, apiKey) { path, err ->
                                    voiceBusy = false
                                    if (path != null) playWav(path) else toast(context, "合成失败：$err")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (voiceBusy) "合成中…" else "测试语音") }
            }
        }
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionText != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}
