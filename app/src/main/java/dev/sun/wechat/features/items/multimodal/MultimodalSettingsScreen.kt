package dev.sun.wechat.features.items.multimodal

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * 外壳只保留一个 [MiuixSecondaryScaffold]，页面切换只换里面内容（同 ThemeWorkshop 的
 * PageScaffold 做法），避免每页各起一套外壳导致背景层层叠加、透出上一页内容。
 * 壁纸层关闭：多模态设置页用纯不透明底色，保证子页不会透出主页。
 *
 * - 主页：四个子开关 + 「语音接口」「视觉接口」两个入口 + 清缓存；
 * - 语音接口页：`/audio/transcriptions` 的服务商预设、地址、Key、模型（可点选）+ 连通性测试，由 [AsrEngine] 使用；
 * - 视觉接口页：自填 OpenAI 兼容地址 + Key + 模型（预设点选或手填），由 [VisionClient] 使用。
 */
@Composable
fun MultimodalSettingsScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val title = when (page) {
        1 -> stringResource(R.string.mm_voice_api_page)
        2 -> stringResource(R.string.mm_vision_api_page)
        else -> stringResource(R.string.feature_multimodal_name)
    }

    // 系统返回键/手势：子页先回主页，主页才退出；否则在接口页一按返回就直接关掉整个设置页
    BackHandler(enabled = page != 0) { page = 0 }

    MiuixSecondaryScaffold(
        title = title,
        onBack = { if (page == 0) onBack() else page = 0 },
        showWallpaper = false,
    ) {
        item {
            when (page) {
                1 -> VoiceApiContent()
                2 -> VisionApiContent()
                else -> HomeContent(
                    onOpenVoice = { page = 1 },
                    onOpenVision = { page = 2 },
                )
            }
        }
    }
}

