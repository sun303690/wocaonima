package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.sun.wechat.ui.content.formatArgbHex
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun MiuixColorPickerWidget(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPlaceholder: Boolean = false,
    enabled: Boolean = true,
) {
    val parsed = remember(value) { runCatching { Color(value.toColorInt()) }.getOrDefault(Color.Black) }
    MiuixBaseWidget(
        title = title,
        summary = value,
        modifier = modifier,
        icon = icon,
        iconPlaceholder = iconPlaceholder,
        enabled = enabled,
        trailingContent = {
            Box(Modifier.size(28.dp).clip(CircleShape).background(parsed).border(1.dp, Color.Gray, CircleShape))
        },
        foreContent = {
            Column(Modifier.padding(top = 8.dp)) {
                Text(value)
                ColorPicker(color = parsed, onColorChanged = { onValueChange(formatArgbHex(it.toArgb())) }, showPreview = false)
            }
        },
    )
}
