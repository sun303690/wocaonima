package dev.sun.wechat.features.items.system

import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature

object RemoveExternalAppSharingSignatureVerify : SwitchFeature(), IResolveDex {

    override val technicalId = "移除分享签名校验"
    override val nameRes = R.string.feature_remove_external_app_sharing_signature_verify_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_remove_external_app_sharing_signature_verify_description

    private val methodSignCheck by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            usingEqStrings("checkAppSignature get local signature failed")
        }
    }

    override fun onEnable() {
        methodSignCheck.hookBefore {
            result = true
        }
    }
}
