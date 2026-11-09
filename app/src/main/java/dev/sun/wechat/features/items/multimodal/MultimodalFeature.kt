package dev.sun.wechat.features.items.multimodal

import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds

/**
 * 多模态消息识别（设置列表里的入口行）。
 *
 * 开关只做总闸；点标题（左侧文字）打开独立设置页 [MultimodalSettingsActivity]，
 * 页面里再配置语音/视觉两个接口与各子开关。
 *
 * 之所以做成 [ClickableFeature] 而不是内嵌 `Ui()`：内嵌 Ui 的条目在设置列表里
 * 只有右侧开关能点，左侧文字点了没反应（用户明确反馈过）。
 */
object MultimodalFeature : ClickableFeature() {

    override val technicalId = "多模态识别"
    override val nameRes = R.string.feature_multimodal_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_multimodal_description

    // 图片/表情要调视觉模型，涉及联网花钱，默认关
    override val defaultEnabled = false

    override fun onClick(context: ComponentActivity) {
        context.startActivity(MultimodalSettingsActivity.createIntent(context))
    }
}
