package dev.sun.wechat.utils

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BGM 混音器：把 TTS 语音与背景音乐混成一条 16bit 单声道 WAV。
 *
 * 输入的语音与 BGM 可以是任意 MediaCodec 能解的格式（wav/mp3/m4a…），
 * 统一走 [AndroidAudioDecoder] 解成 PCM，BGM 线性重采样到人声采样率，
 * 循环铺满人声长度，头部 150ms / 尾部 1.2s 淡入淡出后按增益叠加。
 */
object BgmMixer {
    private const val TAG = "BgmMixer"
    private const val FADE_IN_MS = 150L
    private const val FADE_OUT_MS = 1200L

    /** BGM 来源：内置 raw 资源或本地文件。 */
    sealed interface Bgm {
        data object None : Bgm
        data class Builtin(val resId: Int) : Bgm
        data class LocalFile(val path: String) : Bgm
    }

    private class MonoPcm(val rate: Int, val samples: ShortArray)

    /**
     * 混音并写出 WAV。
     * @param gain BGM 相对人声的线性增益 0..1（建议 0.1~0.3，1.0 = 与人声等响）。
     */
    fun mix(context: Context, voicePath: String, bgm: Bgm, gain: Float, outPath: String): Boolean =
        runCatching {
            val voice = decodeMono(context, voicePath) ?: return false
            if (bgm is Bgm.None || gain <= 0f) {
                writeWav(voice.rate, voice.samples, outPath)
                return true
            }
            val bgmPcm = when (bgm) {
                is Bgm.Builtin -> decodeBuiltin(context, bgm.resId) ?: return false
                is Bgm.LocalFile -> decodeMono(context, bgm.path) ?: return false
                Bgm.None -> return false
            }
            val bgmAtRate = if (bgmPcm.rate == voice.rate) bgmPcm else resample(bgmPcm, voice.rate)
            writeWav(voice.rate, blend(voice.samples, bgmAtRate.samples, gain, voice.rate), outPath)
            true
        }.onFailure { WeLogger.w(TAG, "mix failed: ${it.message}", it) }.getOrDefault(false)

    // ---- 解码：任意格式 → 单声道 PCM ----

    private fun decodeMono(context: Context, path: String): MonoPcm? {
        val tmp = File.createTempFile("bgm_dec_", ".pcm", context.cacheDir)
        return runCatching {
            val fmt = AndroidAudioDecoder.decodeToPcm16(path, tmp)
            val bytes = tmp.readBytes()
            val channels = fmt.channelCount.coerceAtLeast(1)
            val frames = bytes.size / 2 / channels
            val out = ShortArray(frames)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                var acc = 0
                for (c in 0 until channels) acc += bb.getShort().toInt()
                out[i] = (acc / channels).toShort()
            }
            MonoPcm(fmt.sampleRate, out)
        }.onFailure { WeLogger.w(TAG, "decode $path failed: ${it.message}") }.getOrNull().also { tmp.delete() }
    }

    /** 内置 raw 资源没有真实路径，先拷到缓存再解。 */
    private fun decodeBuiltin(context: Context, resId: Int): MonoPcm? {
        val tmp = File(context.cacheDir, "bgm_builtin_$resId")
        return runCatching {
            if (tmp.length() == 0L) {
                context.resources.openRawResource(resId).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            decodeMono(context, tmp.absolutePath)
        }.onFailure { WeLogger.w(TAG, "decode builtin $resId failed: ${it.message}") }.getOrNull()
    }

    // ---- 重采样 / 混合 / 写 WAV ----

    /** 线性插值重采样：给 BGM 用足够（轻微高频损失可接受），人声不动。 */
    private fun resample(src: MonoPcm, targetRate: Int): MonoPcm {
        val n = (src.samples.size.toLong() * targetRate / src.rate).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val step = src.rate.toDouble() / targetRate
        var pos = 0.0
        val last = src.samples.size - 1
        for (i in 0 until n) {
            val i0 = pos.toInt().coerceIn(0, last)
            val i1 = (i0 + 1).coerceAtMost(last)
            val frac = (pos - i0).toFloat()
            out[i] = (src.samples[i0] + (src.samples[i1] - src.samples[i0]) * frac).toInt().toShort()
            pos += step
        }
        return MonoPcm(targetRate, out)
    }

    private fun blend(voice: ShortArray, bgm: ShortArray, gain: Float, rate: Int): ShortArray {
        val out = ShortArray(voice.size)
        val fadeIn = ((rate * FADE_IN_MS) / 1000).coerceAtLeast(1)
        val fadeOut = ((rate * FADE_OUT_MS) / 1000).coerceAtLeast(1)
        val total = voice.size
        for (i in 0 until total) {
            var env = 1f
            if (i < fadeIn) env *= i.toFloat() / fadeIn
            val tail = total - i
            if (tail < fadeOut) env *= tail.toFloat() / fadeOut
            val mixed = voice[i] + bgm[i % bgm.size] * gain * env
            out[i] = mixed.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
        return out
    }

    /** 标准 44 字节头的 16bit 单声道 WAV。 */
    private fun writeWav(rate: Int, samples: ShortArray, outPath: String) {
        val dataLen = samples.size * 2
        File(outPath).outputStream().use { o ->
            fun le16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            fun le32(v: Int) { repeat(4) { o.write((v shr (it * 8)) and 0xFF) } }
            o.write("RIFF".toByteArray()); le32(36 + dataLen); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(rate); le32(rate * 2); le16(2); le16(16)
            o.write("data".toByteArray()); le32(dataLen)
            val bb = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort(s)
            o.write(bb.array())
        }
    }
}
