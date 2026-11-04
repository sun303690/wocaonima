package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 回复模型调用：走 WeAgent 已配置的模型路由（[AgentLlm]），
 * 支持 OpenAI Chat Completions / Responses / Anthropic / Gemini 等服务商，不再依赖手写 URL。
 */
object ReplyHttpClient {
    private val TAG = "ReplyHttp"

    /** 上游限流时的统一话术；上层据此判定是否需要冷却。 */
    const val RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后重试"

    /** 被限流后全局静默的时间，防止连珠炮把孩子送进封禁窗口。 */
    private const val RATE_COOLDOWN_MS = 30_000L

    @Volatile
    private var cooldownUntil = 0L

    /** 生成回复建议 */
    suspend fun generateReply(system: String, user: String): ReplySuggestion = withContext(Dispatchers.IO) {
        val text = complete(system, user)
        if (text.isBlank()) throw IllegalStateException("模型没有返回内容，请换个模型试试")
        try {
            ReplyProtocol.parseReplyText(text)
        } catch (e: Exception) {
            WeLogger.e(TAG, "parse reply failed: $text", e)
            throw IllegalStateException(nonJsonHint(text) ?: "模型输出格式不对，请重试或换个模型", e)
        }
    }

    /** 找话题 */
    suspend fun findTopics(system: String, user: String): List<ReplyProtocol.TopicSuggestion> = withContext(Dispatchers.IO) {
        val text = complete(system, user)
        if (text.isBlank()) throw IllegalStateException("模型没有返回内容，请换个模型试试")
        try {
            ReplyProtocol.parseTopicsText(text)
        } catch (e: Exception) {
            WeLogger.e(TAG, "parse topics failed: $text", e)
            throw IllegalStateException(nonJsonHint(text) ?: "模型输出格式不对，请重试或换个模型", e)
        }
    }

    private suspend fun complete(system: String, user: String): String {
        val modelId = WeAgentRepository.resolvedDefaultModelId()
            ?: throw IllegalStateException("请先在 WeAgent 设置里添加一个模型")
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            val left = (cooldownUntil - now) / 1000 + 1
            throw IllegalStateException("$RATE_LIMITED_MESSAGE（还需 ${left} 秒）")
        }
        return try {
            AgentLlm.completeWithPrompts(modelId, system, user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.e(TAG, "model call failed", e)
            val shown = friendly(e)
            if (shown.startsWith(RATE_LIMITED_MESSAGE)) {
                cooldownUntil = System.currentTimeMillis() + RATE_COOLDOWN_MS
                WeLogger.w(TAG, "rate limited, cool down ${RATE_COOLDOWN_MS / 1000}s")
            }
            throw IllegalStateException(shown, e)
        }
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
