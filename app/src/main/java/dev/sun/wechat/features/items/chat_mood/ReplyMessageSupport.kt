package dev.sun.wechat.features.items.chat_mood

import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.serialization.XmlUtils

/**
 * AI 回复 / 自动回复可参与的消息类型判定与文本化。
 *
 * 微信聊天里除了文字还有语音(34)、表情包(47/1048625)等消息。
 * 语音消息若已由微信转写（模块「自动语音转文字」触发后），优先使用转写文本；
 * 未转写时用 [语音] 占位；表情包没有可读文字，用 [表情] 占位。
 * 占位符让模型知道“对方发来了什么”，从而能针对性地生成回复。
 */
object ReplyMessageSupport {

    private const val TAG = "ReplyMessageSupport"

    /**
     * 除撤回、系统提示、朋友圈外，所有类型都参与 AI 回复/自动回复。
     * 图片、视频、文件、链接、卡片等没有正文的类型，由 [replyText] 生成占位描述
     * （例如「[图片]」「[文件: 方案.pdf]」），让模型知道对方发来的是什么。
     */
    fun isReplyEligible(message: MessageInfo): Boolean {
        val type = message.type ?: return true
        if (type.isSystem) return false
        return when (type.code) {
            MessageType.RECALL.code, MessageType.MOMENTS.code -> false
            else -> true
        }
    }

    /** 是否为纯文字消息（情绪分析只对文字有意义）。 */
    fun isPlainText(message: MessageInfo): Boolean {
        val type = message.type ?: return false
        return type.isText
    }

    /**
     * 把消息转成给模型看的文本：有正文的用正文，没有正文的生成中文占位描述。
     * 语音优先用微信已转写好的文字（用户只需要读取转写结果，不在这里做转写）。
     */
    fun replyText(message: MessageInfo): String? {
        val type = message.type ?: return "[未知消息]"
        val title = xmlTitle(message.content)
        return when {
            type.isText -> message.actualContent.trim().takeIf { it.isNotEmpty() }
            type.code == MessageType.VOICE.code ->
                voiceTranscript(message)?.trim()?.takeIf { it.isNotEmpty() } ?: "[语音]"
            type.isSticker -> "[表情]"
            type.code == MessageType.IMAGE.code -> "[图片]"
            type.code == MessageType.VIDEO.code || type.code == MessageType.MICRO_VIDEO.code -> "[视频]"
            type.code == MessageType.FILE.code -> bracket("文件", title)
            type.isLink -> bracket("链接", title)
            type.isLocation -> "[位置]"
            type.code == MessageType.CARD.code -> "[名片]"
            type.code == MessageType.TRANSFER.code -> "[转账]"
            type.isRedPacket -> "[红包]"
            type.isVideoAccount || type.code == MessageType.ACCOUNT_VIDEO.code -> bracket("视频号", title)
            type.code == MessageType.PAT.code -> "[拍了拍]"
            type.code == MessageType.GROUP_NOTE.code -> "[群公告]"
            type.code == MessageType.APP.code -> bracket("卡片", title)
            type.isVoip -> "[通话]"
            else -> "[${type.displayName}]"
        }
    }

    private fun bracket(kind: String, title: String?): String =
        if (title.isNullOrBlank()) "[$kind]" else "[$kind: $title]"

    /** 从消息 XML 里取标题（链接/文件/卡片都有），取不到返回 null。 */
    private fun xmlTitle(content: String): String? {
        if (content.isBlank() || !content.contains('<')) return null
        return runCatching { XmlUtils.extractXmlTag(content, "title") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 读取微信语音转写文本。
     * 1) 反射消息对象上可能的转写字段（微信版本不同字段名有差异，逐个试）；
     * 2) 兜底解析 content XML 里的 voicetrans（标签或属性）。
     */
    fun voiceTranscript(message: MessageInfo): String? {
        // 0) 微信 bean 上的 getter（不同版本字段名不同，方法名更稳定）
        val methodNames = listOf(
            "getVoiceTransText",
            "getTransText",
            "getVoiceText",
            "getVoiceTrans",
            "getVoiceTranslateText",
        )
        for (name in methodNames) {
            val value = runCatching {
                message.instance.javaClass.getMethod(name).invoke(message.instance) as? String
            }.getOrNull()
            if (!value.isNullOrBlank()) return logTranscript(name, value)
        }
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
            if (!value.isNullOrBlank()) return logTranscript(name, value)
        }
        // 2) 解析 content / msgSource 里的 voicetrans
        val msgSource = runCatching { message.msgSource }.getOrDefault("")
        for (xml in listOf(message.content, msgSource)) {
            if (xml.isBlank()) continue
            extractVoicetrans(xml)?.let { return logTranscript("xml", it) }
        }
        WeLogger.d(TAG, "语音消息未读到转写文本（type=${message.typeCode}, content=${message.content.take(80)}）")
        return null
    }

    private fun logTranscript(source: String, value: String): String {
        val text = value.trim()
        WeLogger.d(TAG, "语音转写取自 $source：${text.take(30)}")
        return text
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
