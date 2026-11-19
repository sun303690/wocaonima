package dev.sun.wechat.features.items.multimodal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import dev.sun.wechat.features.api.core.models.MessageType
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

    /** 视频抽帧张数：3x2 拼一张图，覆盖开头/中间/结尾，用普通图片接口就能读。 */
    private const val VIDEO_FRAMES = 6

    /** 拼图单格边长（像素），六格合计约 1440x960，压缩后远小于接口上限。 */
    private const val FRAME_CELL_PX = 480

    /** 等微信把视频落到本地的最长时间。 */
    private const val VIDEO_WAIT_TIMEOUT_MS = 15_000L

    /** 等落盘的轮询间隔。 */
    private const val VIDEO_POLL_INTERVAL_MS = 200L

    /** 送去识别的视频上限，和 WCS 保持一致。 */
    private const val VIDEO_MAX_BYTES = 10L * 1024 * 1024

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
                type.code == MessageType.VIDEO.code -> video(msg, cacheDir, videoPrompt)
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

    private fun video(msg: WeMessage, cacheDir: File, videoPrompt: String): String? {
        val file = videoFile(msg, cacheDir) ?: return null
        // 主路径：抽帧拼图后用普通图片接口识别，任何能看图的视觉模型都能读。
        val sheet = videoSheet(file, cacheDir)
        if (sheet != null) {
            val text = runCatching { VisionClient.describe(sheet, videoPrompt) }.getOrNull()
            sheet.delete()
            if (!text.isNullOrBlank()) return text
        }
        // 兜底：模型支持原生 video_url 时整段发过去（Qwen-VL 这类接口）。
        return runCatching { VisionClient.describeVideo(file, videoPrompt) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 拿到视频的本地文件：先用微信自己还原的 mp4 路径，
     * 再退回 [WeMessageApi.materializeVideoSource] 的磁盘候选 + VFS 兜底，
     * 都没拿到就按 [VIDEO_WAIT_TIMEOUT_MS] 轮询等微信下载完成。
     */
    private fun videoFile(msg: WeMessage, cacheDir: File): File? {
        val restored = runCatching {
            WeMessageApi.getMsgInfoInstanceByMsgSvrId(msg.msgSvrId, null)
        }.getOrNull()?.let { info ->
            runCatching { WeServiceApi.getVideoMp4PathFromMsgInfo(MessageInfo(info)) }.getOrNull()
        }
        val raw = msg.imgPath
        val directory = File(cacheDir, "media-vision").apply { mkdirs() }
        val destination = File(directory, "video-${msg.msgSvrId}.mp4").toPath()
        val deadline = SystemClock.elapsedRealtime() + VIDEO_WAIT_TIMEOUT_MS
        while (true) {
            if (!restored.isNullOrBlank()) {
                val file = File(restored)
                if (file.isFile && file.length() > 0L) return file
            }
            if (!raw.isNullOrBlank()) {
                val source = runCatching {
                    WeMessageApi.materializeVideoSource(raw, destination, VIDEO_MAX_BYTES)
                }.getOrNull()
                when (source) {
                    is WeMessageApi.VideoSource.Ready -> return source.path.toFile()
                    WeMessageApi.VideoSource.TooLarge -> {
                        WeLogger.d(TAG, "视频超过上限 serverId=${msg.msgSvrId}")
                        return null
                    }
                    else -> Unit
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                WeLogger.d(TAG, "视频始终没落到本地 serverId=${msg.msgSvrId}")
                return null
            }
            runCatching { Thread.sleep(VIDEO_POLL_INTERVAL_MS) }
        }
    }

    /** 从视频里均匀抽 [VIDEO_FRAMES] 帧拼成一张图；抽不到帧返回 null。 */
    private fun videoSheet(source: File, cacheDir: File): File? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frames = ArrayList<Bitmap>(VIDEO_FRAMES)
            for (index in 0 until VIDEO_FRAMES) {
                val timeUs = if (durationMs > 0L) {
                    durationMs * 1000L * index / VIDEO_FRAMES
                } else {
                    index * 1_000_000L
                }
                val frame = runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: continue
                frames += frame
            }
            if (frames.isEmpty()) {
                WeLogger.d(TAG, "视频抽帧为空 ${source.name}")
                return null
            }
            return composeSheet(frames, cacheDir, "video")
        } catch (t: Throwable) {
            WeLogger.w(TAG, "视频抽帧失败 ${source.name}: ${t.message}")
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 把若干帧按 3 列网格拼成一张 JPEG，等比缩放并居中，画完释放帧。 */
    private fun composeSheet(frames: List<Bitmap>, cacheDir: File, prefix: String): File? {
        val columns = 3
        val rows = (frames.size + columns - 1) / columns
        val sheet = runCatching {
            Bitmap.createBitmap(FRAME_CELL_PX * columns, FRAME_CELL_PX * rows, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (sheet == null) {
            frames.forEach { it.recycle() }
            return null
        }
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            frames.forEachIndexed { index, frame ->
                val scale = minOf(
                    FRAME_CELL_PX.toFloat() / frame.width,
                    FRAME_CELL_PX.toFloat() / frame.height,
                )
                val width = (frame.width * scale).toInt().coerceAtLeast(1)
                val height = (frame.height * scale).toInt().coerceAtLeast(1)
                val left = (index % columns) * FRAME_CELL_PX + (FRAME_CELL_PX - width) / 2
                val top = (index / columns) * FRAME_CELL_PX + (FRAME_CELL_PX - height) / 2
                canvas.drawBitmap(frame, null, Rect(left, top, left + width, top + height), paint)
            }
        } finally {
            frames.forEach { it.recycle() }
        }
        val out = ByteArrayOutputStream()
        val ok = runCatching { sheet.compress(Bitmap.CompressFormat.JPEG, 82, out) }.getOrDefault(false)
        val bytes = if (ok) out.toByteArray() else null
        sheet.recycle()
        if (bytes == null) return null
        val directory = File(cacheDir, "media-vision").apply { mkdirs() }
        val target = File(directory, "$prefix-${System.nanoTime()}.jpg")
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
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
