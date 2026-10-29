package dev.sun.wechat.ui.utils.theme

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix component engine root for WeKit-owned Compose pages. */
@Composable
fun MiuixModuleTheme(content: @Composable () -> Unit) {
    MiuixTheme(content = content)
}
