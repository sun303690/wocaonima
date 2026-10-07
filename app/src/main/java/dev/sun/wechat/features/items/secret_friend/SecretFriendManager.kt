package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import androidx.activity.ComponentActivity
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast

/**
 * 密友名单管理 = 密友功能**主控开关** + 名单编辑入口。
 *
 * - 开关关闭（主控关）：所有依赖名单的密友隐藏/拦截功能立即失效（名单视为空），
 *   被删除的会话行恢复显示；**名单本身保留**，重新打开无需重新勾选。
 * - 开关打开（主控开）：按存储名单 + 各功能自身开关恢复生效。
 *
 * 名单数据读写走 [SecretFriendState]（getStoredWxIds/setWxIds 不受主控影响），
 * 各隐藏功能消费 [SecretFriendState.getWxIds]（主控关闭时返回空 → 整体放行）。
 */

object SecretFriendManager : ClickableFeature() {
    override val technicalId = "密友名单管理"
    override val nameRes: Int = R.string.secret_friend_08_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "SecretFriendManager"

    /** 默认开启：从未操作过该开关的用户，密友功能照常按各自开关工作。 */
    override val defaultEnabled: Boolean = true

    override fun onClick(context: ComponentActivity) = openSelector(context)

    /** 打开密友名单选择器；任意 Context 可用（供 #my# 面板等入口复用）。 */
    fun openSelector(context: android.content.Context) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择密友",
                contacts = regularContacts,
                // 显示真实存储名单（即使主控当前关闭也能看到已有勾选）
                initialSelectedWxIds = SecretFriendState.getStoredWxIds(),
                onDismiss = onDismiss,
            ) {
                SecretFriendState.setWxIds(it)
                // 名单变化后对账：恢复被删行（移出名单的密友），仍在名单中的重新隐藏
                HideConversations.reconcileOnListChange()
                showToast("密友名单已更新（${it.size} 人）")
                onDismiss()
            }
        }
    }

    override fun onEnable() {
        // 方案1：主控重新开启，隐藏靠 SQL 过滤(getWxIds)即可生效，无需删行恢复
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "master enabled")
    }

    override fun onDisable() {
        // 主控关闭：getWxIds 返回空 → 查询全部放行 → 密友会话行自然显示
        HideConversations.reconcileOnListChange()
        WeLogger.i(TAG, "master disabled: hiding disabled, list kept")
        showToast("密友功能已关闭，密友已恢复显示；名单已保留")
    }
}
