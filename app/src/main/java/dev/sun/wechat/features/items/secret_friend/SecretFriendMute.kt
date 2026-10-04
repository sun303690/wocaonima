package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.WeLogger

/**
 * 密友消息通知隐藏（浮云「隐藏密友消息通知」语义）：密友被隐藏期间自动设置「消息免打扰」。
 *
 * 稳妥实现：不做字段重写（rcontact 的 mute 位由服务器 oplog 同步，查询期重写会在
 * 会话设置页露馅），而是走微信原生免打扰通道 [WeConversationApi.setDnd]——开启本开关
 * 或名单新增成员时，对尚未来打扰的密友逐个 setDnd(true)。与「隐藏联系人」同一条
 * 已验证通道（OpenImOpLogLogic oplog，服务器同步）。
 *
 * 关闭开关**不回滚**免打扰（与隐藏联系人一致：无法区分用户自设的免打扰，回滚会误恢复）。
 */
// @Feature(was name=密友消息通知隐藏)
override val technicalId: String = "密友消息通知隐藏"
override val nameRes: Int = R.string.secret_friend_12_name
override val categoryIds: List<String> = listOf("密友功能")
override val descriptionRes: Int? = null

object MuteSecretFriend : SwitchFeature() {

    private const val TAG = "MuteSecretFriend"

    private val listChangedListener = { ensureAllSecretsMuted() }

    override fun onEnable() {
        SecretFriendState.addOnListChanged(listChangedListener)
        ensureAllSecretsMuted()
    }

    override fun onDisable() {
        SecretFriendState.removeOnListChanged(listChangedListener)
    }

    /** 对名单内尚未免打扰的密友逐个开启（幂等，名单变动时只补齐增量）。 */
    private fun ensureAllSecretsMuted() {
        for (wxId in SecretFriendState.getWxIds()) {
            if (WeConversationApi.isDnd(wxId)) continue
            WeLogger.d(TAG, "auto muting secret friend $wxId")
            runCatching { WeConversationApi.setDnd(wxId, true) }
                .onFailure { WeLogger.w(TAG, "failed to mute secret friend $wxId", it) }
        }
    }
}
