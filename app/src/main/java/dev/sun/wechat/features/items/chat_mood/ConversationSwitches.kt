package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import java.security.MessageDigest

/**
 * 按会话的记忆开关（1:1 移植自 Yanwai ConversationSwitches）：
 * 只存显式打开过的会话；读取走进程内快照，不做 IPC。
 * key 用 talker 的 SHA-256，避免 KvStore 里出现明文会话名。
 *
 * 两套互不影响的开关：
 *  - 情绪分析（[isEnabled] / [setEnabled]）
 *  - 自动帮我回（[isReplyEnabled] / [setReplyEnabled]），不依赖情绪分析是否开启
 */
object ConversationSwitches {
    private const val TAG = "ConversationSwitches"
    private const val KEY_ENABLED_CHATS = "mood_enabled_chats"
    private const val KEY_REPLY_CHATS = "mood_reply_enabled_chats"

    @Volatile
    private var enabledKeys: Set<String> = KvStore.getStringSetOrDef(KEY_ENABLED_CHATS, emptySet())

    @Volatile
    private var replyEnabledKeys: Set<String> = KvStore.getStringSetOrDef(KEY_REPLY_CHATS, emptySet())

    fun isEnabled(talker: String?): Boolean =
        storageKey(talker)?.let { it in enabledKeys } == true

    fun isReplyEnabled(talker: String?): Boolean =
        storageKey(talker)?.let { it in replyEnabledKeys } == true

    @Synchronized
    fun setEnabled(talker: String?, enabled: Boolean): Boolean {
        val key = storageKey(talker) ?: return false
        val next = if (enabled) enabledKeys + key else enabledKeys - key
        if (next == enabledKeys) return true
        if (!persist(KEY_ENABLED_CHATS, next)) return false
        enabledKeys = next
        return true
    }

    @Synchronized
    fun setReplyEnabled(talker: String?, enabled: Boolean): Boolean {
        val key = storageKey(talker) ?: return false
        val next = if (enabled) replyEnabledKeys + key else replyEnabledKeys - key
        if (next == replyEnabledKeys) return true
        if (!persist(KEY_REPLY_CHATS, next)) return false
        replyEnabledKeys = next
        return true
    }

    /** 关掉所有聊天的自动帮我回（设置页「全部关闭」用）。 */
    @Synchronized
    fun clearReplyEnabled(): Boolean {
        if (replyEnabledKeys.isEmpty()) return true
        if (!persist(KEY_REPLY_CHATS, emptySet())) return false
        replyEnabledKeys = emptySet()
        return true
    }

    private fun persist(key: String, values: Set<String>): Boolean = runCatching {
        KvStore.putStringSet(key, values)
        true
    }.onFailure {
        WeLogger.e(TAG, "conversation switch persist failed", it)
    }.getOrDefault(false)

    private fun storageKey(talker: String?): String? {
        if (talker.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest(talker.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
