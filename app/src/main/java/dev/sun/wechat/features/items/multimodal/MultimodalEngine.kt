package dev.sun.wechat.features.items.multimodal

import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.items.chat.AsrEngine
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.collections.LruCache
import dev.sun.wechat.utils.serialization.XmlUtils
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 多模态消息识别：把「没有文字的消息」翻译成一段短文字，交给 AI 回复。
 *
 * 覆盖四类：
 *  - 语音(34)：微信自己的转写拿不到时，用 OpenAI 兼容的 `/audio/transcriptions` 识别（复用 [AsrEngine]）。
 *  - 图片(3)：把图片下载到本机后交给视觉模型，得到「图里是什么」的一句话描述。
 *  - 表情包(47/1048625)：解密贴纸后交给视觉模型理解情绪含义，**同一 md5 只识别一次**。
 *  - 卡片 / 链接 / 文件(49 等)：不走网络，直接从 XML 里抽标题、描述、链接、扩展名与大小。
 *
 * 两类开销不同，所以两条路：卡片类在 [textFor] 里同步算；语音/图片/表情需要联网，
 * 由 [prefetch] 在后台先跑，结果按 key 落盘缓存。AI 组装上下文时只读缓存，
 * 没命中就用原来的占位符（`[图片]` 之类）并顺手发起一次后台识别，下一轮就有内容了。
 */
object MultimodalEngine {

    private const val TAG = "MultimodalEngine"

    // ---- 子开关：用户可以单独关掉某一类识别 ----
    var imageEnabled by prefOption("mm_image_enabled", true)
    var stickerEnabled by prefOption("mm_sticker_enabled", true)
    var cardEnabled by prefOption("mm_card_enabled", true)

    // ---- 视觉模型：直接用 WeAgent 里已配好的模型（只选一个支持看图的）----
    var visionModelId by prefOption("mm_vision_model_id", "")

    private const val IMAGE_PROMPT =
        "用一句中文描述这张图片的内容。如果图里有文字，把关键文字也说出来。不超过 60 字。"
    private const val STICKER_PROMPT =
        "这是一个微信表情包。用不超过 20 字说明它表达的情绪或含义。"

    /** 内存层缓存，避免每次都读盘。 */
    private val memory = LruCache<String, String>(maxLimit = 256)

    /** 正在识别的 key，防止同一条消息重复花钱。 */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /**
     * 取一条消息的多模态文本（只读缓存，不联网）。
     * 卡片/链接/文件这类纯解析的会在这里直接算出来。
     */
    fun textFor(message: MessageInfo): String? {
        if (!MultimodalFeature.isEnabled) return null
        val type = message.type ?: return null
        return when {
            type.isText -> null
            type.code == MessageType.VOICE.code -> readCache(voiceKey(message))
            type.code == MessageType.IMAGE.code ->
                if (imageEnabled) readCache(imageKey(message)) else null
            type.isSticker -> if (stickerEnabled) readCache(stickerKey(message)) else null
            else -> if (cardEnabled) cardText(message) else null
        }
    }

    /** 语音/图片/表情没有缓存时，在后台识别一次，下次组装上下文就能用上。 */
    fun prefetch(message: MessageInfo, onDone: ((String?) -> Unit)? = null) {
        if (!MultimodalFeature.isEnabled) return
        val type = message.type ?: return
        when {
            type.code == MessageType.VOICE.code -> {
                if (!AsrEngine.ready()) return
                val encPath = AsrEngine.voiceFileKey(message) ?: return
                val key = voiceKey(message)
                if (readCache(key) != null) return
                runOnce(key, { AsrEngine.transcribeEncPath(encPath) }, onDone)
            }
            type.code == MessageType.IMAGE.code -> {
                // 不单独指定视觉模型时由 VisionClient 回落到 WeAgent 默认模型
                if (!imageEnabled) return
                if (message.serverId <= 0L) return
                val key = imageKey(message)
                if (readCache(key) != null) return
                runOnce(key, { describeImage(message) }, onDone)
            }
            type.isSticker -> {
                if (!stickerEnabled) return
                val key = stickerKey(message)
                if (readCache(key) != null) return
                runOnce(key, { describeSticker(message) }, onDone)
            }
        }
    }

    // ---- 后台执行 ----

