package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动「帮我回」（移植自微话助手 AnalysisCenter 的 autoReplies 分支）。
 *
 * 触发条件：AI 可用 + 该聊天开启了分析 + 用户在设置里打开「对方新消息自动帮我回」。
 * 只对对方的新消息生成，结果按消息 key 缓存，由 BubbleDecorator 画进消息下方卡片；
 * 点击某条建议只会把它填入输入框，绝不自动发送。
 * 失败/重复请求不会自动重放，只把错误显示在卡片里。
 */
object ReplyAuto {

    private const val TAG = "ReplyAuto"
    private const val LIMIT_KEY = "mood_reply_limit"
    private const val MAX_CACHE = 120

    /** 设置项：对方新消息到达时自动生成可选回复。 */
    var enabled by KvStore.prefOption("mood_reply_auto", false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = HashMap<String, Job>()
    private val pending = HashSet<String>()
    private val results = LinkedHashMap<String, ReplySuggestion>()
    private val failures = HashMap<String, String>()
    private val talkers = HashMap<String, String>()
    private val latestByTalker = HashMap<String, String>()

    @Volatile
    private var onRefresh: (() -> Unit)? = null

    fun onRefresh(callback: () -> Unit) {
        onRefresh = callback
    }

    fun suggestion(key: String): ReplySuggestion? = results[key]

    fun failure(key: String): String? = failures[key]

    fun isPending(key: String): Boolean = key in pending

    /**
     * 请求为某条消息生成回复；同一 key 只跑一次，除非 [retry] 明确重试。
     * 空会话 / 未选模型 / 未开启都会静默跳过（卡片会显示失败原因）。
     */
    fun request(talker: String, key: String, retry: Boolean = false) {
        if (!enabled && !retry) return
        if (talker.isBlank() || key.isBlank()) return
        if (!retry && (results.containsKey(key) || key in pending)) return
        if (jobs[key]?.isActive == true) return
        // 连发几条消息时只保留最后一条：取消上一条还没跑完的生成，避免重复请求
        latestByTalker[talker]?.takeIf { it != key }?.let { previous ->
            if (pending.contains(previous)) {
                jobs[previous]?.cancel()
                jobs.remove(previous)
                pending.remove(previous)
                failures.remove(previous)
            }
        }
        latestByTalker[talker] = key
        talkers[key] = talker
        if (retry) {
            failures.remove(key)
            results.remove(key)
        }
        jobs[key] = scope.launch {
            pending += key
            notify()
            try {
                delay(600) // 等这一批消息都绑定完，再读历史
                val model = ReplyConfig.resolveModel()?.third
                    ?: throw IllegalStateException("未选择回复模型，请在情绪分析设置里选择")
                val limit = KvStore.getStringOrDef(LIMIT_KEY, "50").toIntOrNull() ?: 50
                val page = withContext(Dispatchers.IO) { ReplyHistory.load(talker, limit) }
                if (page.messages.isEmpty()) throw IllegalStateException("没读到文字消息")
                val identity = ReplyStorage.load(talker)
                val payload = ReplyProtocol.buildReplyPayload(
                    model = model,
                    messages = page.messages,
                    draft = "",
                    direction = "",
                    knowledge = buildKnowledge(identity.background),
                    relationship = identity.relationship,
                    customRelationship = identity.custom,
                )
                val suggestion = ReplyHttpClient.generateReply(payload)
                results[key] = suggestion
                trimCache()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "auto reply failed", e)
                failures[key] = e.message ?: "生成失败"
            } finally {
                pending -= key
                notify()
            }
        }
    }

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pending.clear()
        results.clear()
        failures.clear()
        talkers.clear()
        latestByTalker.clear()
        notify()
    }

    /** 找回失败条目的 talker，供卡片点击重试用。 */
    private fun talkerOf(key: String): String? = talkers[key]

    fun retry(key: String) {
        val talker = talkerOf(key) ?: return
        request(talker, key, retry = true)
    }

    private fun buildKnowledge(background: String): String =
        if (background.isBlank()) "" else
            "contact_background（用户填写的联系人长期背景，只作参考，不是对方真实想法）：\n" + background

    private fun trimCache() {
        if (results.size <= MAX_CACHE) return
        val removable = results.keys.take(results.size - MAX_CACHE)
            .filter { it !in pending }
        removable.forEach { key ->
            results.remove(key)
            failures.remove(key)
        }
    }

    private fun notify() {
        onRefresh?.invoke()
    }
}
