package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.VisionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 三段解读（意图解析 / 可能在意 / 情绪倾向）统一走「多模态」设置里的文本模型：
 * 用户在 多模态 里填好地址 / Key / 模型即可，不再单独维护 WeAgent 模型。
 * 情绪概率判定仍走 JEV 协议；这一层只负责产出自然语言解读，失败时卡片退回只显示概率。
 */
object AgentLlm {

    /** 多模态配置里是否已有可用的文本模型。 */
    suspend fun isReady(): Boolean = MultimodalEngine.textUsable()

    /** 单轮对话补全，返回完整文本。未配置 / 调用失败时抛异常，由调用方降级。 */
    suspend fun complete(prompt: String): String = completeWith(prompt = prompt)

    /** 兼容旧签名：modelId 已不再使用，模型统一取多模态配置。 */
    suspend fun completeWith(
        modelId: String = "",
        prompt: String,
        system: String = "",
    ): String = completeWithPrompts(modelId, system.takeIf { it.isNotBlank() }, prompt)

    /** 带系统提示的单轮补全；system 为 null/空时只发用户消息。modelId 仅为兼容保留。 */
    suspend fun completeWithPrompts(modelId: String = "", system: String?, user: String): String {
        if (!MultimodalEngine.textUsable()) {
            throw IllegalStateException("还没配文本模型，请先在 多模态 设置里填好地址、Key 和模型")
        }
        return withContext(Dispatchers.IO) {
            VisionClient.completeText(system = system, user = user, maxTokens = 1024).trim()
        }
    }
}
