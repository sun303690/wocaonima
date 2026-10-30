package dev.sun.wechat.features.items.beautify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.sun.wechat.activity.settings.LocalComponentActivity
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.utils.theme.EngineModuleTheme

/** WCX v344 完整主题工坊在 WeKit 中的独立宿主页。 */
class ThemeWorkshopActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: android.content.Context) = android.content.Intent(context, ThemeWorkshopActivity::class.java)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalComponentActivity provides this) {
                WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                    EngineModuleTheme {
                        ThemeWorkshopScreen(onBack = ::finish)
                    }
                }
            }
        }
    }
}
