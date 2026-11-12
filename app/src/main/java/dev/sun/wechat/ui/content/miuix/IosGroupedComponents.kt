package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.ui.utils.theme.IosGrouped
import dev.sun.wechat.ui.utils.theme.LocalIosGroupedPalette
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 当前组合是否已经位于一张分组卡片内。
 *
 * 兼容层里的行组件（`MiuixBaseWidget` 等）据此决定是「只画一行」还是「自己包一张卡片」：
 * 在 [IosCard] / [MiuixSegmentedColumn] / [MiuixBaseItemContainer] 内部只画行，
 * 单独使用时自动补卡片，避免出现裸行或卡片套卡片。
 */
val LocalInIosCard = staticCompositionLocalOf { false }

/**
 * iOS「分组卡片」基础件：分组标题 + 圆角卡片 + 卡片内部行。
 *
 * 所有模块页面都用这一套：
 * ```
 * IosSectionHeader("界面")
 * IosCard {
 *     IosRow("深色模式", "跟随系统", onClick = { ... }) { IosChevron() }
 *     IosDivider()
 *     IosSwitchRow("显示图标", checked = on, onCheckedChange = { ... })
 * }
 * ```
 * 颜色全部取 [LocalIosGroupedPalette]，浅色/深色自动切换，不在页面里写死色值。
 */

/** 分组标题（iOS: 小组灰字，左缩进与卡片内文字对齐）。 */
@Composable
fun IosSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val palette = LocalIosGroupedPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = IosGrouped.separatorInset,
                end = IosGrouped.separatorInset,
                top = IosGrouped.sectionSpacing,
                bottom = IosGrouped.headerSpacing,
            )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = palette.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!footnote.isNullOrEmpty()) {
            Text(
                text = footnote,
                fontSize = 12.sp,
                color = palette.labelTertiary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 圆角分组卡片（iOS: inset grouped，12dp 圆角、无边框、无阴影）。 */
@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    color: Color = LocalIosGroupedPalette.current.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(IosGrouped.cardRadius)
    CompositionLocalProvider(LocalInIosCard provides true) {
        Column(
            modifier
                .fillMaxWidth()
                .background(color = color, shape = shape),
            content = content,
        )
    }
}

/** 卡片内分隔线（左缩进 16dp，iOS 上分隔线不顶到卡片边缘）。 */
@Composable
fun IosDivider(startInset: Dp = IosGrouped.separatorInset, endInset: Dp = 0.dp) {
    val palette = LocalIosGroupedPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset, end = endInset)
            .height(0.5.dp)
            .background(palette.separator)
    )
}

/** 通用行：左侧标题 + 可选摘要 + 尾部内容（箭头 / 开关 / 值）。可有 onClick。 */
@Composable
fun IosRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalIosGroupedPalette.current
    val titleColor = when {
        danger -> palette.danger
        enabled -> palette.labelPrimary
        else -> palette.labelTertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = IosGrouped.rowMinHeight)
            .padding(
                start = IosGrouped.separatorInset,
                end = IosGrouped.separatorInset,
                top = IosGrouped.rowVerticalPadding,
                bottom = IosGrouped.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = palette.labelSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 开关行；[divider] 为真时在本行下方补一条缩进分隔线（同组多行时除最后一行都用它）。 */
@Composable
fun IosSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    divider: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        IosRow(
            title = title,
            summary = summary,
            enabled = enabled,
            onClick = { if (enabled) onCheckedChange(!checked) },
        ) {
            Switch(checked = checked, onCheckedChange = { onCheckedChange(it) })
        }
        if (divider) IosDivider()
    }
}

/** 右侧带值的行（iOS: 值右对齐、灰色，行可点进入下一级）。 */
@Composable
fun IosValueRow(
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    showChevron: Boolean = onClick != null,
) {
    val palette = LocalIosGroupedPalette.current
    IosRow(title = title, onClick = onClick, danger = danger) {
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                fontSize = 15.sp,
                color = if (danger) palette.danger else palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) IosChevron()
    }
}

/** 行尾箭头（iOS: 灰色 chevron，不用主题强调色）。 */
@Composable
fun IosChevron(modifier: Modifier = Modifier, color: Color = LocalIosGroupedPalette.current.chevron) {
    Text(text = "›", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = color, modifier = modifier)
}

/** 页面底（iOS: systemGroupedBackground）。 */
@Composable
fun IosPageBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalIosGroupedPalette.current
    Column(modifier.fillMaxWidth().background(palette.groupedBackground)) { content() }
}

/** 分组之间的间距占位。 */
@Composable
fun IosSectionGap(height: Dp = IosGrouped.sectionSpacing) {
    Spacer(Modifier.height(height))
}

/** 卡片上下留白（放在 IosCard 之后收尾用）。 */
@Composable
fun IosCardBottomGap() {
    Spacer(Modifier.size(2.dp))
}
