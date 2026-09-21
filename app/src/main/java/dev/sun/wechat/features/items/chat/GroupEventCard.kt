package dev.sun.wechat.features.items.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.sun.wechat.features.api.core.WeAppMsgApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.fs.KnownPaths
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.math.min

/**
 * 进退群通知卡片：左侧成员头像 + 右侧通知文本。
 * 渲染为 PNG 临时文件，调用方经 WeMessageApi.sendImage 发群后删除。
 */
object GroupEventCard {

    private const val TAG = "GroupEventCard"
    private const val W = 680
    private const val PAD = 36
    private const val AVATAR = 132
    private const val GAP = 30
    private const val TITLE_SIZE = 40
    private const val BODY_SIZE = 30
    private const val LINE = 44
    private const val CARD_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val REAL_NAME_WAIT_MS = 4_000L

    private val AVATAR_COLORS = intArrayOf(
        Color.rgb(0, 171, 122),
        Color.rgb(255, 77, 79),
        Color.rgb(22, 119, 255),
        Color.rgb(255, 158, 0),
        Color.rgb(124, 77, 255),
        Color.rgb(0, 150, 136),
    )

    data class Event(
        val isJoin: Boolean,
        val wxid: String,
        val weNick: String,       // %userName% 微信昵称
        val groupNick: String,    // %groupNickname% 群内昵称（空则回退微信昵称）
        val inviter: String,      // %inviter% 邀请人（仅进群）
        val realNameTail: String, // 实名（掩码，如 "**辰"；来源见 [realName]）
        val groupName: String,    // %groupName%
    )

    /**
     * 取成员实名（掩码，如 `**辰` / `A*辰`）。数据来自模块已有的实名缓存：
     * [DisplayGroupMemberRealNamesLastChar]（尾字，走 beforetransfer CGI）与
     * [BruteForceGroupMemberRealNamesFirstChar]（首字，爆破）；两者都未命中时返回空串。
     * 本函数只读缓存；卡片组装用 [resolveRealName]，后者在缓存未命中时会补抓一次。
     */
    fun realName(wxid: String): String {
        if (wxid.isBlank()) return ""
        val last = DisplayGroupMemberRealNamesLastChar
            .takeIf { it.isActive }?.realNames?.get(wxid)
        val first = BruteForceGroupMemberRealNamesFirstChar
            .takeIf { it.isActive }?.realNames?.get(wxid)
        return when {
            first != null && last != null -> {
                val tail = last.last()
                val middle = last.dropLast(1)
                if (middle.isEmpty()) "$first$tail" else "$first*$tail"
            }
            first != null -> "$first?"
            else -> last.orEmpty()
        }
    }

    /**
     * 卡片用实名：先读缓存；未命中且「显示群成员实名尾字」已启用时，主动补抓一次并最多等
     * [REAL_NAME_WAIT_MS]。开关关闭时不补抓（其缓存未加载，写入会覆盖用户已有缓存）。
     * 必须在非主线程调用。
     */
    private fun resolveRealName(wxid: String, groupId: String): String {
        realName(wxid).takeIf { it.isNotBlank() }?.let { return it }
        if (wxid.isBlank() || !DisplayGroupMemberRealNamesLastChar.isActive) return ""
        DisplayGroupMemberRealNamesLastChar.fetchRealNameBlocking(wxid, groupId, REAL_NAME_WAIT_MS)
        return realName(wxid)
    }

    private val avatarCache = HashMap<String, Bitmap>()

