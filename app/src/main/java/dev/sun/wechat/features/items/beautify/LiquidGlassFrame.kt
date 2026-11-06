package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.hookAfterDirectly

/**
 * 液态玻璃框（移植自 WePeak 2.8.0 LiquidGlassFrame）。
 *
 * 给微信界面里的列表卡片/大块视图包一层半透明「液态玻璃」背景：主体半透明圆角矩形
 * + 顶部高光渐变 + 浅色模式底部阴影 + 描边，深浅色自适应。
 *
 * 归属：「应用全局背景」面板下的子项（global_bg_glass_*），由该 feature 的开关
 * 驱动 install()/uninstall()，不再作为独立的注册 feature 出现在功能列表。
 *
 * hook 面（与 WePeak 一致，均为框架方法 hookAfter）：
 * - View.onAttachedToWindow：ViewGroup 递归套玻璃
 * - AbsListView.obtainView / ViewGroup.addView：新挂载的子视图即时套玻璃
 * - View.setBackground(Drawable) / setBackgroundDrawable(Drawable)：
 *   微信重设背景后重新包一层（以「背景已是玻璃包装器」为去重护栏，天然防递归）
 *
 * 说明：本功能改写的是视图背景，无法逐个还原；关闭后需重启微信才完全恢复原样。
 */
object LiquidGlassFrame {

    private const val TAG = "LiquidGlassFrame"

    // 配置项（全局背景面板：global_bg_glass_*，默认值与 WePeak 同源）
    const val KEY_ENABLED = "global_bg_glass_enabled"
    const val KEY_OPACITY = "global_bg_glass_opacity"
    const val KEY_RADIUS_DP = "global_bg_glass_radius"
    const val KEY_MARGIN_H_DP = "global_bg_glass_margin_h"
    const val DEF_OPACITY = 0.55f
    const val DEF_RADIUS_DP = 16f
    const val DEF_MARGIN_H_DP = 12f

    /** 命中高度阈值：40dp（与 WePeak 一致） */
    private const val MIN_HEIGHT_DP = 40f

    /** WePeak 的全局排除 Activity 清单（相册/视频/扫描/视频号等不适合玻璃化的界面） */
    private val EXCLUDED_ACTIVITIES = setOf(
        "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity",
        "com.tencent.mm.plugin.recordvideo.activity.MMRecordUI",
        "com.tencent.mm.plugin.fav.ui.detail.FavoriteImgDetailUI",
        "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI",
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI",
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryGridUI",
        "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI",
        "com.tencent.mm.plugin.subapp.ui.gallery.GestureGalleryUI",
        "com.tencent.mm.plugin.gallery.picker.view.ImageCropUI",
        "com.tencent.mm.plugin.sns.ui.SnsBrowseUI",
        "com.tencent.mm.plugin.finder.feed.ui.FinderShareFeedRelUI",
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI",
        "com.tencent.mm.plugin.location_soso.SoSoProxyUI",
        "com.tencent.mm.plugin.finder.feed.ui.FinderProfileTimeLineUI",
        "com.tencent.mm.plugin.sns.ui.SnsGalleryUI",
        "com.tencent.mm.pluginsdk.ui.ProfileHdHeadImg",
        "com.tencent.mm.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI",
        "com.tencent.mm.plugin.voip.ui.VideoActivity",
    )

    /** TaskBar 文字染色记录（原色），与 WePeak b0 的 WeakHashMap 同义 */
    private val taskBarOriginalTextColors = HashMap<View, Int>()

    /** 已挂载的 hook 句柄（由「应用全局背景」开关驱动挂/卸） */
    private val handles = mutableListOf<me.hd.wauxv.hook.HookHandle>()

    fun isActive(): Boolean = handles.isNotEmpty()

    /** 挂载全部框架 hook；重复调用无副作用 */
    fun install() {
        if (handles.isNotEmpty()) return

        // View.onAttachedToWindow（protected，反射取出）
        runCatching {
            View::class.java.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }
        }.onSuccess { method ->
            handles += method.hookAfterDirectly {
                val view = thisObject as? View ?: return@hookAfterDirectly
                handleAttached(view)
            }
        }.onFailure { WeLogger.e(TAG, "hook onAttachedToWindow 失败", it) }

        // AbsListView.obtainView(int, boolean[])
        runCatching {
            AbsListView::class.java.getDeclaredMethod(
                "obtainView",
                Int::class.javaPrimitiveType,
                BooleanArray::class.java
            ).apply { isAccessible = true }
        }.onSuccess { method ->
            handles += method.hookAfterDirectly {
                maybeWrapView(result as? View ?: return@hookAfterDirectly)
            }
        }.onFailure { WeLogger.e(TAG, "hook obtainView 失败", it) }

