// SPDX-License-Identifier: GPL-3.0-only
package dev.sun.wechat.features.items.beautify

import dev.sun.wechat.R
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger

/**
 * 微信用雪花朵朵 —— 给微信每一个页面叠加雪花层。
 *
 * 挂载方式参考「应用全局背景」：注册 Application.ActivityLifecycleCallbacks，
 * 每次 Activity 进入前台就把 SnowOverlay.SnowView 贴到 decorView 上
 * （按 tag 去重），模块自己的页面在 SnowOverlay 里按包名跳过。
 */
object SnowEffect : SwitchFeature() {

    override val technicalId = SnowOverlay.TECHNICAL_ID
    override val nameRes = R.string.feature_snow_effect_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_snow_effect_description

    private const val TAG = "WeKitSnow"

    override fun onEnable() {
        runCatching { SnowOverlay.install(HostInfo.application) }
            .onFailure { WeLogger.w(TAG, "snow onEnable failed", it) }
    }

    override fun onDisable() {
        runCatching { SnowOverlay.uninstall() }
            .onFailure { WeLogger.w(TAG, "snow onDisable failed", it) }
    }
}
