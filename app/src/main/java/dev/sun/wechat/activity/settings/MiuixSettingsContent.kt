package dev.sun.wechat.activity.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.activity.self_check.SelfCheckSettingsActivity
import dev.sun.wechat.ui.utils.theme.AppThemeMode
import dev.sun.wechat.ui.utils.theme.SettingsUiEngine
import dev.sun.wechat.ui.content.miuix.IosCard
import dev.sun.wechat.ui.content.miuix.IosChevron
import dev.sun.wechat.ui.content.miuix.IosDivider
import dev.sun.wechat.ui.content.miuix.IosRow
import dev.sun.wechat.ui.content.miuix.IosSwitchRow
import dev.sun.wechat.ui.utils.theme.IosGrouped
import dev.sun.wechat.ui.utils.theme.LocalIosGroupedPalette
import dev.sun.wechat.ui.content.miuix.MiuixBaseItemContainer
import dev.sun.wechat.ui.content.miuix.MiuixSettingsPage
import dev.sun.wechat.ui.utils.theme.PageTransitionAnimation
import dev.sun.wechat.features.core.BaseFeature
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.core.FeaturesProvider
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import dev.sun.wechat.utils.WeLogger
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.Color
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Wallpaper

private enum class MiuixPage { HOME, FEATURES, CATEGORY, APPEARANCE, BACKUP, LOGS, ABOUT, LICENSE }

@Composable
fun MiuixSettingsContent(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(MiuixPage.HOME) }
    var query by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(FEATURE_CATEGORIES.first().id) }
    // 页面是本地状态栈而不是 NavHost，必须自己接管返回键，否则返回会直接关掉设置。
    androidx.activity.compose.BackHandler(enabled = page != MiuixPage.HOME) {
        page = when (page) {
            MiuixPage.CATEGORY -> MiuixPage.FEATURES
            MiuixPage.LICENSE -> MiuixPage.ABOUT
            else -> MiuixPage.HOME
        }
    }
    when (page) {
        MiuixPage.HOME -> MiuixHome(
            onFinish,
            { page = MiuixPage.FEATURES },
            { id -> categoryId = id; page = MiuixPage.CATEGORY },
            { query = it; page = MiuixPage.FEATURES },
            { page = MiuixPage.APPEARANCE },
            { page = MiuixPage.BACKUP },
            { page = MiuixPage.LOGS },
            { page = MiuixPage.ABOUT },
        )
        MiuixPage.FEATURES -> MiuixFeatures(
            query,
            { query = it },
            { page = MiuixPage.HOME },
            { id -> categoryId = id; page = MiuixPage.CATEGORY },
        )
        MiuixPage.CATEGORY -> MiuixCategoryPage(categoryId, { page = MiuixPage.FEATURES })
        MiuixPage.APPEARANCE -> MiuixAppearancePage { page = MiuixPage.HOME }
        MiuixPage.BACKUP -> MiuixBackupPage { page = MiuixPage.HOME }
        MiuixPage.LOGS -> MiuixLogsPage { page = MiuixPage.HOME }
        MiuixPage.ABOUT -> MiuixAboutPage({ page = MiuixPage.HOME }, { page = MiuixPage.LICENSE })
        MiuixPage.LICENSE -> MiuixLicensePage { page = MiuixPage.ABOUT }
    }
}

