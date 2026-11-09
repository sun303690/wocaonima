package dev.sun.wechat.features.items.chat

import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.utils.AndroidAudioDecoder
import dev.sun.wechat.utils.AudioUtils
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.MediaFileTypeDetector
import dev.sun.wechat.utils.WeLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 语音识别（ASR）：把微信语音消息转成文字，喂给 AI 回复。
 *
 * 微信自己的「转文字」是可选、而且常常不可用（对方没开、服务端不给、只活在内存 bean 里），
 * 所以这里自带一条 OpenAI 兼容的识别通道：`POST {baseUrl}/audio/transcriptions`（multipart，
 * 字段 model + file，返回 {"text": "..."}）。硅基流动 SenseVoice、OpenAI whisper、Groq、
 * 各类中转站都是这个形状，填地址 + Key + 模型名即可。
 *
 * 流程：微信语音是 SILK → `AudioUtils.silkToPcm` → `AudioUtils.pcmToMp3` → 上传 → 文本。
 * 结果按 encPath 缓存在 cacheDir/asr 下，同一条语音不会重复花钱。
 */
object AsrEngine {

    private const val TAG = "AsrEngine"
    private const val DEFAULT_MODEL = "whisper-1"

    /** native 的 pcmToMp3 按 24 kHz 写死，解码出来的 PCM 必须先重采样到这里。 */
    private const val TARGET_SAMPLE_RATE = 24000

    /** 设置页「选择模型」里的候选，用户也可以自己填别的。 */
    val MODELS = listOf(
        "whisper-1",
        "gpt-4o-transcribe",
        "gpt-4o-mini-transcribe",
        "FunAudioLLM/SenseVoiceSmall",
        "whisper-large-v3",
    )

    /**
     * 一键填好「地址 + 模型」的服务商预设（Key 仍要自己去开通后填）。
     *
     * 只收真正提供 OpenAI 兼容 `/audio/transcriptions` 的服务商。七牛云
     * (api.qnaigc.com) 没有这个端点（实测 404），所以不在列表里。
     */
    class Preset(val name: String, val baseUrl: String, val model: String)

    val PRESETS = listOf(
        Preset("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"),
        Preset("OpenAI", "https://api.openai.com/v1", "whisper-1"),
        Preset("Groq", "https://api.groq.com/openai/v1", "whisper-large-v3"),
    )

    /** 套用预设：只写地址与模型，不动 Key 与开关。 */
    fun applyPreset(preset: Preset) {
        baseUrl = preset.baseUrl
        model = preset.model
    }

    /** 接口根地址，例如 https://api.siliconflow.cn/v1 ；也接受直接填完整的 .../audio/transcriptions。 */
    var baseUrl by prefOption("asr_base_url", "")
    var apiKey by prefOption("asr_api_key", "")
    var model by prefOption("asr_model", DEFAULT_MODEL)

    /**
     * 语音识别总闸。开关与地址/Key 都在「多模态识别」那一页里（两件事同一个界面），
     * 涉及联网花钱，默认关。
     */
    var enabled by prefOption("asr_enabled", false)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 是否具备自动识别能力（开关打开，且地址、Key 都填了）。 */
    fun ready(): Boolean =
        enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()

    /**
     * 识别一条语音消息，回调在后台线程。成功回 (文本, null)，失败回 (null, 原因)。
     */
    fun transcribeMessage(message: MessageInfo, cb: (String?, String?) -> Unit) {
        if (message.type?.code != MessageType.VOICE.code) {
            cb(null, "不是语音消息"); return
        }
        if (!ready()) {
            cb(null, "未配置语音识别接口"); return
        }
        val encPath = message.imagePath
        if (encPath.isNullOrBlank()) {
            cb(null, "拿不到语音文件路径"); return
        }
        thread(name = "wekit-asr") {
            runCatching { transcribeEncPath(encPath) }
                .onSuccess { cb(it, null) }
                .onFailure { cb(null, it.message ?: "识别失败") }
        }
    }

