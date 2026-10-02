package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.utils.WeLogger
import java.util.WeakHashMap

/**
 * 情绪分析在聊天页的入口（对照言外 HostUi / ReplyHostUi）：
 *
 * 1. ＋面板顶部一行原生「分析」开关（[ChatMoodPlusRow]）——点击按会话切换情绪分析，
 *    长按打开设置。这是主入口，对应言外把 Switch 挂进 AppPanel 的做法。
 * 2. 长按 ＋ / 发送键弹出的操作菜单里也保留一个「情绪分析」条目（[WeChatInputBarMenuApi]），
 *    作为备用入口。
 */
object ChatMoodHostUi : WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "ChatMoodHostUi"

    /** 每个聊天 Activity 一份面板挂载器，弱引用避免泄漏。 */
    private val rows = WeakHashMap<Activity, ChatMoodPlusRow>()

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "mood_analysis_toggle",
            icon = MaterialSymbols.Outlined.Auto_awesome,
            label = "情绪分析",
            isSupported = { _, _ -> MoodAnalyzer.enabled },
            onClick = { context, _ -> toggleForCurrentChat(context) },
            onLongClick = { context, _ -> openSettings(context) },
        ),
    )

    /**
     * 消息绑定时驱动面板开关挂载与状态同步（由 [MessageSniffer] 调用）。
     * 面板会被微信复用/重建，故每次都重定位当前 footer。
     */
    fun onMessageBound(view: android.view.View) {
        if (!MoodAnalyzer.enabled) return
        val activity = view.context as? Activity ?: return
        if (activity.isFinishing) return
        runCatching { rowFor(activity).update() }
            .onFailure { WeLogger.e(TAG, "plus row update failed", it) }
    }

    private fun rowFor(activity: Activity): ChatMoodPlusRow = rows.getOrPut(activity) {
        ChatMoodPlusRow(
            activity = activity,
            stateProvider = { currentTalker()?.let { ConversationSwitches.isEnabled(it) } },
            onToggle = { checked -> applyToggle(activity, checked) },
            onLongPress = { openSettings(activity) },
        )
    }

    private fun currentTalker(): String? = WeCurrentConversationApi.value.takeIf { it.isNotBlank() }

    private fun applyToggle(context: android.content.Context, checked: Boolean) {
        val talker = currentTalker()
        if (talker == null) {
            dev.sun.wechat.utils.android.showToast(context, "暂未识别当前聊天，请重进聊天页")
            return
        }
        if (!ConversationSwitches.setEnabled(talker, checked)) {
            dev.sun.wechat.utils.android.showToast(context, "开关未保存，请重进聊天页重试")
            return
        }
        if (checked) {
            MessageSniffer.refresh()
        } else {
            BubbleDecorator.clearAll()
        }
        // 立即同步面板开关状态（例如从长按菜单切换时）
        (context as? Activity)?.let { rows[it]?.update() }
    }

    private fun toggleForCurrentChat(context: android.content.Context) {
        val talker = currentTalker()
        if (talker == null) {
            dev.sun.wechat.utils.android.showToast(context, "暂未识别当前聊天，请重进聊天页")
            return
        }
        val enabled = ConversationSwitches.isEnabled(talker)
        applyToggle(context, !enabled)
        if (ConversationSwitches.isEnabled(talker) != enabled) {
            dev.sun.wechat.utils.android.showToast(
                context, if (!enabled) "已开启本会话情绪分析" else "已关闭本会话情绪分析")
        }
    }

    private fun openSettings(context: android.content.Context) {
        runCatching {
            val comp = context as? ComponentActivity ?: return
            MoodFeature.onClick(comp)
        }.onFailure { WeLogger.e(TAG, "open settings failed", it) }
    }

    /** 功能停用时拆掉所有面板挂载，恢复微信原生面板。 */
    fun detachAll() {
        synchronized(rows) {
            rows.values.forEach { runCatching { it.detach() } }
            rows.clear()
        }
    }
}
