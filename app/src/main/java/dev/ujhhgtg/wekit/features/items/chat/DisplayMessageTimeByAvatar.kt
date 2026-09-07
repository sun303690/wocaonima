package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch

/** 显示消息时间：在头像下方显示发送时间。 */
object DisplayMessageTimeByAvatar : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "显示消息时间"
    override val nameRes = R.string.feature_display_message_time_by_avatar_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_message_time_by_avatar_description

    private var timePattern by prefOption("avatar_time_pattern", "HH:mm")
    private var textSize by prefOption("avatar_time_text_size", 6)
    private var textColor by prefOption("avatar_time_text_color", "#FF8AB22F")

    /** Marks the time TextView we insert under the avatar, so a recycled row is reused, not re-added. */
    private val avatarTimeTag = 0x7E000020

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private fun epochToMillis(epoch: Long): Long {
        // field_createTime 可能是秒（10 位）或毫秒（13 位），统一转毫秒
        return if (epoch in 1_000_000_000L..9_999_999_999L) epoch * 1000 else epoch
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        // 系统消息（时间胶囊/进群提示等）没有普通头像布局，跳过。
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.type?.isSystem == true) return

        val tag = view.tag ?: return
        val avatar = tag.reflekt()
            .firstField {
                name = "avatarIV"
                superclass()
            }
            .get() as? View ?: return
        val mask = avatar.parent as? FrameLayout ?: return

        var time = mask.getTag(avatarTimeTag) as? TextView
        if (time == null) {
            time = TextView(mask.context)
            time.tag = null
            time.setTag(avatarTimeTag, true)
            mask.addView(
                time,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                )
            )
            // 位置：MaskLayout 与头像等高，把时间顶到 MaskLayout 下缘正下方。
            time.post {
                val lp = time.layoutParams as? FrameLayout.LayoutParams ?: return@post
                lp.topMargin = mask.height
                time.layoutParams = lp
            }
        }

        time.text = formatEpoch(epochToMillis(msgInfo.createTime), timePattern)
        time.visibility = View.VISIBLE

        val parsedColor = runCatching { textColor.toColorInt() }.getOrElse { android.graphics.Color.GRAY }
        time.setTextColor(parsedColor)
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext = LocalWeKitLocalizedContext.current
            var timeFormatInput by remember { mutableStateOf(timePattern) }
            var textSizeInputRaw by remember { mutableStateOf(textSize.toString()) }
            var textColorInput by remember { mutableStateOf(textColor) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_display_message_time_by_avatar_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                OutlinedTextField(
                                    value = timeFormatInput,
                                    onValueChange = { timeFormatInput = it },
                                    label = { Text(stringResource(R.string.avatar_time_format)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = textSizeInputRaw,
                                    onValueChange = { textSizeInputRaw = it.filter { c -> c.isDigit() } },
                                    label = { Text(stringResource(R.string.avatar_time_font_size)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.avatar_time_color),
                                    value = textColorInput,
                                    onValueChange = { textColorInput = it },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val textSizeInput = textSizeInputRaw.toIntOrNull()
                        if (textSizeInput == null || textSizeInput <= 0) {
                            showToast(localizedContext.getString(R.string.avatar_time_invalid_number))
                            return@Button
                        }
                        timePattern = timeFormatInput.ifBlank { "HH:mm" }
                        textSize = textSizeInput
                        textColor = textColorInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }
}
