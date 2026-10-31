// SPDX-License-Identifier: GPL-3.0-only
//
// 微信页面下雪 —— 按 Wren 模块 SnowView 的原实现重写（纯 Canvas 绘制）。
// 不注册 RenderEffect、不做背景模糊；挂载方式参考「应用全局背景」的
// ActivityLifecycleCallbacks，模块自己的页面一律跳过。
package dev.sun.wechat.features.items.beautify

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger

object SnowOverlay {

    const val TECHNICAL_ID = "微信用雪花朵朵"
    private const val TAG = "WeKitSnow"
    private const val VIEW_TAG = "wekit_snow_overlay"

    val isEnabled: Boolean
        get() = KvStore.getBoolOrDef(TECHNICAL_ID, false)

    private var app: Application? = null

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isEnabled) return
            if (activity.javaClass.name.startsWith("dev.sun.wechat")) return
            runCatching { attach(activity) }
                .onFailure { WeLogger.w(TAG, "attach snow overlay failed", it) }
        }
    }

    fun install(application: Application) {
        if (app === application) return
        app?.unregisterActivityLifecycleCallbacks(callbacks)
        app = application
        application.registerActivityLifecycleCallbacks(callbacks)
        runCatching { WeLogger.i(TAG, "snow overlay installed") }
    }

    fun uninstall() {
        app?.unregisterActivityLifecycleCallbacks(callbacks)
        app = null
    }

    private fun attach(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(VIEW_TAG) != null) return
        val snow = SnowView(activity)
        snow.tag = VIEW_TAG
        snow.isClickable = false
        snow.isFocusable = false
        decor.addView(
            snow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * 雪花层：部分画六角雪花（三根交叉的线 + 侧枝），部分画圆点；
     * 白色主体下面垫一层半透明黑阴影，浅色背景上也看得清。
     */
    class SnowView(context: android.content.Context) : View(context) {

        private val particles = Array(COUNT) { Particle() }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastFrame = System.nanoTime()
        private var seed = System.nanoTime().toInt()
        private var seed2 = (System.nanoTime() ushr 32).toInt() or 1

        private class Particle {
            var x = 0f
            var y = 0f
            var vx = 0f
            var vy = 0f
            var size = 0f
            var swayPhase = 0f
            var swayAmp = 0f
            var alpha = 0f
            var spokes = 0
        }

        private fun rnd(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            seed2 = seed2 xor (seed2 shl 13); seed2 = seed2 xor (seed2 ushr 17); seed2 = seed2 xor (seed2 shl 5)
            return ((seed xor seed2) ushr 8) / 16777216f
        }

        private fun reset(p: Particle, fromTop: Boolean) {
            p.x = rnd() * maxOf(1, width)
            p.y = if (fromTop) -20f else rnd() * maxOf(1, height)
            p.vy = 26f + rnd() * 70f
            p.vx = (rnd() - 0.5f) * 14f
            p.size = 5f + rnd() * 11f
            p.swayAmp = p.size * 1.2f
            p.swayPhase = rnd() * (Math.PI.toFloat() * 2f)
            p.alpha = 0.55f + rnd() * 0.40f
            p.spokes = if (rnd() < 0.62f) 6 else 0
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            for (p in particles) reset(p, false)
        }

        override fun onDraw(canvas: Canvas) {
            val now = System.nanoTime()
            val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = now
            val w = width.toFloat()
            val h = height.toFloat()
            for (p in particles) {
                p.swayPhase += dt * 1.6f
                p.x += (p.vx + kotlin.math.sin(p.swayPhase) * p.swayAmp) * dt
                p.y += p.vy * dt
                if (p.y - p.size * 2f > h) reset(p, true)
                if (p.x < -30f) p.x = w + 30f
                if (p.x > w + 30f) p.x = -30f
                val white = ((p.alpha * 255).toInt() shl 24) or 0xFFFFFF
                // 先画阴影层（向下偏移一点），再画白色主体：浅色背景上不会“消失”。
                if (p.spokes > 0) {
                    drawFlake(canvas, p, 0x40000000, p.x + 0.9f, p.y + 1.4f)
                    drawFlake(canvas, p, white, p.x, p.y)
                } else {
                    dotPaint.color = 0x40000000
                    canvas.drawCircle(p.x + 0.9f, p.y + 1.4f, p.size * 0.55f, dotPaint)
                    dotPaint.color = white
                    canvas.drawCircle(p.x, p.y, p.size * 0.55f, dotPaint)
                }
            }
            if (windowVisibility == VISIBLE) postInvalidateOnAnimation()
        }

        private fun drawFlake(canvas: Canvas, p: Particle, color: Int, cx: Float, cy: Float) {
            strokePaint.color = color
            strokePaint.strokeWidth = (p.size * 0.22f).coerceAtLeast(1.6f)
            val r = p.size
            for (i in 0 until 3) {
                val angle = i * (Math.PI / 3.0).toFloat() + p.swayPhase * 0.15f
                val dx = kotlin.math.cos(angle) * r
                val dy = kotlin.math.sin(angle) * r
                canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, strokePaint)
                // 侧枝：靠近端点处向外张开的短枝
                for (side in intArrayOf(1, -1)) {
                    val bx = cx + dx * 0.62f
                    val by = cy + dy * 0.62f
                    val branchAngle = angle + side * 0.9f
                    val bl = r * 0.34f
                    canvas.drawLine(
                        bx, by,
                        bx + kotlin.math.cos(branchAngle) * bl,
                        by + kotlin.math.sin(branchAngle) * bl,
                        strokePaint,
                    )
                }
            }
        }

        private companion object {
            const val COUNT = 75
        }
    }
}
