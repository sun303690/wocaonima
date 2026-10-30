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

    /** 生成回复建议 */
    suspend fun generateReply(system: String, user: String): ReplySuggestion = withContext(Dispatchers.IO) {
        val text = complete(system, user)
        if (text.isBlank()) throw IllegalStateException("模型没有返回内容，请换个模型试试")
        try {
            ReplyProtocol.parseReplyText(text)
        } catch (e: Exception) {
            WeLogger.e(TAG, "parse reply failed: $text", e)
            throw IllegalStateException("模型输出格式不对，请重试或换个模型")
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
            throw IllegalStateException("模型输出格式不对，请重试或换个模型")
        }
    }

    private suspend fun complete(system: String, user: String): String {
        val modelId = WeAgentRepository.resolvedDefaultModelId()
            ?: throw IllegalStateException("请先在 WeAgent 设置里添加一个模型")
        return try {
            AgentLlm.completeWithPrompts(modelId, system, user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.e(TAG, "model call failed", e)
            throw IllegalStateException(friendly(e), e)
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
            code == 429 -> "请求过于频繁，请稍后重试"
            code != null -> "模型服务暂不可用（HTTP $code）"
            message.isBlank() -> "模型调用失败，请重试"
            else -> message
        }
    }
}