    private fun runOnce(key: String, block: () -> String?, onDone: ((String?) -> Unit)? = null) {
        if (!inFlight.add(key)) return
        thread(name = "wekit-mm") {
            try {
                val outcome = runCatching(block)
                val text = outcome.getOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    writeCache(key, text)
                    WeLogger.d(TAG, "识别完成 key=$key len=${text.length}")
                } else {
                    // 空结果以前是静默的，日志里只剩「AI reply skipped: multimodal without text」，
                    // 分不清是拿不到图片、模型回了空，还是调用抛错，这里补一条可诊断的日志。
                    val reason = outcome.exceptionOrNull()?.message
                    WeLogger.d(
                        TAG,
                        "识别为空 key=$key${if (reason.isNullOrBlank()) "" else " 原因=$reason"}"
                    )
                }
                onDone?.invoke(text)
            } catch (e: Exception) {
                WeLogger.w(TAG, "识别失败 key=$key：${e.message}")
                onDone?.invoke(null)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun describeImage(message: MessageInfo): String? {
        val path = WeMessageApi.cacheImage(message.serverId)
        if (path.isNullOrBlank()) {
            WeLogger.d(TAG, "图片还没缓存好 serverId=${message.serverId}")
            return null
        }
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            WeLogger.d(TAG, "图片文件为空 ${file.name}")
            return null
        }
        return VisionClient.describe(file, IMAGE_PROMPT)
    }

    private fun describeSticker(message: MessageInfo): String? {
        val md5 = message.stickerMd5?.takeIf { it.isNotBlank() }
            ?: message.imagePath?.takeIf { it.length >= 32 }
            ?: return null
        val destination = File(cacheDir(), "stk_$md5.img").toPath()
        val decoded = WeMessageApi.decodeStickerToFile(md5, destination)
        if (decoded == null) {
            WeLogger.d(TAG, "表情解密失败 md5=$md5（这类表情 WeKit 解不出来，跳过）")
            return null
        }
        val file = decoded.toFile()
        if (!file.isFile || file.length() == 0L) return null
        return VisionClient.describe(file, STICKER_PROMPT)
    }

    // ---- 卡片 / 链接 / 文件：纯 XML 解析，不联网 ----

    private fun cardText(message: MessageInfo): String? {
        val type = message.type ?: return null
        val xml = message.content
        return when {
            type.code == MessageType.FILE.code -> {
                val title = xmlTag(xml, "title")
                val extension = xmlTag(xml, "fileext").ifEmpty { xmlAttr(xml, "fileext") }
                val size = xmlTag(xml, "totallen").ifEmpty { xmlAttr(xml, "totallen") }
                val detail = describeFile(title, extension, size)
                detail?.let { "[文件: $it]" }
            }
            type.isLink -> {
                val parts = listOfNotNull(
                    xmlTag(xml, "title").takeIf { it.isNotEmpty() },
                    xmlTag(xml, "des").takeIf { it.isNotEmpty() },
                    urlOf(message).takeIf { it.isNotEmpty() },
                )
                parts.takeIf { it.isNotEmpty() }?.let { "[链接: ${it.joinToString(" | ")}]" }
            }
            type.code == MessageType.APP.code -> {
                val parts = listOfNotNull(
                    xmlTag(xml, "title").takeIf { it.isNotEmpty() },
                    xmlTag(xml, "des").takeIf { it.isNotEmpty() },
                    xmlTag(xml, "sourcedisplayname").takeIf { it.isNotEmpty() },
                )
                parts.takeIf { it.isNotEmpty() }?.let { "[卡片: ${it.joinToString(" | ")}]" }
            }
            type.code == MessageType.VIDEO.code || type.code == MessageType.MICRO_VIDEO.code -> {
                val seconds = xmlTag(xml, "playlength").ifEmpty { xmlAttr(xml, "playlength") }
                val detail = describeVideo(seconds)
                detail?.let { "[视频: $it]" }
            }
            else -> null
        }
    }

    private fun describeFile(title: String, extension: String, size: String): String? {
        val parts = mutableListOf<String>()
        if (title.isNotEmpty()) parts += title
        if (extension.isNotEmpty()) parts += "格式 ${extension.uppercase()}"
        size.toLongOrNull()?.takeIf { it > 0 }?.let { parts += humanSize(it) }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun describeVideo(seconds: String): String? {
        val value = seconds.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return if (value >= 60) "${value / 60} 分 ${value % 60} 秒" else "${value} 秒"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun urlOf(message: MessageInfo): String =
        xmlTag(message.content, "url").ifEmpty { xmlAttr(message.content, "url") }

    private fun xmlTag(content: String?, tag: String): String =
        content?.takeIf { it.isNotBlank() }
            ?.let { runCatching { XmlUtils.extractXmlTag(it, tag) }.getOrNull() }
            ?.trim()
            .orEmpty()

    private fun xmlAttr(content: String?, attr: String): String =
        content?.takeIf { it.isNotBlank() }
            ?.let { runCatching { XmlUtils.extractXmlAttr(it, attr) }.getOrNull() }
            ?.trim()
            .orEmpty()

    // ---- 缓存 ----

    private fun voiceKey(message: MessageInfo): String = "voice_${stamp(message)}"

    private fun imageKey(message: MessageInfo): String = "img_${message.serverId}"

    private fun stickerKey(message: MessageInfo): String {
        val md5 = message.stickerMd5?.takeIf { it.isNotBlank() }
            ?: message.imagePath?.takeIf { it.length >= 32 }
            ?: stamp(message)
        return "stk_$md5"
    }

    private fun stamp(message: MessageInfo): String =
        if (message.serverId > 0L) message.serverId.toString() else message.id.toString()

    private fun cacheDir(): File = File(HostInfo.application.cacheDir, "multimodal").apply { mkdirs() }

    private fun readCache(key: String): String? {
        memory[key]?.let { return it }
        val file = File(cacheDir(), "$key.txt")
        if (!file.isFile) return null
        val text = runCatching { file.readText().trim() }.getOrNull()
        if (text.isNullOrEmpty()) return null
        memory[key] = text
        return text
    }

    private fun writeCache(key: String, value: String) {
        memory[key] = value
        runCatching { File(cacheDir(), "$key.txt").writeText(value) }
    }

    /** 清掉所有多模态识别缓存（设置页用）。 */
    fun clearCache() {
        memory.clear()
        runCatching { cacheDir().deleteRecursively() }
    }
}
