package dev.sun.wechat.ui.utils.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 组件引擎根节点：WeKit 自己的所有 Compose 页面都从这里进。
 *
 * 统一成 iOS「分组列表」观感：
 * - 页面底色、卡片底色、三级标签灰、强调色全部来自 [IosGroupedPalette]（浅色 / 深色两套）；
 * - 深色跟随模块主题设置（[ThemeSettings.themeMode]，含「跟随系统」），不再依赖微信自己的夜间开关；
 * - 底色不透明：卡片之间靠 22dp 分组间距区分，不再透出下层微信界面；
 * - 同步状态栏 / 导航栏图标明暗，深色下不会出现「黑底黑字图标」。
 */
@Composable
fun MiuixModuleTheme(
    darkTheme: Boolean? = null,
    @Suppress("UNUSED_PARAMETER") opaqueBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme ?: ThemeSettings.themeMode.resolve()) IosDarkPalette else IosLightPalette

    val hostContext = LocalContext.current
    DisposableEffect(hostContext, palette.isDark) {
        val activity = hostContext.findActivity()
        activity?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !palette.isDark
                isAppearanceLightNavigationBars = !palette.isDark
            }
        }
        onDispose { }
    }

    val base = MiuixTheme.colorScheme
    // 把 Miuix 的语义 token 整体映射到 iOS 分组调色板：底色/卡片/标签灰/强调/危险各归其位，
    // 这样页面里直接写 MiuixTheme.colorScheme.xxx 的地方在浅色与深色下都成立，
    // 不需要每个页面自己判断夜间模式。
    CompositionLocalProvider(LocalIosGroupedPalette provides palette) {
        MiuixTheme(
            colors = base.copy(
                background = palette.groupedBackground,
                onBackground = palette.labelPrimary,
                surface = palette.card,
                onSurface = palette.labelPrimary,
                surfaceVariant = palette.controlFill,
                onSurfaceVariant = palette.labelSecondary,
                onSurfaceVariantSummary = palette.labelSecondary,
                surfaceContainer = palette.card,
                surfaceContainerHigh = palette.cardPressed,
                surfaceContainerHighest = palette.cardPressed,
                surfaceBright = palette.elevatedBackground,
                primary = palette.accent,
                onPrimary = Color.White,
                primaryContainer = palette.accent.copy(alpha = if (palette.isDark) 0.24f else 0.12f),
                onPrimaryContainer = palette.accent,
                secondary = palette.accent,
                secondaryContainer = palette.controlFill,
                onSecondaryContainer = palette.labelPrimary,
                tertiaryContainer = palette.controlFill,
                onTertiaryContainer = palette.labelPrimary,
                error = palette.danger,
                errorContainer = palette.danger.copy(alpha = if (palette.isDark) 0.24f else 0.12f),
                onErrorContainer = palette.danger,
                outline = palette.separator,
                outlineVariant = palette.separator,
            ),
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}