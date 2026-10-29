package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared Miuix host and primitives for every WeKit-owned configuration page. */
@Composable
fun MiuixSettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 82.dp, bottom = 24.dp),
            content = content,
        )
        Surface(Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.background.copy(alpha = .95f)) {
            Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 38.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.onBackground)
                Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                actions?.invoke()
            }
        }
    }
}

@Composable
fun MiuixSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(Modifier.fillMaxWidth().clip(shape), shape = shape, color = MiuixTheme.colorScheme.surfaceContainer) {
        Column(content = content)
    }
}

@Composable
fun MiuixSettingsAction(title: String, summary: String = "", onClick: () -> Unit) {
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
    BasicComponent(
        title = title,
        summary = summary.ifEmpty { null },
        endActions = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) },
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
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
}
