package dev.sun.wechat.ui.utils.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix component engine root for WeKit-owned Compose pages. */
@Composable
fun MiuixModuleTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    // Miuix follows the host configuration automatically. Keep darkTheme as a
    // compatibility hint for standalone settings activities during migration.
    @Suppress("UNUSED_VARIABLE")
    val requestedDarkTheme = darkTheme
    // 统一「半透明底」：所有模块页面（设置、二级页、主题工坊…）都透出下层画面，
    // 与主题设置页的观感保持一致。颜色来自宿主 MiuixTheme，只把 background 的 alpha 降下来。
    // 宿主窗口透明，页面半透明底才透得出下层画面（只在模块自己的 Activity 里改，不动微信）。
    val hostContext = LocalContext.current
    DisposableEffect(hostContext) {
        val activity = hostContext.findActivity()
        activity?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        onDispose { }
    }
    val base = MiuixTheme.colorScheme
    MiuixTheme(
        colors = base.copy(background = base.background.copy(alpha = GLASS_ALPHA)),
        content = content,
    )
}

/** 模块页面底色不透明度（1f = 完全不透明）。 */
private const val GLASS_ALPHA = 0.92f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
