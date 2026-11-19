package dev.sun.wechat.features.items.multimodal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import dev.sun.wechat.features.api.core.MessageType
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.WeServiceApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.WeMessage
import dev.sun.wechat.utils.WeLogger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把一条消息里的媒体（图片 / 表情 / 动图 / 视频）变成一句可读的描述，喂给 AI 回复做上下文。
 *
 * 和 [MultimodalEngine] 的预取是两条线：那边是「消息进列表时就识别好写缓存」，
 * 这边是「要生成回复时按需把历史窗口里的媒体补上」，共用同一个视觉接口配置。
 */
object MediaVision {

    private const val TAG = "MediaVision"

    /** 动图抽帧张数：2x2 拼一张 storyboard，一次请求就能看出动作。 */
    private const val STORYBOARD_FRAMES = 4

    /** 视觉接口是否可用（地址 / Key / 模型都填了）。 */
    fun available(): Boolean = MultimodalEngine.visionUsable()

    /** 这条消息类型是不是需要视觉模型才能读出内容的媒体。 */
    fun isVisual(type: MessageType?): Boolean {
        if (type == null) return false
        if (type.isSticker) return true
        return type.code == MessageType.IMAGE.code ||
            type.code == MessageType.MICRO_VIDEO.code ||
            type.code == MessageType.VIDEO.code
    }

    /**
     * 识别一条消息的媒体内容；拿不到文件、接口没配、识别失败都返回 null，绝不让调用方崩。
     *
     * 调用方一般在 IO 线程上串行调用，所以这里用阻塞式的 [VisionClient]。
     */
    fun describe(
        msg: WeMessage,
        cacheDir: File,
        imagePrompt: String,
        stickerPrompt: String,
        animationPrompt: String,
        videoPrompt: String,
    ): String? {
        val type = msg.type ?: return null
        if (!available()) return null
        return runCatching {
            when {
                type.isSticker -> sticker(msg, cacheDir, stickerPrompt, animationPrompt)
                type.code == MessageType.VIDEO.code -> video(msg, videoPrompt)
                type.code == MessageType.IMAGE.code || type.code == MessageType.MICRO_VIDEO.code ->
                    image(msg, cacheDir, imagePrompt, animationPrompt)
                else -> null
            }
        }.getOrElse {
            WeLogger.w(TAG, "媒体识别失败 type=${type.name}：${it.message}")
            null
        }
    }

    private fun image(
        msg: WeMessage,
        cacheDir: File,
        imagePrompt: String,
        animationPrompt: String,
    ): String? {
        val path = WeMessageApi.cacheImage(msg.msgSvrId)
        if (path.isNullOrBlank()) {
            WeLogger.d(TAG, "图片还没缓存好 serverId=${msg.msgSvrId}")
            return null
        }
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        if (isGif(file)) {
            val storyboard = storyboard(file, cacheDir)
            if (storyboard != null) {
                val text = runCatching { VisionClient.describe(storyboard, animationPrompt) }.getOrNull()
                storyboard.delete()
                if (!text.isNullOrBlank()) return text
            }
        }
        return VisionClient.describe(file, imagePrompt)
    }

    private fun sticker(
        msg: WeMessage,
        cacheDir: File,
        stickerPrompt: String,
        animationPrompt: String,
    ): String? {
        val md5 = stickerMd5Of(msg) ?: return null
        val directory = File(cacheDir, "media-vision").apply { mkdirs() }
        val destination = File(directory, "stk_$md5.img")
        val decoded = WeMessageApi.decodeStickerToFile(md5, destination.toPath()) ?: run {
            // 解密结果是确定的：同一个 md5 解不出来，重试也没用。
            WeLogger.d(TAG, "表情解密失败 md5=$md5")
            return null
        }
        val file = decoded.toFile()
        if (!file.isFile || file.length() == 0L) return null
        if (isGif(file)) {
            val storyboard = storyboard(file, cacheDir)
            if (storyboard != null) {
                val text = runCatching { VisionClient.describe(storyboard, animationPrompt) }.getOrNull()
                storyboard.delete()
                if (!text.isNullOrBlank()) return text
            }
        }
        return VisionClient.describe(file, stickerPrompt)
    }

    private fun video(msg: WeMessage, videoPrompt: String): String? {
        val info = runCatching {
            WeMessageApi.getMsgInfoInstanceByMsgSvrId(msg.msgSvrId, null)
        }.getOrNull() ?: run {
            WeLogger.d(TAG, "视频消息取不到 MessageInfo serverId=${msg.msgSvrId}")
            return null
        }
        val path = runCatching {
            WeServiceApi.getVideoMp4PathFromMsgInfo(MessageInfo(info))
        }.getOrNull()
        if (path.isNullOrBlank()) {
            WeLogger.d(TAG, "视频 mp4 路径还原失败，可能还没下载完 serverId=${msg.msgSvrId}")
            return null
        }
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return VisionClient.describeVideo(file, videoPrompt)
    }

    /**
     * 表情 md5：`field_imgPath` 有时直接是 32 位 md5，否则从 appmsg XML 里找。
     *
     * 微信 8.0.6x 的 appmsg 动画表情是 `<type>8</type>` 且 `field_imgPath=null`，
     * md5 放在 `<emoticonmd5>` 标签里（`<md5>` 属性/标签常年为空）。
     */
    private fun stickerMd5Of(msg: WeMessage): String? {
        msg.imgPath?.takeIf { it.length >= 32 }?.let { return it }
        val xml = msg.content
        ATTR_MD5.find(xml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        TAG_MD5.find(xml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return TAG_EMOTICON_MD5.find(xml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun isGif(file: File): Boolean {
        if (file.name.endsWith(".gif", ignoreCase = true)) return true
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(3)
                input.read(head) == 3 && head[0] == 'G'.code.toByte() &&
                    head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * 动图抽帧拼 2x2 大图：视觉模型看不了动图，但看一张「四连拍」能读懂动作。
     *
     * 用 [Movie] 逐帧 seek 后画到同一张 Bitmap 上，不依赖 FFmpeg 也不碰微信内部解码器。
     */
    @Suppress("DEPRECATION")
    private fun storyboard(source: File, cacheDir: File): File? {
        val movie = Movie.decodeFile(source.absolutePath) ?: return null
        val width = movie.width()
        val height = movie.height()
        if (width <= 0 || height <= 0) return null
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val columns = 2
        val rows = (STORYBOARD_FRAMES + columns - 1) / columns
        val sheet = runCatching {
            Bitmap.createBitmap(width * columns, height * rows, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(sheet)
        for (index in 0 until STORYBOARD_FRAMES) {
            movie.setTime(duration * index / STORYBOARD_FRAMES)
            canvas.save()
            canvas.translate(
                ((index % columns) * width).toFloat(),
                ((index / columns) * height).toFloat(),
            )
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
        }
        val out = ByteArrayOutputStream()
        val ok = runCatching { sheet.compress(Bitmap.CompressFormat.JPEG, 82, out) }.getOrDefault(false)
        sheet.recycle()
        if (!ok) return null
        val directory = File(cacheDir, "media-vision").apply { mkdirs() }
        val target = File(directory, "story-${System.nanoTime()}.jpg")
        return runCatching {
            target.writeBytes(out.toByteArray())
            target
        }.getOrNull()
    }

    private val ATTR_MD5 = Regex("\\bmd5\\s*=\\s*\"([0-9a-fA-F]{16,})\"")
    private val TAG_MD5 = Regex("<md5>([^<]+)</md5>")
    private val TAG_EMOTICON_MD5 = Regex("<emoticonmd5>([^<]+)</emoticonmd5>")
}
