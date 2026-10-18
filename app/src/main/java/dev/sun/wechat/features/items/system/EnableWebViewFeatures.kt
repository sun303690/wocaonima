package dev.sun.wechat.features.items.system

import android.app.Activity
import com.tencent.mm.plugin.webview.ui.tools.WebViewUI
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.reflection.bool
import dev.sun.wechat.utils.reflection.int

object EnableWebViewFeatures : SwitchFeature(), IResolveDex {

    override val technicalId = "强制启用 WebView 菜单"
    override val nameRes = R.string.feature_enable_web_view_features_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_enable_web_view_features_description

    private val methodInitWebViewFeatures by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"
            usingEqStrings(
                "banRightBtn:%b, showFixToolsBtn:%b",
                "MicroMsg.WebViewFtsQuickHelper"
            )
        }
    }

    override fun onEnable() {
        WebViewUI::class.reflekt().apply {
            firstMethod {
                name = "showOptionMenu"
                parameters(bool)
            }.hookBefore {
                args[0] = true
                val activity = thisObject as Activity
                activity.intent.putExtra("hide_option_menu", false)
            }

            firstMethod {
                name = "showOptionMenu"
                parameters(int, bool)
            }.hookBefore {
                args[1] = true
                val activity = thisObject as Activity
                activity.intent.putExtra("hide_option_menu", false)
            }
        }

        methodInitWebViewFeatures.hookBefore {
            (thisObject as WebViewUI).intent.apply {
                putExtra("hide_option_menu", false)
                putExtra("KRightBtn", false)
            }
        }
    }
}
