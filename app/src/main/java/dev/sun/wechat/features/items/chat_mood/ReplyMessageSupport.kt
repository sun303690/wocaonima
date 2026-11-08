package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.collections.LruCache
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
        // 多模态识别（语音转写 / 图片理解 / 表情含义 / 卡片解析）：
        // 命中缓存就用识别结果；没命中先给原占位符，同时让后台识别一次，下一轮组装上下文就有内容。
        val enriched = MultimodalEngine.textFor(message)
        return when {
            type.isText -> message.actualContent.trim().takeIf { it.isNotEmpty() }
            type.code == MessageType.VOICE.code ->
                enriched
                    ?: voiceTranscript(message)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: placeholder(message, "[语音]")
            type.isSticker -> enriched ?: placeholder(message, "[表情]")
            type.code == MessageType.IMAGE.code -> enriched ?: placeholder(message, "[图片]")
            type.code == MessageType.VIDEO.code || type.code == MessageType.MICRO_VIDEO.code -> "[视频]"
            type.code == MessageType.FILE.code -> enriched ?: bracket("文件", title)
            type.isLink -> enriched ?: bracket("链接", title)
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

    /**
     * 先用占位符，同时让 [MultimodalEngine] 在后台识别一次；
     * 结果落盘后，下一次组装上下文（卡片刷新 / 下一条消息）就能读到识别文本。
     */
    private fun placeholder(message: MessageInfo, text: String): String {
        MultimodalEngine.prefetch(message)
        return text
    }

    private fun bracket(kind: String, title: String?): String =
        if (title.isNullOrBlank()) "[$kind]" else "[$kind: $title]"

    /** 从消息 XML 里取标题（链接/文件/卡片都有），取不到返回 null。 */
    private fun xmlTitle(content: String): String? {
        if (content.isBlank() || !content.contains('<')) return null
        return runCatching { XmlUtils.extractXmlTag(content, "title") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 转写相关成员名特征：字段/方法名里带 trans 或 voice 才值得当候选。 */
    private val TRANSCRIPT_NAME_HINT = Regex("(?i)(trans|voice)")

    /**
     * 同一条语音只记一次「读不到转写」。
     * 语音行的 View 在一次会话里会被反复绑定（实测同一条能命中上百次），
     * 不在入口去重的话 D 级日志会把日志刷爆（实测一天 3193 行、占比三分之一）。
     */
    private val loggedMissingVoice = LruCache<Long, Boolean>(maxLimit = 512)

    /** 每个消息类只 dump 一次候选字段/方法名：下一次拿日志就能直接对着真实字段名改。 */
    private val dumpedClasses = HashSet<String>()

    /**
     * 读取微信语音转写文本。
     *
     * 硬编码几个字段名在 8.0.78 上一个都没命中（一整天 18 条语音、0 次命中），
     * 所以改成「按名字特征在继承链上全扫」：
     * 1) 已知 getter 名（快路径，命中就返回）；
     * 2) 类继承链上所有「0 参、返回 String、名字含 trans/voice」的方法，逐个试；
     * 3) 类继承链上所有「String 类型、名字含 trans/voice」的字段，逐个读；
     * 4) 兜底解析 content / msgSource 里的 voicetrans（标签或属性）。
     * 全都没命中时按消息 id 记一次日志，并 dump 该类所有候选成员名，供后续定位。
     */
    fun voiceTranscript(message: MessageInfo): String? {
        val instance = message.instance
        // 0) 已知 getter（不同版本字段名不同，方法名更稳定）
        val methodNames = listOf(
            "getVoiceTransText",
            "getTransText",
            "getVoiceText",
            "getVoiceTrans",
            "getVoiceTranslateText",
        )
        for (name in methodNames) {
            val value = runCatching {
                instance.javaClass.getMethod(name).invoke(instance) as? String
            }.getOrNull()
            if (isTranscriptLike(value)) return logTranscript(name, value!!)
        }
        // 1) 通用扫描 getter
        scanTranscriptGetters(instance)?.let { (name, value) -> return logTranscript(name, value) }
        // 2) 通用扫描字段
        scanTranscriptFields(instance)?.let { (name, value) -> return logTranscript(name, value) }
        // 3) 解析 content / msgSource 里的 voicetrans
        val msgSource = runCatching { message.msgSource }.getOrDefault("")
        for (xml in listOf(message.content, msgSource)) {
            if (xml.isBlank()) continue
            extractVoicetrans(xml)?.let { return logTranscript("xml", it) }
        }
        val id = runCatching { message.id }.getOrDefault(-1L)
        val firstSeen = synchronized(loggedMissingVoice) {
            if (loggedMissingVoice[id] == true) false else { loggedMissingVoice[id] = true; true }
        }
        if (firstSeen) {
            WeLogger.d(TAG, "语音消息未读到转写文本（type=${message.typeCode}, content=${message.content.take(80)}）")
            dumpTranscriptCandidates(instance)
        }
        return null
    }

    /** 转写文本的合理性判据：非空、不是 XML 正文、不是纯数字时长之类。 */
    private fun isTranscriptLike(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val text = value.trim()
        if (text.startsWith("<")) return false
        if (text.contains("<msg")) return false
        return true
    }

    private fun scanTranscriptGetters(instance: Any): Pair<String, String>? {
        // 先认名字里带 trans 的（转写本义），再放宽到带 text 的；
        // 不接受只有 voice 的名字，否则容易把音频路径/时长当成转写塞进提示词
        scanTranscriptGetters(instance, keyword = "trans")?.let { return it }
        return scanTranscriptGetters(instance, keyword = "text")
    }

    private fun scanTranscriptGetters(instance: Any, keyword: String): Pair<String, String>? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (method in clazz.declaredMethods) {
                if (method.parameterCount != 0) continue
                if (method.returnType != String::class.java) continue
                if (!method.name.contains(keyword, ignoreCase = true)) continue
                val value = runCatching {
                    if (!method.isAccessible) method.isAccessible = true
                    method.invoke(instance) as? String
                }.getOrNull()
                if (isTranscriptLike(value)) return method.name to value!!
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun scanTranscriptFields(instance: Any): Pair<String, String>? {
        scanTranscriptFields(instance, keyword = "trans")?.let { return it }
        return scanTranscriptFields(instance, keyword = "text")
    }

    private fun scanTranscriptFields(instance: Any, keyword: String): Pair<String, String>? {
        // 先扫子类再扫父类：转写字段一般落在具体消息 bean 自己身上
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type != String::class.java) continue
                if (!field.name.contains(keyword, ignoreCase = true)) continue
                val value = runCatching {
                    if (!field.isAccessible) field.isAccessible = true
                    field.get(instance) as? String
                }.getOrNull()
                if (isTranscriptLike(value)) return field.name to value!!
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** 一次进程内每个类只 dump 一次：把名字里带 trans/voice 的成员全列出来。 */
    private fun dumpTranscriptCandidates(instance: Any) {
        val clazz = instance.javaClass
        val first = synchronized(dumpedClasses) { dumpedClasses.add(clazz.name) }
        if (!first) return
        val methods = ArrayList<String>()
        val fields = ArrayList<String>()
        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            for (method in cursor.declaredMethods) {
                if (method.parameterCount == 0 && method.returnType == String::class.java &&
                    TRANSCRIPT_NAME_HINT.containsMatchIn(method.name)
                ) {
                    methods += method.name
                }
            }
            for (field in cursor.declaredFields) {
                if (field.type == String::class.java && TRANSCRIPT_NAME_HINT.containsMatchIn(field.name)) {
                    fields += field.name
                }
            }
            cursor = cursor.superclass
        }
        WeLogger.d(
            TAG,
            "语音转写候选 dump class=${clazz.name} getters=${methods.take(30)} fields=${fields.take(30)}",
        )
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