@Composable
private fun MiuixHome(
    onFinish: () -> Unit,
    openFeatures: () -> Unit,
    openCategory: (String) -> Unit,
    search: (String) -> Unit,
    openAppearance: () -> Unit,
    openBackup: () -> Unit,
    openLogs: () -> Unit,
    openAbout: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    MiuixSettingsPage("WeKit · Miuix", onFinish) {
        item {
            MiuixSearch(query, { query = it }, { search(query) })
            Spacer(Modifier.height(12.dp))
            MiuixSectionTitle("快捷入口")
            MiuixCard {
                IosRow(
                    title = "全部功能",
                    summary = "浏览分类、搜索与配置所有功能",
                    onClick = openFeatures,
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Extension, Color(0xFF4C8DFF)) }
                ) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "外观设置",
                    summary = "主题模式、强调色、圆角与动效",
                    onClick = openAppearance,
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Wallpaper, Color(0xFF9B6BFF)) }
                ) { IosChevron() }
            }
            Spacer(Modifier.height(4.dp))
            MiuixSectionTitle("功能分类")
            MiuixCard {
                FEATURE_CATEGORIES.forEachIndexed { index, category ->
                    val enabledCount = FeaturesProvider.ALL_FEATURES.count {
                        category.id in it.categoryIds && (it as? SwitchFeature)?.isEnabled == true
                    }
                    IosRow(
                        title = stringResource(category.titleRes),
                        summary = "${enabledCount} 项已启用",
                        onClick = { openCategory(category.id) },
                        leading = { MiuixPreferenceIcon(category.icon, categoryTint(index)) }
                    ) { IosChevron() }
                    if (index != FEATURE_CATEGORIES.lastIndex) IosDivider()
                }
            }
            Spacer(Modifier.height(4.dp))
            MiuixSectionTitle("工具")
            MiuixCard {
                IosRow(title = "主题工坊", summary = "完整主题、壁纸、气泡与诊断", onClick = {
                        (context as? ComponentActivity)?.let(dev.sun.wechat.features.items.beautify.ThemeStore::onClick)
                    }, leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Star, Color(0xFFFF9F0A)) }) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "备份与还原",
                    summary = "导出、导入或清空模块配置",
                    onClick = openBackup,
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Upload, Color(0xFF34C759)) }
                ) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "日志中心",
                    summary = "模块日志、崩溃日志与主题诊断",
                    onClick = openLogs,
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Article, Color(0xFF8E8E93)) }
                ) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "自检与诊断",
                    summary = "检查 Hook、权限与运行环境",
                    onClick = { context.startActivity(Intent(context, SelfCheckSettingsActivity::class.java)) },
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Bug_report, Color(0xFFFF453A)) }
                ) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "关于与许可",
                    summary = "版本、开源协议与项目链接",
                    onClick = openAbout,
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Info, Color(0xFF30B0C7)) }
                ) { IosChevron() }
            }
        }
    }
}

private fun categoryTint(index: Int): Color = when (index % 6) {
    0 -> Color(0xFF4C8DFF)
    1 -> Color(0xFF34C759)
    2 -> Color(0xFFFF9F0A)
    3 -> Color(0xFFFF453A)
    4 -> Color(0xFF9B6BFF)
    else -> Color(0xFF30B0C7)
}

@Composable
private fun MiuixSectionTitle(text: String) {
    // iOS 分组标题：卡外灰色小字，左缩进与卡内文字对齐。
    Text(
        text,
        fontSize = 13.sp,
        color = LocalIosGroupedPalette.current.labelSecondary,
        modifier = Modifier.padding(start = IosGrouped.separatorInset, top = IosGrouped.sectionSpacing, bottom = IosGrouped.headerSpacing),
    )
}

@Composable
private fun MiuixPreferenceIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MiuixAppearancePage(back: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var showEnginePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val target = java.io.File(context.filesDir, "settings_background.img")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                ThemeSettings.updateSettingsWallpaperPath(target.absolutePath)
                tick++
            }
        }
    }
    MiuixChoiceDialog(
        show = showEnginePicker,
        title = "UI 组件引擎",
        options = SettingsUiEngine.entries.map { engine -> engine.displayName to { ThemeSettings.updateUiEngine(engine) } },
        onDismiss = { showEnginePicker = false },
    )
    MiuixSettingsPage("外观设置", back) {
        item {
            MiuixSectionTitle("引擎")
            MiuixCard {
                IosRow(title = "主题模式", summary = ThemeSettings.themeMode.displayName, onClick = {
                        val entries = AppThemeMode.entries
                        ThemeSettings.updateThemeMode(entries[(entries.indexOf(ThemeSettings.themeMode) + 1) % entries.size]); tick++
                    }, leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Wallpaper, Color(0xFF9B6BFF)) }) { IosChevron() }
                IosDivider()
                IosRow(title = "页面切换动效", summary = ThemeSettings.pageTransitionAnimation.name, onClick = {
                        val entries = PageTransitionAnimation.entries
                        ThemeSettings.updatePageTransitionAnimation(entries[(entries.indexOf(ThemeSettings.pageTransitionAnimation) + 1) % entries.size]); tick++
                    }, leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Star, Color(0xFFFF9F0A)) }) { IosChevron() }
                IosDivider()
                IosRow(
                    title = "UI 组件引擎",
                    summary = ThemeSettings.uiEngine.displayName,
                    onClick = { showEnginePicker = true },
                    leading = { MiuixPreferenceIcon(MaterialSymbols.Outlined.Extension, Color(0xFF4C8DFF)) }
                ) { IosChevron() }
            }
            Spacer(Modifier.height(4.dp))
            MiuixSectionTitle("背景")
            MiuixCard {
                IosSwitchRow(
                    title = "设置页背景图",
                    summary = "在设置页显示背景图片",
                    checked = ThemeSettings.settingsWallpaper,
                    onCheckedChange = { ThemeSettings.updateSettingsWallpaper(it); tick++ }
                )
                if (ThemeSettings.settingsWallpaper) {
                    IosDivider()
                    IosRow(
                        title = "选择背景图片",
                        summary = ThemeSettings.settingsWallpaperPath?.let { java.io.File(it).name } ?: "跟随主题壁纸",
                        onClick = { picker.launch("image/*") }
                    ) { IosChevron() }
                    if (ThemeSettings.settingsWallpaperPath != null) {
                        IosDivider()
                        IosRow(
                            title = "清除自定义图片",
                            summary = "恢复使用主题壁纸",
                            onClick = { ThemeSettings.updateSettingsWallpaperPath(null); tick++ }
                        ) { IosChevron() }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            MiuixSectionTitle("取色")
            MiuixCard {
                IosSwitchRow(
                    title = "使用动态壁纸颜色",
                    summary = "从系统壁纸提取强调色",
                    checked = ThemeSettings.dynamicWallpaper,
                    onCheckedChange = { ThemeSettings.updateDynamicWallpaper(it); tick++ }
                )
                IosDivider()
                IosSwitchRow(
                    title = "同时应用到微信",
                    summary = "重启微信后生效",
                    checked = ThemeSettings.applyToWechat,
                    onCheckedChange = { ThemeSettings.updateApplyToWechat(it); tick++ }
                )
            }
        }
    }
}

