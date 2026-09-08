package dev.sun.wechat.features.items.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.features.api.net.WePacketHelper
import dev.sun.wechat.features.api.net.models.protobuf.OpLog
import dev.sun.wechat.features.api.net.models.protobuf.OpLogRespProto
import dev.sun.wechat.features.api.net.models.protobuf.SetNicknameProto
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger

object SetProfileNickname : ClickableFeature() {

    override val technicalId = "设置微信昵称"
    override val nameRes = R.string.feature_set_profile_nickname_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_set_profile_nickname_description

    private const val TAG = "SetProfileNickname"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var nickname by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_set_profile_nickname_name)) },
                text = {
                    TextField(
                        label = { Text(stringResource(R.string.profile_new_nickname)) },
                        value = nickname, onValueChange = { nickname = it }, singleLine = false
                    )
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        val reqBytes = OpLog.encodeSingle(
                            OpLog.CMD_SET_NICKNAME, SetNicknameProto(nickname = nickname)
                        )

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
                        ) {
                            onSuccess { bytes ->
                                val resp = bytes?.let { OpLogRespProto.decode(it) }
                                WeLogger.i(TAG, "success: ret=${resp?.ret}")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.profile_nickname_success)) },
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.profile_nickname_server_code,
                                                    resp?.ret?.toString() ?: stringResource(R.string.unknown),
                                                )
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                                        }
                                    )
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text(stringResource(R.string.profile_nickname_failure)) },
                                        text = {
                                            Text(stringResource(R.string.profile_nickname_failure_details, type, code, msg))
                                        },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
