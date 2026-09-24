package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.R
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.WeLogger

/**
 * 言外·情绪分析：文字气泡下方显示情绪 / 潜台词 / 沟通建议。
 * 对应 Yanwai 的 MessageSniffer + BubbleDecorator。走 KSP FeaturesScanner 自动注册。
 */
object MoodFeature : SwitchFeature() {

    override val technicalId = "情绪分析"
    override val nameRes = R.string.mood_feature_name
    override val descriptionRes = R.string.mood_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override fun onEnable() {
        MoodAnalyzer.enabled = true
        MessageSniffer.ensureSubscribed()
        WeLogger.i(TAG, "情绪分析已启用")
    }

    override fun onDisable() {
        MoodAnalyzer.enabled = false
        BubbleDecorator.clearAll()
        MoodStore.clear()
        WeLogger.i(TAG, "情绪分析已停用")
    }

    private const val TAG = "MoodFeature"
}