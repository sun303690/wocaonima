package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 「帮我回」建议的本地落盘缓存。
 *
 * 只存模型产出的建议正文与理由，不存微信聊天原文 —— 原文就地即弃，落盘的只有结论。
 * 另记一份当时的网名／群名与 talker 摘要，好让这堆账人眼也认得出是谁。
 * 键是一枚输入指纹：会话、末条消息、条数、身份、自定义身份、背景、方向、协议版本，
 * 任一段变动，旧账即废，免得内容换了还端出旧话。
 *
 * 落盘位置在微信自己的外部媒体目录下，与 WeKit 其余配置分开存放；
 * 有效期 20 天，写入时顺带扫一遍目录清过期件（懒清理，不另起定时器，不多耗电）。
 */
object ReplyCache {

    private const val TAG = "ReplyCache"
    private const val VERSION = 1
    private const val TTL_MS = 20L * 24 * 60 * 60 * 1000
    private const val DIR_NAME = "kite_reply_cache"
    // 与 WEX、lh、Hchat 这些模块平辈，直接坐在微信的外部媒体目录下；
    // 早先多写了一层 wechat/，那层本就不存在，改了。
    private const val BASE_DIR = "/sdcard/Android/media/com.tencent.mm"
    private const val MAX_NAME = 64

    private val root: File? by lazy {
        runCatching {
            val dir = File(BASE_DIR, DIR_NAME)
            if (!dir.isDirectory) dir.mkdirs()
            // 跟 WEX 学的：压一张 .nomedia，免得被图库或微信自己的清扫当成杂图。
            runCatching { File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
            dir.takeIf { it.isDirectory && it.canWrite() }
        }.onFailure { WeLogger.w(TAG, "cache dir unavailable", it) }.getOrNull()
    }

    /**
     * 输入指纹。末条消息用文本本身参与摘要，不必依赖微信消息 id，
     * 跨进程、跨重启都算得一样。
     */
    fun fingerprint(
        talker: String,
        lastText: String,
        count: Int,
        relationship: String,
        custom: String,
        background: String,
        direction: String,
    ): String = sha1(
        listOf(
            VERSION.toString(),
            talker,
            lastText.trim(),
            count.toString(),
            relationship,
            custom.trim(),
            background.trim(),
            direction.trim(),
        ).joinToString("\u0001")
    )

    /** 命中即返回；指纹不符、过期、残破一律当未命中，并顺手抹掉那条坏账。 */
    fun load(talker: String, fingerprint: String): ReplySuggestion? {
        val file = fileFor(talker) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optInt("version") != VERSION) return@runCatching null
            if (json.optString("fingerprint") != fingerprint) return@runCatching null
            if (isExpired(json.optLong("savedAt"))) {
                file.delete()
                return@runCatching null
            }
            val array = json.optJSONArray("parts") ?: return@runCatching null
            val parts = (0 until array.length()).map { array.optString(it).trim() }
                .filter { it.isNotBlank() }
            if (parts.isEmpty()) return@runCatching null
            ReplySuggestion(parts, json.optString("reason"))
        }.getOrElse {
            WeLogger.w(TAG, "bad cache entry, drop", it)
            runCatching { file.delete() }
            null
        }
    }

    fun save(
        talker: String,
        fingerprint: String,
        suggestion: ReplySuggestion,
        displayName: String = "",
    ) {
        val file = fileFor(talker) ?: return
        runCatching {
            val json = JSONObject()
                .put("version", VERSION)
                .put("fingerprint", fingerprint)
                .put("savedAt", System.currentTimeMillis())
                .put("name", displayName.trim().take(MAX_NAME))
                // 只留 talker 摘要的头八位，够人眼对照，不把 wxid 整条摊在外部目录里。
                .put("tag", sha1(talker).take(8))
                .put("parts", JSONArray(suggestion.parts))
                .put("reason", suggestion.reason)
            file.writeText(json.toString())
            sweep()
        }.onFailure { WeLogger.w(TAG, "save cache failed", it) }
    }

    /** 该会话上一次记账时的网名／群名；没有账或读不出来就给空串。 */
    fun nameOf(talker: String): String {
        val file = fileFor(talker) ?: return ""
        if (!file.isFile) return ""
        return runCatching { JSONObject(file.readText()).optString("name") }.getOrDefault("")
    }

    /** 懒清理：过期件、空文件、读不出来的，一概抹掉。 */
    fun sweep() {
        val dir = root ?: return
        runCatching {
            dir.listFiles()?.forEach { entry ->
                if (!entry.isFile) return@forEach
                if (entry.length() == 0L || isExpired(entry.lastModified())) entry.delete()
            }
        }.onFailure { WeLogger.w(TAG, "sweep failed", it) }
    }

    private fun isExpired(stamp: Long): Boolean =
        stamp <= 0L || System.currentTimeMillis() - stamp > TTL_MS

    private fun fileFor(talker: String): File? {
        val dir = root ?: return null
        return File(dir, sha1(talker) + ".json")
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