@Composable
private fun MiuixLogsPage(back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val logFiles = remember(refresh) {
        WeLogger.allLogFiles
            .map { it.toFile() }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }
    MiuixSettingsPage("日志中心", back) {
        item {
            MiuixCard {
                MiuixAction("主题诊断", "复制当前主题渲染与页面判定状态") {
                    val text = dev.sun.wechat.features.items.beautify.ThemeStore.diagnose()
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("WeKit Theme Diagnostics", text))
                }
                IosDivider()
                MiuixAction("刷新日志", "当前共 ${logFiles.size} 个日志文件") { refresh++ }
            }
        }
        items(logFiles, key = { it.absolutePath }) { file ->
            MiuixCard {
                MiuixAction(file.name, "${file.length() / 1024} KB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}") {
                    val text = runCatching { file.readText().takeLast(200_000) }.getOrDefault("读取失败")
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText(file.name, text))
                }
            }
        }
    }
}

@Composable
private fun MiuixAboutPage(back: () -> Unit, openLicense: () -> Unit) {
    val context = LocalContext.current
    MiuixSettingsPage("关于", back) {
        item {
            MiuixCard {
                MiuixAction("WeKit", "版本 ${dev.sun.wechat.BuildConfig.VERSION_NAME}") { }
                IosDivider()
                MiuixAction("开源许可", "查看第三方组件与许可证", openLicense)
                IosDivider()
                MiuixAction("项目主页", "GitHub · sun303690/wocaonima") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/sun303690/wocaonima")))
                }
            }
        }
    }
}

@Composable
private fun MiuixLicensePage(back: () -> Unit) {
    val licenses = listOf(
        "Miuix · Compose Multiplatform UI components",
        "AndroidX · Android Jetpack libraries",
        "Kotlin · Kotlin standard library and coroutines",
        "YukiHookAPI / LSPosed APIs · Xposed integration",
        "MMKV · Tencent key-value storage",
    )
    MiuixSettingsPage("开源许可", back) {
        item { MiuixCard { licenses.forEachIndexed { i, text -> if (i > 0) IosDivider(); MiuixAction(text, "详见项目内许可证与上游仓库") { } } } }
    }
}

@Composable
private fun MiuixBackupPage(back: () -> Unit) {
    MiuixSettingsPage("备份与还原", back) {
        item {
            val context = LocalContext.current
            MiuixCard {
                MiuixAction("导出配置", "保存完整 WeKit 配置备份") {
                    SettingsConfigActions.export(context) { context }
                }
                IosDivider()
                MiuixAction("导入配置", "从备份文件恢复并重启微信") {
                    SettingsConfigActions.importFromDocument(context) { context }
                }
                IosDivider()
                MiuixAction("清空配置", "删除所有模块设置并重启微信") {
                    SettingsConfigActions.clearAndRestart()
                }
            }
        }
    }
}

