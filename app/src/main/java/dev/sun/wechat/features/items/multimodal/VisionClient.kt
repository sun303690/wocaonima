package dev.sun.wechat.features.items.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.sun.wechat.utils.WeLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 视觉理解客户端：OpenAI 兼容的 `POST {baseUrl}/chat/completions`，图片以 data URI 内联。
 *
 * 硅基流动、OpenAI、Gemini(OpenAI 兼容模式)、各类中转站都是这个形状，
 * 填地址 + Key + 模型名即可，换服务商不用改代码，也不动 WeAgent 的全局模型配置。
 *
 * 图片会先做体积控制：小于 1.4 MB 且格式受支持的原样发送，否则解码后缩到最长边 1024 再压 JPEG。
 */
object VisionClient {

    private const val TAG = "VisionClient"
    private const val MAX_RAW_BYTES = 1_400_000
    private const val MAX_EDGE = 1024
    private const val MAX_TOKENS = 200

    /** 视频直传上限：整段 base64 会膨胀 4/3，再大中转站多半直接断流。 */
    private const val MAX_VIDEO_BYTES = 10L * 1024 * 1024

    /** 必须是 3 的倍数，否则分块编码会在每块末尾塞填充符，拼出来的 base64 是坏的。 */
    private const val CHUNK_SIZE = 3 * 8192

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 同步调用视觉接口，返回模型给的一句话描述；失败抛异常（调用方一般在后台线程）。 */
    fun describe(file: File, prompt: String): String {
        if (!MultimodalEngine.visionUsable()) throw IllegalStateException("未配置视觉接口")
        if (!file.isFile || file.length() == 0L) throw IllegalStateException("图片文件不存在")
        val dataUri = encode(file) ?: throw IllegalStateException("不支持的图片格式")

        val payload = JSONObject().apply {
            put("model", modelName())
            put("max_tokens", MAX_TOKENS)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", prompt))
                                .put(
                                    JSONObject().put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUri)),
                                ),
                        )
                    },
                ),
            )
        }

        val response = runCatching { client.newCall(request(payload)).execute() }.getOrElse {
            WeLogger.w(TAG, "视觉请求失败：${it.message}")
            throw IllegalStateException("网络失败：${it.message}")
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException("接口返回 ${it.code}：${body.take(160)}")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
            return parseContent(json).ifEmpty { throw IllegalStateException("接口没返回描述") }
        }
    }

    /**
     * 同步识别视频：整段 base64 塞不进内存，所以请求体是边读边写。
     *
     * 形状是 OpenAI 兼容的 `video_url` 分段：`prefix + base64(流式) + suffix`，
     * 三段拼出一个完整 JSON 请求体，长度可预算所以能带上 Content-Length。
     */
    fun describeVideo(file: File, prompt: String): String {
        if (!MultimodalEngine.visionUsable()) throw IllegalStateException("未配置视觉接口")
        if (!file.isFile || file.length() == 0L) throw IllegalStateException("视频文件不存在")
        if (file.length() > MAX_VIDEO_BYTES) {
            throw IllegalStateException("视频 ${file.length() / 1024 / 1024}MB 超过 10MB 上限")
        }
        val prefix = buildString {
            append("{\"model\":").append(JSONObject.quote(modelName()))
            append(",\"max_tokens\":").append(MAX_TOKENS)
            append(",\"messages\":[{\"role\":\"user\",\"content\":[")
            append("{\"type\":\"text\",\"text\":").append(JSONObject.quote(prompt)).append("},")
            append("{\"type\":\"video_url\",\"video_url\":{\"url\":\"data:video/mp4;base64,")
        }
        val suffix = "\"}}]}]}"
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()

            override fun contentLength(): Long =
                prefixBytes.size.toLong() + base64Length(file.length()) + suffixBytes.size

            override fun writeTo(sink: BufferedSink) {
                sink.write(prefixBytes)
                val buffer = ByteArray(CHUNK_SIZE)
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.writeUtf8(Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                    }
                }
                sink.write(suffixBytes)
                sink.flush()
            }
        }
        val request = Request.Builder()
            .url(endpoint(MultimodalEngine.visionEndpointBase()))
            .addHeader("Authorization", "Bearer ${MultimodalEngine.visionCredential()}")
            .post(body)
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            WeLogger.w(TAG, "视频视觉请求失败：${it.message}")
            throw IllegalStateException("网络失败：${it.message}")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("接口返回 ${it.code}：${text.take(160)}")
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON：${text.take(120)}")
            return parseContent(json).ifEmpty { throw IllegalStateException("接口没返回描述") }
        }
    }

    private fun base64Length(size: Long): Long = ((size + 2) / 3) * 4

    /**
     * 从 `GET {base}/models` 拉模型列表（OpenAI 兼容接口的标准端点）。
     *
     * 先用 [cachedModels] 兜底秒开，网络失败时回落到它；两者都空才报错。
     * 回调在后台线程，调用方自己 post 回主线程。
     */
    fun fetchModels(cb: (List<String>?, String?) -> Unit) =
        fetchModelsFrom(MultimodalEngine.visionEndpointBase(), MultimodalEngine.visionCredential(), "wekit-vision-models", cb)

    /**
     * 文字接口单独拉模型列表。
     *
     * 用文字接口自己的地址 / Key，不去碰视觉接口那份配置——两家服务商各管各的，
     * 拉列表也不会互相覆盖缓存。
     */
    fun fetchTextModels(cb: (List<String>?, String?) -> Unit) =
        fetchModelsFrom(MultimodalEngine.textEndpointBase(), MultimodalEngine.textCredential(), "wekit-text-models", cb)

    private fun fetchModelsFrom(
        base: String,
        apiKey: String,
        threadName: String,
        cb: (List<String>?, String?) -> Unit,
    ) {
        if (base.isBlank()) {
            cb(null, "还没填接口地址"); return
        }
        if (apiKey.isBlank()) {
            cb(null, "还没填 Key"); return
        }
        thread(name = threadName) {
            runCatching {
                val request = Request.Builder()
                    .url(modelsEndpoint(base))
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("接口返回 ${response.code}：${body.take(160)}")
                    }
                    val json = runCatching { JSONObject(body) }.getOrNull()
                        ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
                    parseModels(json)
                }
            }.onSuccess { list ->
                if (list.isNotEmpty()) cachedModels[base] = list
                WeLogger.d(TAG, "取到 ${list.size} 个模型")
                cb(list.ifEmpty { cachedModels[base] }, null)
            }.onFailure { error ->
                WeLogger.w(TAG, "拉模型列表失败：${error.message}")
                cb(cachedModels[base], error.message ?: "拉取失败")
            }
        }
    }

    /** 最近一次成功拉到的模型列表；按接口地址分桶，视觉与文字互不覆盖。 */
    private val cachedModels = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * 从候选里挑一个最像视觉模型的当默认值：优先名字带 vl / vision / 4o / 4v 的，
     * 其次 gemini / claude / llama-vision，最后回落到第一个。挑不出来返回 null。
     */
    fun pickVisionModel(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val strong = listOf("vl", "vision", "-4o", "4o-", "glm-4v", "glm-4.5v", "llava", "pixtral", "internvl")
        candidates.firstOrNull { id ->
            val lower = id.lowercase()
            strong.any { lower.contains(it) }
        }?.let { return it }
        val weak = listOf("gemini", "claude", "gpt-4", "qwen", "doubao", "step-1v", "moonshot")
        candidates.firstOrNull { id ->
            val lower = id.lowercase()
            weak.any { lower.contains(it) }
        }?.let { return it }
        return candidates.first()
    }

    private fun parseModels(json: JSONObject): List<String> {
        val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
        val ids = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifEmpty { item.optString("name") }
            if (id.isNotBlank()) ids.add(id)
        }
        return ids.distinct().sorted()
    }

    /** `/chat/completions` 与 `/models` 同级；用户填完整端点或只填 base 都兼容。 */
    private fun modelsEndpoint(rawBase: String): String {
        val base = rawBase.trim().trimEnd('/').removeSuffix("/chat/completions")
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /** 文字接口连通性测试：用文字接口自己的地址 / Key / 模型发一条 ping。 */
    fun testTextConnection(cb: (Boolean, String) -> Unit) {
        if (MultimodalEngine.textEndpointBase().isBlank()) {
            cb(false, "还没填接口地址（文字接口和视觉接口都没配）"); return
        }
        if (MultimodalEngine.textCredential().isBlank()) {
            cb(false, "还没填 Key（文字接口和视觉接口都没配）"); return
        }
        thread(name = "wekit-text-test") {
            runCatching {
                val payload = JSONObject()
                    .put("model", textModelName())
                    .put("max_tokens", 8)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                    )
                client.newCall(textRequest(payload)).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("接口返回 ${response.code}：${body.take(160)}")
                    }
                    val json = runCatching { JSONObject(body) }.getOrNull()
                        ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
                    val text = parseContent(json)
                    text.takeIf { it.isNotBlank() } ?: throw IllegalStateException("返回内容为空")
                }
            }.onSuccess { reply ->
                cb(true, "连接成功：${reply.take(40)}")
            }.onFailure { error ->
                WeLogger.w(TAG, "文字接口测试失败：${error.message}")
                cb(false, error.message ?: "测试失败")
            }
        }
    }

    /** 连通性测试：只发一条纯文本 ping，验证地址/Key/模型是否可用，不花图片 token。 */
    fun testConnection(cb: (Boolean, String) -> Unit) {
        if (MultimodalEngine.visionEndpointBase().isBlank()) {
            cb(false, "还没填接口地址"); return
        }
        if (MultimodalEngine.visionCredential().isBlank()) {
            cb(false, "还没填 Key"); return
        }
        thread(name = "wekit-vision-test") {
            runCatching {
                val payload = JSONObject()
                    .put("model", modelName())
                    .put("max_tokens", 8)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", "ping"),
                        ),
                    )
                client.newCall(request(payload)).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("接口返回 ${response.code}：${body.take(160)}")
                    }
                    val json = runCatching { JSONObject(body) }.getOrNull()
                        ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
                    parseContent(json)
                }
            }.onSuccess { text ->
                cb(true, if (text.isBlank()) "连通正常" else "连通正常：${text.take(24)}")
            }.onFailure { error ->
                WeLogger.w(TAG, "视觉连通性测试失败：${error.message}")
                cb(false, error.message ?: "连接失败")
            }
        }
    }

    /**
     * 用「多模态配置」里的同一套地址 / Key / 模型做一次纯文本对话，
     * 走 OpenAI 兼容的 /chat/completions（非流式），供 AI 回复这类不需要图片的调用复用。
     */
    fun completeText(system: String?, user: String, maxTokens: Int = 1024): String {
        val payload = JSONObject()
            .put("model", textModelName())
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put("messages", messagesOf(system, user))
        client.newCall(textRequest(payload)).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("接口返回 ${response.code}：${body.take(160)}")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON：${body.take(120)}")
            return parseContent(json)
        }
    }

    /**
     * 流式对话：同样走「多模态配置」里的地址 / Key / 文本模型，
     * 逐块回调增量文本，返回完整结果。供智能回复、群聊分析这类需要打字机效果的调用复用。
     * 必须在 IO 线程调用（内部是同步阻塞读取）。
     */
    fun streamChat(
        system: String?,
        user: String,
        maxTokens: Int = 2048,
        temperature: Double? = null,
        onDelta: (String) -> Unit = {},
    ): String {
        val payload = JSONObject()
            .put("model", textModelName())
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .put("messages", messagesOf(system, user))
            .apply { if (temperature != null) put("temperature", temperature) }
        client.newCall(textRequest(payload)).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty().take(160)
                throw IllegalStateException("接口返回 ${response.code}：$detail")
            }
            val source = response.body?.source() ?: throw IllegalStateException("返回内容为空")
            val text = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?: continue
                val piece = delta.optString("content")
                if (piece.isEmpty()) continue
                text.append(piece)
                onDelta(piece)
            }
            return text.toString()
        }
    }

    private fun messagesOf(system: String?, user: String): JSONArray = JSONArray().apply {
        if (!system.isNullOrBlank()) {
            put(JSONObject().put("role", "system").put("content", system))
        }
        put(JSONObject().put("role", "user").put("content", user))
    }

    /** 文字任务的模型：文字接口填了就用它，没填就回落到视觉接口的模型。 */
    private fun textModelName(): String = MultimodalEngine.textModelOrVision()
        .ifBlank { throw IllegalStateException("还没选文本模型") }

    private fun modelName(): String = MultimodalEngine.visionModelOrText()
        .ifBlank { throw IllegalStateException("还没选视觉模型") }

    private fun request(payload: JSONObject): Request = Request.Builder()
        .url(endpoint(MultimodalEngine.visionEndpointBase()))
        .addHeader("Authorization", "Bearer ${MultimodalEngine.visionCredential()}")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /** 纯文字请求：文字接口配好就走它自己的地址与 Key，没配则回落视觉接口。 */
    private fun textRequest(payload: JSONObject): Request = Request.Builder()
        .url(endpoint(MultimodalEngine.textEndpointBase()))
        .addHeader("Authorization", "Bearer ${MultimodalEngine.textCredential()}")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /**
     * 兼容 OpenAI（`choices[0].message.content`）、部分中转站的 `data.choices`、
     * 分段 content 数组与纯 `text` 字段。
     */
    private fun parseContent(json: JSONObject): String {
        val choices = json.optJSONArray("choices")
            ?: json.optJSONObject("data")?.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val content = message?.opt("content")
            when (content) {
                is String -> return content.trim()
                is JSONArray -> {
                    val parts = buildString {
                        for (index in 0 until content.length()) {
                            val part = content.optJSONObject(index) ?: continue
                            append(part.optString("text"))
                        }
                    }
                    if (parts.isNotBlank()) return parts.trim()
                }
            }
            message?.optString("reasoning_content")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return json.optString("text").trim()
    }

    /** 允许用户直接填完整端点；否则拼上标准路径。 */
    private fun endpoint(rawBase: String): String {
        val base = rawBase.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    // ---- 图片编码 ----

    private fun encode(file: File): String? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = detectMime(raw)
        if (mime != null && raw.size <= MAX_RAW_BYTES) return dataUri(mime, raw)
        if (mime == null) {
            // WXGF 等微信私有格式先尝试系统解码，解不出来就没法识别
            WeLogger.d(TAG, "未知图片格式，尝试解码 file=${file.name}")
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: run {
                WeLogger.d(
                    TAG,
                    "图片解码失败（可能是原图还没下载完）file=${file.name} 大小=${raw.size} 字节"
                )
                return mime?.let { dataUri(it, raw) }
            }
        val scaled = scaleDown(bitmap)
        val out = ByteArrayOutputStream()
        val ok = runCatching { scaled.compress(Bitmap.CompressFormat.JPEG, 82, out) }.getOrDefault(false)
        if (!ok) return mime?.let { dataUri(it, raw) }
        return dataUri("image/jpeg", out.toByteArray())
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }
            .getOrDefault(bitmap)
    }

    private fun dataUri(mime: String, bytes: ByteArray): String {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mime;base64,$base64"
    }

    private fun detectMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        if (png) return "image/png"
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if (jpeg) return "image/jpeg"
        val gif = bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
        if (gif) return "image/gif"
        val webp = bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        if (webp) return "image/webp"
        return null
    }
}
