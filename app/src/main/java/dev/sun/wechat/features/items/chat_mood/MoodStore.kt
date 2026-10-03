package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 分析结果。
 *
 * [label] 是给界面看的短标签（比如「开心」「生气」「敷衍」），
 * [score] 是情绪强度 -1.0（负面）到 1.0（正面），[raw] 留着排查模型返回。
 */
data class Mood(
    val label: String,
    val score: Double,
    val risk: Int,
    val raw: String,
    val detail: String = label,
    /** 「智能分析」得到的自然语言解读；模型没配或没跑时为空。 */
    val reading: MoodReading = MoodReading.EMPTY,
)

/**
 * 情绪之外的言外之意，对应官方卡片的「意图解析 / 可能在意 / 情绪倾向」三段。
 * 全部为自由文本，解析失败时整块留空，卡片退回只显示概率。
 */
data class MoodReading(
    val intent: String = "",
    val concern: String = "",
    val tone: String = "",
    val confidence: Double = 0.0,
) {
    val isEmpty: Boolean get() = intent.isBlank() && concern.isBlank() && tone.isBlank()

    companion object { val EMPTY = MoodReading() }
}

/**
 * 分析结果缓存。
 *
 * 三条约束决定了它的形状：
 * 1. 同一条消息及上下文不能重复请求模型 —— 用会话、消息身份和完整输入做键。
 * 2. 界面线程要能**立刻**拿到结果，不能等网络 —— 所以是「先占位、后填充」，
 *    装饰器拿到 null 就先不画，异步补上再通知刷新。
 * 3. 微信进程常被系统回收（切后台/内存紧张），纯内存缓存一丢，重进聊天页就要重新分析、
 *    重新花两次模型调用。所以完成的结果会**同步写进 KvStore**：进程重启后重进，
 *    [get] 命中磁盘缓存直接返回，不再请求模型（失败/超长结果不落盘，宁可重算）。
 */
object MoodStore {

    private const val TAG = "MoodStore"
    /** KvStore 里承载缓存 key 索引的集合 key。 */
    private const val KEY_INDEX = "mood_cache_keys"
    /** 每个缓存条目在 KvStore 里的前缀。 */
    private const val VALUE_PREFIX = "mood_cache_"
    /** 磁盘缓存条数上限：微信每个会话的历史都可能攒很多，超限按最旧淘汰。 */
    private const val MAX_PERSISTED = 300

    private val cache = ConcurrentHashMap<String, Mood>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Length-prefix every field so different contexts or message identities never share a result. */
    fun keyOf(text: String, talker: String?, context: List<ContextMessage> = emptyList(),
        messageId: Long = 0, speaker: String = "对方"): String {
        val source = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(talker.orEmpty())
            field(messageId.toString())
            field(speaker)
            field(text)
            // 上下文只在没有消息身份时参与键（面板/手动触发）。
            // 聊天消息用 talker + createTime 已能唯一定位，再混入上下文会让每次列表重绑定
            // 都算出新键 → 卡片永远读不到结果停在「正在分析」，且每条消息被反复提交模型。
            if (messageId == 0L) context.forEach { field(it.speaker); field(it.text) }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): Mood? {
        cache[key]?.let { return it }
        // 磁盘命中 → 回填内存，避免每次 get 都读 KV。
        val restored = loadPersisted(key)
        if (restored != null) cache[key] = restored
        return restored
    }

    /** 是否已认领但还没结果：界面据此区分「正在分析」和「从未分析」。 */
    fun isPending(key: String): Boolean = pending.contains(key)

    /** 尝试认领一次分析任务；已经在跑或已完成返回 false。 */
    fun claim(key: String): Boolean {
        if (cache.containsKey(key) || loadPersisted(key) != null) return false
        return pending.add(key)
    }

    fun complete(key: String, mood: Mood) {
        cache[key] = mood
        pending.remove(key)
        persist(key, mood)
    }

    /** 失败也要释放认领，否则这条消息永远不会重试。 */
    fun release(key: String) {
        pending.remove(key)
    }

    fun size(): Int = cache.size

    fun clear() {
        cache.clear()
        pending.clear()
        // 清空磁盘缓存索引与所有条目。
        val keys = KvStore.getStringSetOrDef(KEY_INDEX, emptySet()).toSet()
        keys.forEach { KvStore.remove(VALUE_PREFIX + it) }
        KvStore.putStringSet(KEY_INDEX, emptySet())
    }

    // ---- 磁盘持久化 ----

    private fun persist(key: String, mood: Mood) {
        // 内容太长的结果不落盘：restore 时也读不回，落盘纯属浪费 KV 空间。
        val json = encode(mood) ?: return
        val index = KvStore.getStringSetOrDef(KEY_INDEX, emptySet()).toMutableSet()
        val evicted = mutableListOf<String>()
        if (!index.contains(key)) {
            index.add(key)
            while (index.size > MAX_PERSISTED) {
                val oldest = index.iterator().next()
                index.remove(oldest)
                evicted += oldest
            }
        }
        runCatching {
            KvStore.putString(VALUE_PREFIX + key, json)
            KvStore.putStringSet(KEY_INDEX, index)
            evicted.forEach { KvStore.remove(VALUE_PREFIX + it) }
        }.onFailure { WeLogger.w(TAG, "persist failed", it) }
    }

    private fun loadPersisted(key: String): Mood? {
        val json = runCatching { KvStore.getString(VALUE_PREFIX + key) }.getOrNull() ?: return null
        return decode(json)
    }

    private fun encode(mood: Mood): String? {
        // 摘要原始串可能很长，拖慢启动恢复，跳过超长的。
        if (mood.raw.length > 2000 || mood.reading.intent.length > 1000 ||
            mood.reading.concern.length > 1000 || mood.reading.tone.length > 1000
        ) return null
        val reading = mood.reading
        // 规整格式：5 个长度前缀字符串在前，3 个数字在后（'|' 分隔），解析无歧义、纯 JVM 可跑。
        return buildString {
            fun s(value: String) { append(value.length).append(':').append(value) }
            s(mood.label)
            s(mood.detail)
            s(reading.intent)
            s(reading.concern)
            s(reading.tone)
            append('|').append(mood.score)
            append('|').append(mood.risk)
            append('|').append(reading.confidence)
        }
    }

    private fun decode(json: String): Mood? {
        return runCatching {
            var pos = 0
            fun s(): String {
                val colon = json.indexOf(':', pos)
                if (colon < 0) return ""
                val len = json.substring(pos, colon).toIntOrNull() ?: return ""
                val start = colon + 1
                val end = (start + len).coerceAtMost(json.length)
                val value = json.substring(start, end)
                pos = end
                return value
            }
            val label = s()
            val detail = s()
            val intent = s()
            val concern = s()
            val tone = s()
            val tail = json.substring(pos).trimStart('|')
            val nums = tail.split('|')
            val score = nums.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val risk = nums.getOrNull(1)?.toIntOrNull() ?: 0
            val confidence = nums.getOrNull(2)?.toDoubleOrNull() ?: 0.0

            if (label.isBlank() && detail.isBlank() && score == 0.0) return@runCatching null
            Mood(
                label = label,
                score = score,
                risk = risk,
                raw = label,
                detail = detail,
                reading = MoodReading(intent, concern, tone, confidence),
            )
        }.getOrNull()
    }
}
