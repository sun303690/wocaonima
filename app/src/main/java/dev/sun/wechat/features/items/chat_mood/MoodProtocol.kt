package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 把 Yanwai 的「Jev 原生 choice 协议」改写成标准 chat prompt + 简单 JSON 解析，
 * 以便用 WeKit 用户自配的任意模型（DeepSeek/GPT/Gemini…）完成同样的两轮分析。
 *
 * 与 Jev 原版的差异：标准模型不给标定概率分布，因此命中即视为 clear，
 * 情绪百分比用相对倾向近似（见 [emotionProbabilities]）。
 */
object MoodProtocol {
    val emotions = linkedMapOf(
        "happy" to "开心", "calm" to "平静", "sad" to "失落",
        "hurt" to "委屈", "annoyed" to "生气", "relieved" to "缓和", "unknown" to "不明确",
    )
    val progress = linkedMapOf(
        "sharing" to "分享经历或自然闲聊", "clarify" to "等具体事实或细节",
        "reassure" to "等关心或重视的回应", "explain" to "等澄清误会或承认问题",
        "act" to "已有解释，等具体行动", "accepted" to "已明确接受回应或安排",
        "closing" to "明确告别或自然收尾", "unknown" to "无法确定对话阶段",
    )

    private const val SCOPE =
        "当前待分析消息是「当前消息」，speaker 是发送者；context 是从旧到新的前文。" +
            "只判断当前消息，区分不同发送者，不把自己的承诺当作对方已经同意。" +
            "仅依据原话，不补造关系、性别、事件或真实心理。短句可能只是普通回应，没有证据就选信息不足或普通解释。" +
            "每个字段独立判断，不假设能看到其他字段的输出。"

    // ---------------- Round 1: 情绪画像 ----------------

