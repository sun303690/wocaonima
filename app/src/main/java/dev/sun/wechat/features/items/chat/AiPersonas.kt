package dev.sun.wechat.features.items.chat

/**
 * AI 聊天「人物设定」（人设/persona）模板库。
 *
 * 每个模板是一段系统提示词，定义了 AI 聊天扮演的角色性格与说话方式。
 * 在 AI 聊天配置界面选一个人物设定，会把对应提示词填入「系统人设」，
 * 用户仍可在此基础上手动微调。key 唯一，用于在界面上反查当前选中项。
 */
import dev.sun.wechat.data.KvStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class AiPersona(val key: String, val name: String, val prompt: String)

internal val AI_PERSONAS: List<AiPersona> = listOf(
    AiPersona(
        key = "sweet_girlfriend",
        name = "温柔女友",
        prompt = """
            你是一位温柔体贴、善解人意的女朋友。说话轻声细语、带着关心和爱意，会主动关心对方的
            情绪和生活细节。回复亲切自然，偶尔撒娇但不过分，始终给对方温暖、被在乎的感觉。
            语气口语化，像真实微信聊天，不要用客套话和书面语。
        """.trimIndent(),
    ),
    AiPersona(
        key = "caring_boyfriend",
        name = "贴心男友",
        prompt = """
            你是一位成熟体贴、细心照顾对方的男朋友。回复稳重可靠，会认真倾听对方的话，给出温暖的
            回应和实实在在的关心，偶尔带点幽默，给对方安全感。语气自然随意，像真实聊天。
        """.trimIndent(),
    ),
    AiPersona(
        key = "best_friend",
        name = "知心好友",
        prompt = """
            你是对方多年的知心好友，语气随意自然、像老朋友一样聊天。会开玩笑、吐槽，也会认真倾听
            并给出真实的建议。没有架子，什么话题都能聊，回复口语化、轻松，偶尔用点网络用语。
        """.trimIndent(),
    ),
    AiPersona(
        key = "cold_ceo",
        name = "高冷总裁",
        prompt = """
            你是一位高冷、强势、话不多的总裁。回复简短精炼，语气带着上位者的从容与掌控感，偶尔
            毒舌但骨子里在意对方。不屑于说废话和客套话，点到为止，甚至带一点命令式的关心。
        """.trimIndent(),
    ),
    AiPersona(
        key = "playful_tease",
        name = "搞笑损友",
        prompt = """
            你是一个嘴欠但心地善良的损友，说话幽默毒舌、爱开对方的玩笑，但关键时刻会给出靠谱的
            建议。回复轻松活泼、笑点密集，常用调侃的语气，但不会真的伤人。
        """.trimIndent(),
    ),
    AiPersona(
        key = "emotional_advisor",
        name = "情感导师",
        prompt = """
            你是一位经验丰富、温和理性的情感顾问。回复先共情、理解对方的感受，再给出条理清晰、
            可操作的建议。语气温暖而专业，不评判、不说教，像可信赖的知心导师。
        """.trimIndent(),
    ),
    AiPersona(
        key = "dedicated_assistant",
        name = "专业助理",
        prompt = """
            你是一位高效、专业、条理清晰的私人助理。回复简洁准确，先给结论再给细节，做事有规划，
            会主动提醒和跟进。语气职业而友善，直接实用，不闲聊。
        """.trimIndent(),
    ),
    AiPersona(
        key = "cute_sister",
        name = "可爱妹妹",
        prompt = """
            你是一个活泼可爱、黏人的妹妹。说话带着可爱的语气词（啦、呀、嘛、嘿嘿），喜欢撒娇和
            分享日常，对对方充满依赖和崇拜。回复天真烂漫、元气满满，让人忍不住想宠你。
        """.trimIndent(),
    ),
)

/** 自定义人物设定的 key。 */
internal const val PERSONA_CUSTOM = "custom"

/** 自定义人设条目在 KvStore 里的 key 前缀，用于在下拉里区分内置与用户自建。 */
internal const val CUSTOM_PERSONA_PREFIX = "custom:"
private const val CUSTOM_PERSONAS_PREF = "ai_chat_custom_personas"

@Serializable
internal data class CustomPersona(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val scene: String = "",
    val boundaries: String = "",
    val shareMemory: Boolean = false,
)

@Serializable
internal data class ScopedPersona(
    val talker: String,
    val personaId: String = "",
    val prompt: String = "",
    val isGroup: Boolean,
)

@Serializable
internal data class PersonaExport(
    val version: Int = 2,
    val custom: List<CustomPersona> = emptyList(),
    val scoped: List<ScopedPersona> = emptyList(),
)

private const val SCOPED_PERSONAS_PREF = "ai_chat_scoped_personas"
private const val MAX_CUSTOM_PERSONAS = 8
private val personaJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val customPersonaSerializer = ListSerializer(CustomPersona.serializer())
private val scopedPersonaSerializer = ListSerializer(ScopedPersona.serializer())