    /** 渲染卡片，返回临时文件；失败返回 null。 */
    fun render(ev: Event): File? {
        cleanupStaleCards()
        val bmp = try {
            draw(ev)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "render failed", t)
            null
        } ?: return null
        val file = File(KnownPaths.moduleCache.toString(), "gmev-${UUID.randomUUID()}.png")
        return try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        } catch (t: Throwable) {
            WeLogger.e(TAG, "write card file failed", t)
            null
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 组装进退群事件（解析群内昵称/邀请人/实名/群名），图片卡片与图文卡片共用。
     */
    fun buildEvent(groupId: String, wxid: String, weNick: String, isJoin: Boolean): Event {
        val groupNick = runCatching { WeDatabaseApi.getGroupMemberDisplayName(groupId, wxid) }
            .getOrNull().orEmpty()
        val inviter = if (isJoin) {
            val inviterWxid = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, wxid) }
                .getOrNull().orEmpty()
            if (inviterWxid.isBlank() || inviterWxid == wxid) ""
            else runCatching { WeDatabaseApi.getDisplayName(inviterWxid) }.getOrNull().orEmpty()
        } else ""
        val groupName = runCatching { WeDatabaseApi.getGroup(groupId)?.nickname }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: groupId
        return Event(isJoin, wxid, weNick, groupNick, inviter, resolveRealName(wxid, groupId), groupName)
    }

    /**
     * 图片卡片入口：组装事件后渲染为 PNG 临时文件。调用方用 [WeMessageApi.sendImage] 发出，
     * 并在发出后延迟删除（见调用点的 CARD_FILE_KEEP_MS）。
     */
    fun renderEvent(groupId: String, wxid: String, weNick: String, isJoin: Boolean): File? =
        render(buildEvent(groupId, wxid, weNick, isJoin))

    /**
     * 图文卡片入口：以微信 AppMsg（`type=5` 链接卡）发出进退群通知——标题+字段在左侧、
     * 成员头像在右侧，点击卡片打开该成员头像 URL。返回 false 表示未发出，调用方回退到
     * [renderEvent] 的图片卡片。
     */
    fun sendEventAppMsg(groupId: String, wxid: String, weNick: String, isJoin: Boolean): Boolean =
        sendAppMsg(groupId, buildEvent(groupId, wxid, weNick, isJoin))

    /**
     * 见 [sendEventAppMsg]。头像 URL 取不到时直接返回 false，由调用方回退图片卡片。
     *
     * 字段区只放 3 行：微信链接卡只渲染卡片高度能容纳的行数，超出部分（含最后一行）会被
     * 截掉，所以字段必须压缩，且把 `实名` 放在第 3 行保证可见。
     */
    fun sendAppMsg(toUser: String, ev: Event): Boolean {
        val title = if (ev.isJoin) "进群通知" else "退群通知"
        val who = if (ev.isJoin) "进群者" else "退群者"
        val des = buildString {
            append(who).append("群内昵称：").append(ev.groupNick.ifBlank { ev.weNick })
            append('\n').append(who).append("ID：").append(ev.wxid.ifBlank { "未知" })
            append('\n').append("实名：").append(ev.realNameTail.ifBlank { "-" })
        }
        val avatarUrl = runCatching { WeDatabaseApi.getAvatarUrl(ev.wxid) }.getOrNull().orEmpty()
        if (!avatarUrl.startsWith("http")) return false
        val thumb = fetchBytes(avatarUrl) ?: return false
        val xml = buildLinkCardXml(title, des, avatarUrl, avatarUrl)
        return WeAppMsgApi.sendXmlAppMsg(toUser, title, "", avatarUrl, thumb, xml)
    }

    /**
     * 群内昵称变更提醒卡片（AppMsg 链接卡，头像可点击）。
     * 头像 URL 取不到时返回 false，由调用方回退文本系统消息。
     */
    fun sendRenameAppMsg(toUser: String, wxId: String, weNick: String, oldName: String, newName: String): Boolean {
        val who = weNick.ifBlank { wxId }
        val title = "改名提醒"
        val des = buildString {
            append(who).append(" (").append(wxId.ifBlank { "未知" }).append(')')
            append('\n').append("原昵称：").append(oldName.ifBlank { "-" })
            append('\n').append("新昵称：").append(newName.ifBlank { "-" })
        }
        val avatarUrl = runCatching { WeDatabaseApi.getAvatarUrl(wxId) }.getOrNull().orEmpty()
        if (!avatarUrl.startsWith("http")) return false
        val thumb = fetchBytes(avatarUrl) ?: return false
        val xml = buildLinkCardXml(title, des, avatarUrl, avatarUrl)
        return WeAppMsgApi.sendXmlAppMsg(toUser, title, "", avatarUrl, thumb, xml)
    }

    /** 微信链接卡（AppMsg type=5）：`url` 即点击后打开的地址（此处为该成员头像 URL）。 */
    private fun buildLinkCardXml(title: String, des: String, url: String, thumbUrl: String): String =
        buildString {
            append("<msg><appmsg appid=\"\" sdkver=\"0\">")
            append("<title>").append(xmlEscape(title)).append("</title>")
            append("<des>").append(xmlEscape(des)).append("</des>")
            append("<action>view</action><type>5</type><showtype>0</showtype>")
            append("<url>").append(xmlEscape(url)).append("</url>")
            append("<thumburl>").append(xmlEscape(thumbUrl)).append("</thumburl>")
            append("</appmsg></msg>")
        }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun fetchBytes(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
    } catch (t: Throwable) {
        WeLogger.w(TAG, "avatar bytes fetch failed: ${t.message}")
        null
    }

    /** 清理 24h 前残留的 gmev-*.png（进程被杀 / 延时删除没跑时的兜底）。 */
    private fun cleanupStaleCards() {
        val cutoff = System.currentTimeMillis() - CARD_CACHE_MAX_AGE_MS
        runCatching {
            KnownPaths.moduleCache.listDirectoryEntries().filter {
                it.isRegularFile() &&
                    it.name.startsWith("gmev-") &&
                    it.name.endsWith(".png") &&
                    it.getLastModifiedTime().toMillis() < cutoff
            }.forEach { it.deleteIfExists() }
        }.onFailure { WeLogger.w(TAG, "failed to clean stale cards", it) }
    }

    private fun draw(ev: Event): Bitmap {
        val who = if (ev.isJoin) "进群者" else "退群者"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val body = buildList {
            add("时间：$time")
            add("群名称：${ev.groupName}")
            add("${who}微信昵称：${ev.weNick}")
            add("${who}群内昵称：${ev.groupNick.ifBlank { ev.weNick }}")
            add("${who}ID：${ev.wxid.ifBlank { "未知" }}")
            if (ev.isJoin && ev.inviter.isNotBlank()) add("邀请人：${ev.inviter}")
            add("实名：${ev.realNameTail.ifBlank { "-" }}")
        }
        val h = PAD + TITLE_SIZE + 26 + body.size * LINE + PAD
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 左侧头像（失败则首字占位圆）
        val ax = (PAD + AVATAR / 2).toFloat()
        val ay = (h / 2).toFloat()
        val avatar = decodeAvatar(ev.wxid)
        if (avatar != null) {
            c.drawBitmap(avatar, ax - AVATAR / 2f, ay - AVATAR / 2f, null)
        } else {
            p.color = AVATAR_COLORS[abs(ev.wxid.hashCode()) % AVATAR_COLORS.size]
            c.drawCircle(ax, ay, AVATAR / 2f, p)
            p.color = Color.WHITE
            p.textSize = AVATAR / 2.2f
            p.textAlign = Paint.Align.CENTER
            val ch = ev.weNick.ifBlank { "?" }.first().toString()
            val fm = p.fontMetrics
            c.drawText(ch, ax, ay - (fm.ascent + fm.descent) / 2f, p)
        }

        // 右侧通知文本
        val tx = (PAD + AVATAR + GAP).toFloat()
        p.textAlign = Paint.Align.LEFT
        p.color = Color.rgb(28, 28, 30)
        p.textSize = TITLE_SIZE.toFloat()
        p.isFakeBoldText = true
        c.drawText(if (ev.isJoin) "进群通知：" else "退群通知：", tx, (PAD + TITLE_SIZE).toFloat(), p)
        p.isFakeBoldText = false
        p.color = Color.rgb(60, 60, 62)
        p.textSize = BODY_SIZE.toFloat()
        var y = (PAD + TITLE_SIZE + 26 + BODY_SIZE).toFloat()
        for (line in body) {
            c.drawText(line, tx, y, p)
            y += LINE
        }
        return bmp
    }

    /** 从 img_flag 表的 qlogo URL 拉头像并裁圆形；失败返回 null。 */
    private fun decodeAvatar(wxid: String): Bitmap? {
        if (wxid.isEmpty()) return null
        avatarCache[wxid]?.let { return it }
        val url = runCatching { WeDatabaseApi.getAvatarUrl(wxid) }.getOrNull().orEmpty()
        if (!url.startsWith("http")) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: return null
                val circ = circle(raw, AVATAR)
                if (circ !== raw) raw.recycle()
                synchronized(avatarCache) { avatarCache[wxid] = circ }
                circ
            }
        } catch (t: Throwable) {
            WeLogger.w(TAG, "avatar fetch failed wxid=$wxid: ${t.message}")
            null
        }
    }

    private fun circle(bmp: Bitmap, size: Int): Bitmap {
        val side = min(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = if (sq.width != size || sq.height != size) {
            Bitmap.createScaledBitmap(sq, size, size, true).also { if (it !== sq) sq.recycle() }
        } else {
            sq
        }
        scaled.eraseColor(Color.TRANSPARENT)
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
        Canvas(scaled).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        mask.recycle()
        return scaled
    }
}
