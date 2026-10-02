package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.utils.WeLogger

/**
 * 情绪分析在聊天输入栏（＋）面板的入口。
 * 对照言外 ReplyHostUi/HostUi：面板提供「分析」条目，点击切换当前会话的分析开关，
 * 长按打开设置。开关按会话（talker）本地记住，只作用于当前聊天页。
 */
object ChatMoodHostUi : WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "ChatMoodHostUi"

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "mood_analysis_toggle",
            icon = MaterialSymbols.Outlined.Auto_awesome,
            label = "情绪分析",
            isSupported = { _, _ -> MoodAnalyzer.enabled },
            onClick = { context, _ ->
                toggleForCurrentChat(context)
            },
            onLongClick = { context, _ ->
                openSettings(context)
            },
        ),
    )

    private fun toggleForCurrentChat(context: android.content.Context) {
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank()) {
            dev.sun.wechat.utils.android.showToast(context, "暂未识别当前聊天，请重进聊天页")
            return
        }
        val enabled = ConversationSwitches.isEnabled(talker)
        if (ConversationSwitches.setEnabled(talker, !enabled)) {
            if (!enabled) {
                // 打开：补触发一次分析
                MessageSniffer.refresh()
            } else {
                // 关闭：清当前会话所有气泡卡
                BubbleDecorator.clearAll()
            }
            dev.sun.wechat.utils.android.showToast(context, if (!enabled) "已开启本会话情绪分析" else "已关闭本会话情绪分析")
        }
    }

    private fun openSettings(context: android.content.Context) {
        runCatching {
            val comp = context as? ComponentActivity
                ?: (context as? Activity) as? ComponentActivity
                ?: return
            MoodFeature.onClick(comp)
        }.onFailure { WeLogger.e(TAG, "open settings failed", it) }
    }
}