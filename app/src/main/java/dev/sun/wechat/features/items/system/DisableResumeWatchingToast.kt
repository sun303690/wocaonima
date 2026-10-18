package dev.sun.wechat.features.items.system

import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature

object DisableResumeWatchingToast : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用「刚刚在看」提醒"
    override val nameRes = R.string.feature_disable_resume_watching_toast_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_resume_watching_toast_description

    private val methodShowRecoveryToast by dexMethod {
        matcher {
            paramCount = 0
            usingEqStrings(
                "MicroMsg.RecoveryHelper",
                "topActivity == null or isFinishing or isDestroyed",
                "recoveryObj == null ",
                "toast_button",
                "view_exp",
            )
        }
    }

    override fun onEnable() {
        methodShowRecoveryToast.hookBefore {
            result = null
        }
    }
}
