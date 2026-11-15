package dev.sun.wechat.ui.utils

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.style.ReplacementSpan
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 炫彩文字（彩色渐变横向流动）。
 *
 * 配色三档：起始色 → 中间色 → 结束色。中间色给 null 时退化成两色往返
 * （起始 → 结束 → 起始），给了中间色则是起 → 中 → 止 → 中 → 起。
 *
 * 两套接入方式，按宿主控件的绘制方式选用：
 *
 * - [attach] / [detach]：把渐变着色器直接挂到 [TextView.paint] 上，适合文字全部是纯文本、
 *   不打 span 的场景（例如头像下方的时间）。
 * - [RainbowTextSpan]：给 [android.text.Spannable] 的某一段文字上色，适合与其它 span
 *   （角色徽章、实名标注）共存的场景。用 span 时需要配合 [watch] 让宿主 View 持续重绘。
 *
 * 相位由进程内单例 [ValueAnimator] 驱动，所有接入点共享同一个相位，因此同屏多行文字的
 * 渐变是同步流动的；没有接入点时动画自动停掉，不占 CPU。
 */
object RainbowText {

    /** 默认渐变起始色（与微信红包红接近）。 */
    const val DEFAULT_START = "#FFFF3B30"

    /** 默认渐变中间色（系统绿）。 */
    const val DEFAULT_MID = "#FF34C759"

    /** 默认渐变结束色（系统蓝）。 */
    const val DEFAULT_END = "#FF0A84FF"

    /** 渐变走完一个完整周期的时长（毫秒）。 */
    private const val PERIOD_MS = 2400L

    /** 相位推进步长（毫秒）。40ms ≈ 25fps，足够顺滑又不浪费性能。 */
    private const val TICK_MS = 40L

    private const val STEP = TICK_MS.toFloat() / PERIOD_MS

    /** 当前相位，取值 [0, 1)，由内部 ticker 推进，绘制侧只读。 */
    @Volatile
    var phase: Float = 0f
        private set

    private class Slot(
        var colors: IntArray,
        val matrix: Matrix,
    ) {
        var width: Float = -1f
        var shader: LinearGradient? = null
    }

