package dev.sun.wechat.features.items.chat.musicorder

import android.content.Context
import android.media.MediaMetadataRetriever
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.items.system.servers.WeChatService
import dev.sun.wechat.features.items.chat.musicorder.QQMusicOrderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * QQ点歌完整功能（对照 Hchat musicorder 模块移植，功能不缩水）。
 *
 * 能力：
 * - 音乐卡片 + 歌曲语音 双发送
 * - 会话白名单（"开启点歌"/"关闭点歌" 命令按会话切换）
 * - 歌手定制（"关键词&歌手"、默认歌手、替换成点歌人昵称）
 * - 封面替换成点歌人头像
 * - 拦截自己的命令
 * - 完整设置项（触发词/AppID/白名单/发送方式等）
 */
internal class QQMusicOrderRuntime(
    private val context: Context,
    private val logError: (String, Throwable?) -> Unit,
) {
    private val hostContext = context.applicationContext ?: context
    private val settings = QQMusicOrderSettings(context)
    private val client = QQMusicClient()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QQMusicOrder").apply { isDaemon = true }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** 处理一条新插入的文本消息（来自 WeDatabaseListenerApi.onInsert）。 */
    fun onTextInserted(talker: String, content: String, msgSvrId: Long, msgId: Long, isOutgoing: Boolean, isGroup: Boolean, sender: String) {
        if (!settings.isEnabled()) return
        if (talker.isBlank()) return
        if (!isOutgoing && !settings.allowedTalkers().contains(talker)) return
        val normalized = normalizeContent(content, isGroup)
        val command = parseCommand(normalized) ?: return
        submit(talker, msgSvrId.takeIf { it > 0 } ?: msgId, sender, command)
    }

    /** 拦截自己发的点歌命令（返回 true 表示已拦截）。 */
    fun handleOwnCommand(text: String): Boolean {
        if (!settings.isEnabled()) return false
        val clean = text.trim()
        when (clean) {
            ENABLE_CURRENT_CHAT_COMMAND, DISABLE_CURRENT_CHAT_COMMAND -> {
                val talker = currentTalker() ?: return false
                setCurrentChatAllowed(talker, clean == ENABLE_CURRENT_CHAT_COMMAND)
                return true
            }
        }
        if (!settings.interceptOwnCommand()) return false
        val command = parseCommand(clean) ?: return false
        val self = WeApi.selfWxId
        submit(currentTalker().orEmpty(), 0L, self, command)
        return true
    }

    private fun currentTalker(): String? = WeCurrentConversationApi.value.takeIf { it.isNotBlank() }

    private fun setCurrentChatAllowed(talker: String, allowed: Boolean) {
        val next = settings.allowedTalkers().toMutableSet().apply {
            if (allowed) add(talker) else remove(talker)
        }
        settings.saveAllowedTalkers(next)
        val content = if (allowed) {
            "该聊天点歌开关已开启，其他人可以点歌了"
        } else {
            "该聊天点歌开关已关闭，只有你能点歌了"
        }
        runCatching { WeChatService.insertSystemMessage(talker, content, System.currentTimeMillis()) }
            .onFailure { logError("点歌白名单系统消息插入失败", it) }
    }

    private fun submit(talker: String, msgId: Long, sender: String, command: MusicCommand) {
        executor.execute {
            runCatching { process(talker, msgId, sender, command) }
                .onFailure {
                    logError("QQ点歌处理异常", it)
                    sendReply(talker, msgId, "处理失败")
                }
        }
    }

    private fun process(talker: String, msgId: Long, sender: String, command: MusicCommand) {
        val track = when (val result = client.search(command.keyword)) {
            is QQMusicSearchResult.Success -> result.track
            QQMusicSearchResult.NotFound -> {
                sendReply(talker, msgId, "未搜到")
                return
            }
            QQMusicSearchResult.Unavailable -> {
                sendReply(talker, msgId, "获取失败，可能是版权限制或数字专辑")
                return
            }
        }
        val sendCard = settings.sendAsCard()
        val sendVoice = settings.sendAsVoice()
        if (!sendCard && !sendVoice) {
            sendReply(talker, msgId, "请至少开启音乐卡片或歌曲语音发送")
            return
        }
        val sentCard = if (sendCard) {
            val singer = resolveSinger(talker, sender, command.customSinger, track.singer)
            val albumUrl = if (settings.replaceCoverWithAvatar()) {
                WeDatabaseApi.getAvatarUrl(sender).ifBlank { track.coverUrl }
            } else {
                track.coverUrl
            }
            val thumbData = downloadThumb(albumUrl.replace("R500x500", "R300x300"))
            WeMessageApi.shareMusicVideo(
                talker = talker,
                title = track.title,
                description = singer,
                musicUrl = track.landingUrl,
                musicDataUrl = track.playUrl,
                singerName = singer,
                duration = 0,
                songLyric = track.lyric.ifBlank { "[99:99.99]暂无歌词" },
                thumbData = thumbData,
                appId = settings.appId(),
            )
        } else {
            null
        }
        val sentVoice = if (sendVoice) {
            sendTrackAsVoice(talker, track)
        } else {
            null
        }
        when {
            sentCard != false && sentVoice != false -> Unit
            sentCard == false && sentVoice == false -> sendReply(talker, msgId, "音乐卡片和歌曲语音发送失败")
            sentCard == false -> sendReply(talker, msgId, "音乐卡片发送失败")
            sentVoice == false -> sendReply(talker, msgId, "歌曲语音发送失败")
        }
    }

    private fun sendTrackAsVoice(talker: String, track: QQMusicTrack): Boolean {
        val cacheDir = File(hostContext.cacheDir, VOICE_CACHE_DIR)
        if ((!cacheDir.isDirectory && !cacheDir.mkdirs()) || !cacheDir.canWrite()) {
            return false
        }
        val target = File(cacheDir, "qq_music_${System.currentTimeMillis()}_${System.nanoTime()}${audioSuffix(track.playUrl)}")
        val part = File(target.absolutePath + ".part")
        return try {
            if (!downloadAudio(track.playUrl, part, target)) return false
            val durationMs = audioDurationMs(target)
            WeMessageApi.sendVoice(talker, target.absolutePath, durationMs)
        } catch (t: Throwable) {
            logError("QQ点歌语音发送失败", t)
            false
        } finally {
            deleteFile(part)
            deleteFile(target)
        }
    }

    private fun resolveSinger(talker: String, sender: String, customSinger: String?, sourceSinger: String): String {
        if (!customSinger.isNullOrBlank()) return customSinger
        if (settings.customSingerEnabled() && settings.defaultSinger().isNotBlank()) return settings.defaultSinger()
        if (settings.replaceSingerWithNickname() && sender.isNotBlank()) {
            val displayName = WeDatabaseApi.getGroupMemberDisplayName(talker, sender)
                .ifBlank { WeDatabaseApi.getDisplayName(sender) }
            if (displayName.isNotBlank() && displayName != sender) return displayName
        }
        return sourceSinger
    }

    private fun parseCommand(text: String): MusicCommand? {
        val clean = text.trim()
        val trigger = settings.triggers().firstOrNull { clean.startsWith(it) } ?: return null
        var keyword = clean.removePrefix(trigger).trim()
        var customSinger: String? = null
        if (settings.customSingerEnabled()) {
            val separator = keyword.indexOf('&')
            if (separator >= 0) {
                customSinger = keyword.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                keyword = keyword.substring(0, separator).trim()
            }
        }
        return keyword.takeIf { it.isNotEmpty() }?.let { MusicCommand(it, customSinger) }
    }

    private fun sendReply(talker: String, msgId: Long, content: String) {
        if (msgId > 0L && WeMessageApi.sendQuoteTextByMsgSvrId(talker, msgId, content)) return
        WeMessageApi.sendText(talker, content)
    }

    private fun normalizeContent(content: String, group: Boolean): String {
        if (!group) return content.trim()
        val marker = when {
            content.contains(":\n") -> ":\n"
            content.contains(":\\n") -> ":\\n"
            else -> return content.trim()
        }
        return content.substringAfter(marker).trim()
    }

    private fun downloadThumb(url: String): ByteArray? {
        if (url.isBlank()) return null
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection?.connectTimeout = 10_000
            connection?.readTimeout = 10_000
            connection?.instanceFollowRedirects = true
            connection?.setRequestProperty("User-Agent", "MicroMessenger Client")
            val responseCode = connection?.responseCode ?: return@runCatching null
            if (responseCode !in 200..299) return@runCatching null
            connection?.inputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_THUMB_BYTES) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.onFailure { logError("QQ点歌封面下载失败", it) }
            .getOrNull()
            .also { connection?.disconnect() }
    }

    private fun downloadAudio(url: String, part: File, target: File): Boolean {
        if (url.isBlank()) return false
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(url).openConnection() as HttpURLConnection
            connection?.connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            connection?.readTimeout = AUDIO_READ_TIMEOUT_MS
            connection?.instanceFollowRedirects = true
            connection?.setRequestProperty("User-Agent", "MicroMessenger Client")
            connection?.setRequestProperty("Referer", "https://y.qq.com/")
            val responseCode = connection?.responseCode ?: return@runCatching false
            if (responseCode !in 200..299) return@runCatching false
            val contentType = connection?.contentType.orEmpty().substringBefore(';').lowercase()
            if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                return@runCatching false
            }
            val contentLength = connection?.contentLengthLong ?: -1L
            if (contentLength > MAX_AUDIO_BYTES) return@runCatching false
            var total = 0L
            var oversized = false
            connection?.inputStream?.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) {
                            oversized = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@runCatching false
            if (oversized || !part.isFile || part.length() <= 0L) return@runCatching false
            if (target.exists() && !target.delete()) return@runCatching false
            part.renameTo(target) && target.isFile && target.length() > 0L
        }.onFailure { logError("QQ点歌歌曲音频下载失败", it) }
            .getOrDefault(false)
            .also { connection?.disconnect() }
    }

    private fun audioDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000L
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun audioSuffix(url: String): String {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return SUPPORTED_AUDIO_SUFFIXES.firstOrNull(path::endsWith) ?: ".audio"
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) file.deleteOnExit()
    }

    private data class MusicCommand(val keyword: String, val customSinger: String?)

    companion object {
        const val ENABLE_CURRENT_CHAT_COMMAND = "开启点歌"
        const val DISABLE_CURRENT_CHAT_COMMAND = "关闭点歌"
        private const val MESSAGE_MAX_AGE_MS = 30_000L
        private const val MAX_THUMB_BYTES = 128 * 1024
        private const val VOICE_CACHE_DIR = "qq_music_order_voice"
        private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
        private const val AUDIO_READ_TIMEOUT_MS = 30_000
        private const val MAX_AUDIO_BYTES = 128L * 1024L * 1024L
        private val SUPPORTED_AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".wav")
        private const val DEFAULT_BUFFER_SIZE = 8192
    }
}