@Composable
private fun HomeContent(onOpenVoice: () -> Unit, onOpenVision: () -> Unit) {
    val context = LocalContext.current
    var voice by remember { mutableStateOf(AsrEngine.enabled) }
    var image by remember { mutableStateOf(MultimodalEngine.imageEnabled) }
    var sticker by remember { mutableStateOf(MultimodalEngine.stickerEnabled) }
    var card by remember { mutableStateOf(MultimodalEngine.cardEnabled) }
    val notSet = stringResource(R.string.mm_api_not_set)
    val noModel = stringResource(R.string.mm_model_not_set)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixSettingsSectionTitle(stringResource(R.string.mm_switch_hint))
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

        MiuixSettingsSectionTitle(stringResource(R.string.mm_api_hint))
        MiuixSettingsCard {
            MiuixSettingsAction(
                title = stringResource(R.string.mm_voice_api_page),
                summary = if (AsrEngine.baseUrl.isBlank()) notSet else AsrEngine.model.ifBlank { noModel },
                onClick = onOpenVoice,
            )
            MiuixSettingsAction(
                title = stringResource(R.string.mm_vision_api_page),
                summary = if (MultimodalEngine.visionBaseUrl.isBlank()) {
                    notSet
                } else {
                    MultimodalEngine.visionModel.ifBlank { noModel }
                },
                onClick = onOpenVision,
            )
        }

        MiuixSettingsSectionTitle(stringResource(R.string.mm_cache_hint))
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

/** 语音接口页内容：服务商预设一键填 + 地址/Key/模型 + 连通性测试。 */
@Composable
private fun VoiceApiContent() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var url by remember { mutableStateOf(AsrEngine.baseUrl) }
    var key by remember { mutableStateOf(AsrEngine.apiKey) }
    var aes by remember { mutableStateOf(AsrEngine.aesKey) }
    var auth by remember { mutableStateOf(AsrEngine.authKey) }
    var model by remember { mutableStateOf(AsrEngine.model) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixSettingsSectionTitle(stringResource(R.string.mm_preset_hint))
        MiuixSettingsCard {
            AsrEngine.PRESETS.forEach { preset ->
                MiuixSettingsAction(
                    title = preset.name,
                    summary = "${preset.baseUrl} · ${preset.model}",
                    onClick = {
                        AsrEngine.applyPreset(preset)
                        url = preset.baseUrl
                        model = preset.model
                        showToast(context, "已填入 ${preset.name}")
                    },
                )
            }
        }

        MiuixSettingsSectionTitle(stringResource(R.string.asr_base_url))
        MiuixSettingsCard {
            MiuixCompatTextField(
                value = url,
                onValueChange = { url = it; AsrEngine.baseUrl = it.trim() },
                label = stringResource(R.string.asr_base_url),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            MiuixCompatTextField(
                value = key,
                onValueChange = { key = it; AsrEngine.apiKey = it.trim() },
                label = stringResource(R.string.asr_api_key),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            MiuixCompatTextField(
                value = aes,
                onValueChange = { aes = it; AsrEngine.aesKey = it.trim() },
                label = stringResource(R.string.asr_aes_key),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            MiuixCompatTextField(
                value = auth,
                onValueChange = { auth = it; AsrEngine.authKey = it.trim() },
                label = stringResource(R.string.asr_auth_key),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        MiuixSettingsSectionTitle(stringResource(R.string.mm_model_pick))
        MiuixSettingsCard {
            MiuixSettingsAction(
                title = stringResource(R.string.mm_model_pick),
                summary = model.ifBlank { stringResource(R.string.mm_model_not_set) },
                onClick = { showModels = true },
            )
        }

        MiuixSettingsSectionTitle(stringResource(R.string.mm_test_connection))
        MiuixSettingsCard {
            MiuixSettingsAction(
                title = stringResource(if (testing) R.string.mm_testing else R.string.mm_test_connection),
                summary = result,
                onClick = {
                    if (!testing) {
                        testing = true
                        result = ""
                        AsrEngine.testConnection { _, message ->
                            main.post {
                                testing = false
                                result = message
                                showToast(context, message)
                            }
                        }
                    }
                },
            )
        }

        Text(
            text = stringResource(R.string.asr_settings_hint),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }

    if (showModels) {
        MiuixAlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.mm_model_pick), fontSize = 17.sp) },
            text = {
                Column {
                    AsrEngine.MODELS.forEach { preset ->
                        Text(
                            text = if (preset == model) "✓ $preset" else preset,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    model = preset
                                    AsrEngine.model = preset
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
                        AsrEngine.model = picked
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

/**
 * 模型列表拉到之后：当前模型不在列表里（没填 / 换过服务商）就自动挑一个视觉模型并落盘。
 *
 * 只在明确不匹配时才覆盖，用户手动填的合法模型不会被冲掉。
 */
private fun applyPickedModel(candidates: List<String>, apply: (String) -> Unit) {
    if (candidates.isEmpty()) return
    val current = MultimodalEngine.visionModel
    if (current.isNotBlank() && candidates.any { it.equals(current, ignoreCase = true) }) return
    val picked = VisionClient.pickVisionModel(candidates) ?: return
    MultimodalEngine.visionModel = picked
    apply(picked)
}

/** 视觉接口页内容：自填 OpenAI 兼容地址 + Key + 模型（可点选预设，也可手填）。 */
@Composable
private fun VisionApiContent() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var url by remember { mutableStateOf(MultimodalEngine.visionBaseUrl) }
    var key by remember { mutableStateOf(MultimodalEngine.visionApiKey) }
    var model by remember { mutableStateOf(MultimodalEngine.visionModel) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf("") }

    fun refreshModels() {
        if (loading) return
        loading = true
        modelError = ""
        VisionClient.fetchModels { list, error ->
            main.post {
                loading = false
                modelError = error.orEmpty()
                models = list.orEmpty()
                list?.let { candidates -> applyPickedModel(candidates) { picked -> model = picked } }
            }
        }
    }

    // 进页面就按已填的地址/Key 拉一次模型列表；改完地址/Key 点「获取模型列表」刷新
    LaunchedEffect(Unit) {
        if (MultimodalEngine.visionReady()) refreshModels()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixSettingsSectionTitle(stringResource(R.string.mm_vision_api_page))
        MiuixSettingsCard {
            MiuixCompatTextField(
                value = url,
                onValueChange = { url = it; MultimodalEngine.visionBaseUrl = it.trim() },
                label = stringResource(R.string.mm_vision_base_url),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            MiuixCompatTextField(
                value = key,
                onValueChange = { key = it; MultimodalEngine.visionApiKey = it.trim() },
                label = stringResource(R.string.mm_vision_api_key),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        MiuixSettingsSectionTitle(stringResource(R.string.mm_model_pick))
        MiuixSettingsCard {
            MiuixSettingsAction(
                title = stringResource(R.string.mm_model_pick),
                summary = if (loading) {
                    stringResource(R.string.mm_model_fetching)
                } else {
                    model.ifBlank { stringResource(R.string.mm_model_not_set) }
                },
                onClick = { showModels = true },
            )
            MiuixSettingsAction(
                title = stringResource(R.string.mm_model_fetch),
                summary = when {
                    loading -> stringResource(R.string.mm_model_fetching)
                    modelError.isNotEmpty() -> modelError
                    models.isEmpty() -> stringResource(R.string.mm_model_empty)
                    else -> "${models.size} 个可用"
                },
                onClick = { refreshModels() },
            )
        }

        MiuixSettingsSectionTitle(stringResource(R.string.mm_test_connection))
        MiuixSettingsCard {
            MiuixSettingsAction(
                title = stringResource(if (testing) R.string.mm_testing else R.string.mm_test_connection),
                summary = result,
                onClick = {
                    if (!testing) {
                        testing = true
                        result = ""
                        VisionClient.testConnection { _, message ->
                            main.post {
                                testing = false
                                result = message
                                showToast(context, message)
                            }
                        }
                    }
                },
            )
        }

        Text(
            text = stringResource(R.string.mm_vision_hint),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }

    if (showModels) {
        MiuixAlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.mm_model_pick), fontSize = 17.sp) },
            text = {
                Column {
                    // 拉到了就用接口给的列表；没拉到（没网/没填 Key）回落内置预设
                    val candidates = models.ifEmpty { VisionClient.MODELS }
                    candidates.forEach { preset ->
                        Text(
                            text = if (preset == model) "✓ $preset" else preset,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    model = preset
                                    MultimodalEngine.visionModel = preset
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
                        MultimodalEngine.visionModel = picked
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
