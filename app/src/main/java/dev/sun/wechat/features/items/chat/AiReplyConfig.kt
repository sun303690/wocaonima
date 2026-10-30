package dev.sun.wechat.features.items.chat

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.activity.ai.AiReplyConfigActivity
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds

/** 独立入口：AI 回复配置（模型 + 语音）。 */
object AiReplyConfig : ClickableFeature() {

    override val technicalId = "AI回复配置"
    override val nameRes = R.string.feature_ai_reply_config_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_reply_config_description

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, AiReplyConfigActivity::class.java))
    }
}
