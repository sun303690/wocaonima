package dev.sun.wechat.activity.settings

import android.content.Intent
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
import dev.sun.wechat.features.core.BaseFeature
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class MiuixPage { HOME, FEATURES }

@Composable
fun MiuixSettingsContent(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(MiuixPage.HOME) }
    var query by remember { mutableStateOf("") }
    when (page) {
        MiuixPage.HOME -> MiuixHome(onFinish, { page = MiuixPage.FEATURES }, { query = it; page = MiuixPage.FEATURES })
        MiuixPage.FEATURES -> MiuixFeatures(query, { query = it }, { page = MiuixPage.HOME })
    }
}

@Composable
private fun MiuixHome(onFinish: () -> Unit, openFeatures: () -> Unit, search: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    MiuixPageFrame("WeKit · Miuix", onFinish) {
        item {
            MiuixSearch(query, { query = it }, { search(query) })
            Spacer(Modifier.height(12.dp))
            MiuixCard {
                MiuixAction("全部功能", "浏览分类、搜索与配置所有功能", openFeatures)
                HorizontalDivider()
                MiuixAction("主题工坊", "完整主题、壁纸、气泡与诊断") { dev.sun.wechat.features.items.beautify.ThemeStore.onClick(context) }
                HorizontalDivider()
                MiuixAction("自检与诊断", "检查 Hook、权限与运行环境") { context.startActivity(Intent(context, SelfCheckSettingsActivity::class.java)) }
            }
            Spacer(Modifier.height(12.dp))
            Text("功能分类", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            MiuixCard {
                FEATURE_CATEGORIES.forEachIndexed { index, category ->
                    MiuixAction(stringResource(category.nameRes), "${category.enabledItems().size} 项已启用") { openFeatures() }
                    if (index != FEATURE_CATEGORIES.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MiuixFeatures(query: String, update: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val features = remember(query, tick) { FEATURE_CATEGORIES.flatMap { it.items }.distinct().filter { query.isBlank() || it.localizedName(context).contains(query, true) || it.localizedDescription(context).contains(query, true) } }
    MiuixPageFrame("功能", back) {
        item { MiuixSearch(query, update, {}); Spacer(Modifier.height(12.dp)) }
        if (features.isEmpty()) item { MiuixCard { Text(stringResource(R.string.features_no_results), modifier = Modifier.padding(18.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
        items(features, key = { it.id }) { feature ->
            MiuixFeatureRow(feature) { tick++ }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MiuixFeatureRow(feature: BaseFeature, changed: () -> Unit) {
    val context = LocalContext.current
    MiuixCard {
        Row(Modifier.fillMaxWidth().clickable {
            if (feature is ClickableFeature) feature.onClick(context)
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
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(ThemeSettings.cornerRadiusDp.dp)), shape = RoundedCornerShape(ThemeSettings.cornerRadiusDp.dp), color = MiuixTheme.colorScheme.surfaceContainer) { Column(content = content) }
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
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 82.dp, bottom = 24.dp), content = content)
        Surface(Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.background.copy(alpha = .95f)) {
            Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 38.sp, modifier = Modifier.clickable(onClick = back).padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.onBackground)
                Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
            }
        }
    }
}