        // ViewGroup.addView(View, int, LayoutParams)
        runCatching {
            ViewGroup::class.java.getDeclaredMethod(
                "addView",
                View::class.java,
                Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java
            ).apply { isAccessible = true }
        }.onSuccess { method ->
            handles += method.hookAfterDirectly {
                maybeWrapView(args.getOrNull(0) as? View ?: return@hookAfterDirectly)
            }
        }.onFailure { WeLogger.e(TAG, "hook addView 失败", it) }

        // View.setBackground(Drawable) / setBackgroundDrawable(Drawable)
        // 微信之后重设背景时重新包一层；若新背景已是玻璃包装器则跳过（防递归/防重复）。
        listOf("setBackground", "setBackgroundDrawable").forEach { name ->
            runCatching {
                View::class.java.getDeclaredMethod(name, Drawable::class.java)
                    .apply { isAccessible = true }
            }.onSuccess { method ->
                handles += method.hookAfterDirectly {
                    val view = thisObject as? View ?: return@hookAfterDirectly
                    val newBg = args.getOrNull(0) as? Drawable ?: return@hookAfterDirectly
                    if (newBg is GlassMinSizeWrapper) return@hookAfterDirectly
                    if (!viewQualifies(view)) return@hookAfterDirectly
                    view.background = buildWrappedBackground(view)
                }
            }.onFailure { WeLogger.e(TAG, "hook $name 失败", it) }
        }

