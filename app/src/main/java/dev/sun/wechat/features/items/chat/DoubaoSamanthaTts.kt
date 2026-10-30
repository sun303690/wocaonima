package dev.sun.wechat.features.items.chat

import android.os.Handler
import android.os.Looper
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * 豆包网页端 Samantha 音频通道的 WebSocket 客户端。
 *
 * 协议来自用户提供的 doubao_tts.py 与 doubao-voice-audition.js：
 * 握手即鉴权（依赖 doubao.com 登录态 Cookie，缺 Cookie 一定失败），连接建立后先发
 * `{"event":"text","text":...}` 再发 `{"event":"finish"}`；服务端回二进制帧即 aac 音频，
 * 回文本帧则是 open_success / sentence_start / sentence_end / error，或带非 0 的 code。
 *
 * 服务端不会主动关闭连接，所以「静默 [IDLE_GAP_MS]」即视为收完；另有 [RECV_TIMEOUT_MS]
 * 的硬上限，避免网络半死时线程一直挂着。
 *
 * 节流照抄 js 试听面板：两次真实请求至少间隔 [MIN_GAP_MS]（不足则等待），一旦收到服务端
 * 710022002 风控码就进入 [RISK_COOLDOWN_MS] 冷却，冷却期直接拒绝并把剩余秒数回给调用方。
 */
object DoubaoSamanthaTts {

    private const val TAG = "DoubaoSamanthaTts"

    private const val WS_ENDPOINT = "wss://ws-samantha.doubao.com/samantha/audio/tts"
    private const val ORIGIN = "https://www.doubao.com"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val AID = 497858
    private const val VERSION_CODE = 20800
    private const val PC_VERSION = "2.46.3"

    /** 单次合成的硬超时：这么久还没收完就断开 */
    private const val RECV_TIMEOUT_MS = 30_000L

    /** 服务端不主动关连接, 静默这么久即视为合成结束（留 1 秒余量，避免长句间隔被截断） */
    private const val IDLE_GAP_MS = 3_500L

    /** 两次真实合成的最小间隔 (js 面板的 MIN_GAP) */
    private const val MIN_GAP_MS = 8_000L

    /** 服务端 710022002 触发后的冷却时长 (js 面板的 coolUntil) */
    private const val RISK_COOLDOWN_MS = 180_000L

    private const val RISK_CODE = "710022002"

    /** 试听用的固定短句 */
    const val AUDITION_TEXT = "你好，我是豆包语音"

    /**
     * 内置 9 个音色: id 到中文展示名。这份表是唯一事实来源，
     * [TextToSpeech.DOUBAO_VOICES]、设置页试听列表都从它派生。
     */
    val VOICES: List<Pair<String, String>> = listOf(
        "zh_female_taozi_conversation_v4_wvae_bigtts" to "桃子 · 女声对话",
        "zh_female_shuangkuai_emo_v3_wvae_bigtts" to "爽快 · 女声",
        "zh_female_tianmei_conversation_v4_wvae_bigtts" to "甜美 · 女声",
        "zh_female_qingche_moon_bigtts" to "清澈 · 女声",
        "zh_male_yangguang_conversation_v4_wvae_bigtts" to "阳光 · 男声",
        "zh_male_chenwen_moon_bigtts" to "沉稳 · 男声",
        "zh_male_rap_mars_bigtts" to "说唱 · 男声",
        "en_female_sarah_conversation_bigtts" to "英文女声 Sarah",
        "en_male_adam_conversation_bigtts" to "英文男声 Adam",
    )

    private val mh = Handler(Looper.getMainLooper())

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    private val gate = Any()
    private var lastRequestAt = 0L
    private var coolUntil = 0L

    /** 音色 id 的展示名, 表里没有就退回 id 本身。 */
    fun voiceLabel(id: String): String = VOICES.firstOrNull { it.first == id }?.second ?: id

    /** 风控冷却剩余毫秒数, 0 表示未冷却。 */
    fun cooldownRemainingMs(): Long = synchronized(gate) {
        val left = coolUntil - System.currentTimeMillis()
        if (left > 0) left else 0L
    }

    /**
     * 合成一段文本并落盘成 aac。
     *
     * @param text 要朗读的文本
     * @param speaker 音色 id, 取 [VOICES] 里的第一个元素
     * @param cookie doubao.com 登录态 Cookie, 形如 `sessionid=…; sid_guard=…; uid_tt=…`
     * @param ratePercent speech_rate 原值透传 (js 面板就是这个量纲, 未做 -1..1 换算)
     * @param callback 主线程回调 (音频文件路径, 错误信息)；成功时错误信息为空串
     */
    fun synthesize(
        text: String,
        speaker: String,
        cookie: String,
        ratePercent: Int = 0,
        callback: (String?, String) -> Unit,
    ) {
        Thread { runSynthesis(text, speaker, cookie, ratePercent, callback) }.start()
    }

    private fun runSynthesis(
        text: String,
        speaker: String,
        cookie: String,
        ratePercent: Int,
        callback: (String?, String) -> Unit,
    ) {
        // once 保证回调最多投递一次: 线程被中断/内部抛异常时用错误兜底, 不让调用方永久等待
        val once = AtomicBoolean(false)
        try {
            runSynthesisBody(text, speaker, cookie, ratePercent, callback, once)
        } catch (t: Throwable) {
            deliver(callback, null, "合成异常: " + (t.message ?: t.toString()), once)
        }
    }

