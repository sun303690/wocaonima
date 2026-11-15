package dev.sun.wechat.ui.content.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import top.yukonga.miuix.kmp.basic.TextField

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
                var hexInput by remember(value) { mutableStateOf(value) }
                Column(Modifier.padding(top = 4.dp)) {
                    ColorPicker(
                        color = parsed,
                        onColorChanged = {
                            val hex = formatArgbHex(it.toArgb())
                            hexInput = hex
                            onValueChange(hex)
                        },
                        showPreview = false,
                    )
                    // 直接填色值：支持 #RRGGBB / #AARRGGBB，6 位时自动补不透明
                    TextField(
                        value = hexInput,
                        onValueChange = { input ->
                            hexInput = input
                            normalizeArgbHex(input)?.let(onValueChange)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        label = "#AARRGGBB",
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(text = "完成", onClick = { showPicker = false })
            },
        )
    }
}

/** 把用户输入规整成 `#AARRGGBB`；不合法返回 null。 */
private fun normalizeArgbHex(raw: String): String? {
    val body = raw.trim().removePrefix("#").uppercase()
    if (body.length != 6 && body.length != 8) return null
    if (!body.all { it in "0123456789ABCDEF" }) return null
    return if (body.length == 6) "#FF$body" else "#$body"
}
