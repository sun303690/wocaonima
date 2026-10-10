package dev.sun.wechat.features.items.beautify

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.IntNumberPickerWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.android.getTopMostActivity
import dev.ujhhgtg.reflekt.reflekt
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

object BeautifyConversationList : ClickableFeature() {

    override val technicalId = "美化对话列表"
    override val nameRes = R.string.feature_beautify_conversation_list_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_conversation_list_description

    // ── 全局列表卡片化配置（lexin 风格）──
    private var globalCardsEnabled by prefOption("beautify_global_list_cards_enabled", false)
    private var globalCardsAlpha by prefOption("beautify_global_list_cards_alpha", 200)
    private var globalCardsRadius by prefOption("beautify_global_list_cards_radius", 16)
    private var globalCardsInset by prefOption("beautify_global_list_cards_inset", 10)
    private var globalCardsGap by prefOption("beautify_global_list_cards_gap", 8)
    private var globalCardsHeight by prefOption("beautify_global_list_cards_height", 0)
    private var globalCardsDark by prefOption("beautify_global_list_cards_dark", false)

    private val globalFillsStripped = WeakHashMap<View, Boolean>()
    private val globalOrigHeights = WeakHashMap<View, Int>()
    private val globalCardDrawables = WeakHashMap<View, GlobalCardDrawable>()
    private val globalChattingCache = WeakHashMap<View, Boolean>()
    private val globalListCache = WeakHashMap<View, Boolean>()

    private var globalHooksRegistered = false

