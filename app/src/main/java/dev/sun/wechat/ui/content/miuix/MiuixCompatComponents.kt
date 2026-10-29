package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Small Miuix-native replacements for Material chips and list rows. */
@Composable
fun MiuixFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.background(if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { label() }
}

@Composable
fun MiuixListItem(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier, supportingContent: (@Composable () -> Unit)? = null, leadingContent: (@Composable () -> Unit)? = null, trailingContent: (@Composable () -> Unit)? = null, containerColor: Color = Color.Transparent) {
    Row(modifier.fillMaxWidth().background(containerColor).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leadingContent != null) { leadingContent(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) { headlineContent(); supportingContent?.invoke() }
        if (trailingContent != null) { Spacer(Modifier.width(12.dp)); trailingContent() }
    }
}

@Composable
fun MiuixChoiceChip(selected: Boolean, onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    MiuixFilterChip(selected, onClick, modifier = modifier, label = { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null); Spacer(Modifier.width(6.dp)); Text(text) } })
}
