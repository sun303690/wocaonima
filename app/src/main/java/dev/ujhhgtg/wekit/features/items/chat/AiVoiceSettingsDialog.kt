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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

/** AI 语音助手设置面板（卡片式）。 */
internal object AiVoiceSettingsDialog {

    fun show(context: Context) {
        showComposeDialog(context) {
            AiVoiceSettingsContent(context)
        }
    }

    @Composable
    private fun AiVoiceSettingsContent(context: Context) {
        val a = AiVoiceAssistant
        // AI 对话
        var apiUrl by remember { mutableStateOf(a.apiUrl) }
        var apiKey by remember { mutableStateOf(a.apiKey) }
        var model by remember { mutableStateOf(a.model) }
        var prompt by remember { mutableStateOf(a.prompt) }
        var enabled by remember { mutableStateOf(a.enabled) }
        var trigger by remember { mutableStateOf(a.triggerWord) }
        var memoryRounds by remember { mutableStateOf(a.memoryRounds.toString()) }
        var voiceOnly by remember { mutableStateOf(a.voiceOnly) }
        // TTS 引擎
        var engine by remember { mutableStateOf(a.engine) }
        var fishKey by remember { mutableStateOf(a.fishKey) }
        var fishVoice by remember { mutableStateOf(a.fishVoice) }
        var yxKey by remember { mutableStateOf(a.yxKey) }
        var yxVoice by remember { mutableStateOf(a.yxVoice) }
        var bvKey by remember { mutableStateOf(a.bvKey) }
        var bvVoice by remember { mutableStateOf(a.bvVoice) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_voice_assistant_name)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SegmentedColumn(title = stringResource(R.string.aivoice_section_ai)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.aivoice_enabled),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_api_url),
                                value = apiUrl,
                                onValueChange = { apiUrl = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_api_key),
                                value = apiKey,
                                onValueChange = { apiKey = it },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_model),
                                value = model,
                                onValueChange = { model = it },
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
                        // 引擎相关配置（通用：Key + 音色ID）
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_engine_key),
                                value = when (engine) {
                                    "fishaudio" -> fishKey
                                    "yx520" -> yxKey
                                    "bv" -> bvKey
                                    else -> ""
                                },
                                onValueChange = { v ->
                                    when (engine) {
                                        "fishaudio" -> fishKey = v
                                        "yx520" -> yxKey = v
                                        "bv" -> bvKey = v
                                        "vocu" -> a.vocuKey = v
                                        "tiax" -> a.tiaxKey = v
                                    }
                                },
                            )
                        }
                        item {
                            SettingsTextRow(
                                title = stringResource(R.string.aivoice_engine_voice),
                                value = when (engine) {
                                    "fishaudio" -> fishVoice
                                    "yx520" -> yxVoice
                                    "bv" -> bvVoice
                                    else -> ""
                                },
                                onValueChange = { v ->
                                    when (engine) {
                                        "fishaudio" -> fishVoice = v
                                        "yx520" -> yxVoice = v
                                        "bv" -> bvVoice = v
                                        "vocu" -> a.vocuVoice = v
                                        "tiax" -> a.tiaxVoice = v
                                    }
                                },
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                Button(onClick = {
                    a.apiUrl = apiUrl.trim()
                    a.apiKey = apiKey.trim()
                    a.model = model.trim()
                    a.prompt = prompt.trim()
                    a.enabled = enabled
                    a.triggerWord = trigger.trim().ifBlank { "*" }
                    a.memoryRounds = memoryRounds.toIntOrNull()?.coerceIn(1, 999) ?: 5
                    a.voiceOnly = voiceOnly
                    a.engine = engine
                    a.fishKey = fishKey.trim()
                    a.fishVoice = fishVoice.trim()
                    a.yxKey = yxKey.trim()
                    a.yxVoice = yxVoice.trim()
                    a.bvKey = bvKey.trim()
                    a.bvVoice = bvVoice.trim()
                    showToast(context, context.getString(R.string.aivoice_saved))
                    onDismiss()
                }) { Text(stringResource(R.string.aivoice_save)) }
            },
        )
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
