package dev.sun.wechat.features.items.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.sun.wechat.utils.WeLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private const val DEFAULT_MODEL = "gpt-4o-mini"

    /** 设置页「选择模型」里的候选，用户也可以自己填别的。 */
    val MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "Qwen/Qwen2.5-VL-72B-Instruct",
        "Pro/Qwen/Qwen2.5-VL-7B-Instruct",
        "glm-4.5v",
        "doubao-1.5-vision-pro-32k",
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 同步调用视觉接口，返回模型给的一句话描述；失败抛异常（调用方一般在后台线程）。 */
    fun describe(file: File, prompt: String): String {
        if (!MultimodalEngine.visionReady()) throw IllegalStateException("未配置视觉接口")
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
     * 从 `GET {base}/models` 拉模型列表（OpenAI 兼容接口的标准端点）。
     *
     * 先用 [cachedModels] 兜底秒开，网络失败时回落到它；两者都空才报错。
     * 回调在后台线程，调用方自己 post 回主线程。
     */
    fun fetchModels(cb: (List<String>?, String?) -> Unit) {
        if (MultimodalEngine.visionBaseUrl.isBlank()) {
            cb(cachedModels.ifEmpty { null }, "还没填接口地址"); return
        }
        if (MultimodalEngine.visionApiKey.isBlank()) {
            cb(cachedModels.ifEmpty { null }, "还没填 Key"); return
        }
        thread(name = "wekit-vision-models") {
            runCatching {
                val request = Request.Builder()
                    .url(modelsEndpoint())
                    .addHeader("Authorization", "Bearer ${MultimodalEngine.visionApiKey.trim()}")
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
                if (list.isNotEmpty()) cachedModels = list
                WeLogger.d(TAG, "取到 ${list.size} 个模型")
                cb(list.ifEmpty { cachedModels.ifEmpty { null } }, null)
            }.onFailure { error ->
                WeLogger.w(TAG, "拉模型列表失败：${error.message}")
                cb(cachedModels.ifEmpty { null }, error.message ?: "拉取失败")
            }
        }
    }

    /** 最近一次成功拉到的模型列表（进程内缓存，设置页重进不再等网络）。 */
    @Volatile
    private var cachedModels: List<String> = emptyList()

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
    private fun modelsEndpoint(): String {
        val base = MultimodalEngine.visionBaseUrl.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /** 连通性测试：只发一条纯文本 ping，验证地址/Key/模型是否可用，不花图片 token。 */
    fun testConnection(cb: (Boolean, String) -> Unit) {
        if (MultimodalEngine.visionBaseUrl.isBlank()) {
            cb(false, "还没填接口地址"); return
        }
        if (MultimodalEngine.visionApiKey.isBlank()) {
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

    private fun modelName(): String = MultimodalEngine.visionModel.trim().ifBlank { DEFAULT_MODEL }

    private fun request(payload: JSONObject): Request = Request.Builder()
        .url(endpoint())
        .addHeader("Authorization", "Bearer ${MultimodalEngine.visionApiKey.trim()}")
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
    private fun endpoint(): String {
        val base = MultimodalEngine.visionBaseUrl.trim().trimEnd('/')
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
