package dev.sun.wechat.features.items.multimodal

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.features.items.chat.AsrEngine
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.miuix.MiuixAlertDialog
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import dev.sun.wechat.ui.content.miuix.MiuixSecondaryScaffold
import dev.sun.wechat.ui.content.miuix.MiuixSettingsAction
import dev.sun.wechat.ui.content.miuix.MiuixSettingsCard
import dev.sun.wechat.ui.content.miuix.MiuixSettingsSectionTitle
import dev.sun.wechat.ui.content.miuix.MiuixSettingsToggle
import dev.sun.wechat.utils.android.showToast
import top.yukonga.miuix.kmp.basic.Text

/**
 * 多模态识别设置页（独立页面，带返回与大标题）。
 *
 * - 主页：四个子开关 + 「语音接口」「视觉接口」两个入口 + 清缓存；
 * - 语音接口页：`/audio/transcriptions` 的地址、Key、模型（可点选）+ 连通性测试，由 [AsrEngine] 使用；
 * - 视觉接口页：`/chat/completions` 的地址、Key、模型（可点选）+ 连通性测试，由 [VisionClient] 使用。
 */
@Composable
fun MultimodalSettingsScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    when (page) {
        1 -> ApiPage(mode = ApiMode.VOICE, onBack = { page = 0 })
        2 -> ApiPage(mode = ApiMode.VISION, onBack = { page = 0 })
        else -> HomePage(
            onBack = onBack,
            onOpenVoice = { page = 1 },
            onOpenVision = { page = 2 },
        )
    }
}

/** 两个接口页共用同一套布局，用这个区分读写哪个引擎。 */
enum class ApiMode { VOICE, VISION }

@Composable
private fun HomePage(onBack: () -> Unit, onOpenVoice: () -> Unit, onOpenVision: () -> Unit) {
    val context = LocalContext.current
    var voice by remember { mutableStateOf(AsrEngine.enabled) }
    var image by remember { mutableStateOf(MultimodalEngine.imageEnabled) }
    var sticker by remember { mutableStateOf(MultimodalEngine.stickerEnabled) }
    var card by remember { mutableStateOf(MultimodalEngine.cardEnabled) }
    val notSet = stringResource(R.string.mm_api_not_set)
    val noModel = stringResource(R.string.mm_model_not_set)

    MiuixSecondaryScaffold(title = stringResource(R.string.feature_multimodal_name), onBack = onBack) {
        item { MiuixSettingsSectionTitle(stringResource(R.string.mm_switch_hint)) }
        item {
            MiuixSettingsCard {
                MiuixSettingsToggle(
                    title = stringResource(R.string.mm_voice_switch),
                    summary = stringResource(R.string.mm_voice_switch_summary),
                    checked = voice,
                    onCheckedChange = { voice = it; AsrEngine.enabled = it },
                )
                MiuixSettingsToggle(
                    title = stringResource(R.string.mm_image_switch),
                    summary = stringResource(R.string.mm_image_switch_summary),
                    checked = image,
                    onCheckedChange = { image = it; MultimodalEngine.imageEnabled = it },
                )
                MiuixSettingsToggle(
                    title = stringResource(R.string.mm_sticker_switch),
                    summary = stringResource(R.string.mm_sticker_switch_summary),
                    checked = sticker,
                    onCheckedChange = { sticker = it; MultimodalEngine.stickerEnabled = it },
                )
                MiuixSettingsToggle(
                    title = stringResource(R.string.mm_card_switch),
                    summary = stringResource(R.string.mm_card_switch_summary),
                    checked = card,
                    onCheckedChange = { card = it; MultimodalEngine.cardEnabled = it },
                )
            }
        }
        item { MiuixSettingsSectionTitle(stringResource(R.string.mm_api_hint)) }
        item {
            MiuixSettingsCard {
                MiuixSettingsAction(
                    title = stringResource(R.string.mm_voice_api_page),
                    summary = if (AsrEngine.baseUrl.isBlank()) notSet else AsrEngine.model.ifBlank { noModel },
                    onClick = onOpenVoice,
                )
                MiuixSettingsAction(
                    title = stringResource(R.string.mm_vision_api_page),
                    summary = if (MultimodalEngine.visionBaseUrl.isBlank()) notSet else MultimodalEngine.visionModel.ifBlank { noModel },
                    onClick = onOpenVision,
                )
            }
        }
        item { MiuixSettingsSectionTitle(stringResource(R.string.mm_cache_hint)) }
        item {
            MiuixSettingsCard {
                MiuixSettingsAction(
                    title = stringResource(R.string.mm_clear_cache),
                    summary = stringResource(R.string.asr_cache_hint),
                    onClick = {
                        MultimodalEngine.clearCache()
                        AsrEngine.clearCache()
                        showToast(context, "已清空多模态与语音识别缓存")
                    },
                )
            }
        }
    }
}

