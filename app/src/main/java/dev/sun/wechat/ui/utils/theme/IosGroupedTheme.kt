package dev.sun.wechat.ui.utils.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * iOS「分组列表（inset grouped）」配色。
 *
 * 模块内所有 Compose 页面共用这一套语义色：底色 = systemGroupedBackground，
 * 卡片 = secondarySystemGroupedBackground，文字/分隔线/箭头按 iOS 的三级标签灰。
 * 浅色与深色各一份，页面只读 [LocalIosGroupedPalette]，不直接写颜色常量。
 */
data class IosGroupedPalette(
    /** 页面底色（iOS: systemGroupedBackground）。 */
    val groupedBackground: Color,
    /** 分组之上的浮层底色（导航栏、弹层）。 */
    val elevatedBackground: Color,
    /** 分组卡片底色（iOS: secondarySystemGroupedBackground）。 */
    val card: Color,
    /** 卡片内按下态。 */
    val cardPressed: Color,
    /** 卡片内分隔线（iOS: separator，带透明度的标签色）。 */
    val separator: Color,
    /** 主标签色。 */
    val labelPrimary: Color,
    /** 次标签色（行摘要）。 */
    val labelSecondary: Color,
    /** 三级标签色（占位、禁用）。 */
    val labelTertiary: Color,
    /** 强调色（iOS: systemBlue / systemBlue-dark）。 */
    val accent: Color,
    /** 危险色（iOS: systemRed / systemRed-dark）。 */
    val danger: Color,
    /** 行尾箭头。 */
    val chevron: Color,
    /** 输入框 / 分段控件的填充底色。 */
    val controlFill: Color,
    val isDark: Boolean,
)

/** iOS 浅色（Light）。 */
val IosLightPalette = IosGroupedPalette(
    groupedBackground = Color(0xFFF2F2F7),
    elevatedBackground = Color(0xFFF9F9F9),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFE5E5EA),
    separator = Color(0x2E3C3C43),
    labelPrimary = Color(0xFF000000),
    labelSecondary = Color(0x993C3C43),
    labelTertiary = Color(0x4D3C3C43),
    accent = Color(0xFF007AFF),
    danger = Color(0xFFFF3B30),
    chevron = Color(0xFFC7C7CC),
    controlFill = Color(0x14747480),
    isDark = false,
)

/** iOS 深色（Dark）。 */
val IosDarkPalette = IosGroupedPalette(
    groupedBackground = Color(0xFF000000),
    elevatedBackground = Color(0xFF1C1C1E),
    card = Color(0xFF1C1C1E),
    cardPressed = Color(0xFF2C2C2E),
    separator = Color(0x99545458),
    labelPrimary = Color(0xFFFFFFFF),
    labelSecondary = Color(0x99EBEBF5),
    labelTertiary = Color(0x4DEBEBF5),
    accent = Color(0xFF0A84FF),
    danger = Color(0xFFFF453A),
    chevron = Color(0xFF48484A),
    controlFill = Color(0x3D767680),
    isDark = true,
)

/** 当前页面用的 iOS 分组配色；默认浅色，页面外壳会 provide 深色。 */
val LocalIosGroupedPalette = staticCompositionLocalOf { IosLightPalette }

/** iOS 分组列表的尺寸令牌（一处改，全 App 生效）。 */
object IosGrouped {
    /** 两个分组之间的垂直间距。 */
    val sectionSpacing = 22.dp

    /** 分组卡片圆角（iOS inset grouped 为 10–12）。 */
    val cardRadius = 12.dp

    /** 控件圆角（输入框、分段控件）。 */
    val controlRadius = 10.dp

    /** 页面左右安全边距。 */
    val horizontalInset = 16.dp

    /** 单行最小高度（iOS 44dp，中文行加高到 48）。 */
    val rowMinHeight = 48.dp

    /** 单行上下内边距。 */
    val rowVerticalPadding = 11.dp

    /** 分隔线左侧缩进。 */
    val separatorInset = 16.dp

    /** 分组标题与卡片之间的间距。 */
    val headerSpacing = 7.dp
}

/** 读当前主题模式决定浅色/深色（跟随系统 / 强制浅色 / 强制深色）。 */
@Composable
fun rememberIosGroupedPalette(): IosGroupedPalette =
    if (ThemeSettings.themeMode.resolve()) IosDarkPalette else IosLightPalette

/** 把一个现成的配色装进 CompositionLocal（外壳与预览共用）。 */
@Composable
fun ProvideIosGroupedPalette(
    palette: IosGroupedPalette,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIosGroupedPalette provides palette, content = content)
}
