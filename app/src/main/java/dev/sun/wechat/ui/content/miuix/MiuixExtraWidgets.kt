package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixBaseItemContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
fun MiuixTextFieldDialogWidget(
    title: String, value: String, onValueChange: (String) -> Unit, dialogTitle: String,
    confirmLabel: String, dismissLabel: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it }, password: Boolean = false,
    valueHint: String? = null, singleLine: Boolean = true,
) {
    var show by remember { mutableStateOf(false) }; var draft by remember(value) { mutableStateOf(value) }
    MiuixBaseWidget(modifier = modifier, title = title, summary = if (value.isBlank()) valueHint else if (password) "••••••••" else value, enabled = enabled, onClick = { draft = value; show = true })
    if (show) MiuixAlertDialog(onDismissRequest = { show = false }, title = { Text(dialogTitle) }, text = { TextField(value = draft, onValueChange = { draft = filter(it) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) }, confirmButton = { top.yukonga.miuix.kmp.basic.Button(onClick = { show = false; onValueChange(draft) }) { Text(confirmLabel) } }, dismissButton = { top.yukonga.miuix.kmp.basic.Button(onClick = { show = false }) { Text(dismissLabel) } })
}

@Composable
fun MiuixPlaceholderChips(placeholders: List<String>, value: TextFieldValue, isFieldFocused: Boolean, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        placeholders.forEach { placeholder ->
            Text(placeholder, modifier = Modifier.background(MiuixTheme.colorScheme.secondaryContainer).clickable {
                val selection = if (isFieldFocused) value.selection else TextRange(value.text.length); val start = minOf(selection.start, selection.end); val end = maxOf(selection.start, selection.end)
                onValueChange(TextFieldValue(value.text.replaceRange(start, end, placeholder), TextRange(start + placeholder.length)))
            }.padding(horizontal = 8.dp, vertical = 4.dp), style = MiuixTheme.textStyles.footnote1)
        }
    }
}
