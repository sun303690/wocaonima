package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.chat.musicorder.QQMusicOrderRuntime
import dev.sun.wechat.features.items.chat.musicorder.QQMusicOrderSettings
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.DefaultColumn
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap

object QQMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    // 点歌有自己的内部“启用点歌”开关；外层功能必须常驻，才能监听消息和处理指令。
    override val alwaysEnabled = true
    override val technicalId = "QQ音乐点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_qq_music_order_description

    private const val TAG = "QQMusicOrder"

    private var runtime: QQMusicOrderRuntime? = null
    private val handled = ConcurrentHashMap<String, Boolean>()

    override fun onEnable() {
        runtime = QQMusicOrderRuntime(HostInfo.application, { message, t ->
            if (t != null) WeLogger.e(TAG, message, t) else WeLogger.e(TAG, message)
        })
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        runtime?.shutdown()
        runtime = null
        handled.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val talker = values.getAsString("talker").orEmpty()
        if (talker.isBlank() || talker.startsWith("gh_")) return

        val msgInfo = MessageInfo.fromContentValues(values)
        val isOutgoing = values.getAsInteger("isSend") != 0
        val isGroup = msgInfo.isInGroupChat
        val sender = msgInfo.sender
        val content = values.getAsString("content").orEmpty()

        val dedupeKey = values.getAsString("msgSvrId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: values.getAsInteger("localId")?.toString()
        if (dedupeKey != null && handled.putIfAbsent(dedupeKey, true) != null) return
        if (handled.size > 500) handled.clear()

        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        val msgId = values.getAsInteger("localId")?.toLong() ?: 0L

        // 自己发的点歌命令走拦截；别人的走数据库处理
        if (isOutgoing) {
            runtime?.handleOwnCommand(content)?.let { intercepted ->
                if (intercepted) WeLogger.i(TAG, "intercepted own command: $content")
            }
            return
        }
        runtime?.onTextInserted(talker, content, msgSvrId, msgId, isOutgoing, isGroup, sender)
    }

    private val appContext: Context
        get() = HostInfo.application

    private fun showConfigDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            var enabled by remember { mutableStateOf(QQMusicOrderSettings.isEnabled()) }
            var triggerText by remember { mutableStateOf(QQMusicOrderSettings.triggers().joinToString(",")) }
            var appIdText by remember { mutableStateOf(QQMusicOrderSettings.appId()) }
            var sendCard by remember { mutableStateOf(QQMusicOrderSettings.sendAsCard()) }
            var sendVoice by remember { mutableStateOf(QQMusicOrderSettings.sendAsVoice()) }
            var customSinger by remember { mutableStateOf(QQMusicOrderSettings.customSingerEnabled()) }
            var defaultSingerText by remember { mutableStateOf(QQMusicOrderSettings.defaultSinger()) }
            var replaceSingerNick by remember { mutableStateOf(QQMusicOrderSettings.replaceSingerWithNickname()) }
            var replaceCoverAvatar by remember { mutableStateOf(QQMusicOrderSettings.replaceCoverWithAvatar()) }
            var interceptOwn by remember { mutableStateOf(QQMusicOrderSettings.interceptOwnCommand()) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_qq_music_order_name)) },
                text = {
                    DefaultColumn {
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_enable),
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                        )
                        OutlinedTextField(
                            value = triggerText,
                            onValueChange = { triggerText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_trigger_hint)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = appIdText,
                            onValueChange = { appIdText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_appid_hint)) },
                            singleLine = true,
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_send_card),
                            checked = sendCard,
                            onCheckedChange = { sendCard = it },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_send_voice),
                            checked = sendVoice,
                            onCheckedChange = { sendVoice = it },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_custom_singer),
                            checked = customSinger,
                            onCheckedChange = { customSinger = it },
                        )
                        OutlinedTextField(
                            value = defaultSingerText,
                            onValueChange = { defaultSingerText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_default_singer_hint)) },
                            singleLine = true,
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_replace_singer_nickname),
                            checked = replaceSingerNick,
                            onCheckedChange = { replaceSingerNick = it },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_replace_cover_avatar),
                            checked = replaceCoverAvatar,
                            onCheckedChange = { replaceCoverAvatar = it },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_intercept_own_command),
                            checked = interceptOwn,
                            onCheckedChange = { interceptOwn = it },
                        )
                        Text(stringResource(R.string.qq_music_order_whitelist_hint))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        QQMusicOrderSettings.setEnabled(enabled)
                        QQMusicOrderSettings.setTriggers(triggerText)
                        QQMusicOrderSettings.setAppId(appIdText)
                        QQMusicOrderSettings.setSendAsCard(sendCard)
                        QQMusicOrderSettings.setSendAsVoice(sendVoice)
                        QQMusicOrderSettings.setCustomSingerEnabled(customSinger)
                        QQMusicOrderSettings.setDefaultSinger(defaultSingerText)
                        QQMusicOrderSettings.setReplaceSingerWithNickname(replaceSingerNick)
                        QQMusicOrderSettings.setReplaceCoverWithAvatar(replaceCoverAvatar)
                        QQMusicOrderSettings.setInterceptOwnCommand(interceptOwn)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }
}