    /** lexin 风格自绘卡片：颜色/圆角在创建时烘焙好，滚动时零测量 */
    private class GlobalCardDrawable(
        private val colour: Int,
        private val corner: Float,
        private val alpha: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        fun matches(cornerPx: Float, alphaValue: Int): Boolean =
            abs(this.corner - cornerPx) < 0.01f && this.alpha == alphaValue

        override fun draw(canvas: Canvas) {
            try {
                rect.set(bounds)
                clip.reset()
                clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                paint.color = colour
                paint.alpha = alpha
                canvas.drawRect(rect, paint)
                canvas.restore()
            } catch (ignored: Throwable) {
                // 仅外观
            }
        }

        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    override fun onEnable() {
        registerGlobalHooks()
        applyGlobalCardsNow()
    }

    override fun onDisable() {
        globalHooksRegistered = false
        restoreGlobalCards()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var enabled by remember { mutableStateOf(globalCardsEnabled) }
            var dark by remember { mutableStateOf(globalCardsDark) }
            var alpha by remember { mutableStateOf(globalCardsAlpha) }
            var radius by remember { mutableStateOf(globalCardsRadius) }
            var inset by remember { mutableStateOf(globalCardsInset) }
            var gap by remember { mutableStateOf(globalCardsGap) }
            var height by remember { mutableStateOf(globalCardsHeight) }

            fun applyAndRefresh() {
                if (enabled) applyGlobalCardsNow() else restoreGlobalCards()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_conversation_list_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_enabled),
                                description = stringResource(R.string.beautify_global_list_cards_summary),
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    globalCardsEnabled = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "dark", animatedVisibility = enabled) {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_dark),
                                checked = dark,
                                onCheckedChange = {
                                    dark = it
                                    globalCardsDark = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "alpha", animatedVisibility = enabled) {
                            IntNumberPickerWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_alpha),
                                value = alpha,
                                startInt = 0,
                                endInt = 255,
                                stepSize = 5,
                                valueSuffix = "/255",
                                onValueChange = {
                                    alpha = it
                                    globalCardsAlpha = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "radius", animatedVisibility = enabled) {
                            IntNumberPickerWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_radius),
                                value = radius,
                                startInt = 0,
                                endInt = 48,
                                stepSize = 1,
                                valueSuffix = "dp",
                                onValueChange = {
                                    radius = it
                                    globalCardsRadius = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "inset", animatedVisibility = enabled) {
                            IntNumberPickerWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_inset),
                                value = inset,
                                startInt = 0,
                                endInt = 60,
                                stepSize = 1,
                                valueSuffix = "dp",
                                onValueChange = {
                                    inset = it
                                    globalCardsInset = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "gap", animatedVisibility = enabled) {
                            IntNumberPickerWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_gap),
                                value = gap,
                                startInt = 0,
                                endInt = 60,
                                stepSize = 1,
                                valueSuffix = "dp",
                                onValueChange = {
                                    gap = it
                                    globalCardsGap = it
                                    applyAndRefresh()
                                },
                            )
                        }
                        item(key = "height", animatedVisibility = enabled) {
                            IntNumberPickerWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_list_cards_height),
                                value = height,
                                startInt = -24,
                                endInt = 48,
                                stepSize = 2,
                                valueSuffix = "dp",
                                onValueChange = {
                                    height = it
                                    globalCardsHeight = it
                                    applyAndRefresh()
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    /** 是否在打开的会话页里：气泡行要保持自身布局，绝不能做卡片 */
    private fun isInsideChatting(view: View): Boolean {
        globalChattingCache[view]?.let { return it }
        var result = false
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < 40) {
            val name = cur.javaClass.name
            if (name.contains("Chatting") || name.contains("chatting") ||
                name.contains("ChatUI") || name.contains("ChattingUI")
            ) {
                result = true
                break
            }
            cur = cur.parent as? View
            depth++
        }
        globalChattingCache[view] = result
        return result
    }

    /** 是否通用列表（RecyclerView / ListView），排除聊天页 */
    private fun isGenericList(view: View): Boolean {
        globalListCache[view]?.let { return it }
        val name = view.javaClass.name
        val result = !isInsideChatting(view) && (
            name.contains("RecyclerView") ||
                name.endsWith("ListView") ||
                name.contains("PullDownListView")
            )
        globalListCache[view] = result
        return result
    }

    /** 是否为分隔线：1px 全宽细线，落在卡片缝隙里 */
    private fun isSeparator(view: View): Boolean =
        view.height <= 3 && view.width > 0

    /** 递归扫描视图树，给所有通用列表应用卡片 */
    private fun styleGlobalLists(view: View?, depth: Int) {
        if (view == null || depth > 18 || !globalCardsEnabled) return
        try {
            if (isGenericList(view)) {
                applyGlobalCards(view as ViewGroup)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    styleGlobalLists(view.getChildAt(i), depth + 1)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 应用卡片到列表的所有可见行 */
    private fun applyGlobalCards(list: ViewGroup) {
        val density = list.resources.displayMetrics.density
        val inset = (globalCardsInset.coerceIn(0, 60) * density).roundToInt()
        val gap = (globalCardsGap.coerceIn(0, 60) * density).roundToInt()
        val corner = (globalCardsRadius.coerceIn(1, 48) * density).roundToInt().toFloat()
        val alpha = globalCardsAlpha.coerceIn(0, 255)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (isSeparator(child)) {
                child.background = null
                continue
            }
            styleGlobalRow(child, inset, gap, corner, alpha)
        }
    }

    /** 样式化一行；多行容器则递归处理内部行 */
    private fun styleGlobalRow(row: View, inset: Int, gap: Int, corner: Float, alpha: Int) {
        if (isGlobalRowContainer(row)) {
            row.background = null
            resetGlobalMargins(row)
            val group = row as ViewGroup
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j) ?: continue
                if (!child.isShown) continue
                if (child.height >= dp(child, 34) && child.width * 10 >= group.width * 6) {
                    styleGlobalRow(child, inset, gap, corner, alpha)
                } else {
                    stripGlobalRowFills(child, 0)
                }
            }
            return
        }
        try {
            val lp = row.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val mlp = lp
                if (mlp.leftMargin != inset || mlp.rightMargin != inset || mlp.bottomMargin != gap) {
                    mlp.leftMargin = inset
                    mlp.rightMargin = inset
                    mlp.bottomMargin = gap
                    row.layoutParams = mlp
                }
            }
            val current = row.background
            val desired = GlobalCardDrawable(globalCardColour(), corner, alpha)
            if (current !is GlobalCardDrawable || !current.matches(corner, alpha)) {
                row.background = desired
            }
            globalCardDrawables[row] = desired
            if (row.width > 0 && row.height > 0 && !globalFillsStripped.containsKey(row)) {
                globalFillsStripped[row] = true
                stripGlobalRowFills(row, 0)
            }
            applyGlobalCardHeight(row)
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** 全局卡片颜色：深色模式用深底，否则白色 */
    private fun globalCardColour(): Int =
        if (globalCardsDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /** 多行容器判断：多个等高子行或单行但容器更高 */
    private fun isGlobalRowContainer(view: View): Boolean {
        if (view !is ViewGroup) return false
        val g = view
        var rows = 0
        var tallest = 0
        var firstH = -1
        var even = true
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            if (!c.isShown) continue
            val ch = c.height
            if (ch < 100) continue
            if (ch > tallest) tallest = ch
            if (firstH < 0) firstH = ch
            else if (abs(ch - firstH) > Math.max(4, firstH / 8)) even = false
            rows++
        }
        return (rows >= 3 && even) || (rows == 1 && view.height > tallest + dp(view, 8))
    }

    /** 还原行 margin */
    private fun resetGlobalMargins(view: View) {
        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            val mlp = lp
            if (mlp.leftMargin != 0 || mlp.rightMargin != 0 || mlp.bottomMargin != 0) {
                mlp.leftMargin = 0
                mlp.rightMargin = 0
                mlp.bottomMargin = 0
                view.layoutParams = mlp
            }
        }
    }

    /** 清掉行内部的底板，让卡片真正露出来 */
    private fun stripGlobalRowFills(view: View, depth: Int) {
        if (view == null || depth > 4 || view !is ViewGroup) return
        try {
            val g = view
            val w = view.width
            val h = view.height
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i) ?: continue
                if (!c.isShown) continue
                val wide = c.width * 10 >= w * 6
                val plate = wide && h > 0 && w > 0 &&
                    (c.height * 10 >= h * 7 || c.height >= dp(c, 20))
                if (plate && isGlobalOpaqueFill(c)) c.background = null
                stripGlobalRowFills(c, depth + 1)
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 判断是否为不透明底板 */
    private fun isGlobalOpaqueFill(view: View): Boolean {
        try {
            val d = view.background ?: return false
            if (d is GlobalCardDrawable) return false
            if (d is ColorDrawable) return ((d.color ushr 24) and 0xFF) > 200
            if (d is GradientDrawable) {
                val csl = d.color ?: return false
                return ((csl.defaultColor ushr 24) and 0xFF) > 200
            }
            val n = d.javaClass.name
            return n.contains("StateListDrawable") || n.contains("NinePatchDrawable")
        } catch (ignored: Throwable) {
            return false
        }
    }

    /** 卡片高度增量：每行只记一次原始高度，重复不累积 */
    private fun applyGlobalCardHeight(row: View) {
        try {
            val dh = (globalCardsHeight.coerceIn(-24, 48) * row.resources.displayMetrics.density).roundToInt()
            if (dh == 0) return
            val lp = row.layoutParams ?: return
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height <= 0) return
            val orig = globalOrigHeights[row]
                ?: (if (row.height > 0) row.height else lp.height).also {
                    if (it <= 0) return
                    globalOrigHeights[row] = it
                }
            val want = Math.max(1, orig + dh)
            if (lp.height != want) {
                lp.height = want
                row.layoutParams = lp
            }
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** dp 转 px */
    private fun dp(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).roundToInt()

    /** 关闭全局卡片：清理所有缓存，页面重建后微信自然恢复原始背景 */
    private fun restoreGlobalCards() {
        globalFillsStripped.clear()
        globalOrigHeights.clear()
        globalCardDrawables.clear()
        globalChattingCache.clear()
        globalListCache.clear()
    }

    /** 立即对当前可见 Activity 应用全局卡片（开关切换时调用） */
    private fun applyGlobalCardsNow() {
        if (!globalCardsEnabled) return
        try {
            val activity = getTopMostActivity(allowPaused = true) ?: return
            val decor = activity.window?.decorView ?: return
            decor.post {
                if (decor.width > 0 && decor.height > 0) {
                    styleGlobalLists(decor, 0)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 全局 Activity.onResume hook：页面可见时应用卡片 */
    private fun registerGlobalHooks() {
        if (globalHooksRegistered) return
        globalHooksRegistered = true

        Activity::class.reflekt().apply {
            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val activity = thisObject as Activity
                val decor = activity.window?.decorView ?: return@hookAfter
                decor.postDelayed({
                    if (decor.width > 0 && decor.height > 0) {
                        styleGlobalLists(decor, 0)
                    }
                }, 60L)
            }
        }

        View::class.reflekt().apply {
            firstMethod {
                name = "onScrollChanged"
                parameterCount = 4
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val self = thisObject as? View ?: return@hookAfter
                if (self !is ViewGroup) return@hookAfter
                if (!isGenericList(self)) return@hookAfter
                applyGlobalCards(self)
            }
        }
    }
}