    private fun runSynthesisBody(
        text: String,
        speaker: String,
        cookie: String,
        ratePercent: Int,
        callback: (String?, String) -> Unit,
        once: AtomicBoolean,
    ) {
        if (cookie.isBlank()) {
            deliver(callback, null, "未填写豆包 Cookie", once)
            return
        }
        if (text.isBlank()) {
            deliver(callback, null, "文本为空", once)
            return
        }

        val blocked = acquireSlot()
        if (blocked != null) {
            deliver(callback, null, blocked, once)
            return
        }

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val errorRef = AtomicReference("")

        val request = Request.Builder()
            .url(buildUrl(speaker, ratePercent))
            .header("Origin", ORIGIN)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Cookie", cookie)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("event", "text").put("text", text).toString())
                webSocket.send(JSONObject().put("event", "finish").toString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(audio) { audio.write(bytes.toByteArray()) }
            }

            override fun onMessage(webSocket: WebSocket, raw: String) {
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val event = obj.optString("event")
                val code = obj.optInt("code", 0)
                if (event == "error" || code != 0) {
                    errorRef.compareAndSet("", describe(code, obj.optString("message")))
                    webSocket.cancel()
                    done.countDown()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    errorRef.compareAndSet("", "连接被关闭 (code=$code $reason)")
                }
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorRef.compareAndSet("", "连接失败: " + (t.message ?: t.toString()))
                done.countDown()
            }
        })

        val deadline = System.currentTimeMillis() + RECV_TIMEOUT_MS
        var received = 0
        var lastGrowth = System.currentTimeMillis()
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                if (done.await(minOf(remaining, 200L), TimeUnit.MILLISECONDS)) break
                val size = synchronized(audio) { audio.size() }
                if (size != received) {
                    received = size
                    lastGrowth = System.currentTimeMillis()
                } else if (size > 0 && System.currentTimeMillis() - lastGrowth > IDLE_GAP_MS) {
                    break
                }
            }
        } catch (_: InterruptedException) {
            ws.cancel()
            deliver(callback, null, "合成被中断", once)
            return
        }
        ws.cancel()

        val bytes = synchronized(audio) { audio.toByteArray() }
        if (bytes.isEmpty()) {
            val raw = errorRef.get().ifEmpty { "接收超时 (${RECV_TIMEOUT_MS / 1000} 秒内没有收到音频)" }
            deliver(callback, null, friendly(raw), once)
            return
        }

        val dest = File(HostInfo.application.cacheDir, "tts_${System.currentTimeMillis()}.aac")
        dest.writeBytes(bytes)
        if (errorRef.get().isNotEmpty()) {
            WeLogger.w(TAG, "部分音频已落盘, 但服务端报错: ${errorRef.get()}")
        }
        deliver(callback, dest.absolutePath, "", once)
    }

    /** 拿到一次请求槽位；返回 null 表示可以发，否则返回给用户看的拒绝原因。会阻塞到满足最小间隔。 */
    private fun acquireSlot(): String? = synchronized(gate) {
        val now = System.currentTimeMillis()
        if (coolUntil > now) {
            val left = (coolUntil - now + 999) / 1000
            return@synchronized "豆包风控冷却中（$RISK_CODE），请 $left 秒后再试"
        }
        val gap = lastRequestAt + MIN_GAP_MS - now
        if (gap > 0) runCatching { Thread.sleep(gap) }
        lastRequestAt = System.currentTimeMillis()
        null
    }

    /** 命中风控码时进冷却, 并把剩余秒数补进错误文案。 */
    private fun friendly(raw: String): String {
        if (!raw.contains(RISK_CODE)) return raw
        synchronized(gate) { coolUntil = System.currentTimeMillis() + RISK_COOLDOWN_MS }
        return "$raw（已进入 ${RISK_COOLDOWN_MS / 1000} 秒冷却）"
    }

    private fun describe(code: Int, message: String): String = when {
        code != 0 && message.isNotEmpty() -> "code=$code $message"
        code != 0 -> "code=$code 合成失败"
        message.isNotEmpty() -> message
        else -> "服务端返回 error 但未说明原因"
    }

    private fun buildUrl(speaker: String, ratePercent: Int): String {
        // 三个身份字段每次随机, 伪装成一次全新的网页会话
        val dev = (7_400_000_000_000_000_000L + Random.nextLong(999_999_999_999_999_999L)).toString()
        return "$WS_ENDPOINT?speaker=$speaker&format=aac&speech_rate=$ratePercent&pitch=0" +
            "&version_code=$VERSION_CODE&language=zh&device_platform=web" +
            "&aid=$AID&real_aid=$AID&pkg_type=release_version" +
            "&device_id=$dev&pc_version=$PC_VERSION&web_id=$dev&tea_uuid=$dev" +
            "&region=&sys_region=&samantha_web=1&use-olympus-account=1&web_tab_id=${UUID.randomUUID()}"
    }

    private fun deliver(
        callback: (String?, String) -> Unit,
        path: String?,
        error: String,
        once: AtomicBoolean,
    ) {
        if (!once.compareAndSet(false, true)) return
        if (error.isNotEmpty()) WeLogger.w(TAG, "samantha tts failed: $error")
        mh.post { callback(path, error) }
    }
}