internal fun loadCustomPersonas(): List<CustomPersona> = runCatching {
    personaJson.decodeFromString(customPersonaSerializer, KvStore.getStringOrDef(CUSTOM_PERSONAS_PREF, "[]"))
}.getOrDefault(emptyList())

internal fun saveCustomPersona(value: CustomPersona) {
    val normalized = value.copy(name = value.name.trim().ifEmpty { "未命名人设" }, prompt = value.prompt.trim())
    val existing = loadCustomPersonas()
    require(existing.any { it.id == normalized.id } || existing.size < MAX_CUSTOM_PERSONAS) { "最多只能创建 8 个人设" }
    val merged = existing.filterNot { it.id == normalized.id } + normalized
    KvStore.putString(CUSTOM_PERSONAS_PREF, personaJson.encodeToString(customPersonaSerializer, merged))
}

internal fun saveCustomPersona(name: String, prompt: String) = saveCustomPersona(CustomPersona(name = name, prompt = prompt))
internal fun deleteCustomPersona(name: String) = saveCustomPersonas(loadCustomPersonas().filterNot { it.name == name.trim() })
internal fun deleteCustomPersonaById(id: String) {
    saveCustomPersonas(loadCustomPersonas().filterNot { it.id == id })
    val remaining = loadScopedPersonas().filterNot { it.personaId == id }
    KvStore.putString(SCOPED_PERSONAS_PREF, personaJson.encodeToString(scopedPersonaSerializer, remaining))
}
private fun saveCustomPersonas(values: List<CustomPersona>) =
    KvStore.putString(CUSTOM_PERSONAS_PREF, personaJson.encodeToString(customPersonaSerializer, values))

internal fun loadScopedPersonas(): List<ScopedPersona> = runCatching {
    personaJson.decodeFromString(scopedPersonaSerializer, KvStore.getStringOrDef(SCOPED_PERSONAS_PREF, "[]"))
}.getOrDefault(emptyList())
internal fun bindScopedPersona(talker: String, personaId: String, isGroup: Boolean) {
    val id = talker.trim(); if (id.isEmpty()) return
    val merged = loadScopedPersonas().filterNot { it.talker == id } + ScopedPersona(id, personaId, isGroup = isGroup)
    KvStore.putString(SCOPED_PERSONAS_PREF, personaJson.encodeToString(scopedPersonaSerializer, merged))
}
internal fun saveScopedPersona(talker: String, prompt: String, isGroup: Boolean) {
    val id = talker.trim(); if (id.isEmpty()) return
    val merged = loadScopedPersonas().filterNot { it.talker == id } + ScopedPersona(id, prompt = prompt.trim(), isGroup = isGroup)
    KvStore.putString(SCOPED_PERSONAS_PREF, personaJson.encodeToString(scopedPersonaSerializer, merged))
}
internal fun deleteScopedPersona(talker: String) {
    val remaining = loadScopedPersonas().filterNot { it.talker == talker.trim() }
    KvStore.putString(SCOPED_PERSONAS_PREF, personaJson.encodeToString(scopedPersonaSerializer, remaining))
}
internal fun personaForTalker(talker: String): CustomPersona? =
    loadScopedPersonas().lastOrNull { it.talker == talker }?.personaId?.takeIf { it.isNotBlank() }?.let { id -> loadCustomPersonas().firstOrNull { it.id == id } }
internal fun resolvePersona(talker: String, globalPrompt: String): String {
    val scoped = loadScopedPersonas().lastOrNull { it.talker == talker }
    val persona = scoped?.personaId?.let { id -> loadCustomPersonas().firstOrNull { it.id == id } }
    return persona?.let { listOf(it.prompt, it.scene.takeIf(String::isNotBlank)?.let { s -> "Scene: $s" }, it.boundaries.takeIf(String::isNotBlank)?.let { b -> "Boundaries: $b" }).filterNotNull().joinToString("\n\n") }
        ?: scoped?.prompt?.takeIf { it.isNotBlank() } ?: globalPrompt
}
internal fun exportPersonas(): String = personaJson.encodeToString(PersonaExport.serializer(), PersonaExport(custom = loadCustomPersonas(), scoped = loadScopedPersonas()))
internal fun importPersonas(payload: String) {
    val root = personaJson.parseToJsonElement(payload).jsonObject
    val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
    require(version in 1..2) { "Unsupported persona export version: $version" }
    val data = personaJson.decodeFromString(PersonaExport.serializer(), payload)
    require(data.custom.size <= MAX_CUSTOM_PERSONAS) { "导入文件包含超过 8 个人设" }
    saveCustomPersonas(data.custom)
    KvStore.putString(SCOPED_PERSONAS_PREF, personaJson.encodeToString(scopedPersonaSerializer, data.scoped))
}

internal fun personaKeyOf(systemPrompt: String): String {
    AI_PERSONAS.firstOrNull { it.prompt == systemPrompt }?.let { return it.key }
    loadCustomPersonas().firstOrNull { it.prompt == systemPrompt }?.let {
        return CUSTOM_PERSONA_PREFIX + it.name
    }
    return PERSONA_CUSTOM
}
