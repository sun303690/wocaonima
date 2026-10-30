package dev.sun.wechat.ui.content.miuix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sun.wechat.ui.utils.theme.SettingsUiEngine
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 当前界面引擎是否为 Material 3（Expressive）。
 *
 * 这是 Miuix 兼容层的总开关：本包内所有 `Miuix*` 组件在 M3 引擎下都会转发到
 * [dev.sun.wechat.ui.content.m3] 里与上游 WeKit 一致的 M3 实现，因此全仓
 * 166 个使用 Miuix 包装组件的调用点无需改动即可跟随引擎切换。
 */
@Composable
fun material3Engine(): Boolean = ThemeSettings.uiEngine == SettingsUiEngine.MATERIAL3

/** 非 Composable 上下文（如 LazyListScope 扩展）里读引擎用的版本。 */
fun material3EngineCurrent(): Boolean = ThemeSettings.uiEngine == SettingsUiEngine.MATERIAL3

/**
 * M3 引擎下给残留的原生 Miuix 组件（`top.yukonga.miuix.kmp.basic.*` 直引）兜底配色：
 * 把 MaterialTheme 的配色映射成 Miuix 的 Colors，字体走 Miuix 默认文本样式。
 */
@Composable
fun MiuixMaterial3Palette(content: @Composable () -> Unit) {
    val m3 = MaterialTheme.colorScheme
    val colors = remember(m3) {
        lightColorScheme().apply {
            primary = m3.primary
            onPrimary = m3.onPrimary
            primaryVariant = m3.primaryContainer
            onPrimaryVariant = m3.onPrimaryContainer
            primaryContainer = m3.primaryContainer
            onPrimaryContainer = m3.onPrimaryContainer
            secondary = m3.secondary
            onSecondary = m3.onSecondary
            secondaryVariant = m3.secondaryContainer
            onSecondaryVariant = m3.onSecondaryContainer
            secondaryContainer = m3.secondaryContainer
            onSecondaryContainer = m3.onSecondaryContainer
            secondaryContainerVariant = m3.surfaceContainerHigh
            onSecondaryContainerVariant = m3.onSurfaceVariant
            tertiaryContainer = m3.tertiaryContainer
            onTertiaryContainer = m3.onTertiaryContainer
            tertiaryContainerVariant = m3.tertiary
            background = m3.background
            onBackground = m3.onBackground
            onBackgroundVariant = m3.onSurfaceVariant
            surface = m3.surface
            onSurface = m3.onSurface
            surfaceVariant = m3.surfaceVariant
            onSurfaceSecondary = m3.onSurfaceVariant
            onSurfaceVariantSummary = m3.onSurfaceVariant
            onSurfaceVariantActions = m3.onSurfaceVariant
            surfaceContainer = m3.surfaceContainer
            onSurfaceContainer = m3.onSurfaceContainer
            onSurfaceContainerVariant = m3.onSurfaceVariant
            surfaceContainerHigh = m3.surfaceContainerHigh
            onSurfaceContainerHigh = m3.onSurfaceContainer
            surfaceContainerHighest = m3.surfaceContainerHighest
            onSurfaceContainerHighest = m3.onSurfaceContainer
            outline = m3.outline
            dividerLine = m3.outlineVariant
            error = m3.error
            onError = m3.onError
            errorContainer = m3.errorContainer
            onErrorContainer = m3.onErrorContainer
        }
    }
    MiuixTheme(colors = colors, content = content)
}
