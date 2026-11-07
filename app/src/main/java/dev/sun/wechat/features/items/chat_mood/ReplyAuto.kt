package dev.sun.wechat.features.items.chat_mood

import android.os.Handler
import android.os.Looper
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
 * 对方新消息到达时生成，打开会话开关时也会对最近一条历史消息补触发（见 MessageSniffer.refresh）；
 * 结果按消息 key 缓存，由 BubbleDecorator 画进消息下方卡片；
 * 点击某条建议只会把它填入输入框，绝不自动发送。
 * 失败/重复请求不会自动重放，只把错误显示在卡片里。
 */
object ReplyAuto {

    private const val TAG = "ReplyAuto"
    private const val LIMIT_KEY = "mood_reply_limit"
    private const val MAX_CACHE = 120

    /** 撞上限流后，自动回复整体歇一阵再说话，免得一封一封往刀口上撞。 */
    private const val RATE_PAUSE_MS = 90_000L

    /** 上游内容安全拦截不是抖动，是上游在盯：自动回复歇一分钟，等风头过去。 */
    private const val HIGH_RISK_PAUSE_MS = 60_000L

    /** 设置项：对方新消息到达时自动生成可选回复。 */
    var enabled by KvStore.prefOption("mood_reply_auto", false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobs = HashMap<String, Job>()
    private val pending = HashSet<String>()
    private val results = LinkedHashMap<String, ReplySuggestion>()
    private val failures = HashMap<String, String>()
    private val talkers = HashMap<String, String>()
    private val latestByTalker = HashMap<String, String>()

    @Volatile
    private var pausedUntil = 0L

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
        val now = System.currentTimeMillis()
        if (now < pausedUntil) {
            WeLogger.d(TAG, "rate pause active, skip auto reply (${(pausedUntil - now) / 1000}s left)")
            return
        }
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
            notifyRefresh()
            try {
                delay(600) // 等这一批消息都绑定完，再读历史
                val limit = KvStore.getStringOrDef(LIMIT_KEY, "50").toIntOrNull() ?: 50
                val page = withContext(Dispatchers.IO) { ReplyHistory.load(talker, limit) }
                if (page.messages.isEmpty()) throw IllegalStateException("没读到文字消息")
                val identity = ReplyStorage.load(talker)
                // 与手动「帮我回」同一套指纹：会话、末条消息、条数、身份、背景、方向。
                // 自动与手动共用一本落盘账，谁先算过，另一方直接命中，不再向模型要第二遍。
                val fingerprint = ReplyCache.fingerprint(
                    talker = talker,
                    lastText = page.messages.last().text,
                    count = page.messages.size,
                    relationship = identity.relationship.id,
                    custom = identity.custom,
                    background = identity.background,
                    direction = "",
                )
                val cached = withContext(Dispatchers.IO) { ReplyCache.load(talker, fingerprint) }
                if (cached != null) {
                    results[key] = cached
                    trimCache()
                    return@launch
                }
                val prompts = ReplyProtocol.buildReplyPrompts(
                    messages = page.messages,
                    draft = "",
                    direction = "",
                    knowledge = buildKnowledge(identity.background),
                    relationship = identity.relationship,
                    customRelationship = identity.custom,
                    previousReplies = previousRepliesOf(talker),
                )
                val suggestion = ReplyHttpClient.generateReply(prompts.system, prompts.user)
                results[key] = suggestion
                withContext(Dispatchers.IO) { ReplyCache.save(talker, fingerprint, suggestion, page.displayName) }
                trimCache()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "auto reply failed", e)
                val shown = e.message ?: "生成失败"
                failures[key] = shown
                if (shown.startsWith(ReplyHttpClient.RATE_LIMITED_MESSAGE)) {
                    pausedUntil = System.currentTimeMillis() + RATE_PAUSE_MS
                    WeLogger.w(TAG, "rate limited, pause auto reply ${RATE_PAUSE_MS / 1000}s")
                }
                // 冷却期的报错话术（「上游内容安全拦截，冷却中…」）与直出话术都含这五个字，一并接住
                if (shown.contains("内容安全拦截")) {
                    pausedUntil = System.currentTimeMillis() + HIGH_RISK_PAUSE_MS
                    WeLogger.w(TAG, "high-risk rejection, pause auto reply ${HIGH_RISK_PAUSE_MS / 1000}s")
                }
            } finally {
                pending -= key
                notifyRefresh()
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
        notifyRefresh()
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

    /**
     * 收集同一会话最近生成过的回复（按生成先后，去重，最多留 6 条），
     * 喂给提示词提醒模型换角度，避免连续几条消息的卡片建议一模一样。
     */
    private fun previousRepliesOf(talker: String): List<String> {
        val seen = LinkedHashSet<String>()
        results.entries.forEach { (key, suggestion) ->
            if (talkers[key] == talker) suggestion.parts.forEach { seen += it }
        }
        return seen.toList().takeLast(6)
    }

    private fun trimCache() {
        if (results.size <= MAX_CACHE) return
        val removable = results.keys.take(results.size - MAX_CACHE)
            .filter { it !in pending }
        removable.forEach { key ->
            results.remove(key)
            failures.remove(key)
        }
    }

    private fun notifyRefresh() {
        // 刷新一律投递到下一轮主线程消息循环：request/cancel 可能发生在消息列表布局遍历中
        // （onMessageViewAttached 内联路径），原地刷新会在遍历中途改卡片 View 树（chips.removeAllViews），
        // 触发系统 NPE（ViewGroup.removeAllViewsInLayout unFocus null child）。
        mainHandler.post { onRefresh?.invoke() }
    }
}
