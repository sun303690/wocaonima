package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sun.wechat.ui.utils.theme.IosGrouped
import dev.sun.wechat.ui.utils.theme.LocalIosGroupedPalette
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text

/** Small Miuix-native replacements for Material chips and list rows. */
@Composable
fun MiuixFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    val palette = LocalIosGroupedPalette.current
    Box(
        modifier.background(
            if (selected) palette.accent.copy(alpha = if (palette.isDark) 0.24f else 0.12f) else palette.controlFill,
            RoundedCornerShape(IosGrouped.controlRadius),
        )
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(6.dp))
            }
            label()
        }
    }
}

@Composable
fun MiuixListItem(
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(containerColor)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = IosGrouped.rowMinHeight)
            .padding(
                start = IosGrouped.separatorInset,
                end = IosGrouped.separatorInset,
                top = IosGrouped.rowVerticalPadding,
                bottom = IosGrouped.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) { leadingContent(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) { headlineContent(); supportingContent?.invoke() }
        if (trailingContent != null) { Spacer(Modifier.width(12.dp)); trailingContent() }
    }
}

@Composable
fun MiuixChoiceChip(selected: Boolean, onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    MiuixFilterChip(selected, onClick, modifier = modifier, label = { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null); Spacer(Modifier.width(6.dp)); Text(text) } })
}
