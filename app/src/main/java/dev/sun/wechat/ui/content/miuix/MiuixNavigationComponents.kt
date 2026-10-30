package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sun.wechat.ui.content.miuix.DropdownOption
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Check
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.ExpressiveBackButton(onClick = onClick, modifier = modifier)
        return
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription)
    }
}

@Composable
fun <T> MiuixOptionDropdown(
    expanded: Boolean,
    value: T,
    options: List<DropdownOption<T>>,
    onDismissRequest: () -> Unit,
    onValueChange: (T) -> Unit,
) {
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.ExpressiveOptionDropdown(
            expanded = expanded,
            value = value,
            options = options.map { dev.sun.wechat.ui.content.m3.DropdownOption(it.value, it.label) },
            onDismissRequest = onDismissRequest,
            onValueChange = onValueChange,
        )
        return
    }
    MiuixDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        options.forEach { option ->
            MiuixDropdownMenuItem(
                text = { Text(option.label) },
                onClick = { onValueChange(option.value) },
                trailingIcon = if (option.value == value) ({
                    Icon(MaterialSymbols.Outlined.Check, null)
                }) else null,
            )
        }
    }
}

@Composable
fun MiuixActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    if (material3Engine()) {
        androidx.compose.material3.Button(onClick = onClick, modifier = modifier) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, contentDescription = null)
                Spacer(Modifier.width(6.dp))
            }
            androidx.compose.material3.Text(text)
        }
        return
    }
    Button(onClick = onClick, modifier = modifier) {
        Row {
            if (icon != null) {
                Icon(icon, null)
                Spacer(Modifier.width(6.dp))
            }
            Text(text)
        }
    }
}