    private val paintSlots = WeakHashMap<TextView, Slot>()
    private val spanTargets: MutableSet<TextView> =
        Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())

    private var ticker: ValueAnimator? = null

    /** 解析 `#RRGGBB` / `#AARRGGBB`，失败时退回 [fallback]。 */
    fun parseColor(value: String, fallback: String = DEFAULT_START): Int =
        runCatching { value.toColorInt() }
            .getOrElse { runCatching { fallback.toColorInt() }.getOrDefault(DEFAULT_START.toColorInt()) }

    /** 三档配色 → 首尾闭合的色标数组。中间色为空时退化成两色往返。 */
    private fun stopsOf(startColor: Int, midColor: Int?, endColor: Int): IntArray =
        if (midColor == null || midColor == startColor || midColor == endColor) {
            intArrayOf(startColor, endColor, startColor)
        } else {
            intArrayOf(startColor, midColor, endColor, midColor, startColor)
        }

    private fun positionsOf(colors: IntArray): FloatArray =
        if (colors.size == 5) {
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        } else {
            floatArrayOf(0f, 0.5f, 1f)
        }

    /** 给整个 [textView] 的文字套上流动渐变。重复调用只会更新配色。 */
    fun attach(textView: TextView, startColor: Int, midColor: Int?, endColor: Int) {
        val colors = stopsOf(startColor, midColor, endColor)
        val slot = paintSlots[textView] ?: Slot(colors, Matrix()).also {
            paintSlots[textView] = it
        }
        slot.colors = colors
        slot.width = -1f
        ensureTicker()
        applySlot(textView, slot)
    }

    /** 取消 [attach]，并把文字颜色还给 [TextView] 自己的 textColor。 */
    fun detach(textView: TextView) {
        paintSlots.remove(textView)
        textView.paint.shader = null
        textView.invalidate()
    }

    /** 登记一个使用 [RainbowTextSpan] 的 View，让它在相位推进时持续重绘。 */
    fun watch(textView: TextView) {
        spanTargets.add(textView)
        ensureTicker()
    }

    /** 取消 [watch] 登记。View 被回收后会自动从弱引用集合里消失，通常不需要手动调用。 */
    fun unwatch(textView: TextView) {
        spanTargets.remove(textView)
    }

    /**
     * 构造一个宽度为 [width] 的重复渐变着色器，并按当前相位横向平移。
     *
     * 色标数组首尾同色 + [Shader.TileMode.REPEAT] 让平铺接缝处颜色一致，
     * 相位平移一个周期后画面完全重合，所以循环无跳变。
     */
    fun shaderFor(
        width: Float,
        startColor: Int,
        midColor: Int?,
        endColor: Int,
        matrix: Matrix = Matrix(),
    ): LinearGradient {
        val w = if (width > 1f) width else 1f
        val colors = stopsOf(startColor, midColor, endColor)
        val shader = LinearGradient(
            0f, 0f, w, 0f,
            colors,
            positionsOf(colors),
            Shader.TileMode.REPEAT,
        )
        matrix.setTranslate(phase * w, 0f)
        shader.setLocalMatrix(matrix)
        return shader
    }

    private fun applySlot(textView: TextView, slot: Slot) {
        val text = textView.text?.toString().orEmpty()
        val width = textView.paint.measureText(text).let { if (it > 1f) it else 1f }

        if (slot.shader == null || slot.width != width) {
            slot.shader = LinearGradient(
                0f, 0f, width, 0f,
                slot.colors,
                positionsOf(slot.colors),
                Shader.TileMode.REPEAT,
            )
            slot.width = width
        }

        val shader = slot.shader ?: return
        slot.matrix.setTranslate(phase * width, 0f)
        shader.setLocalMatrix(slot.matrix)
        textView.paint.shader = shader
        textView.invalidate()
    }

    private fun ensureTicker() {
        if (ticker != null) return
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }

    private fun tick() {
        phase += STEP
        if (phase >= 1f) phase -= 1f

        if (paintSlots.isEmpty() && spanTargets.isEmpty()) {
            ticker?.cancel()
            ticker = null
            return
        }

        for ((view, slot) in paintSlots) {
            applySlot(view, slot)
        }
        for (view in spanTargets) {
            view.invalidate()
        }
    }
}

/**
 * 给 [android.text.Spannable] 的一段文字上流动渐变色的 [ReplacementSpan]。
 *
 * [midColor] 为空时是两色往返，非空时是起 → 中 → 止 → 中 → 起。
 * 只接管绘制，不改字号与行高（[getSize] 里把字体度量原样拷给 [Paint.FontMetricsInt]）。
 * 宿主 View 需要先 [RainbowText.watch]，否则相位推进时不会重绘。
 */
class RainbowTextSpan(
    private val startColor: Int,
    private val midColor: Int?,
    private val endColor: Int,
) : ReplacementSpan() {

    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = -1f

    private val colors: IntArray =
        if (midColor == null || midColor == startColor || midColor == endColor) {
            intArrayOf(startColor, endColor, startColor)
        } else {
            intArrayOf(startColor, midColor, endColor, midColor, startColor)
        }

    private val positions: FloatArray =
        if (colors.size == 5) {
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        } else {
            floatArrayOf(0f, 0.5f, 1f)
        }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return paint.measureText(text, start, end).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = paint.measureText(text, start, end).let { if (it > 1f) it else 1f }

        val cached = shader
        if (cached == null || shaderWidth != width) {
            shader = LinearGradient(
                0f, 0f, width, 0f,
                colors,
                positions,
                Shader.TileMode.REPEAT,
            ).also { shaderWidth = width }
        }

        val active = shader ?: return
        matrix.setTranslate(RainbowText.phase * width, 0f)
        active.setLocalMatrix(matrix)

        val previousShader = paint.shader
        paint.shader = active
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        paint.shader = previousShader
    }
}
