package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmClient
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRequest
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelEntity
import dev.sun.wechat.agent.model.ModelProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 传输层：用 WeKit 用户自配的模型（ModelProviderManager）完成两轮分析。
 * Round1 得 ChatProfile，Round2 得精选卡 + 动作，最终返回 Mood。
 */
object MoodTransport {

    suspend fun analyze(input: AnalysisInput, shouldContinue: suspend () -> Boolean = { true }): Mood {
        val modelId = WeAgentRepository.firstModelId() ?: error("未配置 AI 模型，请先在 WeAgent 设置")
        val model = WeAgentRepository.getModel(modelId) ?: error("模型不可用")
        val provider = WeAgentRepository.getModelProvider(model.providerId) ?: error("模型渠道不可用")
        val client = ModelProviderManager.clientFor(provider)

        val profile = MoodProtocol.parseProfile(chat(client, model, MoodProtocol.profileMessages(input)))
        if (!shouldContinue()) throw kotlinx.coroutines.CancellationException("分析已停止")
        val cards = ChatTemplates.candidates(profile)
        val actions = ChatActions.candidates(profile)
        if (cards.isEmpty() && actions.isEmpty()) return MoodProtocol.fallback(profile)
        val detail = MoodProtocol.parseDetail(
            chat(client, model, MoodProtocol.detailMessages(input, profile)),
            profile,
        )
        return detail
    }

    private suspend fun chat(
        client: LlmClient,
        model: ModelEntity,
        messages: List<LlmMessage>,
    ): String = withContext(Dispatchers.IO) {
        val request = ModelProviderManager.buildRequest(model, messages, emptyList(), stream = true)
        val sb = StringBuilder()
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> sb.append(event.text)
                is LlmStreamEvent.Completed -> if (sb.isEmpty()) event.message.content?.let { sb.append(it) }
                is LlmStreamEvent.Failed -> throw event.error
                else -> {}
            }
        }
        sb.toString().trim()
    }
}