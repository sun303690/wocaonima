package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.utils.WeLogger

/**
 * 情绪分析在聊天页的入口（对照言外 HostUi / ReplyHostUi）。
 *
 * 只用**长按 ＋ / 发送键**弹出的操作菜单条目（[WeChatInputBarMenuApi]）：
 * 不往微信 ＋面板（AppPanel）里注入任何 View —— 那是固定高度的原生容器，
 * 追加子 View 会把原生网格挤出可视区/透明，还会与 ChatToolbar 的结构读取冲突。
 * （曾用注入 AppPanel 的方式加「分析」开关，真机出现「微信＋面板功能消失、开关透明」，已彻底删除。）
 *
 * 菜单条目：点击按会话切换情绪分析，长按打开设置。
 */
object ChatMoodHostUi : WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "ChatMoodHostUi"

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
}
