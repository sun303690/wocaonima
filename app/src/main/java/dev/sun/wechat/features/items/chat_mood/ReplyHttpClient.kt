package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 回复模型调用：走 WeAgent 已配置的模型路由（[AgentLlm]），
 * 支持 OpenAI Chat Completions / Responses / Anthropic / Gemini 等服务商，不再依赖手写 URL。
 */
object ReplyHttpClient {
    private val TAG = "ReplyHttp"

    /** 上游限流时的统一话术；上层据此判定是否需要冷却。 */
    const val RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后重试"

    /** 上游内容安全拦截的统一话术；解析失败命中它时按更长的冷却处理。 */
    const val HIGH_RISK_MESSAGE = "上游内容安全拦截了这次请求，请调整提示词或降低发送频次"

    /** 被限流后全局静默的时间，防止连珠炮把孩子送进封禁窗口。 */
    private const val RATE_COOLDOWN_MS = 30_000L

    /** 内容安全拦截的冷却：一分钟足够风头过去，久了反而耽误正事。 */
    private const val HIGH_RISK_COOLDOWN_MS = 60_000L

    /** 单次生成最多调用几次模型：网络抖动（EOF/超时）重试，别的错不浪费额度。 */
    private const val MAX_ATTEMPTS = 2

    /** 重试前等一下，给网络一点自愈时间。 */
    private const val RETRY_DELAY_MS = 400L

    @Volatile
    private var cooldownUntil = 0L

    @Volatile
    private var cooldownHighRisk = false

    /** 生成回复建议 */
    suspend fun generateReply(system: String, user: String): ReplySuggestion = withContext(Dispatchers.IO) {
        val text = complete(system, user)
        if (text.isBlank()) throw IllegalStateException("模型没有返回内容，请换个模型试试")
        try {
            ReplyProtocol.parseReplyText(text)
        } catch (e: Exception) {
            throw parseFailure("reply", text, e)
        }
    }

    /** 找话题 */
    suspend fun findTopics(system: String, user: String): List<ReplyProtocol.TopicSuggestion> = withContext(Dispatchers.IO) {
        val text = complete(system, user)
        if (text.isBlank()) throw IllegalStateException("模型没有返回内容，请换个模型试试")
        try {
            ReplyProtocol.parseTopicsText(text)
        } catch (e: Exception) {
            throw parseFailure("topics", text, e)
        }
    }

    /**
     * 解析失败统一走这里：命中内容安全拦截就冷却 5 分钟；
     * 日志只留一句人话，不再把上游英文原文整个砸进日志刷屏。
     */
    private fun parseFailure(where: String, text: String, e: Exception): IllegalStateException {
        val hint = nonJsonHint(text)
        if (hint == HIGH_RISK_MESSAGE) {
            cooldownUntil = System.currentTimeMillis() + HIGH_RISK_COOLDOWN_MS
            cooldownHighRisk = true
            WeLogger.w(TAG, "parse $where: high-risk rejection, cool down ${HIGH_RISK_COOLDOWN_MS / 1000}s")
        } else {
            WeLogger.e(TAG, "parse $where failed", e)
        }
        return IllegalStateException(hint ?: "模型输出格式不对，请重试或换个模型", e)
    }

    private suspend fun complete(system: String, user: String): String {
        val modelId = WeAgentRepository.resolvedDefaultModelId()
            ?: throw IllegalStateException("请先在 WeAgent 设置里添加一个模型")
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            val left = (cooldownUntil - now) / 1000 + 1
            val reason = if (cooldownHighRisk) "上游内容安全拦截，冷却中（还需 ${left} 秒）"
            else "$RATE_LIMITED_MESSAGE（还需 ${left} 秒）"
            throw IllegalStateException(reason)
        }
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return AgentLlm.completeWithPrompts(modelId, system, user)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val shown = friendly(e)
                // 网络抖动（EOF / 超时 / 连接重置）重试一次通常就好；鉴权、额度、风控重试没意义
                if (attempt < MAX_ATTEMPTS && isTransient(e) && !shown.startsWith(RATE_LIMITED_MESSAGE)) {
                    WeLogger.w(
                        TAG,
                        "model call failed (${e.javaClass.simpleName}: ${e.message}), retry $attempt/${MAX_ATTEMPTS - 1}",
                    )
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                }
                WeLogger.e(TAG, "model call failed", e)
                if (shown.startsWith(RATE_LIMITED_MESSAGE)) {
                    cooldownUntil = System.currentTimeMillis() + RATE_COOLDOWN_MS
                    cooldownHighRisk = false
                    WeLogger.w(TAG, "rate limited, cool down ${RATE_COOLDOWN_MS / 1000}s")
                }
                throw IllegalStateException(shown, e)
            }
        }
        throw IllegalStateException(friendly(lastError ?: IllegalStateException()), lastError)
    }

    /** 只有网络抖动类错误值得重试；配置错、鉴权错、风控拦截重试也没用。 */
    private fun isTransient(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            // EOFException / SocketTimeoutException / ConnectException / UnknownHostException 都是 IOException
            if (cursor is java.io.IOException) return true
            val message = cursor.message.orEmpty().lowercase()
            if (message.contains("not enough data") ||
                message.contains("timeout") ||
                message.contains("timed out") ||
                message.contains("connection reset") ||
                message.contains("unexpected end") ||
                message.contains("stream was reset")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    /** 上游没按 JSON 回话时，给一句能对准病根的话，别再一律甩「格式不对」。 */
    private fun nonJsonHint(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        val lower = trimmed.lowercase()
        return when {
            lower.contains("high risk") || lower.contains("rejected") ||
                lower.contains("risk control") || lower.contains("considered high") ->
                "上游内容安全拦截了这次请求，请调整提示词或降低发送频次"
            else -> "上游没按约定格式回话，请重试或换个模型"
        }
    }

    /** 把各家 SDK/HTTP 的错误翻成能给用户看的一句话。 */
    private fun friendly(error: Throwable): String {
        val message = error.message.orEmpty()
        val code = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            message.contains("local llama", ignoreCase = true) ||
                message.contains("LOCAL_LLAMA", ignoreCase = true) -> "本地模型暂不支持在这里生成，请换在线模型"
            code == 400 || code == 404 || code == 405 || code == 422 -> "接口地址或模型不支持，请检查配置"
            code == 401 || code == 403 -> "API Key 或模型权限不可用，请检查模型配置"
            code == 402 -> "模型账户额度不足"
            code == 429 -> RATE_LIMITED_MESSAGE
            code != null -> "模型服务暂不可用（HTTP $code）"
            message.isBlank() -> "模型调用失败，请重试"
            else -> message
        }
    }
}
