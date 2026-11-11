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
import java.util.concurrent.FutureTask
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

    /** SILK 明文魔数；native 解码器认它。 */
    private val SILK_MAGIC = "#!SILK_V3".toByteArray(Charsets.US_ASCII)

    /** 微信在魔数前插的 1 字节版本前缀（0x02 旧语音、0x03 长语音都见过）。 */
    private val SILK_PREFIXES = setOf(0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte())

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
     * 语音解密用的 AES Key（16 字节起，key 与 IV 同值）。
     *
     * 微信 CDN 语音是 AES-128-CBC，部分机型/版本在 `voice2/` 落盘的是加密体而不是明文 SILK，
     * 这时 [MediaFileTypeDetector] 认不出容器，就用这两个串去解（与 Hchat 的 asr.aesKey / asr.authKey 同名同义）。
     * 都留空时跳过解密分支（明文 SILK/AMR 不需要）。
     */
    var aesKey by prefOption("asr_aes_key", "")

    /** 备用解密串（Hchat 里叫 asr.authKey），与 [aesKey] 一起当候选。 */
    var authKey by prefOption("asr_auth_key", "")

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
        val encPath = voiceFileKey(message)
        if (encPath.isNullOrBlank()) {
            cb(null, "拿不到语音文件路径"); return
        }
        thread(name = "wekit-asr") {
            runCatching { transcribeEncPath(encPath) }
                .onSuccess { cb(it, null) }
                .onFailure { cb(null, it.message ?: "识别失败") }
        }
    }

    /** 只读本地识别缓存：同一条语音之前识别过就直接给，不联网、不阻塞。 */
    fun cachedTranscript(encPath: String): String? = runCatching {
        File(HostInfo.application.cacheDir, "asr")
            .resolve("${cacheKey(encPath)}.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 给「历史上下文」用：有缓存立刻返回；没有就现识别，最多等 [timeoutMs]，超时返回 null。
     *
     * 超时不算白跑——识别仍在后台线程继续，成功后照样写进缓存，下一次构造上下文直接命中。
     */
    fun transcriptForContext(encPath: String, timeoutMs: Long = 15_000L): String? {
        cachedTranscript(encPath)?.let { return it }
        if (!ready()) return null
        val task = FutureTask { runCatching { transcribeEncPath(encPath) }.getOrNull() }
        Thread(task, "wekit-asr-ctx").apply { isDaemon = true }.start()
        return runCatching { task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
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

        // 微信 API 给的路径优先；它空着/文件不存在时，退到按 voice2 目录规则直查。
        val fromApi = runCatching { WeMessageApi.getVoiceFullPath(encPath) }.getOrDefault("")
        val sourcePath = fromApi.takeIf { it.isNotBlank() && File(it).let { f -> f.isFile && f.length() > 0 } }
            ?: locateVoiceFile(encPath)?.also {
                WeLogger.d(TAG, "微信 API 没给出可用路径，voice2 直查命中：$it")
            }
            ?: throw IllegalStateException(
                "语音文件还没下载（${encPath.ifBlank { "空路径" }}）；先在微信里播放一次这条语音"
            )
        val source = File(sourcePath)
        if (!source.isFile || source.length() == 0L) {
            throw IllegalStateException(
                "语音文件不存在或为空（${source.name}，${source.length()} 字节）"
            )
        }
        // 先按容器判格式；认不出就先怀疑是加密体（微信 CDN 语音 AES-128-CBC，key=IV）。
        var workFile = source
        var format = MediaFileTypeDetector.detectAudio(source.toPath())
        var decrypted = false
        if (format == null) {
            tryDecryptSilk(source, dir, key)?.let {
                workFile = it
                format = MediaFileTypeDetector.AudioFormat.SILK
                decrypted = true
            }
        }
        val info = buildString {
            append("文件=${source.name} 大小=${source.length()} 字节")
            append(" 格式=${format?.name ?: "未识别"}")
            if (decrypted) append("（已解密）")
            append(" 头=${headHex(source)}")
        }
        WeLogger.d(TAG, "语音源 $info")

        val workPath = workFile.absolutePath
        val pcm = File(dir, "$key.pcm")
        val mp3 = File(dir, "$key.mp3")
        val wav = File(dir, "$key.wav")

        val upload: File? = when (format) {
            MediaFileTypeDetector.AudioFormat.SILK -> {
                val playable = normalizeSilk(workFile, dir, key)
                if (!runCatching {
                        AudioUtils.silkToPcm(playable.absolutePath, pcm.absolutePath)
                    }.getOrDefault(false)) {
                    throw IllegalStateException("SILK 转 PCM 失败（$info）")
                }
                toUploadable(pcm, mp3, wav)
            }

            // 认不出容器：先按 SILK 试 native 解码，再退系统 MediaCodec，
            // 都失败才按「加密体 / 未知容器」分层报错（附头部字节便于排查）。
            null -> {
                val playable = normalizeSilk(workFile, dir, key)
                val silkOk = runCatching {
                    AudioUtils.silkToPcm(playable.absolutePath, pcm.absolutePath)
                }.getOrDefault(false)
                if (silkOk) {
                    toUploadable(pcm, mp3, wav)
                } else {
                    pcm.delete()
                    val decoded = runCatching {
                        AndroidAudioDecoder.decodeToPcm16(workPath, pcm)
                    }.getOrNull() ?: throw IllegalStateException(unknownFormatMessage(info))
                    if (decoded.channelCount > 1) {
                        throw IllegalStateException(
                            "语音是 ${decoded.channelCount} 声道，暂不支持（$info）"
                        )
                    }
                    resampleTo24k(pcm, decoded.sampleRate)
                    toUploadable(pcm, mp3, wav)
                }
            }

            // 微信部分版本把语音存成 AMR/其他容器，native 的 SILK 解码器认不了，
            // 交给系统 MediaCodec 解码，再重采样到 24 kHz（native 的 pcmToMp3 按 24 kHz 写死）。
            else -> {
                val decoded = runCatching {
                    AndroidAudioDecoder.decodeToPcm16(workPath, pcm)
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
                toUploadable(pcm, mp3, wav)
            }
        }

        pcm.delete()
        if (decrypted) workFile.delete()
        if (upload == null) throw IllegalStateException("PCM 转 MP3/WAV 都失败（$info）")

        val text = try {
            request(upload)
        } finally {
            upload.delete()
        }
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
    /** 微信 shared_prefs 目录（auth_key 在里面，自动解密用）。 */
    private val PREFS_DIRS = arrayOf(
        "/data/data/com.tencent.mm/shared_prefs",
        "/data/user/0/com.tencent.mm/shared_prefs",
    )

    /** 匹配 `<string name="_auth_key">值</string>` 之类的键值。 */
    private val AUTH_KEY_PATTERN = Regex("""name="([^"]*auth[_-]?key[^"]*)">([^<]+)<""")

    /** 微信语音文件所在的三个候选根目录（模块跑在微信进程内，读权限同微信）。 */
    private val VOICE_ROOTS = arrayOf(
        "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg",
        "/data/data/com.tencent.mm/MicroMsg",
        "/data/user/0/com.tencent.mm/MicroMsg",
    )

    /**
     * 取这条语音的「文件名/路径」：`imagePath` 优先，空了退 `content`（有的版本只在 content 里），
     * 再退消息对象上的 `getNativeUrl()`。任一命中即可，后面 [transcribeEncPath] 自己会找文件。
     */
    fun voiceFileKey(message: MessageInfo): String? {
        message.imagePath?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        message.content?.trim()?.takeIf { it.startsWith("msg_") }?.let { return it }
        nativeUrlOf(message)?.takeIf { it.startsWith("msg_") || it.contains("/voice2/") }?.let { return it }
        return null
    }

    /** 反射拿 `getNativeUrl()`（不同微信版本字段名不一致时的最后兜底）。 */
    private fun nativeUrlOf(message: MessageInfo): String? = runCatching {
        message.instance.javaClass.methods
            .firstOrNull { it.name == "getNativeUrl" && it.parameterCount == 0 }
            ?.invoke(message.instance) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 按微信的落盘规则直查语音文件：
     * `MicroMsg/<32 位 hash>/voice2/<文件名前 2 位>/<第 3–4 位>/<文件名>.amr|.silk`。
     *
     * 只在 `getVoiceFullPath` 拿不到可用文件时使用——它绕开微信 API，坏处是慢（要遍历 hash 目录），
     * 好处是 API 换实现/返回空串时仍能自愈。
     */
    fun locateVoiceFile(fileName: String): String? {
        var name = fileName.trim().substringAfterLast('/')
        if (name.endsWith(".amr", true) || name.endsWith(".silk", true)) {
            name = name.substringBeforeLast('.')
        }
        if (name.length < 4) return null
        val sub1 = name.substring(0, 2)
        val sub2 = name.substring(2, 4)
        for (root in VOICE_ROOTS) {
            val rootDir = File(root)
            val hashes = runCatching { rootDir.list() }.getOrNull() ?: continue
            for (hash in hashes) {
                if (hash.length != 32) continue
                for (ext in arrayOf(".amr", ".silk")) {
                    val candidate = File(rootDir, "$hash/voice2/$sub1/$sub2/$name$ext")
                    if (candidate.isFile && candidate.length() > 0) return candidate.absolutePath
                }
            }
        }
        return null
    }

    /**
     * 把 24 kHz/单声道 PCM 变成能上传的文件：优先 MP3，失败退 WAV。
     *
     * 个别机型的 native `pcmToMp3` 会返回空/极小的输出，而 WAV 只是加 44 字节头，
     * 各家 ASR 都收（SiliconFlow、OpenAI、Groq 都支持 wav），所以留一条退路。
     */
    private fun toUploadable(pcm: File, mp3: File, wav: File): File? {
        val mp3Ok = runCatching {
            AudioUtils.pcmToMp3(pcm.absolutePath, mp3.absolutePath)
        }.getOrDefault(false)
        if (mp3Ok && mp3.isFile && mp3.length() > 512) return mp3
        if (pcmToWav(pcm, wav)) {
            WeLogger.d(TAG, "MP3 转码不可用，改用 WAV 上传")
            return wav
        }
        return null
    }

    /** 手写 44 字节 WAV 头（PCM 16bit / 单声道 / [TARGET_SAMPLE_RATE]）。 */
    private fun pcmToWav(pcm: File, wav: File): Boolean = runCatching {
        val data = pcm.readBytes()
        if (data.size < 100) return@runCatching false
        val out = java.io.ByteArrayOutputStream(data.size + 44)
        fun le32(v: Int) = byteArrayOf(
            v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
        )
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val blockAlign = 2 // 单声道 × 16bit
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(36 + data.size))
        out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))
        out.write(le16(1))                       // PCM
        out.write(le16(1))                       // 单声道
        out.write(le32(TARGET_SAMPLE_RATE))
        out.write(le32(TARGET_SAMPLE_RATE * blockAlign))
        out.write(le16(blockAlign))
        out.write(le16(16))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(data.size))
        out.write(data)
        wav.writeBytes(out.toByteArray())
        wav.isFile && wav.length() > 44
    }.getOrDefault(false)

    /**
     * 加密体尝试解密：AES-128-CBC，key 与 IV 都用同一串 16 字节，允许 0 或 6 字节头。
     * 只有解出来带 SILK 魔数才算成功（避免把随机数据当音频送去识别）。
     * @return 解密后的临时 SILK 文件；未配置 Key 或没解开返回 null。
     */
    /**
     * 加密体自动解密：候选 Key 逐个试——ASR 里填的 aesKey / authKey 优先，其次微信自己的登录 auth_key
     * （模块跑在微信进程内，与微信同 uid，可直接读它的 shared_prefs）。
     *
     * 算法固定为 AES-128-CBC、key 与 IV 同值、允许 0 或 6 字节头；
     * 只有解出来带 SILK 魔数才采信，避免把随机数据当音频送去识别。
     *
     * @return 解密后的临时 SILK 文件；没解出来返回 null。
     */
    private fun tryDecryptSilk(source: File, dir: File, key: String): File? {
        val raw = runCatching { source.readBytes() }.getOrNull() ?: return null
        if (isSilk(raw)) return null
        val secrets = candidateSecrets()
        if (secrets.isEmpty()) {
            WeLogger.d(TAG, "语音像加密体，但没拿到可用的解密 Key")
            return null
        }
        for ((secret, origin) in secrets) {
            val keyBytes = secret.take(16).toByteArray(Charsets.UTF_8)
            for (skip in intArrayOf(0, 6)) {
                val len = raw.size - skip
                if (len < 16 || len % 16 != 0) continue
                val plain = runCatching {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                        javax.crypto.spec.IvParameterSpec(keyBytes)
                    )
                    cipher.doFinal(raw, skip, len)
                }.getOrNull() ?: continue
                if (isSilk(plain)) {
                    val out = File(dir, "$key.dec.silk")
                    out.writeBytes(plain)
                    WeLogger.d(TAG, "语音解密成功 source=$origin skip=$skip len=${plain.size}")
                    return out
                }
            }
        }
        WeLogger.d(TAG, "语音解密失败：试过 ${secrets.size} 个 Key 都没解出 SILK")
        return null
    }

    /** 候选解密 Key（去重，标注来源）：ASR 的 aesKey → authKey → 微信 auth_key。 */
    private fun candidateSecrets(): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        aesKey.trim().takeIf { it.length >= 16 }?.let { out[it] = "aesKey" }
        authKey.trim().takeIf { it.length >= 16 }?.let { out.putIfAbsent(it, "authKey") }
        for ((value, origin) in readAuthKeys()) {
            if (value.length >= 16) out.putIfAbsent(value, origin)
        }
        return out.map { it.key to it.value }
    }

    /** 读微信 shared_prefs 里的 auth_key，作为自动解密候选；读不到返回空表。 */
    private fun readAuthKeys(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for (dir in PREFS_DIRS) {
            val file = File(dir, "auth_info_key_prefs.xml")
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            for (m in AUTH_KEY_PATTERN.findAll(text)) {
                val value = m.groupValues[2].trim()
                if (value.length >= 16) result.add(value to "auth_key")
            }
        }
        return result
    }

    /** 明文 SILK 判定：允许微信常见的 0x02 / 0x03 版本前缀。 */
    private fun isSilk(bytes: ByteArray): Boolean {
        val magic = SILK_MAGIC
        if (bytes.size < 10) return false
        val offset = if (bytes[0] in SILK_PREFIXES) 1 else 0
        if (bytes.size < offset + magic.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i]) return false
        }
        return true
    }

    /**
     * native 的 `silk_to_pcm` 只对 0x02 前缀做 seek(10)，0x03 等其它前缀会被判成非法头。
     * 这里把「1 字节前缀 + `#!SILK_V3`」重写成纯魔数头的文件，交给 native 直接解。
     */
    private fun normalizeSilk(source: File, dir: File, cacheKey: String): File {
        val plain = File(dir, "$cacheKey.plain.silk")
        if (plain.isFile && plain.length() > 0) return plain
        return runCatching {
            val bytes = source.readBytes()
            if (bytes.size <= SILK_MAGIC.size) return source
            val prefixed = bytes[0] in SILK_PREFIXES &&
                    bytes.copyOfRange(1, SILK_MAGIC.size + 1).contentEquals(SILK_MAGIC)
            if (!prefixed) return source
            plain.outputStream().use { output ->
                output.write(SILK_MAGIC)
                output.write(bytes, SILK_MAGIC.size + 1, bytes.size - SILK_MAGIC.size - 1)
            }
            plain
        }.getOrDefault(source)
    }

    /** 认不出格式时的提示（解密已自动尝试过）。 */
    private fun unknownFormatMessage(info: String): String =
        "认不出这个语音的格式，自动解密也没成功（$info）；先在微信里播放一次这条语音再试"

    private fun headHex(file: File, count: Int = 16): String = runCatching {
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
