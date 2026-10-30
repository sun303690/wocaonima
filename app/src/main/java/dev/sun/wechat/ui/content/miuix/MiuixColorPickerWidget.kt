package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 颜色选择行：卡片上只显示色块，点开后在弹窗里用调色板调整。
 * 早期实现把 [ColorPicker] 直接塞进行内 foreContent，会把标题挤成一列竖排、
 * 调色板也溢出卡片，故改为弹窗。
 */
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
    if (material3Engine()) {
        dev.sun.wechat.ui.content.m3.ColorPickerWidget(
            title = title,
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            icon = icon,
            iconPlaceholder = iconPlaceholder,
            enabled = enabled,
        )
        return
    }
    val parsed = remember(value) { runCatching { Color(value.toColorInt()) }.getOrDefault(Color.Black) }
    var showPicker by remember { mutableStateOf(false) }

    MiuixBaseWidget(
        title = title,
        summary = value,
        modifier = modifier,
        icon = icon,
        iconPlaceholder = iconPlaceholder,
        enabled = enabled,
        onClick = if (enabled) ({ showPicker = true }) else null,
        trailingContent = {
            Box(Modifier.size(28.dp).clip(CircleShape).background(parsed).border(1.dp, Color.Gray, CircleShape))
        },
    )

    if (showPicker) {
        MiuixAlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(title) },
            text = {
                Column(Modifier.padding(top = 4.dp)) {
                    Text(value)
                    ColorPicker(
                        color = parsed,
                        onColorChanged = { onValueChange(formatArgbHex(it.toArgb())) },
                        showPreview = false,
                    )
                }
            },
            confirmButton = {
                TextButton(text = "完成", onClick = { showPicker = false })
            },
        )
    }
}