    /**
     * 同步识别一个微信语音 encPath（阻塞，调用方自己放 IO 线程）。
     * @return 识别文本；失败抛异常。
     */
    fun transcribeEncPath(encPath: String): String {
        val key = cacheKey(encPath)
        val dir = File(HostInfo.application.cacheDir, "asr").apply { mkdirs() }
        val textCache = File(dir, "$key.txt")
        textCache.takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val sourcePath = WeMessageApi.getVoiceFullPath(encPath)
        if (sourcePath.isBlank()) throw IllegalStateException("语音文件还没下载")
        val source = File(sourcePath)
        if (!source.isFile || source.length() == 0L) {
            throw IllegalStateException(
                "语音文件不存在或为空（${source.name}，${source.length()} 字节）"
            )
        }
        val format = MediaFileTypeDetector.detectAudio(source.toPath())
        val info = "文件=${source.name} 大小=${source.length()} 格式=${format?.name ?: "未识别"} 头=${headHex(source)}"
        WeLogger.d(TAG, "语音源 $info")

        val pcm = File(dir, "$key.pcm")
        val mp3 = File(dir, "$key.mp3")
        val ok = when (format) {
            MediaFileTypeDetector.AudioFormat.SILK ->
                if (!AudioUtils.silkToPcm(sourcePath, pcm.absolutePath)) {
                    throw IllegalStateException("SILK 转 PCM 失败（$info）")
                } else {
                    AudioUtils.pcmToMp3(pcm.absolutePath, mp3.absolutePath)
                }

            null -> throw IllegalStateException("认不出这个语音的格式（$info）")

            // 微信部分版本把语音存成 AMR/其他容器，native 的 SILK 解码器认不了，
            // 交给系统 MediaCodec 解码，再重采样到 24 kHz（native 的 pcmToMp3 按 24 kHz 写死）。
            else -> {
                val decoded = runCatching {
                    AndroidAudioDecoder.decodeToPcm16(sourcePath, pcm)
                }.getOrElse {
                    throw IllegalStateException(
                        "解码 ${format?.name ?: "未知"} 失败：${it.message}（$info）"
                    )
                }
                if (decoded.channelCount > 1) {
                    throw IllegalStateException(
                        "语音是 ${decoded.channelCount} 声道，暂不支持（$info）"
                    )
                }
                resampleTo24k(pcm, decoded.sampleRate)
                AudioUtils.pcmToMp3(pcm.absolutePath, mp3.absolutePath)
            }
        }
        pcm.delete()
        val ready = ok && mp3.isFile && mp3.length() > 0
        if (!ready) throw IllegalStateException("PCM 转 MP3 失败（$info）")

        val text = request(mp3)
        mp3.delete()
        if (text.isEmpty()) throw IllegalStateException("接口没返回文字")
        runCatching { textCache.writeText(text) }
        WeLogger.i(TAG, "识别成功 key=$key len=${text.length}")
        return text
    }

    /**
     * 连通性自检（设置页「测试连通性」按钮）。
     *
     * 拿一段 0.5 秒静音 WAV 真打一次 `/audio/transcriptions`：只有 HTTP 2xx 才算通，
     * 静音多半没有转写文本，所以「返回空文本」也判成功——这一步只验地址/Key/模型对不对。
     */
    fun testConnection(cb: (Boolean, String) -> Unit) {
        if (baseUrl.isBlank()) {
            cb(false, "还没填接口地址"); return
        }
        if (apiKey.isBlank()) {
            cb(false, "还没填 Key"); return
        }
        thread(name = "wekit-asr-test") {
            runCatching {
                val dir = File(HostInfo.application.cacheDir, "asr").apply { mkdirs() }
                val probe = File(dir, "probe.wav")
                probe.writeBytes(silentWav(500))
                val text = request(probe)
                probe.delete()
                text
            }.onSuccess { text ->
                cb(true, if (text.isBlank()) "连通正常（静音没有转写结果）" else "连通正常：${text.take(24)}")
            }.onFailure { cb(false, it.message ?: "连接失败") }
        }
    }

