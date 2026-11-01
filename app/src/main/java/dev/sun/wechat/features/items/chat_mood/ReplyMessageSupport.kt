package dev.sun.wechat.features.items.chat_mood

import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType

/**
 * AI 回复 / 自动回复可参与的消息类型判定与文本化。
 *
 * 微信聊天里除了文字还有语音(34)、表情包(47/1048625)等消息。
 * 语音消息若已由微信转写（模块「自动语音转文字」触发后），优先使用转写文本；
 * 未转写时用 [语音] 占位；表情包没有可读文字，用 [表情] 占位。
 * 占位符让模型知道“对方发来了什么”，从而能针对性地生成回复。
 */
object ReplyMessageSupport {

    /** 文本、语音、表情包消息可参与 AI 回复/自动回复；其余（图片/视频/文件/卡片/链接等）跳过。 */
    fun isReplyEligible(message: MessageInfo): Boolean {
        val type = message.type ?: return false
        return type.isText || type.code == MessageType.VOICE.code || type.isSticker
    }

    /** 是否为纯文字消息（情绪分析只对文字有意义）。 */
    fun isPlainText(message: MessageInfo): Boolean {
        val type = message.type ?: return false
        return type.isText
    }

    /** 把消息转成给模型看的文本：文字用原文，语音优先用微信转写文本（否则 [语音]），表情用 [表情]。 */
    fun replyText(message: MessageInfo): String? {
        val type = message.type ?: return null
        return when {
            type.isText -> message.actualContent.trim().takeIf { it.isNotEmpty() }
            type.code == MessageType.VOICE.code ->
                voiceTranscript(message)?.trim()?.takeIf { it.isNotEmpty() } ?: "[语音]"
            type.isSticker -> "[表情]"
            else -> null
        }
    }

    /**
     * 读取微信语音转写文本。
     * 1) 反射消息对象上可能的转写字段（微信版本不同字段名有差异，逐个试）；
     * 2) 兜底解析 content XML 里的 voicetrans（标签或属性）。
     */
    fun voiceTranscript(message: MessageInfo): String? {
        // 1) 反射常见字段名
        val fieldNames = listOf(
            "field_transText",
            "field_voiceText",
            "field_translateText",
            "field_trans_text",
            "field_voiceTransText",
        )
        for (name in fieldNames) {
            val value = runCatching {
                message.instance.reflekt().getField(name, true) as? String
            }.getOrNull()
            if (!value.isNullOrBlank()) return value!!.trim()
        }
        // 2) 解析 content XML 里的 voicetrans
        val xml = message.content
        if (xml.isNotBlank()) {
            extractVoicetrans(xml)?.let { return it }
        }
        return null
    }

    private fun extractVoicetrans(xml: String): String? {
        // 标签形式 <voicetrans><![CDATA[文本]]></voicetrans> 或 <voicetrans>文本</voicetrans>
        val tagStart = xml.indexOf("<voicetrans>")
        if (tagStart >= 0) {
            val after = xml.substring(tagStart + "<voicetrans>".length)
            if (after.startsWith("<![CDATA[")) {
                val end = after.indexOf("]]>")
                if (end > 0) return after.substring("<![CDATA[".length, end).trim()
            }
            val tagEnd = after.indexOf("</voicetrans>")
            if (tagEnd > 0) return after.substring(0, tagEnd).trim()
        }
        // 属性形式 voicetrans="..." 或 voicetrans='...'
        val attr = Regex("""voicetrans\s*=\s*"([^"]*)"|voicetrans\s*=\s*'([^']*)'""")
            .find(xml)
        if (attr != null) {
            val value = attr.groupValues[1].ifEmpty { attr.groupValues[2] }
            if (value.isNotBlank()) return value.trim()
        }
        return null
    }
}
