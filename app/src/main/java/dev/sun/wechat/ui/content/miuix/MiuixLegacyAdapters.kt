package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import dev.sun.wechat.ui.utils.theme.IosGrouped
import dev.sun.wechat.ui.utils.theme.LocalIosGroupedPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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
    val row: @Composable () -> Unit = {
        MiuixIosRow(
            title = title,
            summary = summary ?: description,
            icon = icon,
            iconColor = iconColor,
            enabled = enabled,
            selected = selected,
            isError = isError,
            titleStyle = titleStyle,
            onClick = onClick,
            onTrailingClick = onTrailingClick,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            trailingDivider = trailingDivider,
            foreContent = foreContent,
            modifier = modifier,
        )
    }
    // iOS 分组列表：已在分组卡片里（MiuixSegmentedColumn / IosCard）就只画一行；
    // 单独使用时自己包一层卡片，避免出现「裸行」。
    if (LocalInIosCard.current) {
        row()
    } else {
        IosCard { row() }
    }
}

/** iOS 分组列表的单行：图标 + 标题 + 摘要 + 尾部内容，不自己画卡片。 */
@Composable
private fun MiuixIosRow(
    title: String,
    summary: String?,
    icon: ImageVector?,
    iconColor: Color?,
    enabled: Boolean,
    selected: Boolean,
    isError: Boolean,
    titleStyle: TextStyle,
    onClick: (() -> Unit)?,
    onTrailingClick: (() -> Unit)?,
    leadingContent: (@Composable RowScope.() -> Unit)?,
    trailingContent: (@Composable BoxScope.() -> Unit)?,
    trailingDivider: Boolean,
    foreContent: (@Composable BoxScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalIosGroupedPalette.current
    val titleColor = when {
        isError -> palette.danger
        !enabled -> palette.labelTertiary
        else -> palette.labelPrimary
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .background(
                when {
                    selected -> palette.cardPressed
                    isError -> palette.danger.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = IosGrouped.rowMinHeight)
                .padding(
                    start = IosGrouped.separatorInset,
                    end = IosGrouped.separatorInset,
                    top = IosGrouped.rowVerticalPadding,
                    bottom = IosGrouped.rowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, tint = iconColor ?: palette.accent)
            leadingContent?.invoke(this)
            if (icon != null || leadingContent != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = titleStyle, color = titleColor)
                if (!summary.isNullOrBlank()) {
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = palette.labelSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (foreContent != null) Box { foreContent() }
            if (trailingContent != null) Box { trailingContent() }
            if (onTrailingClick != null) IconButton(onClick = onTrailingClick) { Icon(MaterialSymbols.Outlined.Delete, null) }
        }
        if (trailingDivider) IosDivider()
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
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { onCheckedChange(it) },
                enabled = enabled,
            )
        },
    )
}

class MiuixSegmentedColumnScope {
    private val entries = mutableListOf<@Composable () -> Unit>()
    fun item(key: Any? = null, animatedVisibility: Boolean = true, content: @Composable () -> Unit) {
        if (animatedVisibility) entries += content
    }
    @Composable internal fun Render() {
        entries.forEachIndexed { index, entry ->
            if (index > 0) IosDivider()
            entry()
        }
    }
}

@Composable
fun MiuixSegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    contentPadding: PaddingValues = PaddingValues(vertical = 0.dp),
    titlePadding: PaddingValues = PaddingValues(start = IosGrouped.separatorInset, bottom = IosGrouped.headerSpacing),
    content: MiuixSegmentedColumnScope.() -> Unit,
) {
    val scope = MiuixSegmentedColumnScope().apply(content)
    val inDialog = dev.sun.wechat.ui.utils.LocalInComposeDialog.current
    val palette = LocalIosGroupedPalette.current
    // iOS inset grouped：分组标题是卡外的灰色小字，卡片 12dp 圆角、无阴影、行间 0.5dp 分隔线。
    // 弹窗里的内容不套卡片（弹窗本身已是卡片），避免双层圆角。
    // 页面外壳（MiuixSecondaryScaffold）已提供 16dp 左右边距，这里不再叠加，避免卡片越缩越窄。
    Column(modifier.fillMaxWidth()) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = palette.labelSecondary,
                modifier = Modifier
                    .padding(titlePadding)
                    .padding(start = if (inDialog) 0.dp else IosGrouped.separatorInset, top = if (inDialog) 0.dp else IosGrouped.sectionSpacing),
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalInIosCard provides true) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (inDialog) Modifier
                        else Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(IosGrouped.cardRadius))
                            .background(palette.card)
                    )
                    .padding(contentPadding)
            ) {
                scope.Render()
            }
        }
    }
}
