package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import dev.sun.wechat.utils.WeLogger

/**
 * 「分析」开关在聊天输入栏＋面板（com.tencent.mm.pluginsdk.ui.chat.AppPanel）里的原生挂载。
 *
 * ⚠️ 不能改动 AppPanel 的既有子结构：WeKit 的 ChatToolbar.snapshotTools 硬编码读取
 *    findViewByChildIndexes(0,0,0)（= 装有 GridView 的 MMFlipper）并 cast 成 ViewGroup，
 *    任何包装/插队都会把它挤成非 ViewGroup → ClassCastException（真实崩溃，见 8.0.77）。
 *
 * 因此这里只在 AppPanel **末尾追加**一行原生 Switch，index 0 的容器原样保留；
 * 点击开关按会话切换情绪分析、长按打开设置。按「当前 footer」去重 + 全局布局监听重试，
 * 结构不符时安全跳过（绝不半改造面板）。
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
    private var row: LinearLayout? = null
    private var control: Switch? = null

    private var footer: View? = null
    private var observer: ViewTreeObserver? = null
    private var blockedPanel: ViewGroup? = null
    private var refreshing = false
    private var syncing = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = detach()
    }

    /** 每次消息绑定/布局变化调用：定位当前 footer，必要时挂开关并同步状态。 */
    fun update() {
        // 已挂好且 footer 仍可见时，只同步开关状态，避免每次绑定都全量扫描 decorView。
        val existing = footer
        if (existing != null && existing.isAttachedToWindow && existing.isShown &&
            panel != null && row?.parent === panel
        ) {
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
        removeRow()
        control = null
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            syncRow()
        } catch (error: Exception) {
            blockedPanel = panel
            runCatching { removeRow() }
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
            removeRow()
            blockedPanel = null
            return
        }
        if (found === blockedPanel) return
        // 已挂载且行仍在面板里时只同步状态。
        if (panel === found && row?.parent === found) {
            syncControl()
            return
        }
        // 结构校验：index 0 必须是容器且含 MMFlipper——确保我们绝不破坏 (0,0,0)。
        val container = found.getChildAt(0) as? ViewGroup ?: return
        if (descendants(container).none { it.javaClass.name.endsWith(".MMFlipper") }) return
        if (found.height < (180 * activity.resources.displayMetrics.density).toInt()) return

        removeRow()
        val toggle = createControl()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, -1))
        panel = found
        this.row = row
        runCatching {
            if (found is FrameLayout) {
                found.addView(row, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ))
            } else {
                found.addView(row, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            syncControl()
        }.onFailure {
            blockedPanel = found
            removeRow()
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

    private fun removeRow() {
        control?.setOnCheckedChangeListener(null)
        control?.setOnLongClickListener(null)
        (row?.parent as? ViewGroup)?.removeView(row)
        row = null
        panel = null
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
