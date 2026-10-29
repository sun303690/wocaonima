package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixBaseWidget(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    description: String? = summary,
    icon: ImageVector? = null,
    iconColor: Color? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    isError: Boolean = false,
    iconPlaceholder: Boolean = true,
    titleStyle: TextStyle = MiuixTheme.textStyles.body1,
    onClick: (() -> Unit)? = null,
    onTrailingClick: (() -> Unit)? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingDivider: Boolean = false,
    foreContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val cardColor = when {
        isError -> MiuixTheme.colorScheme.errorContainer
        selected -> MiuixTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.surfaceContainer
    }
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = cardColor), onClick = if (enabled) onClick else null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, tint = iconColor ?: MiuixTheme.colorScheme.primary)
            leadingContent?.invoke(this)
            if (icon != null || leadingContent != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = titleStyle)
                val detail = description ?: summary
                if (!detail.isNullOrBlank()) Text(detail, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (foreContent != null) Box { foreContent() }
            if (trailingContent != null) Box { trailingContent() }
            if (onTrailingClick != null) IconButton(onClick = onTrailingClick) { Icon(MaterialSymbols.Outlined.Delete, null) }
        }
        if (trailingDivider) HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
fun MiuixSwitchWidget(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    description: String? = summary,
    icon: ImageVector? = null,
    iconPlaceholder: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    isError: Boolean = false,
    trailingDivider: Boolean = false,
) {
    MiuixBaseWidget(
        title = title,
        modifier = modifier,
        summary = summary,
        description = description,
        icon = icon,
        iconPlaceholder = iconPlaceholder,
        enabled = enabled,
        isError = isError,
        onClick = if (enabled) onClick ?: { onCheckedChange(!checked) } else null,
    )
}

class MiuixSegmentedColumnScope {
    private val entries = mutableListOf<@Composable () -> Unit>()
    fun item(key: Any? = null, animatedVisibility: Boolean = true, content: @Composable () -> Unit) {
        if (animatedVisibility) entries += content
    }
    @Composable internal fun Render() { entries.forEach { it() } }
}

@Composable
fun MiuixSegmentedColumn(modifier: Modifier = Modifier, title: String = "", contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), titlePadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp), content: MiuixSegmentedColumnScope.() -> Unit) {
    val scope = MiuixSegmentedColumnScope().apply(content)
    Column(modifier.fillMaxWidth().padding(contentPadding)) {
        if (title.isNotEmpty()) SmallTitle(text = title, modifier = Modifier.padding(titlePadding))
        scope.Render()
    }
}
