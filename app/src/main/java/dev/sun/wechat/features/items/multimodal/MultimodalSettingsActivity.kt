package dev.sun.wechat.features.items.multimodal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.sun.wechat.activity.settings.LocalComponentActivity
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.utils.theme.MiuixModuleTheme

/**
 * 多模态识别的独立设置页宿主。
 *
 * 与「主题工坊」一致：设置列表里只占一行，点标题跳到这个 Activity，
 * 页面内是 [MultimodalSettingsScreen]（主页 / 语音接口 / 视觉接口）。
 */
class MultimodalSettingsActivity : ComponentActivity() {

    companion object {
        fun createIntent(context: Context) = Intent(context, MultimodalSettingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalComponentActivity provides this) {
                WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                    MiuixModuleTheme(opaqueBackground = true) {
                        MultimodalSettingsScreen(onBack = ::finish)
                    }
                }
            }
        }
    }
}
