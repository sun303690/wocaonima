package dev.sun.wechat.ui.utils.theme

import androidx.compose.runtime.Composable
import dev.sun.wechat.ui.content.miuix.MiuixMaterial3Palette
import dev.sun.wechat.ui.content.nuke.NukeModuleTheme

/**
 * 引擎感知的模块主题根：功能页 / 对话框 / 独立 Activity 统一用它，
 * 取代此前无条件的 [MiuixModuleTheme]，使 Material 3 引擎下整棵组件树拿到
 * MaterialExpressiveTheme（并为残留原生 Miuix 组件提供 M3 配色桥）。
 */
@Composable
fun EngineModuleTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    when (ThemeSettings.uiEngine) {
        SettingsUiEngine.MATERIAL3 -> ModuleTheme(
            darkTheme = darkTheme ?: ThemeSettings.themeMode.resolve(),
        ) {
            MiuixMaterial3Palette(content = content)
        }

        SettingsUiEngine.MIUIX -> MiuixModuleTheme(darkTheme = darkTheme, content = content)

        SettingsUiEngine.NUKE -> NukeModuleTheme(content = content)
    }
}