@Composable
private fun ApiPage(mode: ApiMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val voice = mode == ApiMode.VOICE

    var url by remember { mutableStateOf(if (voice) AsrEngine.baseUrl else MultimodalEngine.visionBaseUrl) }
    var key by remember { mutableStateOf(if (voice) AsrEngine.apiKey else MultimodalEngine.visionApiKey) }
    var model by remember { mutableStateOf(if (voice) AsrEngine.model else MultimodalEngine.visionModel) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }

    val presets = if (voice) AsrEngine.MODELS else VisionClient.MODELS
    val title = stringResource(if (voice) R.string.mm_voice_api_page else R.string.mm_vision_api_page)

    MiuixSecondaryScaffold(title = title, onBack = onBack) {
        item { MiuixSettingsSectionTitle(stringResource(if (voice) R.string.asr_base_url else R.string.mm_vision_base_url)) }
        item {
            MiuixSettingsCard {
                MiuixCompatTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (voice) AsrEngine.baseUrl = it.trim() else MultimodalEngine.visionBaseUrl = it.trim()
                    },
                    label = stringResource(if (voice) R.string.asr_base_url else R.string.mm_vision_base_url),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                MiuixCompatTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        if (voice) AsrEngine.apiKey = it.trim() else MultimodalEngine.visionApiKey = it.trim()
                    },
                    label = stringResource(if (voice) R.string.asr_api_key else R.string.mm_vision_api_key),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        item { MiuixSettingsSectionTitle(stringResource(R.string.mm_model_pick)) }
        item {
            MiuixSettingsCard {
                MiuixSettingsAction(
                    title = stringResource(R.string.mm_model_pick),
                    summary = model.ifBlank { stringResource(R.string.mm_model_not_set) },
                    onClick = { showModels = true },
                )
            }
        }
        item { MiuixSettingsSectionTitle(stringResource(R.string.mm_test_connection)) }
        item {
            MiuixSettingsCard {
                MiuixSettingsAction(
                    title = stringResource(if (testing) R.string.mm_testing else R.string.mm_test_connection),
                    summary = result,
                    onClick = {
                        if (!testing) {
                            testing = true
                            result = ""
                            val callback: (Boolean, String) -> Unit = { _, message ->
                                main.post {
                                    testing = false
                                    result = message
                                    showToast(context, message)
                                }
                            }
                            if (voice) AsrEngine.testConnection(callback) else VisionClient.testConnection(callback)
                        }
                    },
                )
            }
        }
        item {
            Text(
                text = stringResource(if (voice) R.string.asr_settings_hint else R.string.mm_vision_hint),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }

    if (showModels) {
        MiuixAlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.mm_model_pick), fontSize = 17.sp) },
            text = {
                Column {
                    presets.forEach { preset ->
                        Text(
                            text = if (preset == model) "✓ $preset" else preset,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    model = preset
                                    if (voice) AsrEngine.model = preset else MultimodalEngine.visionModel = preset
                                    showModels = false
                                }
                                .padding(vertical = 7.dp),
                        )
                    }
                    MiuixCompatTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = stringResource(R.string.mm_model_custom),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val picked = custom.trim()
                    if (picked.isNotEmpty()) {
                        model = picked
                        if (voice) AsrEngine.model = picked else MultimodalEngine.visionModel = picked
                    }
                    showModels = false
                }) { Text(stringResource(R.string.mm_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showModels = false }) { Text(stringResource(R.string.mm_cancel)) }
            },
        )
    }
}
