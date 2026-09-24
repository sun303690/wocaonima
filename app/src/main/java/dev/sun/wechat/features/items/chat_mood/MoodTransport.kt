package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmClient
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRequest
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.data.entity.ModelEntity
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.preferences.WePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 传输层：用 WeKit 用户自配的模型（ModelProviderManager）完成两轮分析。
 * Round1 得 ChatProfile，Round2 得精选卡 + 动作，最终返回 Mood。
 */
object MoodTransport {

    /** 用户为情绪分析单独选的模型 id；空 = 用 WeAgent 默认模型。 */
    var modelId by WePrefs.prefOption("mood_model_id", "")

    // ---- Jev/TypeSafe 渠道（言外那套模型）----
    var jevEnabled by WePrefs.prefOption("mood_jev_enabled", false)
    var jevProviderId by WePrefs.prefOption("mood_jev_provider", "typesafe")
    var jevKey by WePrefs.prefOption("mood_jev_key", "")
    var jevEndpoint by WePrefs.prefOption("mood_jev_endpoint", ApiSettings.DEFAULT_ENDPOINT)
    var jevModel by WePrefs.prefOption("mood_jev_model", "")

    private val jevClient = JevHttpClient()

    /** 构造 Jev 渠道配置；未启用或配置非法时返回 null（回退标准模型路径）。 */
    private fun jevSettingsOrNull(): ApiSettings? = runCatching {
        if (!jevEnabled) return null
        ApiSettings.fromInput(jevEndpoint, jevKey, jevProviderId, jevModel)
    }.getOrNull()

    suspend fun analyze(input: AnalysisInput, shouldContinue: suspend () -> Boolean = { true }): Mood {
        // Jev/TypeSafe 协议路径（言外模型）
        jevSettingsOrNull()?.let { s ->
            val profile = JevProtocol.parseProfile(
                jevClient.exchange(JevProtocol.payload(input.text, s.model, input.context, input.speaker), s))
            if (!shouldContinue()) throw kotlinx.coroutines.CancellationException("分析已停止")
            val cards = ChatTemplates.candidates(profile)
            val actions = ChatActions.candidates(profile)
            if (cards.isEmpty() && actions.isEmpty()) return JevProtocol.fallback(profile)
            return JevProtocol.parseDetail(
                jevClient.exchange(JevProtocol.detailPayload(input, s.model, profile), s), profile)
        }

        // 标准模型路径（ModelProviderManager）
        val id = modelId.ifBlank {
            WeAgentRepository.firstModelId() ?: error("未配置 AI 模型，请先在 WeAgent 设置添加模型")
        }
        val model = WeAgentRepository.getModel(id) ?: error("模型不可用或已删除")
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