    fun profileMessages(input: AnalysisInput): List<LlmMessage> {
        val sys = "你是微信聊天情绪分析器。严格只输出一个符合字段要求的 JSON 对象，不要任何多余文字或代码块。"
        val user = buildString {
            append(SCOPE).append('\n')
            append("当前消息（").append(input.speaker).append("）：\n").append(input.text).append("\n\n")
            append("前文（从旧到新）：\n")
            input.context.takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES).forEachIndexed { _, c ->
                append("  [").append(c.speaker).append("] ").append(c.text).append('\n')
            }
            append("\n请输出 JSON，键名固定，每个键取下列一个可选值（值为括号里的 key）：\n")
            append("scene: ").append(JSONObject(ChatTemplates.scenes).toString()).append('\n')
            append("emotion: ").append(JSONObject(emotions).toString()).append('\n')
            append("progress: ").append(JSONObject(progress).toString()).append('\n')
            ChatFacts.questions.forEach { (key, q) ->
                append(key).append(" 判断“").append(q.instructions).append("” 可选值：")
                    .append(JSONObject(q.options).toString()).append('\n')
            }
        }
        return listOf(LlmMessage(LlmRole.SYSTEM, sys), LlmMessage(LlmRole.USER, user))
    }

    /** 解析 Round1 输出（标准 JSON）为 ChatProfile；选中项视为 clear。 */
    fun parseProfile(body: String): ChatProfile {
        val answers = JSONObject(extractJson(body))
        fun decision(key: String, options: Map<String, String>): ChatDecision {
            val chosen = answers.optString(key).ifBlank { "unknown" }
            val valid = if (chosen in options) chosen else if (options.containsKey("unknown")) "unknown" else "none"
            return ChatDecision(valid, mapOf(valid to 1.0), 1.0)
        }
        return ChatProfile(
            scene = decision("scene", ChatTemplates.scenes),
            emotion = decision("emotion", emotions),
            progress = decision("progress", progress),
            facts = ChatFacts.questions.mapValues { (key, q) -> decision(key, q.options) },
        )
    }

    // ---------------- Round 2: 精选卡 + 动作 ----------------

    fun detailMessages(input: AnalysisInput, profile: ChatProfile): List<LlmMessage> {
        val sys = "你是微信聊天情绪分析器。严格只输出一个符合要求的 JSON 对象，不要任何多余文字或代码块。"
        val cards = ChatTemplates.candidates(profile)
        val actions = ChatActions.candidates(profile)
        require(cards.isNotEmpty() || actions.isNotEmpty())
        val user = buildString {
            append("前一轮判断（仅供参考，可能有误，以真实聊天原文为准）：\n")
            append("scene=").append(profile.scene.choice).append(" emotion=").append(profile.emotion.choice)
                .append(" progress=").append(profile.progress.choice).append('\n')
            profile.facts.forEach { (k, v) -> append("  ").append(k).append("=").append(v.choice).append('\n') }
            append("\n当前消息（").append(input.speaker).append("）：\n").append(input.text).append("\n\n")
            append("前文：\n")
            input.context.takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES).forEach { c ->
                append("  [").append(c.speaker).append("] ").append(c.text).append('\n')
            }
            if (cards.isNotEmpty()) {
                append("\n候选解读卡（请选一张最贴合当前消息的，或 none）：\n")
                cards.forEach { c -> append("  ").append(c.id).append(": ").append(c.title)
                    .append(" 问题: ").append(c.question).append('\n') }
            }
            if (actions.isNotEmpty()) {
                append("\n下一步动作（选一个最适合的，或 none）：\n")
                ChatActions.options(profile).forEach { (id, desc) ->
                    if (id != "none") append("  ").append(id).append(": ").append(desc).append('\n')
                }
            }
            append("\n输出 JSON：\n")
            if (cards.isNotEmpty()) {
                append("{\"focus\":\"<一张卡片id或none>\",\"reading\":\"signal|ordinary|unclear\",")
            } else {
                append("{\"reading\":\"signal|ordinary|unclear\",")
            }
            if (actions.isNotEmpty()) append("\"action\":\"<动作id或none>\"")
            append("}")
        }
        return listOf(LlmMessage(LlmRole.SYSTEM, sys), LlmMessage(LlmRole.USER, user))
    }

    fun parseDetail(body: String, profile: ChatProfile): Mood {
        val cards = ChatTemplates.candidates(profile)
        val actions = ChatActions.candidates(profile)
        require(cards.isNotEmpty() || actions.isNotEmpty())
        val answers = JSONObject(extractJson(body))
        val focusId = answers.optString("focus")
        val card = cards.firstOrNull { it.id == focusId }
        val reading = answers.optString("reading")
        val actionId = answers.optString("action")
        val selectedAction = actions.firstOrNull { it.id == actionId }
        val lines = mutableListOf(emotionProbabilities(profile))
        if (card != null && reading == "signal") {
            lines += "事件：" + ChatTemplates.scenes.getValue(card.scene).substringBefore('：')
            lines += card.question
            lines += "· " + card.signal
        } else if (card != null && reading == "ordinary") {
            lines += "事件：" + ChatTemplates.scenes.getValue(card.scene).substringBefore('：')
            lines += card.question
            lines += "· " + card.ordinary
        }
        if (selectedAction != null) lines += "建议：" + selectedAction.text
        val label = when {
            card != null -> ChatTemplates.scenes.getValue(card.scene).substringBefore('：')
            selectedAction != null -> "下一步动作"
            else -> "情绪概率"
        }
        return Mood(label, emotionScore(profile), 0, "", lines.joinToString("\n"))
    }

    fun fallback(profile: ChatProfile): Mood =
        Mood("情绪概率", emotionScore(profile), 0, "", emotionProbabilities(profile))

    private fun emotionProbabilities(profile: ChatProfile): String {
        val primary = listOf("happy", "calm", "annoyed")
        val visible = primary + emotions.keys.filter { it !in primary }
        return "情绪：" + visible.joinToString(" · ") {
            "${emotions.getValue(it)} ${score(it, profile)}%"
        }
    }

    /** 标准模型无概率分布，用相对倾向：命中项高，其余按关键性子集给个近似。仅作展示。 */
    private fun score(key: String, profile: ChatProfile): Int = when {
        profile.emotion.choice == key -> (88..96).random()
        (key == "happy" && profile.emotion.choice == "relieved") -> (30..45).random()
        (key == "sad" || key == "hurt") && profile.emotion.choice in setOf("sad", "hurt", "annoyed") -> (20..40).random()
        else -> (2..10).random()
    }

    private fun emotionScore(profile: ChatProfile): Double {
        if (!profile.emotion.clear) return 0.0
        return when (profile.emotion.choice) {
            "happy", "relieved", "calm" -> 0.6
            "annoyed" -> -0.5
            "sad", "hurt", "unknown" -> -0.3
            else -> 0.0
        }
    }

    fun extractJson(body: String): String {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回 JSON" }
        return body.substring(start, end + 1)
    }
}