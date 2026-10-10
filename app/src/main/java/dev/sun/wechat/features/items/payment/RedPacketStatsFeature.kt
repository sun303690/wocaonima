package dev.sun.wechat.features.items.payment

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.activity.RedPacketStatsActivity
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.utils.WeLogger

/** 本地记录抢到的红包金额与数量；数据不会上传。 */
object RedPacketStatsFeature : ClickableFeature() {
    override val technicalId = "抢红包金额统计"
    override val nameRes = R.string.feature_red_packet_stats_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_red_packet_stats_description

    private const val TAG = "RedPacketStatsFeature"

    override fun onClick(context: ComponentActivity) {
        runCatching {
            context.startActivity(Intent(context, RedPacketStatsActivity::class.java))
        }.onFailure { WeLogger.e(TAG, "failed to open red packet stats page", it) }
    }
}
