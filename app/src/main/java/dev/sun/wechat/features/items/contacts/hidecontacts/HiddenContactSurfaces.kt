package dev.sun.wechat.features.items.contacts.hidecontacts

import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.contacts.HideContacts

/**
 * 隐藏联系人的各个「隐藏面」。
 *
 * 每个面是一个独立开关（[SwitchFeature]），开关状态就是面是否生效的唯一依据；真正的 Hook
 * 由 [HideContacts] 统一安装一次，各 Hook 内部用 [HideContacts.surfaceOn] 按面判断——
 * 这样多个开关共用一份 Hook，不会出现「同一个 Hook 被装两遍」的问题。
 *
 * 默认全部开启：升级上来的用户名单还在，行为与之前一致；想单独关掉某个面再关即可。
 */

// ── 主页会话 ────────────────────────────────────────────────────────────────

object HideConversationSurface : SwitchFeature() {

    override val technicalId = "主页会话隐藏"
    override val nameRes = R.string.feature_hide_contacts_conversations_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 通讯录 ──────────────────────────────────────────────────────────────────

object HideContactListSurface : SwitchFeature() {

    override val technicalId = "通讯录隐藏"
    override val nameRes = R.string.feature_hide_contacts_contacts_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 群成员 / @ 选择器 ───────────────────────────────────────────────────────

object HideGroupMemberSurface : SwitchFeature() {

    override val technicalId = "群成员选择器隐藏"
    override val nameRes = R.string.feature_hide_contacts_group_members_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 收藏 ────────────────────────────────────────────────────────────────────

object HideFavoriteSurface : SwitchFeature() {

    override val technicalId = "收藏列表隐藏"
    override val nameRes = R.string.feature_hide_contacts_favorites_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 视频号互动 ──────────────────────────────────────────────────────────────

object HideFinderLikeSurface : SwitchFeature() {

    override val technicalId = "视频号互动隐藏"
    override val nameRes = R.string.feature_hide_contacts_finder_like_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 搜索 ────────────────────────────────────────────────────────────────────

object HideSearchSurface : SwitchFeature() {

    override val technicalId = "搜索结果隐藏"
    override val nameRes = R.string.feature_hide_contacts_search_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 朋友圈 ──────────────────────────────────────────────────────────────────

object HideMomentsSurface : SwitchFeature() {

    override val technicalId = "朋友圈隐藏"
    override val nameRes = R.string.feature_hide_contacts_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

object HideMomentsInlineSurface : SwitchFeature() {

    override val technicalId = "朋友圈互动隐藏"
    override val nameRes = R.string.feature_hide_contacts_moments_inline_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 音视频通话 ──────────────────────────────────────────────────────────────

object HideVoipSurface : SwitchFeature() {

    override val technicalId = "音视频通话隐藏"
    override val nameRes = R.string.feature_hide_contacts_voip_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 禁止进入聊天 / 禁止查看资料 ─────────────────────────────────────────────

object BlockHiddenChatSurface : SwitchFeature() {

    override val technicalId = "禁止进入聊天"
    override val nameRes = R.string.feature_hide_contacts_block_chat_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

object BlockHiddenProfileSurface : SwitchFeature() {

    override val technicalId = "禁止查看资料"
    override val nameRes = R.string.feature_hide_contacts_block_profile_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 自动免打扰 ──────────────────────────────────────────────────────────────

object MuteHiddenSurface : SwitchFeature() {

    override val technicalId = "自动免打扰"
    override val nameRes = R.string.feature_hide_contacts_mute_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 新的朋友 / 未读角标 ─────────────────────────────────────────────────────

object HideNewFriendsSurface : SwitchFeature() {

    override val technicalId = "新的朋友隐藏"
    override val nameRes = R.string.feature_hide_contacts_new_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

object HideUnreadSurface : SwitchFeature() {

    override val technicalId = "未读角标隐藏"
    override val nameRes = R.string.feature_hide_contacts_unread_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 自动拒接 ────────────────────────────────────────────────────────────────

object AutoRejectVoipSurface : SwitchFeature() {

    override val technicalId = "自动拒接音视频"
    override val nameRes = R.string.feature_hide_contacts_reject_voip_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 密友消息震动 ────────────────────────────────────────────────────────────

object VibrateHiddenSurface : SwitchFeature() {

    override val technicalId = "密友消息震动"
    override val nameRes = R.string.feature_hide_contacts_vibrate_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 微信团队汇总提醒 ────────────────────────────────────────────────────────

object TeamSummarySurface : SwitchFeature() {

    override val technicalId = "微信团队汇总提醒"
    override val nameRes = R.string.feature_hide_contacts_team_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.SECRET_FRIEND)

    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

/**
 * 各面开关变化后的统一收尾：刷新一次会话列表让结果立刻可见。
 *
 * Hook 是按面动态判断的，所以不需要装卸 Hook —— 开关一变，下一次查询就生效。
 */
internal fun HideContacts.onSurfaceChanged() {
    runCatching { WeConversationApi.reloadConversations() }
}
