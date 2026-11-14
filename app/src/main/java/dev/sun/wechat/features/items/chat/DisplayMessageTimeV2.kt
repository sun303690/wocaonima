package dev.sun.wechat.features.items.chat

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixColorPickerWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HookParam
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/**
 * 气泡内显示时间 v2：在每条消息气泡的右下角叠加绘制发送时间。
 *
 * 实现要点：
 * 1. 不往气泡布局里插 View（LinearLayout 会被挤占），改用 [View.getOverlay] 挂一个自定义
 *    [Drawable]，只在绘制层出现，完全不影响微信原有布局与测量。
 * 2. 位置在 draw 时用 [View.getLocationOnScreen] 由气泡内容节点的相对坐标算出右下角。
 * 3. 炫彩用 [LinearGradient] 三色渐变 + [Matrix] 横向平移做流动相位，一个共享
 *    [ValueAnimator] 驱动全部行，避免每行一个动画。
 * 4. 时间格式自解析：模式字母之外的字符（中文、emoji、空格）自动转成字面量，避免
 *    [SimpleDateFormat] 把中文当成模式字母而抛异常或输出乱码。
 */
object DisplayMessageTimeV2 : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "显示消息时间v2"
    override val nameRes = R.string.feature_display_message_time_v2_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_message_time_v2_description

    private var timePattern by prefOption("msg_time_v2_pattern", "HH:mm")
    private var textSizeSp by prefOption("msg_time_v2_text_size", 10)
    private var rainbowEnabled by prefOption("msg_time_v2_rainbow", true)
    private var colorStart by prefOption("msg_time_v2_color_start", "#FFFF3B30")
    private var colorEnd by prefOption("msg_time_v2_color_end", "#FF0A84FF")

    private const val MIN_SIZE_SP = 6
    private const val MAX_SIZE_SP = 24
    private const val PADDING_DP = 3
    private const val PHASE_DURATION_MS = 2400L

    /** 模式字母白名单，其余字符按字面量处理。 */
    private const val PATTERN_LETTERS = "HhmsSdaEyMKzZXGL"

    /** 每行一个覆盖层，key 为消息行根 View。 */
    private val overlays = WeakHashMap<View, TimeOverlayDrawable>()

    private var animator: ValueAnimator? = null

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        animator?.cancel()
        animator = null
        synchronized(overlays) {
            overlays.entries.toList().forEach { (row, drawable) -> row.overlay?.remove(drawable) }
            overlays.clear()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        try {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            if (msgInfo.type?.isSystem == true) return
            val createTime = msgInfo.createTime
            if (createTime <= 0L) return

            val text = formatTime(createTime, timePattern)
            if (text.isEmpty()) return

            val overlay = ensureOverlay(view)
            overlay.text = text
            overlay.textPx = spToPx(view, textSizeSp.toFloat())
            overlay.padPx = dpToPx(view, PADDING_DP.toFloat())
            overlay.rainbow = rainbowEnabled
            overlay.startColor = runCatching { colorStart.toColorInt() }.getOrDefault(Color.RED)
            overlay.endColor = runCatching { colorEnd.toColorInt() }.getOrDefault(Color.BLUE)
            overlay.invalidateSelf()
            startPhaseAnimatorIfNeeded()
        } catch (error: Exception) {
            WeLogger.d("DisplayMessageTimeV2", "skip bubble time injection", error)
        }
    }

    private fun ensureOverlay(row: View): TimeOverlayDrawable {
        synchronized(overlays) {
            overlays[row]?.let { return it }
            val drawable = TimeOverlayDrawable(row)
            overlays[row] = drawable
            row.overlay?.add(drawable)
            return drawable
        }
    }

    /** 单个共享动画：只推进相位并重绘所有存活覆盖层。 */
    private fun startPhaseAnimatorIfNeeded() {
        if (animator?.isRunning == true) return
        val created = animator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PHASE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animation ->
                val phase = animation.animatedValue as Float
                synchronized(overlays) {
                    overlays.values.toList().forEach { drawable ->
                        drawable.phase = phase
                        drawable.invalidateSelf()
                    }
                }
            }
        }.also { animator = it }
        created.start()
    }

    /**
     * 把用户输入的时间模板转成合法 [SimpleDateFormat] 模板：模式字母原样保留，
     * 其他字符（中文、emoji、空格、标点）整体转成单引号字面量。
     */
    private fun buildJavaPattern(pattern: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isEmpty()) return
            out.append('\'')
            out.append(literal.toString().replace("'", "''"))
            out.append('\'')
            literal.clear()
        }
        for (ch in pattern) {
            if (PATTERN_LETTERS.indexOf(ch) >= 0) {
                flushLiteral()
                out.append(ch)
            } else {
                literal.append(ch)
            }
        }
        flushLiteral()
        return out.toString()
    }

    private fun formatTime(createTime: Long, pattern: String): String {
        val ms = if (createTime > 1000000000000L) createTime else createTime * 1000L
        val template = pattern.ifBlank { "HH:mm" }
        val javaPattern = runCatching { buildJavaPattern(template) }.getOrNull() ?: return ""
        if (javaPattern.isEmpty()) return ""
        return runCatching {
            SimpleDateFormat(javaPattern, Locale.getDefault()).format(Date(ms))
        }.getOrDefault("")
    }

    private fun spToPx(view: View, sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, view.resources.displayMetrics)

    private fun dpToPx(view: View, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, view.resources.displayMetrics)

    private fun isTaggedBubbleView(view: View): Boolean =
        view.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true

    /** 取自消息行里的气泡内容节点：优先 reflekt 拿主容器，失败则深度遍历找带 viewitems tag 的可见节点。 */
    private fun findMessageContent(row: View): View? {
        val holder = row.tag
        if (holder != null) {
            val mainContainer = runCatching {
                holder.reflekt()
                    .firstMethod {
                        name = "getMainContainerView"
                        parameterCount = 0
                        superclass()
                    }
                    .invoke() as? View
            }.getOrNull()
            if (mainContainer != null) return mainContainer
        }
        return depthFirstMatch(row)
    }

    private fun depthFirstMatch(view: View): View? {
        if (view.isShown && isTaggedBubbleView(view)) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                val hit = depthFirstMatch(view.getChildAt(index))
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 覆盖层：在气泡右下角绘制时间文本，可选流动渐变。 */
    private class TimeOverlayDrawable(private val row: View) : Drawable() {

        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
        }
        private val gradientMatrix = Matrix()

        var text: String = ""
        var rainbow: Boolean = false
        var startColor: Int = Color.RED
        var endColor: Int = Color.BLUE
        var textPx: Float = 30f
        var padPx: Float = 8f
        var phase: Float = 0f

        private var anchor: View? = null

        override fun draw(canvas: Canvas) {
            if (text.isEmpty()) return
            val target = resolveAnchor() ?: return
            paint.textSize = textPx
            val textWidth = paint.measureText(text)
            val rowLocation = IntArray(2)
            val anchorLocation = IntArray(2)
            row.getLocationOnScreen(rowLocation)
            target.getLocationOnScreen(anchorLocation)
            val left = (anchorLocation[0] - rowLocation[0]).toFloat()
            val top = (anchorLocation[1] - rowLocation[1]).toFloat()
            val x = left + target.width - textWidth - padPx
            val baseline = top + target.height - padPx - paint.descent()

            if (rainbow) {
                val shader = LinearGradient(
                    x, baseline, x + textWidth, baseline,
                    intArrayOf(startColor, endColor, startColor),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                gradientMatrix.reset()
                gradientMatrix.postTranslate(phase * textWidth, 0f)
                shader.setLocalMatrix(gradientMatrix)
                paint.shader = shader
            } else {
                paint.shader = null
                paint.color = startColor
            }
            canvas.drawText(text, x, baseline, paint)
        }

        private fun resolveAnchor(): View? {
            val cached = anchor
            if (cached != null && cached.isShown && cached.parent != null) return cached
            val found = DisplayMessageTimeV2.findMessageContent(row)
            anchor = found
            return found
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext = LocalWeKitLocalizedContext.current
            var patternInput by remember { mutableStateOf(timePattern) }
            var sizeInputRaw by remember { mutableStateOf(textSizeSp.toString()) }
            var rainbowInput by remember { mutableStateOf(rainbowEnabled) }
            var startInput by remember { mutableStateOf(colorStart) }
            var endInput by remember { mutableStateOf(colorEnd) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_display_message_time_v2_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                TextField(
                                    value = patternInput,
                                    onValueChange = { patternInput = it },
                                    label = stringResource(R.string.msg_time_v2_format_label),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                TextField(
                                    value = sizeInputRaw,
                                    onValueChange = { sizeInputRaw = it.filter { c -> c.isDigit() } },
                                    label = stringResource(R.string.msg_time_v2_size_label),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                MiuixSwitchWidget(
                                    title = stringResource(R.string.msg_time_v2_rainbow_label),
                                    summary = stringResource(R.string.msg_time_v2_rainbow_summary),
                                    checked = rainbowInput,
                                    onCheckedChange = { rainbowInput = it }
                                )
                            }
                            item {
                                MiuixColorPickerWidget(
                                    title = stringResource(R.string.msg_time_v2_color_start),
                                    value = startInput,
                                    onValueChange = { startInput = it }
                                )
                            }
                            item {
                                MiuixColorPickerWidget(
                                    title = stringResource(R.string.msg_time_v2_color_end),
                                    value = endInput,
                                    onValueChange = { endInput = it }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val parsedSize = sizeInputRaw.toIntOrNull()
                        if (parsedSize != null && (parsedSize < MIN_SIZE_SP || parsedSize > MAX_SIZE_SP)) {
                            showToast(localizedContext.getString(R.string.msg_time_v2_size_range))
                            return@Button
                        }
                        val template = patternInput.ifBlank { "HH:mm" }
                        if (buildJavaPattern(template).isEmpty()) {
                            showToast(localizedContext.getString(R.string.msg_time_v2_invalid_pattern))
                            return@Button
                        }
                        timePattern = template
                        textSizeSp = (parsedSize ?: textSizeSp).coerceIn(MIN_SIZE_SP, MAX_SIZE_SP)
                        rainbowEnabled = rainbowInput
                        colorStart = startInput
                        colorEnd = endInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }
}
