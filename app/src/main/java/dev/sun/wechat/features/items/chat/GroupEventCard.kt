package dev.sun.wechat.features.items.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
     * 只读缓存，不在此处触发网络请求，避免进退群事件批量打转账 CGI。
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
     * 便捷入口：给定群ID、成员wxid、微信昵称、是否进群，
     * 内部解析群内昵称/邀请人/实名尾字/群名，渲染卡片返回临时文件。
     * 调用方负责 [WeMessageApi.sendImage] 发出后删除文件。
     */
    fun renderEvent(groupId: String, wxid: String, weNick: String, isJoin: Boolean): File? {
        val groupNick = runCatching { WeDatabaseApi.getGroupMemberDisplayName(groupId, wxid) }
            .getOrNull().orEmpty()
        val inviter = if (isJoin) {
            val inviterWxid = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, wxid) }
                .getOrNull().orEmpty()
            if (inviterWxid.isBlank() || inviterWxid == wxid) ""
            else runCatching { WeDatabaseApi.getDisplayName(inviterWxid) }.getOrNull().orEmpty()
        } else ""
        val tail = realName(wxid)
        val groupName = runCatching { WeDatabaseApi.getGroup(groupId)?.nickname }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: groupId
        return render(Event(isJoin, wxid, weNick, groupNick, inviter, tail, groupName))
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
