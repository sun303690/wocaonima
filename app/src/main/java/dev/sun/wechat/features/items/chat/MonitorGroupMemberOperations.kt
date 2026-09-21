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
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.net.models.protobuf.ChatRoomDataProto
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.reflection.BString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

object MonitorGroupMemberOperations : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "群成员行为监控"
    override val nameRes = R.string.feature_monitor_group_member_operations_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_monitor_group_member_operations_description

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        methodHandleSpanClick.hookBefore {
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

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname") ?: return
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
     * 发进退群通知：优先图文卡片（标题+字段+可点击成员头像），
     * 其次图片卡片，最后回退原文本系统消息。
     */
    private fun dispatchCard(group: String, wxId: String, displayName: String, isJoin: Boolean) {
        val name = displayName.ifBlank { wxId }
        // 图文卡片要拉头像，放后台线程避免阻塞 DB 监听线程
        Thread {
            val sent = runCatching { GroupEventCard.sendEventAppMsg(group, wxId, name, isJoin) }
                .onFailure { WeLogger.e(TAG, "card appmsg failed group=$group wxid=$wxId", it) }
                .getOrDefault(false)
            WeLogger.i(TAG, "MGMO card appmsg group=$group wxid=$wxId isJoin=$isJoin sent=$sent")
            if (sent) return@Thread
            dispatchImageCard(group, wxId, name, displayName, isJoin)
        }.start()
    }

    /** 图片卡片回退；渲染失败再回退文本系统消息。 */
    private fun dispatchImageCard(
        group: String,
        wxId: String,
        name: String,
        displayName: String,
        isJoin: Boolean,
    ) {
        val file = GroupEventCard.renderEvent(group, wxId, name, isJoin)
        if (file != null) {
            val submitted = runCatching { WeMessageApi.sendImage(group, file.absolutePath) }
                .onFailure { WeLogger.e(TAG, "send card image failed group=$group wxid=$wxId", it) }
                .getOrDefault(false)
            WeLogger.i(TAG, "MGMO card image group=$group wxid=$wxId isJoin=$isJoin submitted=$submitted")
            // 上传是异步的：等微信读走 PNG 再删，否则文件消失导致发送失败
            if (submitted) Thread.sleep(5_000L)
            runCatching { file.delete() }
            return
        }
        // 卡片渲染失败，回退文本系统消息
        val displayString = if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId
        val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
        val content = if (isJoin) {
            """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> ${localizedChatString(R.string.chat_group_member_joined)}"""
        } else {
            """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> ${localizedChatString(R.string.chat_group_member_left)}"""
        }
        WeMessageApi.createSimpleMsgInfoAndInsert(
            type = MessageType.SYSTEM.code,
            talker = group,
            content = content,
            currentTime = System.currentTimeMillis()
        )
    }

    private fun handleDisplayNameChange(group: String, origDisplayNames: Map<String, String>, newRoomData: ByteArray?) {
        if (newRoomData == null) return // 本次更新未改动 roomdata

        val newDisplayNames = parseRoomData(newRoomData)
        if (origDisplayNames.isEmpty() || newDisplayNames.isEmpty()) return

        newDisplayNames.forEach { (wxId, newName) ->
            val oldName = origDisplayNames[wxId] ?: return@forEach // 新成员，非昵称修改
            if (oldName == newName) return@forEach

            val displayName = WeDatabaseApi.getDisplayName(wxId)
            val displayString = if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId

            val oldShow = oldName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }
            val newShow = newName.ifEmpty { localizedChatString(R.string.chat_group_member_no_nickname) }

            val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
            val content = """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> ${localizedChatString(R.string.chat_group_member_nickname_changed, oldShow, newShow)}"""

            WeMessageApi.createSimpleMsgInfoAndInsert(
                type = MessageType.SYSTEM.code,
                talker = group,
                content = content,
                currentTime = System.currentTimeMillis()
            )
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
}
