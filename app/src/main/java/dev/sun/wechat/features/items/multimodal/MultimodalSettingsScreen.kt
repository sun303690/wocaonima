package dev.sun.wechat.features.items.multimodal

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.sun.wechat.agent.data.WeAgentRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - 视觉接口页：直接从 WeAgent 已配好的模型里挑一个支持看图的，不用再填地址和 Key，由 [VisionClient] 使用。
 */
@Composable
fun MultimodalSettingsScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val title = when (page) {
        1 -> stringResource(R.string.mm_voice_api_page)
        2 -> stringResource(R.string.mm_vision_api_page)
        else -> stringResource(R.string.feature_multimodal_name)
    }

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
                summary = if (MultimodalEngine.visionModelId.isNotBlank()) {
                    stringResource(R.string.mm_weagent_selected)
                } else {
                    stringResource(R.string.mm_weagent_default_value)
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

/** 视觉接口页内容：模型直接来自 WeAgent（只列支持看图的），不用再填地址与 Key。 */
@Composable
private fun VisionApiContent() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val all by WeAgentRepository.observeModels().collectAsState(initial = emptyList())
    val visionModels = remember(all) { all.filter { it.supportsVision } }
    var selected by remember { mutableStateOf(MultimodalEngine.visionModelId) }
    // 没单独选模型时实际用的是 WeAgent 默认模型，列表里把它标成「默认」
    var defaultId by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        defaultId = withContext(Dispatchers.IO) {
            runCatching { WeAgentRepository.resolvedDefaultModelId() }.getOrNull().orEmpty()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixSettingsSectionTitle(stringResource(R.string.mm_weagent_hint))
        MiuixSettingsCard {
            if (visionModels.isEmpty()) {
                MiuixSettingsAction(
                    title = stringResource(R.string.mm_vision_no_model),
                    summary = stringResource(R.string.mm_vision_no_model_summary),
                    onClick = {},
                )
            } else {
                val effective = selected.ifBlank { defaultId }
                val defaultTag = stringResource(R.string.mm_model_default_tag)
                visionModels.forEach { model ->
                    MiuixSettingsAction(
                        title = (if (model.id == effective) "✓ " else "") + model.displayName,
                        summary = if (model.id == defaultId) {
                            model.modelIdRemote + " · " + defaultTag
                        } else {
                            model.modelIdRemote
                        },
                        onClick = {
                            selected = model.id
                            MultimodalEngine.visionModelId = model.id
                            showToast(context, "已选用 ${model.displayName}")
                        },
                    )
                }
            }
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
}
