package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sun.wechat.ui.content.GlobalImageLoader
import dev.sun.wechat.ui.utils.theme.IosGrouped
import dev.sun.wechat.ui.utils.theme.LocalIosGroupedPalette
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.basic.*

/** 壁纸压层透明度：越小壁纸越明显，但文字对比度越低。卡片本身始终是不透明实色。 */
private const val WALLPAPER_SCRIM_ALPHA = 0.6f

/** Shared Miuix host and primitives for every WeKit-owned configuration page. */
/** 二级页统一标题栏（大标题 + 圆形返回 + 右侧动作），供自定义布局的页面复用。 */
@Composable
fun MiuixSecondaryBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalIosGroupedPalette.current
    Surface(Modifier.fillMaxWidth(), color = palette.elevatedBackground) {
        Row(
            Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    "‹",
                    fontSize = 34.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp),
                    color = palette.accent,
                )
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.labelPrimary)
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/**
 * 全 App 二级页唯一外壳：大标题 + 圆形返回 + 可选背景图 + 统一底色。
 *
 * 所有二级页（设置内二级页、Agent 设置、功能设置 Activity…）都走这里，
 * 不再各自拼 Scaffold/TopAppBar，避免「每个页面一个样」。
 */
@Composable
fun MiuixSecondaryScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showWallpaper: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val palette = LocalIosGroupedPalette.current
    val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    val wallpaper = remember(showWallpaper, ThemeSettings.settingsWallpaper, ThemeSettings.settingsWallpaperPath) {
        if (!showWallpaper || !ThemeSettings.settingsWallpaper) {
            null
        } else {
            ThemeSettings.settingsWallpaperPath?.let { java.io.File(it) }?.takeIf { it.isFile }
                ?: dev.sun.wechat.features.items.beautify.ThemePack.active()?.wallpaper()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            // 底色用 iOS 分组底色，且完全不透明：卡片的分组感来自间距，不靠透明度。
            .background(palette.groupedBackground)
    ) {
        if (wallpaper != null) {
            AsyncImage(
                model = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = GlobalImageLoader,
                modifier = Modifier.fillMaxSize(),
            )
            // 压一层底色：让壁纸透出来，同时保住卡片与文字的对比度。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.groupedBackground.copy(alpha = WALLPAPER_SCRIM_ALPHA)),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerHeight + 12.dp, bottom = 24.dp),
            content = content,
        )
        MiuixSecondaryBar(title = title, onBack = onBack, actions = actions)
    }
}

/** 兼容旧调用点：设置内二级页统一外壳。 */
@Composable
fun MiuixSettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    showWallpaper: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    MiuixSecondaryScaffold(
        title = title,
        onBack = onBack,
        actions = { actions?.invoke() },
        showWallpaper = showWallpaper,
        content = content,
    )
}

/** 设置页里的分组卡片：与 [IosCard] 同一套观感（12dp 圆角、卡片底色、无阴影）。 */
@Composable
fun MiuixSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    IosCard(modifier, content = content)
}

/** 可点进下一级的行；[divider] 为真时行下补一条缩进分隔线。 */
@Composable
fun MiuixSettingsAction(title: String, summary: String = "", onClick: () -> Unit, divider: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        IosRow(title = title, summary = summary.ifEmpty { null }, onClick = onClick) { IosChevron() }
        if (divider) IosDivider()
    }
}

/** 开关行；[divider] 为真时行下补一条缩进分隔线。 */
@Composable
fun MiuixSettingsToggle(
    title: String,
    summary: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean = false,
) {
    IosSwitchRow(
        title = title,
        summary = summary.ifEmpty { null },
        checked = checked,
        onCheckedChange = onCheckedChange,
        divider = divider,
    )
}

@Composable
fun MiuixSettingsSearch(value: String, hint: String, onValueChange: (String) -> Unit, onSearch: () -> Unit = {}) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = hint,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = { Text("⌕", fontSize = 23.sp, modifier = Modifier.clickable(onClick = onSearch)) },
    )
}

@Composable
fun MiuixSettingsSectionTitle(text: String) {
    val palette = LocalIosGroupedPalette.current
    Text(
        text,
        fontSize = 13.sp,
        color = palette.labelSecondary,
        modifier = Modifier.padding(
            start = IosGrouped.separatorInset,
            top = IosGrouped.sectionSpacing,
            bottom = IosGrouped.headerSpacing,
        ),
    )
}
