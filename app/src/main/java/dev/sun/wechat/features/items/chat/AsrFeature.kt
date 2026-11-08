package dev.sun.wechat.features.items.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import top.yukonga.miuix.kmp.basic.Text

/**
 * 语音识别（ASR）特性：独立开关 + 自己的设置页。
 *
 * 开关本身不挂 hook，只是给 [AsrEngine] 一个「允不允许联网识别」的总闸；
 * 具体识别由「帮我回」和「自动回复」在遇到没有微信转写文本的语音消息时触发。
 * 设置页里填 OpenAI 兼容接口（地址 / Key / 模型），换服务商不用改代码。
 */
object AsrFeature : SwitchFeature() {

    override val technicalId = "语音识别"
    override val nameRes = R.string.feature_asr_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_asr_description

    // 涉及联网花钱，默认关，让用户自己打开
    override val defaultEnabled = false

    @Composable
    override fun Ui() {
        var url by remember { mutableStateOf(AsrEngine.baseUrl) }
        var key by remember { mutableStateOf(AsrEngine.apiKey) }
        var model by remember { mutableStateOf(AsrEngine.model) }

        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.asr_settings_hint),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            MiuixCompatTextField(
                value = url,
                onValueChange = {
                    url = it
                    AsrEngine.baseUrl = it.trim()
                },
                label = stringResource(R.string.asr_base_url),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixCompatTextField(
                value = key,
                onValueChange = {
                    key = it
                    AsrEngine.apiKey = it.trim()
                },
                label = stringResource(R.string.asr_api_key),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            MiuixCompatTextField(
                value = model,
                onValueChange = {
                    model = it
                    AsrEngine.model = it.trim().ifBlank { "whisper-1" }
                },
                label = stringResource(R.string.asr_model),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.asr_cache_hint),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
