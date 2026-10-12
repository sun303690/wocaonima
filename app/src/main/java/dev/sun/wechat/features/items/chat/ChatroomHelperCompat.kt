package dev.sun.wechat.features.items.chat

import dev.sun.wechat.R
import dev.sun.wechat.data.WeKitDatabase
import dev.sun.wechat.data.structured.ConversationFolder
import dev.sun.wechat.data.structured.ConversationFolderType
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Ported from WechatChatRoomHelper (zhudongya123) — the classic "微信群消息助手".
 *
 * The original module injected two aggregate rows ("群聊消息" / "服务号消息") into the
 * homepage ListView by hooking GetCount / GetView / getItem and fabricating bean rows. That
 * adapter surgery needed WXClassParser pattern-matching against obfuscated classes for every
 * WeChat release, which is what broke it on 8.0.77.
 *
 * This port reproduces the same zero-config UX on top of WeKit's materialized-folder
 * machinery instead: enabling creates two preset folders (all group chats / all official
 * accounts). The homepage aggregate rows, unread badge aggregation, tap-to-open container,
 * live summary refresh, and share-target interception all come from
 * [ConversationAggregation] — no obfuscated methods are touched here, so this feature
 * inherits that feature's verified 8.0.77 compatibility.
 *
 * Disabling removes the two preset folders; ConversationAggregation's next full reconcile
 * (MainUI.onResume -> syncFoldersToDatabase) reclaims their materialized rows. Folders the
 * user created manually are untouched.
 */
object ChatroomHelperCompat : SwitchFeature() {

    override val technicalId = "群聊消息助手"
    override val nameRes = R.string.feature_chatroom_helper_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chatroom_helper_description

    private const val TAG = "ChatroomHelperCompat"

    // Fixed IDs so repeated enable/disable cycles reuse the same rows instead of piling up.
    private const val GROUPS_FOLDER_ID = "${ConversationAggregation.FOLDER_PREFIX}helper_groups"
    private const val OFFICIALS_FOLDER_ID = "${ConversationAggregation.FOLDER_PREFIX}helper_officials"

    private fun presetFolders() = listOf(
        ConversationFolder(
            id = GROUPS_FOLDER_ID,
            name = "群聊消息",
            type = ConversationFolderType.PRESET_GROUPS,
        ),
        ConversationFolder(
            id = OFFICIALS_FOLDER_ID,
            name = "服务号消息",
            type = ConversationFolderType.PRESET_OFFICIALS,
        ),
    )

    override fun onEnable() {
        // The materialization machinery lives in ConversationAggregation; its hooks must be
        // installed for the entries to appear. Enabling it here persists its switch too —
        // documented in the feature description.
        runCatching {
            ensureAggregationActive()
        }.onFailure { WeLogger.e(TAG, "failed to activate conversation aggregation", it) }

        runCatching {
            runBlocking(Dispatchers.IO) { ensureFolders() }
        }.onFailure { WeLogger.e(TAG, "failed to create helper folders", it) }

        notifyConfigChange()
    }

    override fun onDisable() {
        runCatching {
            runBlocking(Dispatchers.IO) { removeFolders() }
        }.onFailure { WeLogger.e(TAG, "failed to remove helper folders", it) }
        // Materialized rows are reclaimed by the reconcile in notifyFoldersChanged() (host up)
        // or the next MainUI.onResume (cold startup); both drop folders missing from config.
        notifyConfigChange()
    }

    private fun ensureAggregationActive() {
        if (ConversationAggregation.isActive) return
        ConversationAggregation.applyToggle(true)
        WeLogger.i(TAG, "auto-enabled ConversationAggregation for helper entries")
    }

    private suspend fun ensureFolders() {
        val dao = WeKitDatabase.instance.conversationCollectionDao()
        val existing = dao.getFolders().mapTo(mutableSetOf()) { it.id }
        presetFolders().forEach { preset ->
            if (preset.id !in existing) dao.putFolder(preset)
        }
    }

    private suspend fun removeFolders() {
        val dao = WeKitDatabase.instance.conversationCollectionDao()
        presetFolders().forEach { dao.removeFolder(it.id) }
    }

    /**
     * Best-effort accelerator: when enabling while WeChat is already running, notify
     * ConversationAggregation immediately so it drops its caches and runs the full
     * reconcile (creating the materialized rows) right away instead of waiting for the
     * next MainUI.onResume. On cold startup (database not up yet) this no-ops there and
     * the resume hook materializes the rows once WeChat is up.
     */
    private fun notifyConfigChange() {
        runCatching {
            ConversationAggregation.notifyFoldersChanged()
        }.onFailure { WeLogger.w(TAG, "failed to sync helper folders", it) }
    }
}