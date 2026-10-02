package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.Switch
import dev.sun.wechat.utils.WeLogger

/**
 * 「分析」开关在聊天输入栏＋面板（com.tencent.mm.pluginsdk.ui.chat.AppPanel）里的原生挂载。
 *
 * 对照言外 ReplyPlusRow：把面板内容整体包进一层垂直容器，顶部插入一行原生 Switch；
 * 点击开关按会话切换情绪分析，长按打开设置。面板会被微信复用/重建，因此按「当前 footer」
 * 去重 + 全局布局监听重试，结构不符时安全跳过（绝不留下半改造的面板）。
 */
internal class ChatMoodPlusRow(
    private val activity: Activity,
    /** 当前会话分析是否开启；null 表示未识别到会话（开关置灰）。 */
    private val stateProvider: () -> Boolean?,
    private val onToggle: (Boolean) -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val TAG = "ChatMoodPlusRow"

    private var panel: ViewGroup? = null
    private var wrapper: LinearLayout? = null
    private var original: View? = null
    private var originalParams: ViewGroup.LayoutParams? = null

    private var footer: View? = null
    private var observer: ViewTreeObserver? = null
    private var control: Switch? = null
    private var blockedPanel: ViewGroup? = null
    private var refreshing = false
    private var syncing = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = detach()
    }

    /** 每次消息绑定/布局变化调用：定位当前 footer，必要时重挂开关并同步状态。 */
    fun update() {
        // 面板已挂好且 footer 仍然可见时，只需同步开关状态，避免每次绑定都全量扫描 decorView。
        val existing = footer
        if (existing != null && existing.isAttachedToWindow && existing.isShown && panel != null) {
            syncControl()
            return
        }
        val current = footerOf()
        if (footer !== current) {
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            observer = null
            footer?.removeOnAttachStateChangeListener(attachListener)
            footer = current
            current?.takeIf { it.isAttachedToWindow }?.let {
                observer = it.viewTreeObserver.also { tree -> tree.addOnGlobalLayoutListener(layoutListener) }
                it.addOnAttachStateChangeListener(attachListener)
            }
        }
        refresh()
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        footer?.removeOnAttachStateChangeListener(attachListener)
        footer = null
        panel = null
        blockedPanel = null
        restoreContent()
        control = null
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            syncRow()
        } catch (error: Exception) {
            blockedPanel = panel
            runCatching { restoreContent() }
            WeLogger.w(TAG, "REPLY_PLUS_ROW_FAILED " + error.javaClass.simpleName)
        } finally {
            refreshing = false
        }
    }

    private fun syncRow() {
        val found = footer?.takeIf { it.isAttachedToWindow && it.isShown }
            ?.let(::descendants)
            ?.filterIsInstance<ViewGroup>()
            ?.singleOrNull {
                it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.AppPanel" && it.isShown
            }
        if (found == null) {
            restoreContent()
            blockedPanel = null
            return
        }
        if (found === blockedPanel) return
        // 已挂载且结构仍是「host 顶行 + 原生内容」时只同步状态。
        if (panel === found && wrapper?.parent === found &&
            original?.parent === wrapper && found.childCount == 1
        ) {
            syncControl()
            return
        }
        restoreContent()
        // 不依赖混淆 id / 条目位置，只认宿主测量出来的结构：content 里存在 MMFlipper。
        val content = found.getChildAt(0) as? LinearLayout
        if (found.childCount != 1 || content == null ||
            descendants(content).none { it.javaClass.name.endsWith(".MMFlipper") }
        ) return
        if (found.height < (180 * activity.resources.displayMetrics.density).toInt()) return

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val toggle = createControl()
        row.addView(toggle, LinearLayout.LayoutParams(-2, -1))

        val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val params = content.layoutParams
        panel = found
        original = content
        originalParams = params
        wrapper = host
        runCatching {
            found.removeView(content)
            host.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            host.addView(View(activity).apply { setBackgroundColor(0x14000000) }, LinearLayout.LayoutParams(-1, 1))
            host.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            // 旧根可能是 WRAP_CONTENT 高；带权重的子项需要面板完整高度。
            found.addView(host, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            syncControl()
        }.onFailure {
            blockedPanel = found
            restoreContent()
            WeLogger.w(TAG, "REPLY_PLUS_ROW_FAILED " + it.javaClass.simpleName)
        }
    }

    private fun createControl(): Switch = Switch(activity).apply {
        text = "分析"
        textSize = 14f
        switchPadding = dp(3)
        minHeight = dp(48)
        setPadding(dp(4), 0, dp(4), 0)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(0xFF1AAD19.toInt(), 0xFF9E9E9E.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0xFFD6EFD8.toInt(), 0xFFE0E0E0.toInt()))
        setOnCheckedChangeListener { _, checked ->
            if (!syncing) onToggle(checked)
        }
        setOnLongClickListener { onLongPress(); true }
        control = this
        syncControl()
    }

    private fun syncControl() {
        val toggle = control ?: return
        syncing = true
        val enabled = stateProvider()
        toggle.isChecked = enabled == true
        toggle.isEnabled = enabled != null
        toggle.contentDescription = "当前聊天情绪分析开关，本地记住选择；长按打开设置"
        syncing = false
    }

    private fun restoreContent() {
        control?.setOnCheckedChangeListener(null)
        control?.setOnLongClickListener(null)
        val target = panel
        val host = wrapper
        val content = original
        if (target != null && host != null && content != null &&
            (content.parent === host || content.parent == null) &&
            ((host.parent === target && target.childCount == 1) || target.childCount == 0)
        ) {
            (content.parent as? ViewGroup)?.removeView(content)
            if (host.parent === target) target.removeView(host)
            target.addView(content, originalParams)
        } else if (target != null && host?.parent === target) {
            target.removeView(host)
        }
        panel = null
        wrapper = null
        original = null
        originalParams = null
    }

    /** 定位当前可见 footer（与 ChatFooterHooks 一致）。 */
    private fun footerOf(): View? = descendants(activity.window.decorView).singleOrNull {
        it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && it.isShown
    }

    private fun descendants(root: View): List<View> = buildList {
        fun visit(view: View, depth: Int) {
            if (depth > 30 || size >= 1500) return
            add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }

    private fun dp(n: Int): Int = (n * activity.resources.displayMetrics.density).toInt()
}