        WeLogger.i(TAG, "液态玻璃框已挂载（句柄 ${handles.size}）")
    }

    /** 卸下全部 hook；已套用的玻璃背景要等微信重启才完全复原 */
    fun uninstall() {
        if (handles.isEmpty()) return
        val n = handles.size
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        WeLogger.i(TAG, "液态玻璃框已卸载（$n 个句柄，重启微信后背景复原）")
    }

    /** 「应用全局背景」开关联动：总开关关闭或液体玻璃开关关闭时卸载 */
    fun syncInstallState(enabled: Boolean) {
        if (enabled) install() else uninstall()
    }

    // ---- 挂载判定与遍历（对应 WePeak Z/d0） ----

    private fun handleAttached(view: View) {
        if (isExcludedContext(view)) return
        val group = view as? ViewGroup
        if (group != null) {
            if (group.javaClass.name.contains("TaskBar")) recolorTaskBarText(group)
            if (group.javaClass.simpleName == "ConversationListView") return
            applyToViewGroup(group)
        } else {
            maybeWrapView(view)
        }
    }

    private fun applyToViewGroup(group: ViewGroup) {
        val density = group.resources.displayMetrics.density
        val minH = (MIN_HEIGHT_DP * density).toInt()
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.height < minH) continue
            if (child is ViewGroup && child.childCount > 3) {
                var tallChildren = 0
                for (j in 0 until child.childCount) {
                    if ((child.getChildAt(j)?.height ?: 0) >= minH) tallChildren++
                }
                if (tallChildren > 2) {
                    // 容器型子项：逐个处理其中够高的孙视图
                    for (j in 0 until child.childCount) {
                        val sub = child.getChildAt(j) ?: continue
                        if (sub.height >= minH && sub.background !is GlassMinSizeWrapper) {
                            wrapView(sub)
                        }
                    }
                    continue
                }
            }
            if (child.background !is GlassMinSizeWrapper) wrapView(child)
        }
    }

    private fun maybeWrapView(view: View) {
        if (!viewQualifies(view)) return
        if (view.background is GlassMinSizeWrapper) return
        wrapView(view)
    }

    private fun viewQualifies(view: View): Boolean {
        if (isExcludedContext(view)) return false
        val density = view.resources.displayMetrics.density
        if (view.height < (MIN_HEIGHT_DP * density).toInt()) return false
        if (view.javaClass.simpleName == "ConversationListView") return false
        return true
    }

    private fun wrapView(view: View) {
        view.background = buildWrappedBackground(view)
        WeLogger.d(TAG, "液态玻璃已套用：${view.javaClass.name}")
    }

    private fun buildWrappedBackground(view: View): Drawable {
        val density = view.resources.displayMetrics.density
        val dark = isDarkMode(view.context)
        val opacity = KvStore.getFloatOrDef(KEY_OPACITY, DEF_OPACITY)
        val radiusPx = KvStore.getFloatOrDef(KEY_RADIUS_DP, DEF_RADIUS_DP) * density
        val marginH = (KvStore.getFloatOrDef(KEY_MARGIN_H_DP, DEF_MARGIN_H_DP) * density).toInt()
        val marginV = (3f * density).toInt()
        val glass = LiquidGlassDrawable(density, radiusPx, opacity, dark)
        val inset = InsetDrawable(glass, marginH, marginV, marginH, marginV)
        return GlassMinSizeWrapper(inset)
    }

    private fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and 48) == 32

    /** 小程序（AppBrand）与排除清单内的界面一律不动（对应 WePeak d0 + 排除集） */
    private fun isExcludedContext(view: View): Boolean {
        var ctx: Context? = view.context
        var hops = 0
        while (ctx != null && hops < 8) {
            val name = ctx.javaClass.name
            if (name.contains("appbrand", ignoreCase = true)) return true
            if (name in EXCLUDED_ACTIVITIES) return true
            ctx = (ctx as? ContextWrapper)?.baseContext
            hops++
        }
        return false
    }

    /** TaskBar 文字染黑一次（对应 WePeak b0，记录原色便于排查） */
    private fun recolorTaskBarText(root: View) {
        if (root is TextView) {
            if (!taskBarOriginalTextColors.containsKey(root)) {
                taskBarOriginalTextColors[root] = root.currentTextColor
            }
            root.setTextColor(Color.BLACK)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                root.getChildAt(i)?.let { recolorTaskBarText(it) }
            }
        }
    }

    // ---- 液态玻璃 Drawable（对应 WePeak m06） ----

    /**
     * 纯绘制实现：主体半透明圆角矩形 + 顶部高光渐变 + 浅色模式底部阴影渐变 + 描边。
     * draw 顺序与 WePeak m06.draw 一致（4 次 drawRoundRect）。
     */
    private class LiquidGlassDrawable(
        density: Float,
        private val radiusPx: Float,
        opacity: Float,
        private val dark: Boolean,
    ) : Drawable() {

        /** 透明度基数：clamp(opacity, 0.1, 0.95) * 255（与 WePeak 一致） */
        private val alphaBase = (opacity.coerceIn(0.1f, 0.95f) * 255f).toInt()

        private val rect = RectF()
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (dark) {
                Color.argb(alphaBase.coerceIn(0, 255), 40, 42, 54)
            } else {
                Color.argb(alphaBase.coerceIn(0, 255), 255, 255, 255)
            }
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bottomShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, density)
            color = if (dark) {
                Color.argb((alphaBase * 0.6f).toInt().coerceIn(0, 255), 80, 84, 108)
            } else {
                Color.argb((alphaBase * 0.7f).toInt().coerceIn(0, 255), 255, 255, 255)
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width()
            val h = bounds.height()
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            val topColor = if (dark) {
                Color.argb((alphaBase * 0.35f).toInt().coerceIn(0, 255), 100, 105, 130)
            } else {
                Color.argb((alphaBase * 0.45f).toInt().coerceIn(0, 255), 255, 255, 255)
            }
            highlightPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(topColor, Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            if (!dark) {
                bottomShadowPaint.shader = LinearGradient(
                    0f, h.toFloat(), 0f, h * 0.6f,
                    intArrayOf(
                        Color.argb((alphaBase * 0.2f).toInt().coerceIn(0, 255), 180, 180, 190),
                        Color.argb(0, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(rect, radiusPx, radiusPx, bodyPaint)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, highlightPaint)
            if (!dark) {
                canvas.drawRoundRect(rect, radiusPx, radiusPx, bottomShadowPaint)
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            bodyPaint.alpha = alpha
            highlightPaint.alpha = alpha
            bottomShadowPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bodyPaint.colorFilter = colorFilter
            highlightPaint.colorFilter = colorFilter
            bottomShadowPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---- InsetDrawable 包装器（对应 WePeak l06） ----

    /**
     * InsetDrawable 包装：保住 minimumWidth/Height，并把 onBoundsChange 透传给内层，
     * 防止微信重设背景时尺寸塌陷；同时作为「已处理」的判据（instanceof）。
     */
    private class GlassMinSizeWrapper(
        private val inset: InsetDrawable,
    ) : Drawable() {

        override fun draw(canvas: Canvas) {
            inset.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            inset.bounds = bounds
        }

        override fun getMinimumWidth(): Int = inset.minimumWidth

        override fun getMinimumHeight(): Int = inset.minimumHeight

        override fun getIntrinsicWidth(): Int = inset.intrinsicWidth

        override fun getIntrinsicHeight(): Int = inset.intrinsicHeight

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = inset.opacity

        override fun setAlpha(alpha: Int) {
            inset.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            inset.colorFilter = colorFilter
        }
    }
}
