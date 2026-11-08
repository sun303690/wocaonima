package dev.sun.wechat.features.items.chat_mood

import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.utils.android.showToast

/**
 * 「自动帮我回」的逐聊天开关：长按输入栏 ＋ / 发送键菜单里的独立条目。
 *
 * 和「情绪分析」那条互不影响：这里只切当前聊天的自动帮我回；
 * 情绪分析整行停用也不影响它（入口由常驻的 [AutoReplyEntry] 注册）。
 */
object ReplyAutoHostUi : WeChatInputBarMenuApi.IActionItemsProvider {

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "mood_reply_toggle",
            icon = MaterialSymbols.Outlined.Auto_awesome,
            label = "自动帮我回",
            isSupported = { _, _ -> ReplyAuto.enabled },
            onClick = { context, _ -> toggleForCurrentChat(context) },
            onLongClick = { context, _ -> openSettings(context) },
        ),
    )

    private fun currentTalker(): String? = WeCurrentConversationApi.value.takeIf { it.isNotBlank() }

    private fun toggleForCurrentChat(context: android.content.Context) {
        val talker = currentTalker()
        if (talker == null) {
            showToast(context, "暂未识别当前聊天，请重进聊天页")
            return
        }
        val enabled = ConversationSwitches.isReplyEnabled(talker)
        if (!ConversationSwitches.setReplyEnabled(talker, !enabled)) {
            showToast(context, "开关未保存，请重进聊天页重试")
            return
        }
        if (enabled) {
            // 只清这个聊天的卡；情绪分析开关还开着的话，refresh 会按只显示分析重建
            BubbleDecorator.clearByTalker(talker)
            MessageSniffer.refresh()
            showToast(context, "已关闭本会话自动帮我回")
        } else {
            MessageSniffer.refresh()
            showToast(context, "已开启本会话自动帮我回")
        }
    }

    private fun openSettings(context: android.content.Context) {
        val activity = context as? ComponentActivity ?: return
        AutoReplyEntry.onClick(activity)
    }
}
