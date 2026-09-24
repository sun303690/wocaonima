package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.sun.wechat.utils.WeLogger
import java.util.WeakHashMap

/**
 * 在聊天页标题栏（ActionBarContainer）右上角注入「绘制」开关，随时切换情绪分析卡显示。
 * 直接移植自 Yanwai HostUi，改用 WeKit 的 MoodAnalyzer.showBadge / BubbleDecorator。
 */
object ChatMoodHostUi {
    private const val TAG = "ChatMoodHostUi"
    private val main = Handler(Looper.getMainLooper())
    private val perActivity = WeakHashMap<Activity, Ui>()

    private class Ui(val activity: Activity) {
        var control: Switch? = null
        var syncing = false

        fun showStatus(value: String) {
            syncing = true
            control?.apply {
                visibility = View.VISIBLE
                isChecked = MoodAnalyzer.showBadge
                contentDescription = "绘制分析结果；$value；长按打开设置"
            }
            syncing = false
        }

        fun ensureControl() {
            val decor = activity.window.decorView
            val all = descendants(decor)
            val chat = all.firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") && it.isShown }
            val scope = if (chat != null) descendants(chat) else all
            val header = scope.firstOrNull {
                it.isShown && it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
            } as? FrameLayout
            if (header != null && control?.parent === header && control?.isShown == true) return
            removeControl()
            if (header == null) return
            val toggle = Switch(activity).apply {
                text = "绘制"
                textSize = 12f
                switchPadding = dp(3)
                minHeight = dp(48)
                setPadding(dp(4), 0, dp(4), 0)
                setOnCheckedChangeListener { _, checked ->
                    if (!syncing) {
                        MoodAnalyzer.showBadge = checked
                        if (!checked) BubbleDecorator.clearAll()
                        MessageSniffer.refresh()
                    }
                }
                setOnLongClickListener { openSettings(); true }
            }
            control = toggle
            val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
            val menuLeft = descendants(header).filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
                .map { view -> IntArray(2).also { view.getLocationOnScreen(it) }[0] - headerPos[0] }
                .filter { it > header.width * 0.7 }.minOrNull()
            val menuSpace = menuLeft?.let { header.width - it + dp(4) } ?: dp(60)
            header.addView(toggle, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = menuSpace
            })
            toggle.post {
                if (control !== toggle || !toggle.isAttachedToWindow) return@post
                val title = descendants(header).filterIsInstance<TextView>().firstOrNull {
                    it !== toggle && it.isShown && it.text.isNotBlank() && it.width > dp(90)
                }
                title?.let {
                    it.maxWidth = (header.width - 2 * (toggle.width + menuSpace)).coerceAtLeast(dp(60))
                    it.ellipsize = TextUtils.TruncateAt.END
                }
            }
        }

        private fun removeControl() {
            control?.let { (it.parent as? ViewGroup)?.removeView(it) }
            control = null
        }

        private fun openSettings() {
            runCatching {
                val comp = activity as? ComponentActivity ?: return@runCatching
                MoodFeature.onClick(comp)
            }.onFailure { WeLogger.e(TAG, "open settings failed", it) }
        }
    }

    /** 由 MessageSniffer 在每条消息视图绑定后调用，确保右上角开关已注入。 */
    @JvmStatic
    fun show(activity: Activity, value: String) {
        main.post {
            runCatching {
                val ui = perActivity.getOrPut(activity) { Ui(activity) }
                ui.ensureControl()
                ui.showStatus(value)
            }.onFailure { WeLogger.e(TAG, "host ui failed", it) }
        }
    }

    private fun descendants(root: ViewGroup): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View, depth: Int) {
            if (depth > 14 || out.size > 500) return
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
