package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** AI 语音助手设置面板（卡片式）。音色下拉内置，支持文字转语音发送。 */
internal object AiVoiceSettingsDialog {

    fun show(context: Context) {
        showComposeDialog(context) {
            AiVoiceSettingsContent(context)
        }
    }

    @Composable
    private fun AiVoiceSettingsContent(context: Context) {
        val a = AiVoiceAssistant
        val scope = rememberCoroutineScope()

        // ---- AI 对话 ----
        var enabled by remember { mutableStateOf(a.enabled) }
        var trigger by remember { mutableStateOf(a.triggerWord) }
        var memoryRounds by remember { mutableStateOf(a.memoryRounds.toString()) }
        var voiceOnly by remember { mutableStateOf(a.voiceOnly) }
        var prompt by remember { mutableStateOf(a.prompt) }
        var selectedModelId by remember { mutableStateOf(a.weAgentModelId) }
        var models by remember { mutableStateOf(listOf<dev.ujhhgtg.wekit.agent.data.entity.ModelEntity>()) }

        // ---- 音色 ----
        var engine by remember { mutableStateOf(a.engine) }
        var selectedVoiceId by remember { mutableStateOf(currentEngineVoice(a)) }
        var voiceOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

        // ---- 文字转语音 ----
        var ttsText by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }

        // 加载 WeAgent 模型库
        LaunchedEffect(Unit) {
            models = WeAgentRepository.observeModels().first()
            if (selectedModelId.isEmpty()) selectedModelId = a.weAgentModelId.ifBlank {
                WeAgentRepository.firstModelId() ?: ""
            }
        }