@Composable
private fun MiuixFeatures(
    query: String,
    update: (String) -> Unit,
    back: () -> Unit,
    openCategory: (String) -> Unit,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val revision = FeatureCategoryState.revision
    // 与 wcx 一致：分类固定顺序；分类内保持功能注册顺序，不按名称/时间重排。
    val searching = query.isNotBlank()
    val results = remember(query, revision) {
        if (!searching) emptyList()
        else FeaturesProvider.ALL_FEATURES.distinctBy { it.technicalId }.filter {
            it.localizedName(context).contains(query, true) ||
                it.localizedDescription(context).contains(query, true)
        }
    }
    MiuixSettingsPage("功能", back) {
        item { MiuixSearch(query, update, {}); Spacer(Modifier.height(12.dp)) }
        if (searching) {
            if (results.isEmpty()) {
                item {
                    MiuixCard {
                        Text(
                            stringResource(R.string.features_no_results),
                            modifier = Modifier.padding(18.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                items(results, key = { it.technicalId }) { feature ->
                    MiuixFeatureRow(feature) { tick++ }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            item {
                MiuixCard {
                    FEATURE_CATEGORIES.forEachIndexed { index, category ->
                        val enabledCount = FeaturesProvider.ALL_FEATURES.count {
                            category.id in it.categoryIds && (it as? SwitchFeature)?.isEnabled == true
                        }
                        MiuixAction(stringResource(category.titleRes), "${enabledCount} 项已启用") {
                            openCategory(category.id)
                        }
                        if (index != FEATURE_CATEGORIES.lastIndex) IosDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixCategoryPage(categoryId: String, back: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val revision = FeatureCategoryState.revision
    val title = FEATURE_CATEGORIES.firstOrNull { it.id == categoryId }
        ?.let { stringResource(it.titleRes) } ?: "功能"
    val features = remember(categoryId, revision) {
        FeaturesProvider.ALL_FEATURES
            .distinctBy { it.technicalId }
            .filter { categoryId in it.categoryIds }
    }
    MiuixSettingsPage(title, back) {
        if (features.isEmpty()) {
            item {
                MiuixCard {
                    Text(
                        stringResource(R.string.features_category_empty),
                        modifier = Modifier.padding(18.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(features, key = { it.technicalId }) { feature ->
                MiuixFeatureRow(feature) { tick++ }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MiuixFeatureRow(feature: BaseFeature, changed: () -> Unit) {
    val revision = FeatureCategoryState.revision
    val checked = remember(feature.technicalId, revision) {
        dev.sun.wechat.data.KvStore.getBoolOrDef(
            feature.technicalId,
            (feature as? SwitchFeature)?.defaultEnabled == true,
        )
    }
    // 页面外壳已给 16dp 左右边距，这里不再叠加，避免卡片越缩越窄。
    Column(Modifier.fillMaxWidth()) {
        MiuixBaseItemContainer {
            FeatureRow(item = feature, checked = checked, onCheckedChange = { changed() })
            feature.Ui()
        }
    }
}

@Composable
private fun MiuixChoiceDialog(
    show: Boolean,
    title: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    if (!show) return
    // Miuix 的 OverlayDialog 只有在 Miuix Scaffold 提供的 popup host 里才会真正渲染，
    // 设置页是普通 LazyColumn，没有任何 host，弹窗会静默不显示。这里改用平台 Dialog。
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val palette = LocalIosGroupedPalette.current
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(IosGrouped.cardRadius))
                .background(palette.card)
                .padding(vertical = 6.dp),
        ) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = palette.labelPrimary,
            )
            IosDivider(startInset = 20.dp)
            val current = ThemeSettings.uiEngine.displayName
            options.forEach { (label, action) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { action(); onDismiss() }
                        .defaultMinSize(minHeight = IosGrouped.rowMinHeight)
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        color = if (label == current) palette.accent else palette.labelPrimary,
                    )
                    if (label == current) {
                        Text("✓", fontSize = 16.sp, color = palette.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixSearch(value: String, changed: (String) -> Unit, search: () -> Unit) {
    TextField(value = value, onValueChange = changed, label = stringResource(R.string.features_search_hint), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { Text("⌕", fontSize = 23.sp, modifier = Modifier.clickable { search() }) })
}

@Composable
private fun MiuixCard(content: @Composable ColumnScope.() -> Unit) {
    // iOS inset grouped 卡片：12dp 圆角、无阴影，行分隔线由内容自己画。
    IosCard { content() }
}

@Composable
private fun MiuixToggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    IosSwitchRow(title = title, checked = checked, onCheckedChange = change)
}

@Composable
private fun MiuixAction(title: String, summary: String, click: () -> Unit) {
    IosRow(title = title, summary = summary, onClick = click) { IosChevron() }
}
