package dev.sun.wechat.features.items.moments

import dev.sun.wechat.R
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Camera
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.WeServiceApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.ui.WeMomentsApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.utils.CameraIcon
import dev.sun.wechat.utils.android.ToastUtils.showToastSuspend
import dev.sun.wechat.ui.utils.localizedMomentsString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
object ForwardMessagesToMoments : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "消息转圈"
    override val nameRes = R.string.feature_forward_messages_to_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_forward_messages_to_moments_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.IMAGE, MessageType.VIDEO
    )

    // TEXT and QUOTE both boil down to a piece of text for a Moments post
    private fun MessageInfo.isTextLike() = type == MessageType.TEXT || type == MessageType.QUOTE

    private fun MessageInfo.momentsText(): String =
        if (type == MessageType.QUOTE) quoteMsgActualContent!! else actualContent

    // a selection maps to a valid Moments post if it is one of:
    //   - any number of texts (concatenated)
    //   - any number of texts + any number of images
    //   - any number of texts + exactly one video
    //   - multiple images (already covered by the text+images case with zero texts)
    private fun isSupportedSelection(msgInfos: List<MessageInfo>): Boolean {
        if (msgInfos.isEmpty()) return false
        // only text/image/video participate; quotes count as text
        if (msgInfos.any { it.type != MessageType.IMAGE && it.type != MessageType.VIDEO && !it.isTextLike() }) {
            return false
        }

        val imageCount = msgInfos.count { it.type == MessageType.IMAGE }
        val videoCount = msgInfos.count { it.type == MessageType.VIDEO }

        // video can't be combined with images and at most one video is allowed
        if (videoCount > 1) return false
        if (videoCount == 1 && imageCount > 0) return false

        return true
    }

    private fun forwardSelectionToMoments(activity: android.app.Activity, msgInfos: List<MessageInfo>) {
        val text = msgInfos.filter { it.isTextLike() }
            .joinToString("\n\n") { it.momentsText() }
            .takeIf { it.isNotBlank() }

        val images = msgInfos.filter { it.type == MessageType.IMAGE }
        val video = msgInfos.firstOrNull { it.type == MessageType.VIDEO }

        when {
            video != null -> repostVideoToMoments(activity, video, text)
            images.isNotEmpty() -> repostImagesToMoments(activity, images, text)
            else -> WeMomentsApi.postTextInUi(activity, text ?: "")
        }
    }

    /**
     * 图片转发：先在后台等待每张图下载 + 解密 (微信聊天图是 .dat 加密存储, SnsUploadUI
     * 只认解密后的真实文件路径, 且等待可能长达数秒), 全部就绪后再回主线程打开编辑器。
     * 有图片拿不到 (未加载/解密失败) 则提示先在聊天里点开加载, 不打开编辑器。
     */
    private fun repostImagesToMoments(activity: android.app.Activity, images: List<MessageInfo>, text: String?) {
        scope.launch {
            val paths = images.mapNotNull { msg ->
                runCatching { WeMessageApi.downloadImage(msg.serverId) }.getOrNull()
            }
            if (paths.size != images.size) {
                showToastSuspend(activity, localizedMomentsString(R.string.moments_forward_media_not_ready))
                return@launch
            }
            withContext(Dispatchers.Main) { WeMomentsApi.postImagesInUi(activity, paths, text) }
        }
    }

    /**
     * 视频转发：取本地 mp4 (用户已点开过才有本地文件), 走**相册流程**塞进 SnsUploadUI——
     * 由微信自己的编辑器接管 (长视频 + 封面选择), 视觉上和"从相册选视频发朋友圈"一致。
     * 不用 sight 流程 (Ksnsupload_type=14): 那是 30 秒小视频入口, 长视频进不去。
     * mp4 拿不到则提示先在聊天里点开视频。
     */
    private fun repostVideoToMoments(activity: android.app.Activity, video: MessageInfo, text: String?) {
        scope.launch {
            val mp4 = runCatching { WeServiceApi.getVideoMp4PathFromMsgInfo(video) }.getOrNull()
                ?.takeIf { java.io.File(it).exists() }
            if (mp4 == null) {
                showToastSuspend(activity, localizedMomentsString(R.string.moments_forward_media_not_ready))
                return@launch
            }
            withContext(Dispatchers.Main) { WeMomentsApi.postImagesInUi(activity, listOf(mp4), text) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777009, localizedMomentsString(R.string.moments_forward_messages_menu), CameraIcon, MaterialSymbols.Outlined.Camera,
                isSupported = { it.type in SUPPORTED_MSG_TYPES },
                // Moments can post: pure text, text + images, text + a single video, or multiple
                // images. video can't be mixed with images and only one video is allowed. multiple
                // texts are brute-force concatenated with blank lines.
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgInfos -> isSupportedSelection(msgInfos) },
                    onClick = { _, chattingContext, msgInfos ->
                        forwardSelectionToMoments(chattingContext.activity, msgInfos)
                    }
                ),
                onClick = { _, chattingContext, msgInfo ->
                    val activity = chattingContext.activity

                    when (msgInfo.type) {
                        MessageType.TEXT -> {
                            WeMomentsApi.postTextInUi(activity, msgInfo.actualContent)
                        }

                        MessageType.QUOTE -> {
                            WeMomentsApi.postTextInUi(activity, msgInfo.quoteMsgActualContent!!)
                        }

                        MessageType.IMAGE -> {
                            repostImagesToMoments(activity, listOf(msgInfo), null)
                        }

                        MessageType.VIDEO -> {
                            repostVideoToMoments(activity, msgInfo, null)
                        }

                        else -> {}
                    }
                }
            )
        )
    }
}
