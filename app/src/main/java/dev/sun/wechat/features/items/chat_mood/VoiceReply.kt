package dev.sun.wechat.features.items.chat_mood

import android.os.Handler
import android.os.Looper
import dev.sun.wechat.features.items.chat.TextToSpeech
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast

/**
 * 语音回复：把选中的候选回复合成成语音，直接作为语音消息发出。
 * 对应 WCS「在候选结果中生成并发送语音」；音色优先用「自定义音色」，否则用当前后端的预设音色。
 */
object VoiceReply {

    private const val TAG = "VoiceReply"
    private val main = Handler(Looper.getMainLooper())

    fun enabled(): Boolean = TextToSpeech.voiceReplyEnabled

    /** 后端凭据是否已备齐（魔方要 API Key，豆包要 Cookie）。 */
    fun ready(): Boolean =
        if (TextToSpeech.backend == TextToSpeech.BACKEND_DOUBAO) TextToSpeech.doubaoCookie.isNotBlank()
        else TextToSpeech.apiKey.isNotBlank()

    /**
     * 异步合成并发送；回调在主线程。
     * @param onResult true 表示语音消息已交给微信发送。
     */
    fun send(talker: String, text: String, onResult: (Boolean) -> Unit = {}) {
        val content = text.trim()
        if (talker.isBlank() || content.isBlank() || !enabled() || !ready()) {
            main.post { onResult(false) }
            return
        }
        val custom = TextToSpeech.customVoiceId.trim()
        val callback: (String?, String) -> Unit = { path, error ->
            if (path.isNullOrBlank()) {
                WeLogger.w(TAG, "tts failed: $error")
                main.post { onResult(false) }
            } else {
                // 微信语音消息要求 SILK 编码：先由 sendVoiceTo 做 WAV→SILK 转换再发送
                TextToSpeech.sendVoiceTo(talker, path) { sent ->
                    if (!sent) WeLogger.w(TAG, "sendVoice failed for $talker")
                    main.post {
                        showToast(HostInfo.application, if (sent) "语音已发送" else "语音发送失败")
                        onResult(sent)
                    }
                }
            }
        }
        if (TextToSpeech.backend == TextToSpeech.BACKEND_DOUBAO) {
            val speaker = custom.ifBlank { TextToSpeech.doubaoSpeaker }
            TextToSpeech.generateVoiceDoubao(content, speaker, TextToSpeech.doubaoCookie, callback)
        } else {
            val voiceId = custom.ifBlank { TextToSpeech.selectedVoice }
            val emotion = TextToSpeech.EMOTIONS
                .firstOrNull { it.first == TextToSpeech.selectedEmotion }
                ?.second
                ?: TextToSpeech.EMOTIONS.first().second
            TextToSpeech.generateVoice(content, voiceId, emotion, TextToSpeech.apiKey, callback)
        }
    }
}
