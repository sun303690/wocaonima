package dev.sun.wechat.features.items.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.WeServiceApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.utils.AudioUtils
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.ui.utils.MicIcon
import java.io.File

/**
 * 视频提取声音：长按视频消息 -> 提取音轨 -> 转 silk -> 发送为语音。
 *
 * 移植自 Hchat 的「视频转语音」脚本插件，但用 wocaonima 自身的 API 原生实现：
 * - [WeServiceApi.getVideoMp4PathFromMsgInfo] 取视频本地 mp4 路径
 * - [AudioUtils.anyToSilk] 从视频文件提取音轨并转码为 silk（内部 MediaExtractor 会选第一条 audio 轨道）
 * - [WeMessageApi.sendVoice] 把 silk 作为语音消息发送
 */
object VideoToVoice : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "视频提取声音"
    override val nameRes = R.string.feature_video_to_voice_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_video_to_voice_description
    override val defaultEnabled = true

    private const val TAG = "VideoToVoice"
    private const val MENU_ID = 777027
    private const val SILK_DIR = "video_to_voice"

    private val mh = Handler(Looper.getMainLooper())

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                id = MENU_ID,
                text = localizedChatString(R.string.video_to_voice_menu),
                drawable = MicIcon,
                imageVector = MaterialSymbols.Outlined.Graphic_eq,
                isSupported = { msgInfo ->
                    val type = msgInfo.type
                    type != null && (type.code == MessageType.VIDEO.code || type.code == MessageType.MICRO_VIDEO.code)
                },
                onClick = { view, _, msgInfo ->
                    extractAndSend(view.context, msgInfo)
                }
            )
        )
    }

    private fun extractAndSend(context: Context, msgInfo: MessageInfo) {
        val videoPath = runCatching { WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo) }.getOrNull()
        if (videoPath.isNullOrBlank() || !File(videoPath).isFile) {
            showToast(context, context.getString(R.string.video_to_voice_no_video))
            return
        }
        showToast(context, context.getString(R.string.video_to_voice_extracting))
        Thread {
            val ok = runCatching {
                val dir = File(KnownPaths.moduleCache.toFile(), SILK_DIR).apply { mkdirs() }
                val silkPath = File(dir, "video_to_voice_${System.currentTimeMillis()}.silk").absolutePath
                if (!AudioUtils.anyToSilk(videoPath, silkPath)) {
                    WeLogger.w(TAG, "anyToSilk failed for $videoPath")
                    return@runCatching false
                }
                val durationMs = AudioUtils.getDurationMs(silkPath).toInt()
                if (durationMs <= 0) {
                    WeLogger.w(TAG, "invalid silk duration $durationMs for $silkPath")
                    return@runCatching false
                }
                WeLogger.i(TAG, "sending voice ${File(silkPath).name} ($durationMs ms) to ${msgInfo.talker}")
                WeMessageApi.sendVoice(msgInfo.talker, silkPath, durationMs)
            }.getOrDefault(false)
            val message = if (ok) R.string.video_to_voice_sent else R.string.video_to_voice_failed
            mh.post { showToast(context, context.getString(message)) }
        }.start()
    }

    override fun onClick(context: ComponentActivity) {
        showToast(context, context.getString(R.string.video_to_voice_menu) + "：长按视频消息使用")
    }
}