package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Miuix replacement for Material3 confirmation dialogs used by feature settings. */
@Composable
fun MiuixAlertDialog(
    onDismissRequest: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    OverlayDialog(
        show = true,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.invoke()
            title?.invoke()
            text?.invoke()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
fun MiuixMessageDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    onDismiss: () -> Unit,
) {
    if (!show) return
    OverlayDialog(show = true, title = title, summary = message, onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onDismiss) { Text(confirmText) }
        }
    }
}
