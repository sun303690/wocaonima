package dev.sun.wechat.features.items.multimodal

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.chat.AsrEngine
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.miuix.MiuixAlertDialog
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import dev.sun.wechat.ui.content.miuix.MiuixSettingsAction
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.utils.android.showToast
import top.yukonga.miuix.kmp.basic.Text

/**
 * 多模态消息识别：独立开关 + 分段设置页。
 *
 * 开关本身不挂 hook，只是给 [MultimodalEngine] 一个总闸；
 * 真正干活的是「帮我回 / 自动回复 / 情绪分析」组装上下文时对 [MultimodalEngine.textFor] 的调用，
 * 以及后台的 [MultimodalEngine.prefetch]。
 *
 * 设置页分三屏（同一页面内切换，不弹新窗）：
 * - 主页：四个子开关 + 「语音接口」「视觉接口」两个入口 + 清缓存，尽量紧凑；
 * - 语音接口页：`/audio/transcriptions` 的地址、Key、模型（可点选）+ 连通性测试，由 [AsrEngine] 使用；
 * - 视觉接口页：`/chat/completions` 的地址、Key、模型（可点选）+ 连通性测试，由 [VisionClient] 使用。
 */
object MultimodalFeature : SwitchFeature() {

    override val technicalId = "多模态识别"
    override val nameRes = R.string.feature_multimodal_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_multimodal_description

    // 图片/表情要调视觉模型，涉及联网花钱，默认关
    override val defaultEnabled = false

    @Composable
    override fun Ui() {
        var page by remember { mutableStateOf(0) }
        when (page) {
            1 -> ApiPage(mode = ApiMode.VOICE, onBack = { page = 0 })
            2 -> ApiPage(mode = ApiMode.VISION, onBack = { page = 0 })
            else -> HomePage(onOpenVoice = { page = 1 }, onOpenVision = { page = 2 })
        }
    }
}

/** 两个接口页共用同一套布局，用这个区分读写哪个引擎。 */
enum class ApiMode { VOICE, VISION }

@Composable
private fun HomePage(onOpenVoice: () -> Unit, onOpenVision: () -> Unit) {
    val context = LocalContext.current
    var voice by remember { mutableStateOf(AsrEngine.enabled) }
    var image by remember { mutableStateOf(MultimodalEngine.imageEnabled) }
    var sticker by remember { mutableStateOf(MultimodalEngine.stickerEnabled) }
    var card by remember { mutableStateOf(MultimodalEngine.cardEnabled) }
    val notSet = stringResource(R.string.mm_api_not_set)
    val noModel = stringResource(R.string.mm_model_not_set)

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            text = stringResource(R.string.mm_switch_hint),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        MiuixSwitchWidget(
            title = stringResource(R.string.mm_voice_switch),
            checked = voice,
            onCheckedChange = {
                voice = it
                AsrEngine.enabled = it
            },
            summary = stringResource(R.string.mm_voice_switch_summary),
        )
        MiuixSwitchWidget(
            title = stringResource(R.string.mm_image_switch),
            checked = image,
            onCheckedChange = {
                image = it
                MultimodalEngine.imageEnabled = it
            },
            summary = stringResource(R.string.mm_image_switch_summary),
        )
        MiuixSwitchWidget(
            title = stringResource(R.string.mm_sticker_switch),
            checked = sticker,
            onCheckedChange = {
                sticker = it
                MultimodalEngine.stickerEnabled = it
            },
            summary = stringResource(R.string.mm_sticker_switch_summary),
        )
        MiuixSwitchWidget(
            title = stringResource(R.string.mm_card_switch),
            checked = card,
            onCheckedChange = {
                card = it
                MultimodalEngine.cardEnabled = it
            },
            summary = stringResource(R.string.mm_card_switch_summary),
        )
        Text(
            text = stringResource(R.string.mm_api_hint),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
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
        Button(
            onClick = {
                MultimodalEngine.clearCache()
                AsrEngine.clearCache()
                showToast(context, "已清空多模态与语音识别缓存")
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text(stringResource(R.string.mm_clear_cache)) }
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiuixSettingsAction(title = stringResource(R.string.mm_back), onClick = onBack)
        MiuixCompatTextField(
            value = url,
            onValueChange = {
                url = it
                if (voice) AsrEngine.baseUrl = it.trim() else MultimodalEngine.visionBaseUrl = it.trim()
            },
            label = stringResource(if (voice) R.string.asr_base_url else R.string.mm_vision_base_url),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MiuixCompatTextField(
            value = key,
            onValueChange = {
                key = it
                if (voice) AsrEngine.apiKey = it.trim() else MultimodalEngine.visionApiKey = it.trim()
            },
            label = stringResource(if (voice) R.string.asr_api_key else R.string.mm_vision_api_key),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MiuixSettingsAction(
            title = stringResource(R.string.mm_model_pick),
            summary = model.ifBlank { stringResource(R.string.mm_model_not_set) },
            onClick = { showModels = true },
        )
        Button(
            onClick = {
                if (!testing) {
                    testing = true
                    result = ""
                    val callback: (Boolean, String) -> Unit = { ok, message ->
                        main.post {
                            testing = false
                            result = message
                            showToast(context, message)
                        }
                    }
                    if (voice) AsrEngine.testConnection(callback) else VisionClient.testConnection(callback)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (testing) R.string.mm_testing else R.string.mm_test_connection,
                ),
            )
        }
        if (result.isNotEmpty()) {
            Text(result, fontSize = 13.sp)
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
