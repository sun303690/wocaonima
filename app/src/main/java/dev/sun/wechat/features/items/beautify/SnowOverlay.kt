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

    /** Wren SnowView 的 Kotlin 还原：75 粒子、圆点/线段两种雪花、风力摆动、越界回绕。 */
    class SnowView(context: android.content.Context) : View(context) {

        private val particles = Array(COUNT) { Particle() }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
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
            var line = false
        }

        private fun rnd(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            seed2 = seed2 xor (seed2 shl 13); seed2 = seed2 xor (seed2 ushr 17); seed2 = seed2 xor (seed2 shl 5)
            return ((seed xor seed2) ushr 8) / 16777216f
        }

        private fun reset(p: Particle, fromTop: Boolean) {
            p.x = rnd() * maxOf(1, width)
            p.y = if (fromTop) -10f else rnd() * maxOf(1, height)
            p.vy = 40f + rnd() * 120f
            p.vx = (rnd() - 0.5f) * 20f
            p.size = 2f + rnd() * 6f
            p.swayAmp = p.size * 1.5f
            p.swayPhase = rnd() * (Math.PI.toFloat() * 2f)
            p.alpha = 0.4f + rnd() * 0.6f
            p.line = rnd() < 0.35f
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
                p.swayPhase += dt * 2f
                p.x += (p.vx + kotlin.math.sin(p.swayPhase) * p.swayAmp) * dt
                p.y += p.vy * dt
                if (p.y - p.size > h) reset(p, true)
                if (p.x < -20f) p.x = w + 20f
                if (p.x > w + 20f) p.x = -20f
                val color = ((p.alpha * 255).toInt() shl 24) or 0xFFFFFF
                if (p.line) {
                    linePaint.color = color
                    linePaint.strokeWidth = p.size * 0.8f
                    canvas.drawLine(
                        p.x, p.y - p.size,
                        p.x + kotlin.math.sin(p.swayPhase) * p.size, p.y + p.size,
                        linePaint,
                    )
                } else {
                    dotPaint.color = color
                    canvas.drawCircle(p.x, p.y, p.size, dotPaint)
                }
            }
            if (windowVisibility == VISIBLE) postInvalidateOnAnimation()
        }

        private companion object {
            const val COUNT = 75
        }
    }
}
