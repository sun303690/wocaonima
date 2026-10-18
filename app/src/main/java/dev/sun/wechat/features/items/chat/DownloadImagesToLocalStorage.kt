package dev.sun.wechat.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.utils.DownloadIcon
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DownloadImagesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "图片保存到本地"
    override val nameRes = R.string.feature_download_images_to_local_storage_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_download_images_to_local_storage_description

    private const val TAG = "DownloadImagesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777021,
                localizedChatString(R.string.chat_action_download),
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.type == MessageType.IMAGE }
            ) { _, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = WeMessageApi.downloadImage(msgInfo.serverId) ?: run {
                        WeLogger.e(TAG, "failed to cache & download image")
                        showToastSuspend(localizedChatString(R.string.chat_image_download_failed))
                        return@launch
                    }
                    showToastSuspend(localizedChatString(R.string.chat_image_download_success, path))
                }
            }
        )
    }
}
