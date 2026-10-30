package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.content.Context
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.ui.utils.VectorPathDrawable
import dev.sun.wechat.utils.android.showToast

/**
 * 「帮我回 · 找话题」入口（移植自微话助手 ReplyHostUi 的两个入口）。
 *
 * - 输入栏 ＋/长按菜单：对当前会话打开面板；
 * - 长按某条文字消息：同样打开面板，上下文默认取该会话最近的聊天。
 * 只弹面板、只生成建议；不会自动发送，也不改输入框。
 */
object ReplyEntry :
    WeChatInputBarMenuApi.IActionItemsProvider,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val REPLY_MENU_ID = 777101
    private const val TOPIC_MENU_ID = 777102

    private fun open(context: Context, talker: String) {
        val activity = context as? Activity
        if (activity == null) {
            showToast(context, "请在微信聊天页里使用")
            return
        }
        ReplyPanel.open(activity, talker)
    }

    // ===== 输入栏 ＋ 菜单 =====
    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "mood_reply_panel",
            icon = MaterialSymbols.Outlined.Auto_awesome,
            label = "帮我回 · 找话题",
            isSupported = { _, _ -> true },
            onClick = { context, _ -> open(context, WeCurrentConversationApi.value) },
        ),
    )

    // ===== 长按消息菜单 =====
    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = REPLY_MENU_ID,
            text = "帮我回",
            drawable = ReplyEntryIcon,
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = { message -> message.type?.isText == true },
        ) { _, ctx, message ->
            open(ctx.activity, message.talker)
        },
        WeChatMessageContextMenuApi.MenuItem(
            id = TOPIC_MENU_ID,
            text = "找话题",
            drawable = ReplyEntryIcon,
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = { message -> message.type?.isText == true },
        ) { _, ctx, message ->
            open(ctx.activity, message.talker)
        },
    )
}

private object ReplyEntryIcon : VectorPathDrawable(
    "M19,8l-4,4h3c0,3.31 -2.69,6 -6,6c-1.01,0 -1.97,-0.25 -2.8,-0.7l-1.46,1.46C8.97,19.54 10.43,20 12,20c4.42,0 8,-3.58 8,-8h3L19,8zM6,12c0,-3.31 2.69,-6 6,-6c1.01,0 1.97,0.25 2.8,0.7l1.46,-1.46C15.03,4.46 13.57,4 12,4c-4.42,0 -8,3.58 -8,8H1l4,4l4,-4H6z"
)