        // 引擎或配置变化时加载音色
        LaunchedEffect(engine) {
            selectedVoiceId = currentEngineVoice(a)
            voiceOptions = a.engineVoices(engine)
            if (voiceOptions.isEmpty() && (engine == "fishaudio" || engine == "yx520")) {
                val fetched = a.fetchEngineVoices(engine).getOrNull()
                if (fetched != null && fetched.isNotEmpty()) {
                    voiceOptions = fetched
                    selectedVoiceId = fetched.first().first
                }
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_voice_assistant_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SegmentedColumn(title = stringResource(R.string.aivoice_section_ai)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.aivoice_enabled),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                title = stringResource(R.string.aivoice_model),
                                description = models.firstOrNull { it.id == selectedModelId }?.displayName ?: selectedModelId,
                                value = selectedModelId,
                                options = models.map { DropdownOption(it.id, it.displayName) }
                                    .ifEmpty { listOf(DropdownOption("", stringResource(R.string.aivoice_no_model))) },
                                enabled = models.isNotEmpty(),
                                onValueChange = { selectedModelId = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_trigger),
                                value = trigger,
                                onValueChange = { trigger = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_prompt),
                                value = prompt,
                                onValueChange = { prompt = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_memory_rounds),
                                value = memoryRounds,
                                onValueChange = { memoryRounds = it },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.aivoice_voice_only),
                                description = stringResource(R.string.aivoice_voice_only_desc),
                                checked = voiceOnly,
                                onCheckedChange = { voiceOnly = it },
                            )
                        }
                    }

                    SegmentedColumn(title = stringResource(R.string.aivoice_section_tts)) {
                        item {
                            DropDownMenuWidget(
                                title = stringResource(R.string.aivoice_engine),
                                description = engine,
                                value = engine,
                                options = listOf(
                                    DropdownOption("fishaudio", stringResource(R.string.aivoice_engine_fish)),
                                    DropdownOption("yx520", stringResource(R.string.aivoice_engine_yx)),
                                    DropdownOption("bv", stringResource(R.string.aivoice_engine_bv)),
                                    DropdownOption("vocu", stringResource(R.string.aivoice_engine_vocu)),
                                    DropdownOption("tiax", stringResource(R.string.aivoice_engine_tiax)),
                                ),
                                onValueChange = { engine = it },
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                title = stringResource(R.string.aivoice_engine_voice),
                                description = selectedVoiceId,
                                value = selectedVoiceId,
                                options = voiceOptions.ifEmpty {
                                    listOf(DropdownOption(a.currentEngineVoice(engine), stringResource(R.string.aivoice_voice_manual)))
                                },
                                enabled = voiceOptions.isNotEmpty(),
                                onValueChange = { selectedVoiceId = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_engine_key),
                                value = currentEngineKey(a),
                                onValueChange = { setEngineKey(a, engine, it) },
                            )
                        }
                        if (voiceOptions.isEmpty() && (engine == "fishaudio" || engine == "yx520")) {
                            item {
                                Button(onClick = {
                                    scope.launch {
                                        val fetched = a.fetchEngineVoices(engine).getOrNull()
                                        if (fetched.isNullOrEmpty()) {
                                            showToast(context, context.getString(R.string.aivoice_fetch_failed))
                                        } else {
                                            voiceOptions = fetched
                                            selectedVoiceId = fetched.first().first
                                            showToast(context, context.getString(R.string.aivoice_fetched, fetched.size))
                                        }
                                    }
                                }) { Text(stringResource(R.string.aivoice_fetch_voices)) }
                            }
                        }
                    }

                    // ---- 文字转语音 ----
                    SegmentedColumn(title = stringResource(R.string.aivoice_section_manual_tts)) {
                        item {
                            OutlinedTextField(
                                value = ttsText,
                                onValueChange = { ttsText = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                label = { Text(stringResource(R.string.aivoice_tts_input)) },
                                singleLine = false,
                                minLines = 3,
                                maxLines = 6,
                            )
                        }
                        item {
                            Button(onClick = {
                                val text = ttsText.trim()
                                if (text.isEmpty()) { showToast(context, context.getString(R.string.aivoice_tts_empty)); return@Button }
                                scope.launch {
                                    sending = true
                                    val talker = a.currentTalker()
                                    if (talker.isNullOrEmpty()) {
                                        showToast(context, context.getString(R.string.aivoice_tts_no_talker)); sending = false; return@launch
                                    }
                                    val ok = a.synthesizeAndSendText(talker, text)
                                    sending = false
                                    showToast(context, context.getString(if (ok) R.string.aivoice_tts_sent else R.string.aivoice_tts_failed))
                                }
                            }, enabled = !sending) {
                                Text(stringResource(R.string.aivoice_tts_send))
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.aivoice_tts_hint),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Button(onClick = {
                    a.enabled = enabled
                    a.triggerWord = trigger.trim().ifBlank { "*" }
                    a.memoryRounds = memoryRounds.toIntOrNull()?.coerceIn(1, 999) ?: 5
                    a.voiceOnly = voiceOnly
                    a.prompt = prompt.trim()
                    a.weAgentModelId = selectedModelId
                    a.engine = engine
                    setEngineVoice(a, engine, selectedVoiceId)
                    onDismiss()
                    showToast(context, context.getString(R.string.aivoice_saved))
                }) { Text(stringResource(R.string.aivoice_save)) }
            },
        )
    }

    private fun currentEngineKey(a: AiVoiceAssistant): String = when (a.engine) {
        "fishaudio" -> a.fishKey
        "yx520" -> a.yxKey
        "bv" -> a.bvKey
        "vocu" -> a.vocuKey
        "tiax" -> a.tiaxKey
        else -> ""
    }

    private fun setEngineKey(a: AiVoiceAssistant, engine: String, value: String) {
        when (engine) {
            "fishaudio" -> a.fishKey = value
            "yx520" -> a.yxKey = value
            "bv" -> a.bvKey = value
            "vocu" -> a.vocuKey = value
            "tiax" -> a.tiaxKey = value
        }
    }

    private fun currentEngineVoice(a: AiVoiceAssistant): String = when (a.engine) {
        "fishaudio" -> a.fishVoice
        "yx520" -> a.yxVoice
        "bv" -> a.bvVoice
        "vocu" -> a.vocuVoice
        "tiax" -> a.tiaxVoice
        else -> ""
    }

    private fun setEngineVoice(a: AiVoiceAssistant, engine: String, value: String) {
        when (engine) {
            "fishaudio" -> a.fishVoice = value
            "yx520" -> a.yxVoice = value
            "bv" -> a.bvVoice = value
            "vocu" -> a.vocuVoice = value
            "tiax" -> a.tiaxVoice = value
        }
    }

    @Composable
    private fun SettingsTextRow(
        title: String,
        value: String,
        onValueChange: (String) -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}