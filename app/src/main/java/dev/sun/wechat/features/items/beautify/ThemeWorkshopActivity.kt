package dev.sun.wechat.features.items.beautify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.sun.wechat.activity.settings.LocalComponentActivity
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.utils.theme.ModuleTheme

/** WCX v344 完整主题工坊在 WeKit 中的独立宿主页。 */
class ThemeWorkshopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalComponentActivity provides this) {
                WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                    ModuleTheme {
                        ThemeWorkshopScreen(onBack = ::finish)
                    }
                }
            }
        }
    }
}
