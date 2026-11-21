package dev.sun.wechat.utils

import java.io.File

object AudioUtils {

    fun anyToSilk(sourcePath: String, silkPath: String): Boolean {
        if (nativeAnyToSilk(sourcePath, silkPath)) return true

        val silkFile = File(silkPath).absoluteFile
        var pcmFile: File? = null
        return try {
            val temporaryPcm = File.createTempFile("wekit-audio-", ".pcm", silkFile.parentFile)
            pcmFile = temporaryPcm
            val decoded = AndroidAudioDecoder.decodeToPcm16(sourcePath, temporaryPcm)
            val converted = pcmToSilk(
                temporaryPcm.absolutePath,
                silkPath,
                decoded.sampleRate,
                decoded.channelCount,
            )
            if (!converted) silkFile.delete()
            converted
        } catch (error: Exception) {
            WeLogger.e("AudioUtils", "Android audio decoder fallback failed", error)
            silkFile.delete()
            false
        } finally {
            pcmFile?.delete()
        }
    }

    /**
     * 改语速：解码 → 重采样时按 [speed] 缩放样本数 → 重新编码成 mp3。
     *
     * 天X 的语音接口不认任何语速参数（实测 rate/spd/tempo/speed_ratio 等全部被忽略），
     * 只能在这边改时长；纯线性插值会连带改变音调（慢则更沉、快则更尖），
     * 因为没有引入时间伸缩算法，这是有意的取舍。
     */
    fun changeTempo(sourcePath: String, destPath: String, speed: Float): Boolean {
        if (speed <= 0f || kotlin.math.abs(speed - 1f) < 0.01f) {
            return runCatching {
                File(sourcePath).copyTo(File(destPath), overwrite = true)
                true
            }.getOrDefault(false)
        }
        var decodedFile: File? = null
        var scaledFile: File? = null
        return try {
            val decoded = File.createTempFile("wekit-tempo-", ".pcm", File(destPath).absoluteFile.parentFile)
            decodedFile = decoded
            val info = AndroidAudioDecoder.decodeToPcm16(sourcePath, decoded)
            val scaled = File.createTempFile("wekit-tempo-", ".pcm", File(destPath).absoluteFile.parentFile)
            scaledFile = scaled
            if (!scalePcm16(decoded, scaled, info.sampleRate, speed)) return false
            pcmToMp3(scaled.absolutePath, destPath)
        } catch (error: Exception) {
            WeLogger.e("AudioUtils", "change tempo failed", error)
            false
        } finally {
            decodedFile?.delete()
            scaledFile?.delete()
        }
    }

    /** 把 PCM16 重采样到 24 kHz（native 的 pcmToMp3 写死这个采样率），同时按 [speed] 缩放时长。 */
    private fun scalePcm16(source: File, dest: File, sourceRate: Int, speed: Float): Boolean {
        if (!source.isFile || sourceRate <= 0) return false
        val bytes = source.readBytes()
        val sourceSamples = bytes.size / 2
        if (sourceSamples < 2) return false
        val input = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val ratio = TARGET_PCM_RATE.toDouble() / sourceRate.toDouble() / speed.toDouble()
        val outputSamples = (sourceSamples * ratio).toInt().coerceAtLeast(1)
        val buffer = java.nio.ByteBuffer.allocate(outputSamples * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = buffer.asShortBuffer()
        for (i in 0 until outputSamples) {
            val position = i / ratio
            val index = position.toInt().coerceIn(0, sourceSamples - 1)
            val fraction = position - index
            val current = input.get(index).toInt()
            val next = if (index + 1 < sourceSamples) input.get(index + 1).toInt() else current
            val sample = (current + (next - current) * fraction).toInt()
            output.put(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        dest.writeBytes(buffer.array())
        WeLogger.d(
            "AudioUtils",
            "改语速 ${sourceRate}Hz ×$speed：${sourceSamples} → ${outputSamples} 样本",
        )
        return true
    }

    private const val TARGET_PCM_RATE = 24000

    private external fun nativeAnyToSilk(sourcePath: String, silkPath: String): Boolean
    private external fun pcmToSilk(
        pcmPath: String,
        silkPath: String,
        sampleRate: Int,
        channelCount: Int,
    ): Boolean

    external fun silkToPcm(silkPath: String, pcmPath: String): Boolean
    external fun pcmToMp3(silkPath: String, pcmPath: String): Boolean
    external fun getDurationMs(path: String): Long
}
