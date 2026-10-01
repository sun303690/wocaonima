package dev.sun.wechat.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.net.models.protobuf.ChatRoomDataProto
import dev.sun.wechat.features.core.ApiFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.reflection.BString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * 群成员行为监控（进退群 / 改群昵称卡片）。
 *
 * 原先是「联系人与群组」分类下的独立功能, 现整体并入「群管理」:
 * 开关在群管理设置对话框的「群成员行为监控」一节, 由 [enabled] 驱动。
 * 本对象改为常驻 [ApiFeature]（对用户不可见）, 监听与 hook 常驻安装,
 * 每次事件时读 [enabled] 决定是否生效。
 */
object MonitorGroupMemberOperations : ApiFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "群成员行为监控服务"
    override val nameRes = R.string.feature_monitor_group_member_operations_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_monitor_group_member_operations_description

    /** 由「群管理」设置界面驱动; 首次启动时从旧独立功能的开关迁移 */
    var enabled by prefOption(KEY_ENABLED, false)

    override fun onEnable() {
        migrateLegacySwitch()

        WeDatabaseListenerApi.addListener(this)

        methodHandleSpanClick.hookBefore {
            if (!enabled) return@hookBefore
            val url = args[1]!!.reflekt().firstField {
                type = BString
                modifiers(Modifiers.FINAL)
            }.get()!! as String
            if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

            val wxId = url.substringAfterLast('/')
            val context = (args[0] as View).context

            WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    /** 旧版本中这是独立 SwitchFeature, 开关存在旧键下; 迁移一次避免升级后丢失已启用状态 */
    private fun migrateLegacySwitch() {
        if (KvStore.getBoolOrDef(KEY_MIGRATED, false)) return
        KvStore.putBool(KEY_MIGRATED, true)
        if (KvStore.getBoolOrDef(LEGACY_SWITCH_KEY, false)) {
            KvStore.putBool(KEY_ENABLED, true)
        }
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (!enabled) return
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname") ?: return
        // 监控范围 = 群管理白名单(与群管理自身的处置范围一致): 名单外的群不监控
        if (!GroupManagement.isGroupWhitelisted(group)) return
        val newMemberCount = values.getAsInteger("memberCount")
        val newRawMembers = values.getAsString("memberlist")
        val newRoomData = values.getAsByteArray("roomdata")

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist,memberCount,roomdata FROM chatroom WHERE chatroomname = ?",
            arrayOf(group)
        )

        runCatching {
            cursor.use { cursor ->
                if (!cursor.moveToFirst()) return

                val origRawMembers = cursor.getString(cursor.getColumnIndex("memberlist"))
                if (origRawMembers.isNullOrEmpty()) return
                val origMembers = origRawMembers.split(';')

                // 群昵称（群备注）以 wxId -> displayName 的形式存于 roomdata protobuf 中，
                // displayname 列只是服务端截断后的预览（最多 4 个名字），无法可靠映射到成员。
                val origRoomData = cursor.getBlob(cursor.getColumnIndex("roomdata"))
                val origDisplayNames = parseRoomData(origRoomData)

                handleMemberLeave(
                    group, origMembers, origDisplayNames, newRawMembers, newMemberCount,
                    cursor.getInt(cursor.getColumnIndex("memberCount"))
                )
                handleMemberJoin(
                    group, origMembers, newRawMembers, newMemberCount,
                    cursor.getInt(cursor.getColumnIndex("memberCount"))
                )
                handleDisplayNameChange(group, origDisplayNames, newRoomData)
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    private fun handleMemberLeave(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newMemberCount: Int?,
        origMemberCount: Int
    ) {
        if (newRawMembers == null || newMemberCount == null) return
        if (origMemberCount == 0 || newMemberCount >= origMemberCount) return

        val newMembers = newRawMembers.split(';').toSet()
        val leavers = origMembers - newMembers

        leavers.forEach { wxId ->
            val displayName = (origDisplayNames[wxId] ?: "").ifEmpty { WeDatabaseApi.getDisplayName(wxId) }
            dispatchCard(group, wxId, displayName, isJoin = false)
        }
    }

    private fun handleMemberJoin(
        group: String,
        origMembers: List<String>,
        newRawMembers: String?,
        newMemberCount: Int?,
        origMemberCount: Int
    ) {
        if (newRawMembers == null || newMemberCount == null) return
        if (newMemberCount <= origMemberCount) return

        val newMembers = newRawMembers.split(';').toSet()
        val joiners = newMembers - origMembers

        joiners.forEach { wxId ->
            val displayName = WeDatabaseApi.getDisplayName(wxId)
            dispatchCard(group, wxId, displayName, isJoin = true)
        }
    }

    /**
     * 发进退群通知：只发 AppMsg 图文卡片（标题+昵称/ID+可点击成员头像）。
     * 发不出去就不发：头像拉不到或发送异常仅记日志，不回退图片卡/文本。
     */
    private fun dispatchCard(group: String, wxId: String, displayName: String, isJoin: Boolean) {
        val name = displayName.ifBlank { wxId }
        // 图文卡片要拉头像，放后台线程避免阻塞 DB 监听线程
        Thread {
            val sent = runCatching { GroupEventCard.sendEventAppMsg(group, wxId, name, isJoin) }
                .onFailure { WeLogger.e(TAG, "card appmsg failed group=$group wxid=$wxId", it) }
                .getOrDefault(false)
            WeLogger.i(TAG, "MGMO card appmsg group=$group wxid=$wxId isJoin=$isJoin sent=$sent")
        }.start()
    }

    private fun handleDisplayNameChange(group: String, origDisplayNames: Map<String, String>, newRoomData: ByteArray?) {
        if (newRoomData == null) return // 本次更新未改动 roomdata

        val newDisplayNames = parseRoomData(newRoomData)
        if (origDisplayNames.isEmpty() || newDisplayNames.isEmpty()) return

        newDisplayNames.forEach { (wxId, newName) ->
            val oldName = origDisplayNames[wxId] ?: return@forEach // 新成员，非昵称修改
            if (oldName == newName) return@forEach

            val displayName = WeDatabaseApi.getDisplayName(wxId)
            val oldShow = oldName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }
            val newShow = newName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }

            // 改名提醒：只发 AppMsg 改名卡；发不出去就不发（仅记日志）
            Thread {
                val sent = runCatching {
                    GroupEventCard.sendRenameAppMsg(group, wxId, displayName, oldShow, newShow)
                }.onFailure { WeLogger.e(TAG, "rename appmsg failed group=$group wxid=$wxId", it) }
                    .getOrDefault(false)
                WeLogger.i(TAG, "MGMO rename card group=$group wxid=$wxId sent=$sent")
            }.start()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    private const val TAG = "MonitorGroupMemberOperations"

    /** 「群管理」设置界面里的开关键 */
    private const val KEY_ENABLED = "glg_monitor_enabled"

    /** 迁移只跑一次的标记 */
    private const val KEY_MIGRATED = "glg_monitor_migrated"

    /** 旧独立功能的开关键（SwitchFeature 以 technicalId 存状态） */
    private const val LEGACY_SWITCH_KEY = "群成员行为监控"
}
