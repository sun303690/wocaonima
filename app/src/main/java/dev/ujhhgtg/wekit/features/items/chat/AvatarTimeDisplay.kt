package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch

/** View tag marking the avatar-time TextView we inject under each avatar. */
private const val AVATAR_TIME_TAG = 0x7E001100

/**
 * 头像下方显示时间：在聊天窗口每条消息（对方与我）的头像正下方，
 * 显示该消息的发送时间 HH:mm，粉红色。
 */
object AvatarTimeDisplay : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "头像下显示时间"
    override val nameRes = R.string.feature_avatar_time_display_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_avatar_time_display_description

    private const val TAG = "AvatarTimeDisplay"

    /** 时间格式：HH:mm。 */
    private const val TIME_PATTERN = "HH:mm"

    /** 粉红色（Pink）。 */
    private val PINK = Color.parseColor("#FF69B4")

    /** 字号（sp）。 */
    private const val TEXT_SIZE_SP = 11f

    override val noSwitchWidget = true

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(param: HookParam, view: View) {
        val tag = view.tag ?: return
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        // 定位头像（可能是 MaskLayout 包裹，也可能是直接子 View）
        val avatar = tag.reflekt()
            .firstField {
                name = "avatarIV"
                superclass()
            }.get() as? View ?: return

        val avatarContainer = avatar.parent as? ViewGroup ?: return

        // 复用/创建头像下方的 TextView
        var timeView = avatarContainer.findViewWithTag<TextView>(AVATAR_TIME_TAG)
        if (timeView == null) {
            timeView = TextView(avatarContainer.context).apply {
                this.tag = AVATAR_TIME_TAG
                setTextColor(PINK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                // 默认加在父容器末尾；若头像在靠前位置，则紧跟头像插入
                avatarContainer.addView(this, lp)
            }
        }

        // 把 TextView 移到头像下方（紧跟头像 index 之后）
        val avatarIndex = avatarContainer.indexOfChild(avatar)
        val timeIndex = avatarContainer.indexOfChild(timeView)
        if (avatarIndex >= 0 && timeIndex != avatarIndex + 1) {
            avatarContainer.removeView(timeView)
            avatarContainer.addView(timeView, avatarIndex + 1)
        }

        // 更新文本：HH:mm，粉红
        timeView.text = formatEpoch(msgInfo.createTime, TIME_PATTERN)
        timeView.setTextColor(PINK)

        // 隐藏系统自带的时间（可选：这里保留系统时间不干扰，仅新增头像下方时间）
        WeLogger.d(TAG, "avatar time set for msg ${msgInfo.id}")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_avatar_time_display_name)) },
                text = {
                    Text(stringResource(R.string.feature_avatar_time_display_description))
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {},
            )
        }
    }
}
