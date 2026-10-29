package dev.sun.wechat.activity.settings

import android.content.Intent
import androidx.activity.ComponentActivity
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
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.utils.theme.PageTransitionAnimation
import dev.sun.wechat.features.core.BaseFeature
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.core.FeaturesProvider
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import dev.sun.wechat.utils.WeLogger
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class MiuixPage { HOME, FEATURES, CATEGORY, APPEARANCE, BACKUP, LOGS, ABOUT, LICENSE }

@Composable
fun MiuixSettingsContent(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(MiuixPage.HOME) }
    var query by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(FEATURE_CATEGORIES.first().id) }
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
    MiuixPageFrame("WeKit · Miuix", onFinish) {
        item {
            MiuixSearch(query, { query = it }, { search(query) })
            Spacer(Modifier.height(12.dp))
            MiuixCard {
                MiuixAction("全部功能", "浏览分类、搜索与配置所有功能", openFeatures)
                HorizontalDivider()
                MiuixAction("外观设置", "主题模式、强调色、圆角与动效", openAppearance)
                HorizontalDivider()
                MiuixAction("主题工坊", "完整主题、壁纸、气泡与诊断") {
                    (context as? ComponentActivity)?.let(dev.sun.wechat.features.items.beautify.ThemeStore::onClick)
                }
                HorizontalDivider()
                MiuixAction("备份与还原", "导出、导入或清空模块配置", openBackup)
                HorizontalDivider()
                MiuixAction("日志中心", "模块日志、崩溃日志与主题诊断", openLogs)
                HorizontalDivider()
                MiuixAction("自检与诊断", "检查 Hook、权限与运行环境") { context.startActivity(Intent(context, SelfCheckSettingsActivity::class.java)) }
                HorizontalDivider()
                MiuixAction("关于与许可", "版本、开源协议与项目链接", openAbout)
            }
            Spacer(Modifier.height(12.dp))
            Text("功能分类", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            MiuixCard {
                FEATURE_CATEGORIES.forEachIndexed { index, category ->
                    val enabledCount = FeaturesProvider.ALL_FEATURES.count {
                        category.id in it.categoryIds && (it as? SwitchFeature)?.isEnabled == true
                    }
                    MiuixAction(stringResource(category.titleRes), "${enabledCount} 项已启用") { openCategory(category.id) }
                    if (index != FEATURE_CATEGORIES.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MiuixAppearancePage(back: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    MiuixPageFrame("外观设置", back) {
        item {
            MiuixCard {
                MiuixAction("主题模式", ThemeSettings.themeMode.displayName) {
                    val entries = AppThemeMode.entries
                    ThemeSettings.updateThemeMode(entries[(entries.indexOf(ThemeSettings.themeMode) + 1) % entries.size]); tick++
                }
                HorizontalDivider()
                MiuixAction("页面切换动效", ThemeSettings.pageTransitionAnimation.name) {
                    val entries = PageTransitionAnimation.entries
                    ThemeSettings.updatePageTransitionAnimation(entries[(entries.indexOf(ThemeSettings.pageTransitionAnimation) + 1) % entries.size]); tick++
                }
                HorizontalDivider()
                MiuixDropDownMenuWidget(
                    title = "UI 组件引擎",
                    description = null,
                    value = ThemeSettings.uiEngine,
                    options = SettingsUiEngine.entries.map {
                        DropdownOption(it, it.displayName)
                    },
                    onValueChange = {
                        ThemeSettings.updateUiEngine(it)
                        tick++
                    },
                )
                HorizontalDivider()
                MiuixToggle("设置页背景图", ThemeSettings.settingsWallpaper) {
                    ThemeSettings.updateSettingsWallpaper(it); tick++
                }
                HorizontalDivider()
                MiuixToggle("使用动态壁纸颜色", ThemeSettings.dynamicWallpaper) {
                    ThemeSettings.updateDynamicWallpaper(it); tick++
                }
                HorizontalDivider()
                MiuixToggle("同时应用到微信", ThemeSettings.applyToWechat) {
                    ThemeSettings.updateApplyToWechat(it); tick++
                }
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
    MiuixPageFrame("日志中心", back) {
        item {
            MiuixCard {
                MiuixAction("主题诊断", "复制当前主题渲染与页面判定状态") {
                    val text = dev.sun.wechat.features.items.beautify.ThemeStore.diagnose()
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("WeKit Theme Diagnostics", text))
                }
                HorizontalDivider()
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
    MiuixPageFrame("关于", back) {
        item {
            MiuixCard {
                MiuixAction("WeKit", "版本 ${dev.sun.wechat.BuildConfig.VERSION_NAME}") { }
                HorizontalDivider()
                MiuixAction("开源许可", "查看第三方组件与许可证", openLicense)
                HorizontalDivider()
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
    MiuixPageFrame("开源许可", back) {
        item { MiuixCard { licenses.forEachIndexed { i, text -> if (i > 0) HorizontalDivider(); MiuixAction(text, "详见项目内许可证与上游仓库") { } } } }
    }
}

@Composable
private fun MiuixBackupPage(back: () -> Unit) {
    MiuixPageFrame("备份与还原", back) {
        item {
            val context = LocalContext.current
            MiuixCard {
                MiuixAction("导出配置", "保存完整 WeKit 配置备份") {
                    SettingsConfigActions.export(context) { context }
                }
                HorizontalDivider()
                MiuixAction("导入配置", "从备份文件恢复并重启微信") {
                    SettingsConfigActions.importFromDocument(context) { context }
                }
                HorizontalDivider()
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
    MiuixPageFrame("功能", back) {
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
                        if (index != FEATURE_CATEGORIES.lastIndex) HorizontalDivider()
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
    MiuixPageFrame(title, back) {
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
    val context = LocalContext.current
    MiuixCard {
        Row(Modifier.fillMaxWidth().clickable {
            if (feature is ClickableFeature) (context as? ComponentActivity)?.let(feature::onClick)
        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(feature.localizedName(context), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                Text(feature.localizedDescription(context), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (feature is SwitchFeature) Switch(feature.isEnabled, onCheckedChange = { feature.applyToggle(it); changed() })
            else Text("›", fontSize = 25.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MiuixSearch(value: String, changed: (String) -> Unit, search: () -> Unit) {
    TextField(value = value, onValueChange = changed, label = stringResource(R.string.features_search_hint), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { Text("⌕", fontSize = 23.sp, modifier = Modifier.clickable { search() }) })
}

@Composable
private fun MiuixCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

@Composable
private fun MiuixToggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    BasicComponent(
        title = title,
        endActions = { Switch(checked = checked, onCheckedChange = change) },
        onClick = { change(!checked) },
    )
}

@Composable
private fun MiuixAction(title: String, summary: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface); Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        Text("›", fontSize = 25.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun MiuixPageFrame(title: String, back: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val showWallpaper = ThemeSettings.settingsWallpaper
    val wallpaper = remember(showWallpaper) {
        if (showWallpaper) dev.sun.wechat.features.items.beautify.ThemePack.active()?.wallpaper() else null
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        if (wallpaper != null) {
            coil3.compose.AsyncImage(
                model = wallpaper,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                imageLoader = dev.sun.wechat.ui.content.GlobalImageLoader,
                modifier = Modifier.fillMaxSize(),
            )
            // 压一层底色保证卡片/文字对比度
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background.copy(alpha = 0.9f)),
            )
        }
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 64.dp + 12.dp, bottom = 24.dp),
            content = content,
        )
        Surface(Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.background.copy(alpha = .95f)) {
            Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 38.sp, modifier = Modifier.clickable(onClick = back).padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.onBackground)
                Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
            }
        }
    }
}
