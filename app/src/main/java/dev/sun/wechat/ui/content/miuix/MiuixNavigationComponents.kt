package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sun.wechat.ui.content.miuix.DropdownOption
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
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, contentDescription)
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
    MiuixDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        options.forEach { option ->
            MiuixDropdownMenuItem(
                text = { Text(option.label) },
                onClick = { onValueChange(option.value) },
                trailingIcon = if (option.value == value) ({
                    Icon(androidx.compose.material.icons.Icons.Outlined.Check, null)
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