    /** 文件头 8 字节的十六进制，出错时带上，方便一眼看出到底是 SILK、AMR 还是别的东西。 */
    private fun headHex(file: File, count: Int = 8): String = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) return@runCatching "空"
            buffer.take(read).joinToString(" ") { "%02X".format(it) }
        }
    }.getOrDefault("读取失败")

    /**
     * 把单声道 16bit PCM 线性重采样到 [TARGET_SAMPLE_RATE]，就地覆盖文件。
     *
     * MediaCodec 解 AMR-NB 出来是 8 kHz，而 native 的 pcmToMp3 按 24 kHz 编码，
     * 不重采样的话会被当成 3 倍速播放，识别结果直接废掉。
     */
    private fun resampleTo24k(file: File, sourceRate: Int) {
        if (sourceRate == TARGET_SAMPLE_RATE || !file.isFile) return
        val bytes = file.readBytes()
        val sourceSamples = bytes.size / 2
        if (sourceSamples < 2) return
        val source = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val ratio = TARGET_SAMPLE_RATE.toDouble() / sourceRate
        val outputSamples = (sourceSamples * ratio).toInt().coerceAtLeast(1)
        val outputBuffer = java.nio.ByteBuffer.allocate(outputSamples * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = outputBuffer.asShortBuffer()
        for (i in 0 until outputSamples) {
            val position = i / ratio
            val index = position.toInt()
            val fraction = position - index
            val current = source.get(index).toInt()
            val next = if (index + 1 < sourceSamples) source.get(index + 1).toInt() else current
            val sample = (current + (next - current) * fraction).toInt()
            output.put(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        file.writeBytes(outputBuffer.array())
        WeLogger.d(TAG, "重采样 ${sourceRate}Hz → ${TARGET_SAMPLE_RATE}Hz，${sourceSamples} → ${outputSamples} 样本")
    }

    /** 0.5 秒 16k / 单声道 / 16bit 静音 WAV，只用来自检，不进缓存。 */
    private fun silentWav(millis: Int): ByteArray {
        val sampleRate = 16000
        val dataSize = sampleRate * millis / 1000 * 2
        val buffer = java.nio.ByteBuffer.allocate(44 + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)              // PCM
        buffer.putShort(1)              // 单声道
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)   // 字节率
        buffer.putShort(2)              // 块对齐
        buffer.putShort(16)             // 位深
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        return buffer.array()
    }

    /** 把各家五花八门的失败翻译成人话，用户看提示就知道下一步该改哪里。 */
    private fun friendlyError(code: Int, body: String): String = when (code) {
        401, 403 -> "Key 不对或没有权限（HTTP $code），检查接口 Key"
        404 -> "这家没有 /audio/transcriptions 转写接口（HTTP 404），换硅基流动 / OpenAI / Groq"
        429 -> "请求太频繁，被限流了（HTTP 429），过一会儿再试"
        400 -> "参数不被接受（HTTP 400），可能是模型名不支持 ${model.ifBlank { "" }}：${body.take(120)}"
        in 500..599 -> "服务端出错（HTTP $code），稍后再试：${body.take(120)}"
        else -> "接口返回 $code：${body.take(120)}"
    }

    private fun request(file: File): String {
        val mime = if (file.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model.trim().ifBlank { DEFAULT_MODEL })
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(endpoint())
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(form)
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            WeLogger.w(TAG, "ASR 请求失败：${it.message}")
            throw IllegalStateException("网络失败：${it.message}")
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException(friendlyError(it.code, body))
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
            // OpenAI 形状是 {"text": "..."}；个别中转站包了一层 {"data": {...}}
            return json.optString("text").trim()
                .ifEmpty { json.optJSONObject("data")?.optString("text").orEmpty().trim() }
        }
    }

    /** 允许用户直接填完整端点；否则拼上标准路径。 */
    private fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
    }

    /** 同一条语音（encPath 相同）只识别一次，缓存文件名用它的哈希。 */
    private fun cacheKey(encPath: String): String =
        Integer.toHexString(encPath.hashCode())

    /** 清掉所有识别缓存（设置里「清空识别缓存」用）。 */
    fun clearCache() {
        runCatching { File(HostInfo.application.cacheDir, "asr").deleteRecursively() }
    }
}
