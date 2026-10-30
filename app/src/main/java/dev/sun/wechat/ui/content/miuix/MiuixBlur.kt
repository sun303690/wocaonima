package dev.sun.wechat.ui.content.miuix

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import dev.sun.wechat.ui.content.rememberMaterial3BlurBackdrop as rememberM3BlurBackdrop
import dev.sun.wechat.ui.content.m3AppBarBlur as m3AppBarBlurModifier
import dev.sun.wechat.ui.content.m3AppBarColor as m3AppBarColorOf

@Composable
fun rememberMiuixBlurBackdrop(enabled: Boolean = true): LayerBackdrop? {
    if (material3Engine()) return rememberM3BlurBackdrop(enabled)
    if (!enabled || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surfaceContainer
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun LayerBackdrop?.miuixAppBarColor(): Color {
    if (material3Engine()) return this.m3AppBarColorOf()
    return if (this != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
}

@Composable
fun Modifier.miuixAppBarBlur(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    blendAlpha: Float = 0.8f,
    shape: Shape = RectangleShape,
): Modifier {
    if (material3Engine()) {
        return this.m3AppBarBlurModifier(backdrop, enabled = enabled, blurRadius = blurRadius, blendAlpha = blendAlpha, shape = shape)
    }
    if (!enabled || backdrop == null) return this
    val blendColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = blendAlpha)
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(blendColors = listOf(BlendColorEntry(color = blendColor))),
        ),
    )
}

/** Records the modified content into [backdrop] so an app-bar blur layer can sample it. */
fun Modifier.miuixBackdropLayer(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) layerBackdrop(backdrop) else this
