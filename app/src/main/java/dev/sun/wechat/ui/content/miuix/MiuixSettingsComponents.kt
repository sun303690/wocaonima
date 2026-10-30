package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.sun.wechat.ui.content.GlobalImageLoader
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared Miuix host and primitives for every WeKit-owned configuration page. */
/** 二级页统一标题栏（大标题 + 圆形返回 + 右侧动作），供自定义布局的页面复用。 */
@Composable
fun MiuixSecondaryBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.background.copy(alpha = .95f)) {
        Row(
            Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    "‹",
                    fontSize = 38.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp),
                    color = MiuixTheme.colorScheme.onBackground,
                )
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
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
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.SettingsScaffold(
            title = title,
            onBack = onBack,
            actions = actions,
            content = content,
        )
        return
    }
    val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
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
            // MiuixTheme 不提供根 Surface，缺底色时页面会透出下层的微信界面。
            .background(MiuixTheme.colorScheme.background)
    ) {
        if (wallpaper != null) {
            AsyncImage(
                model = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                imageLoader = GlobalImageLoader,
                modifier = Modifier.fillMaxSize(),
            )
            // 压一层底色保证卡片/文字对比度
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background.copy(alpha = 0.94f)),
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

@Composable
fun MiuixSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.BaseItemContainer(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
        return
    }
    val shape = RoundedCornerShape(20.dp)
    Surface(Modifier.fillMaxWidth().clip(shape), shape = shape, color = MiuixTheme.colorScheme.surfaceContainer) {
        Column(content = content)
    }
}

@Composable
fun MiuixSettingsAction(title: String, summary: String = "", onClick: () -> Unit) {
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.BaseWidget(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            description = summary.ifEmpty { null },
            onClick = onClick,
        )
        return
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface)
            if (summary.isNotEmpty()) Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text("›", fontSize = 25.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
fun MiuixSettingsToggle(title: String, summary: String = "", checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.SwitchWidget(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            description = summary.ifEmpty { null },
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        return
    }
    BasicComponent(
        title = title,
        summary = summary.ifEmpty { null },
        endActions = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
fun MiuixSettingsSearch(value: String, hint: String, onValueChange: (String) -> Unit, onSearch: () -> Unit = {}) {
    if (material3Engine()) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { androidx.compose.material3.Text(hint) },
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = onSearch) {
                    androidx.compose.material3.Text("\u2315")
                }
            },
        )
        return
    }
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
    if (material3Engine()) {
        androidx.compose.material3.Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
        return
    }
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
}
