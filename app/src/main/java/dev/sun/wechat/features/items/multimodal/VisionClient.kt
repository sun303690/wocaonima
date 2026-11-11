package dev.sun.wechat.features.items.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmImage
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * 视觉理解客户端：不走自填地址，直接用 WeAgent 里已配好的模型。
 *
 * 好处是换服务商、换 Key、换模型都只改 WeAgent 一处；OpenAI / Responses / Gemini / Anthropic
 * 各家的图片形状由 [ModelProviderManager] 的适配器负责，这里只提供 base64 + mime。
 *
 * 图片会先做体积控制：小于 1.4 MB 且格式受支持的原样发送，否则解码后缩到最长边 1024 再压 JPEG。
 */
object VisionClient {

    private const val TAG = "VisionClient"
    private const val MAX_RAW_BYTES = 1_400_000
    private const val MAX_EDGE = 1024

    /** 同步调用视觉模型，返回模型给的一句话描述；失败抛异常（调用方一般在后台线程）。 */
    fun describe(file: File, prompt: String): String {
        if (!file.isFile || file.length() == 0L) throw IllegalStateException("图片文件不存在")
        val image = encode(file) ?: throw IllegalStateException("不支持的图片格式")
        return runBlocking { ask(resolveModelId(MultimodalEngine.visionModelId), prompt, image) }
    }

    /**
     * 没单独指定视觉模型时回落到 WeAgent 的默认模型——候选回复、情绪分析、多模态共用同一份配置。
     */
    private suspend fun resolveModelId(preferred: String): String =
        preferred.ifBlank { WeAgentRepository.resolvedDefaultModelId().orEmpty() }
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("还没在 WeAgent 里选视觉模型")

    /** 连通性测试：只发一条纯文本 ping，不花图片 token。 */
    fun testConnection(cb: (Boolean, String) -> Unit) {
        val preferred = MultimodalEngine.visionModelId
        thread(name = "wekit-vision-test") {
            runCatching { runBlocking { ask(resolveModelId(preferred), "ping", null) } }
                .onSuccess { text -> cb(true, if (text.isBlank()) "连通正常" else "连通正常：${text.take(24)}") }
                .onFailure { error ->
                    WeLogger.w(TAG, "视觉连通性测试失败：${error.message}")
                    cb(false, error.message ?: "连接失败")
                }
        }
    }

    /** 单轮补全：图片作为 [LlmImage] 挂在用户消息上，由服务商适配器转成各自格式。 */
    private suspend fun ask(modelId: String, prompt: String, image: LlmImage?): String {
        val model = WeAgentRepository.getModel(modelId)
            ?: throw IllegalStateException("视觉模型不存在，请重新选择")
        val provider = WeAgentRepository.getModelProvider(model.providerId)
            ?: throw IllegalStateException("模型未绑定服务商")
        val client = ModelProviderManager.clientFor(provider)
        val messages = listOf(
            LlmMessage(
                role = LlmRole.USER,
                content = prompt,
                images = if (image == null) emptyList() else listOf(image),
            ),
        )
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = messages,
            tools = emptyList(),
            stream = true,
        )
        val sb = StringBuilder()
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> sb.append(event.text)
                is LlmStreamEvent.Completed ->
                    if (sb.isEmpty()) event.message.content?.let { sb.append(it) }
                is LlmStreamEvent.Failed -> throw event.error
                else -> {}
            }
        }
        return sb.toString().trim()
    }

    // ---- 图片编码 ----

    private fun encode(file: File): LlmImage? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = detectMime(raw)
        if (mime != null && raw.size <= MAX_RAW_BYTES) return LlmImage(base64(raw), mime)
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
                return mime?.let { LlmImage(base64(raw), it) }
            }
        val scaled = scaleDown(bitmap)
        val out = ByteArrayOutputStream()
        val ok = runCatching { scaled.compress(Bitmap.CompressFormat.JPEG, 82, out) }.getOrDefault(false)
        if (!ok) return mime?.let { LlmImage(base64(raw), it) }
        return LlmImage(base64(out.toByteArray()), "image/jpeg")
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

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

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
