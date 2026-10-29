package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text

data class DropdownOption<T>(val value: T, val label: String)

@Composable
fun MiuixBaseSupportingWidget(title: String, modifier: Modifier = Modifier, description: String? = null, iconPlaceholder: Boolean = false, onClick: (() -> Unit)? = null, enabled: Boolean = true, supportingContent: @Composable () -> Unit) {
    MiuixBaseWidget(title = title, summary = description, modifier = modifier, iconPlaceholder = iconPlaceholder, onClick = onClick, enabled = enabled)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { supportingContent() }
}

@Composable
fun MiuixRadioButtonWidget(title: String, modifier: Modifier = Modifier, description: String? = null, icon: ImageVector? = null, iconPlaceholder: Boolean = false, selected: Boolean = false, enabled: Boolean = true, trailingDivider: Boolean = false, onClick: (() -> Unit)? = null, onSelect: (() -> Unit)? = null) {
    MiuixBaseWidget(title = title, summary = description, modifier = modifier, icon = icon, iconPlaceholder = iconPlaceholder, selected = selected, enabled = enabled, onClick = onClick ?: onSelect, trailingContent = { RadioButton(selected = selected, onClick = onSelect ?: onClick, enabled = enabled) })
}

@Composable
fun MiuixIntNumberPickerWidget(icon: ImageVector? = null, iconPlaceholder: Boolean = false, title: String, description: String? = null, enabled: Boolean = true, value: Int, startInt: Int, endInt: Int, stepSize: Int = 0, showTooltip: Boolean = true, valueSuffix: String = "", subduedValue: Boolean = false, onValueClick: (() -> Unit)? = null, onValueChange: (Int) -> Unit) {
    MiuixBaseSupportingWidget(title, description = description, iconPlaceholder = iconPlaceholder, enabled = enabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(value = value.toFloat(), onValueChange = { onValueChange(it.toInt().coerceIn(startInt, endInt)) }, valueRange = startInt.toFloat()..endInt.toFloat(), modifier = Modifier.weight(1f), enabled = enabled)
            Text("$value$valueSuffix", modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun <T> MiuixDropDownMenuWidget(icon: ImageVector? = null, iconPlaceholder: Boolean = false, title: String, description: String?, value: T, options: List<DropdownOption<T>>, enabled: Boolean = true, onValueChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == value }
    MiuixBaseWidget(title = title, summary = description ?: selected?.label, icon = icon, iconPlaceholder = iconPlaceholder, enabled = enabled, onClick = { expanded = true })
    MiuixDropdownMenu(expanded, { expanded = false }) {
        options.forEach { option -> MiuixDropdownMenuItem(text = { Text(option.label) }, onClick = { onValueChange(option.value); expanded = false }) }
    }
}
