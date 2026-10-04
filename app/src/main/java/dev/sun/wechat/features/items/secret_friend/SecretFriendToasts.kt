package dev.sun.wechat.features.items.secret_friend

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.ui.utils.showComposeDialog

/**
 * 提示自定义组 15 / 16：操作提示总开关（含文案编辑）、长按菜单显示文字。
 */
// ─────────────────────────── 15. 操作提示 ───────────────────────────

/**
 * 操作提示（总开关）：控制密友相关操作的 Toast（临时解除/恢复隐藏、加入/移除密友、
 * 朋友圈动态隐藏提示）。开关关闭时 [SecretFriendState.showToastIfEnabled] 静默；
 * 添加/移除密友等显式菜单操作仍会直接 Toast 以提供反馈。
 * 下方编辑行可自定义各条提示文案。
 */

object SecretFriendToastToggle : SwitchFeature() {
    override val technicalId: String = "操作提示"
    override val nameRes: Int = R.string.secret_friend_27_name
    override val categoryIds: List<String> = listOf("密友功能")
    override val descriptionRes: Int? = null


    // 开关状态必须同步到 SecretFriendState.toastsEnabled（showToastIfEnabled 读的是它）：
    // 此前无任何代码写该 pref，开关永远不生效
    override fun onEnable() {
        SecretFriendState.toastsEnabled = true
    }

    override fun onDisable() {
        SecretFriendState.toastsEnabled = false
    }

    @Composable
    override fun Ui() {
        Column {
            Spacer(Modifier.height(4.dp))
            TextFieldDialogWidget(
                title = "临时解除提示",
                value = SecretFriendState.toastTempShown,
                onValueChange = { SecretFriendState.toastTempShown = it },
                dialogTitle = "临时解除提示文案",
                confirmLabel = "确定",
                dismissLabel = "取消",
            )
            TextFieldDialogWidget(
                title = "恢复隐藏提示",
                value = SecretFriendState.toastTempOff,
                onValueChange = { SecretFriendState.toastTempOff = it },
                dialogTitle = "恢复隐藏提示文案",
                confirmLabel = "确定",
                dismissLabel = "取消",
            )
            TextFieldDialogWidget(
                title = "加入密友提示",
                value = SecretFriendState.toastAdded,
                onValueChange = { SecretFriendState.toastAdded = it },
                dialogTitle = "加入密友提示文案",
                confirmLabel = "确定",
                dismissLabel = "取消",
            )
            TextFieldDialogWidget(
                title = "取消密友提示",
                value = SecretFriendState.toastRemoved,
                onValueChange = { SecretFriendState.toastRemoved = it },
                dialogTitle = "取消密友提示文案",
                confirmLabel = "确定",
                dismissLabel = "取消",
            )
            TextFieldDialogWidget(
                title = "朋友圈隐藏提示",
                value = SecretFriendState.toastMomentHidden,
                onValueChange = { SecretFriendState.toastMomentHidden = it },
                dialogTitle = "朋友圈动态隐藏提示文案",
                confirmLabel = "确定",
                dismissLabel = "取消",
            )
        }
    }
}

// ─────────────────────── 16. 菜单显示文字 ───────────────────────

/**
 * 菜单显示文字（可点击项）：自定义「会话列表长按添加 / 通讯录长按添加」注入菜单的
 * 显示文字（默认「加入密友」）。已加入密友的联系人菜单固定显示「取消密友」。
 */

object SecretFriendMenuTextConfig : ClickableFeature() {
    override val technicalId: String = "菜单显示文字"
    override val nameRes: Int = R.string.secret_friend_28_name
    override val categoryIds: List<String> = listOf("密友功能")
    override val descriptionRes: Int? = null


    override val alwaysEnabled: Boolean = true
    override val noSwitchWidget: Boolean = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(SecretFriendState.addMenuText) }

            AlertDialogContent(
                title = { Text("菜单显示文字") },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(12) },
                        singleLine = true,
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (draft.isNotBlank()) SecretFriendState.addMenuText = draft.trim()
                        onDismiss()
                    }) { Text("保存") }
                },
            )
        }
    }
}
