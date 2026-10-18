package dev.sun.wechat.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ApiFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.utils.LifecycleOwnerProvider
import dev.sun.wechat.ui.utils.rootView
import dev.sun.wechat.ui.utils.setLifecycleOwner

object WeViewTreeLifecycleProvider : ApiFeature() {

    override val technicalId = "Compose 生命周期提供方"
    override val nameRes = R.string.feature_we_view_tree_lifecycle_provider_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
