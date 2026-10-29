package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AutoCacheImages : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动缓存图片"
    override val nameRes = R.string.feature_auto_cache_images_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_cache_images_description

    private const val TAG = "AutoCacheImages"

    private var useWhitelist by KvStore.prefOption("autocache_images_use_whitelist", false)
    private var whitelist by KvStore.prefOption("autocache_images_whitelist", emptySet())
    private var blacklist by KvStore.prefOption("autocache_images_blacklist", emptySet())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.IMAGE.code) return

        // 自己发出的图片本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return

        if (useWhitelist) {
            if (talker !in whitelist) return
        } else {
            if (talker in blacklist) return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        WeLogger.i(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        CoroutineScope(Dispatchers.IO).launch {
            val path = WeMessageApi.cacheImage(msgSvrId)
            if (path != null) {
                WeLogger.i(TAG, "cached image to $path")
            } else {
                WeLogger.e(TAG, "failed to auto-cache image msgSvrId=$msgSvrId")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_cache_images_name)) },
                text = {
                    MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            MiuixSwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(if (useWhitelistState) R.string.filter_list_whitelist_selected else R.string.filter_list_blacklist_selected),
                                description = stringResource(if (useWhitelistState) R.string.chat_auto_cache_images_whitelist_description else R.string.chat_auto_cache_images_blacklist_description),
                                checked = useWhitelistState,
                                onCheckedChange = {
                                    useWhitelistState = it
                                    useWhitelist = it
                                },
                            )
                        }
                        item {
                            MiuixBaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(if (useWhitelistState) R.string.filter_list_configure_whitelist else R.string.filter_list_configure_blacklist),
                                description = stringResource(R.string.filter_list_select_contacts_hint),
                                onClick = {
                                val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelistState) whitelist else blacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = stringResource(if (useWhitelistState) R.string.filter_list_select_whitelist else R.string.filter_list_select_blacklist),
                                        contacts = contacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelistState) {
                                            whitelist = selectedIds
                                        } else {
                                            blacklist = selectedIds
                                        }
                                        showToast(localizedChatQuantity(R.plurals.filter_list_contacts_saved, selectedIds.size, selectedIds.size))
                                        onDismiss()
                                    }
                                }
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }
}
