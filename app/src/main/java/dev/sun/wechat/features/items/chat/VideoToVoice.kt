package dev.sun.wechat.features.items.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Graphic_eq
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
import dev.sun.wechat.utils.serialization.XmlUtils
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
        val videoPath = resolveVideoPath(msgInfo)
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

    /**
     * 三级回退取视频本地文件路径：
     * 1. 微信自身的 restore mp4 服务（[WeServiceApi.getVideoMp4PathFromMsgInfo]）；
     * 2. `field_imgPath` 本身若是可用路径/相对 acc 目录的路径，直接用；
     * 3. 按消息 XML 里的 md5 在 `<accPath>/video/` 下递归找同名 mp4。
     *
     * 旧实现只有第 1 级，8.0.78 上一旦 `field_imgPath` 为空或服务返回空，就只剩「未找到视频」。
     */
    private fun resolveVideoPath(msgInfo: MessageInfo): String? {
        val direct = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
        if (!direct.isNullOrBlank() && File(direct).isFile) return direct
        WeLogger.w(TAG, "restore 服务未取到视频，imagePath=${msgInfo.imagePath} 返回=$direct")

        val imagePath = msgInfo.imagePath
        if (!imagePath.isNullOrBlank()) {
            val asFile = File(imagePath)
            if (asFile.isFile) return asFile.absolutePath
            val accPath = runCatching { WeMessageApi.accPath() }.getOrDefault("")
            if (accPath.isNotBlank()) {
                val candidates = listOf("video/", "video/thumb/", "")
                for (prefix in candidates) {
                    val f = File(accPath + prefix + imagePath.substringAfterLast('/'))
                    if (f.isFile) return f.absolutePath
                }
            }
        }

        val md5 = runCatching { XmlUtils.extractXmlAttr(msgInfo.content, "md5") }.getOrNull()
        if (md5.isNullOrBlank()) {
            WeLogger.w(TAG, "消息 XML 里没有 md5，无法按文件名兜底 id=${msgInfo.id}")
            return null
        }
        val accPath = runCatching { WeMessageApi.accPath() }.getOrDefault("")
        if (accPath.isBlank()) {
            WeLogger.w(TAG, "取不到微信 acc 目录，无法按 md5 兜底 md5=$md5")
            return null
        }
        val videoDir = File(accPath, "video")
        val hit = runCatching {
            videoDir.walkTopDown()
                .filter { it.isFile && it.name.contains(md5, ignoreCase = true) }
                .maxByOrNull { it.length() }
        }.getOrNull()
        WeLogger.i(TAG, "按 md5 兜底查找视频 md5=$md5 命中=${hit?.absolutePath}")
        return hit?.absolutePath
    }

    override fun onClick(context: ComponentActivity) {
        showToast(context, context.getString(R.string.video_to_voice_menu) + "：长按视频消息使用")
    }
}