package dev.sun.wechat.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.features.core.*
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Independent Miuix settings shell. It reuses WeKit's feature registry and actions. */
@Composable
fun MiuixSettingsContent(onFinish: () -> Unit) {
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    BackHandler { if (category != null || query.isNotEmpty()) { category = null; query = "" } else onFinish() }
    val context = LocalWeKitLocalizedContext.current
    val all = remember { FeaturesProvider.ALL_FEATURES.distinctBy { it.technicalId } }
    val visible = remember(category, query, FeatureCategoryState.revision) {
        when {
            query.isNotBlank() -> all.filter { it.localizedName(context).contains(query, true) || it.localizedDescription(context).contains(query, true) }
            category == NEW_FEATURES_CATEGORY -> FeatureCategoryState.newItems
            category == ENABLED_FEATURES_CATEGORY -> FeatureCategoryState.enabledItems()
            category != null -> all.filter { category in it.categoryIds }
            else -> emptyList()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (category == null) "WeKit · Miuix" else "‹ ${context.getString(featureCategoryTitleRes(category!!))}",
                modifier = Modifier.weight(1f).clickable(enabled = category != null) { category = null },
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { androidx.compose.material3.Text(context.getString(R.string.features_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (category == null && query.isBlank()) {
                item { MiuixCategoryRow(context.getString(featureCategoryTitleRes(NEW_FEATURES_CATEGORY))) { category = NEW_FEATURES_CATEGORY } }
                item { MiuixCategoryRow(context.getString(featureCategoryTitleRes(ENABLED_FEATURES_CATEGORY))) { category = ENABLED_FEATURES_CATEGORY } }
                items(FEATURE_CATEGORIES, key = { it.id }) { item ->
                    MiuixCategoryRow(context.getString(item.titleRes)) { category = item.id }
                }
            } else if (visible.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Text(context.getString(R.string.features_no_results)) } }
            } else {
                items(visible, key = { it.technicalId }) { MiuixFeatureRow(it) }
            }
        }
    }
}

@Composable
private fun MiuixCategoryRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp)
        Text("›", color = MiuixTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiuixFeatureRow(feature: BaseFeature) {
    val activity = LocalComponentActivity.current
    val context = LocalWeKitLocalizedContext.current
    var checked by remember(feature.technicalId, FeatureCategoryState.revision) {
        mutableStateOf(KvStore.getBoolOrDef(feature.technicalId, (feature as? SwitchFeature)?.defaultEnabled == true))
    }
    val open = { if (feature is ClickableFeature) runCatching { feature.onClick(activity) }; Unit }
    Row(Modifier.fillMaxWidth().clickable(enabled = feature is ClickableFeature, onClick = open).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(feature.localizedName(context), fontSize = 16.sp)
            val desc = feature.localizedDescription(context)
            if (desc.isNotBlank()) Text(desc, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariant)
        }
        when {
            feature is ClickableFeature && feature.noSwitchWidget -> Text("›")
            feature is SwitchFeature -> Switch(checked = checked, onCheckedChange = { value ->
                if (feature.onBeforeToggle(value, activity)) {
                    KvStore.putBool(feature.technicalId, value)
                    feature.isEnabled = value
                    checked = value
                    FeatureCategoryState.notifyToggleChanged()
                }
            })
        }
    }
    feature.Ui()
}
