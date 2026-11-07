package dev.sun.wechat.features.items.chat

/**
 * 语音转写文本的运行时缓存（消息 id → 转写文本）。
 *
 * 微信的语音转写文本只活在内存 bean 里（field_transText），不落数据库；
 * 消息行绑定（MessageSniffer）时顺手记一笔，AI 回复（AiChat）被语音消息
 * 触发时按消息 id 取用——这样自动回复就能读对方语音转写后的文本。
 */
object VoiceTranscriptStore {

    private const val MAX_ENTRIES = 200

    private val store = LinkedHashMap<Long, String>(16, 0.75f, true)
    private val lock = Any()

    fun put(msgId: Long, transcript: String) {
        if (msgId <= 0) return
        val text = transcript.trim()
        if (text.isEmpty()) return
        synchronized(lock) {
            store[msgId] = text
            while (store.size > MAX_ENTRIES) {
                val eldest = store.keys.firstOrNull() ?: break
                store.remove(eldest)
            }
        }
    }

    fun get(msgId: Long): String? = synchronized(lock) {
        store[msgId]?.takeIf { it.isNotBlank() }
    }
}
