package dev.sun.wechat.features.api.ui

import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.ApiFeature
import dev.sun.wechat.features.core.FeatureCategoryIds

object WeMainActivityBeautifyApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信主屏幕美化服务"
    override val nameRes = R.string.feature_we_main_activity_beautify_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_main_activity_beautify_api_description

    val methodDoOnCreate by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
        }
    }
}
