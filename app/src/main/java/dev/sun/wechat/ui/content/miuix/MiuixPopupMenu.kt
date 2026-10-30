package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun MiuixDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    if (material3Engine()) {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = androidx.compose.ui.unit.DpOffset(offset.x, offset.y),
        ) {
            content()
        }
        return
    }
    val density = LocalDensity.current
    Popup(
        alignment = androidx.compose.ui.Alignment.TopEnd,
        offset = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Card(modifier = modifier) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
fun MiuixDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (material3Engine()) {
        androidx.compose.material3.DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
        )
        return
    }
    MiuixListItem(
        headlineContent = text,
        modifier = modifier,
        leadingContent = leadingIcon,
        trailingContent = trailingIcon,
        enabled = enabled,
        onClick = onClick,
    )
}
