package dev.sun.wechat.features.items.chat_mood

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.ui.WeChatInputBarApi
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「帮我回 · 找话题」的聊天上下文读取（对应微话助手 ReplyDatabaseHistory + ChatContext）。
 *
 * 直接走 WeDatabaseApi 读当前账号的 message 表：
 * 只取最近 [limit] 条，按时间正序交给模型；非文字消息跳过；群聊剥掉 "wxid:\n" 前缀。
 * 草稿通过 WeChatInputBarApi 监听输入栏文本获得，只作为上下文，不写回输入框。
 */
object ReplyHistory {

    private const val TAG = "ReplyHistory"

    @Volatile
    private var draft: String = ""

    private val subscribed = AtomicBoolean(false)

    private val listener = object : WeChatInputBarApi.IInputBarListener {
        override fun onTextChanged(chatFooter: ChatFooter, text: String) {
            draft = text
        }
    }

    fun ensureSubscribed() {
        if (subscribed.compareAndSet(false, true)) {
            runCatching { WeChatInputBarApi.addListener(listener) }
                .onFailure { WeLogger.e(TAG, "subscribe input bar failed", it) }
        }
    }

    data class Page(
        val talker: String,
        val displayName: String,
        val messages: List<ContextMessage>,
        val draft: String,
    )

    suspend fun load(talker: String, limit: Int): Page = withContext(Dispatchers.IO) {
        val rows = WeDatabaseApi.getMessages(talker, 1, limit).reversed()
        val displayName = runCatching { WeDatabaseApi.getDisplayName(talker) }
            .getOrDefault("")
            .ifBlank { talker }
        val isGroup = talker.endsWith("@chatroom")
        val messages = rows.mapNotNull { message ->
            if (message.type?.isText != true) return@mapNotNull null
            var text = message.content
            if (isGroup && message.isSend == 0) {
                val index = text.indexOf(":\n")
                if (index in 1..64) text = text.substring(index + 2)
            }
            text = text.trim()
            if (text.isEmpty()) return@mapNotNull null
            ContextMessage(
                speaker = if (message.isSend == 1) "我" else displayName,
                text = text.take(500),
            )
        }
        Page(talker, displayName, messages, draft.trim().take(8000))
    }
}
