package dev.sun.wechat.ui.utils.theme

import androidx.compose.runtime.Composable
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
    MiuixTheme(content = content)
}
