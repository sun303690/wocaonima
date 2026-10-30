package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 「帮我回」的本地持久化（对应微话助手 ReplyIdentityStore + ReplyRoleStore，改用 KvStore）。
 *
 * - 每个联系人的身份与长期背景按 talker 分开保存，不会串到别的会话；
 * - 角色库是用户自己整理的「身份 + 背景」模板，可保存 / 套用 / 删除。
 * 这里全部是本地配置，不上传。
 */
object ReplyStorage {

    private const val TAG = "ReplyStorage"
    private const val MAX_CUSTOM = 40
    private const val MAX_BACKGROUND = 2000
    private const val MAX_ROLES = 100

    private fun identityKey(talker: String) = "mood_reply_identity_$talker"
    private const val ROLES_KEY = "mood_reply_roles"

    data class ContactIdentity(
        val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED,
        val custom: String = "",
        val background: String = "",
    )

    data class Role(
        val id: String,
        val name: String,
        val relationship: ReplyRelationship,
        val custom: String,
        val background: String,
    )

    fun load(talker: String): ContactIdentity {
        val raw = KvStore.getString(identityKey(talker)) ?: return ContactIdentity()
        return runCatching {
            val json = JSONObject(raw)
            val relationship = ReplyRelationship.entries.firstOrNull { it.id == json.optString("relationship") }
                ?: ReplyRelationship.UNSPECIFIED
            ContactIdentity(
                relationship = relationship,
                custom = json.optString("custom").take(MAX_CUSTOM),
                background = json.optString("background").take(MAX_BACKGROUND),
            )
        }.getOrElse {
            WeLogger.w(TAG, "bad stored identity, reset")
            ContactIdentity()
        }
    }

    fun save(talker: String, identity: ContactIdentity) {
        if (talker.isBlank()) return
        val json = JSONObject()
            .put("relationship", identity.relationship.id)
            .put("custom", identity.custom.take(MAX_CUSTOM))
            .put("background", identity.background.take(MAX_BACKGROUND))
        KvStore.putString(identityKey(talker), json.toString())
    }

    fun roles(): List<Role> {
        val raw = KvStore.getString(ROLES_KEY) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                Role(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    relationship = ReplyRelationship.entries.firstOrNull { it.id == json.optString("relationship") }
                        ?: ReplyRelationship.OTHER,
                    custom = json.optString("custom").take(MAX_CUSTOM),
                    background = json.optString("background").take(MAX_BACKGROUND),
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrElse {
            WeLogger.w(TAG, "bad stored roles, reset")
            emptyList()
        }
    }

    fun saveRole(role: Role): List<Role> {
        val list = roles().toMutableList()
        val index = list.indexOfFirst { it.id == role.id }
        if (index >= 0) list[index] = role else if (list.size < MAX_ROLES) list.add(role)
        persistRoles(list)
        return list
    }

    fun deleteRole(id: String): List<Role> {
        val list = roles().filterNot { it.id == id }
        persistRoles(list)
        return list
    }

    fun newRole(name: String, relationship: ReplyRelationship, custom: String, background: String): Role =
        Role(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(MAX_CUSTOM).ifBlank { relationship.displayLabel(custom) },
            relationship = relationship,
            custom = custom.trim().take(MAX_CUSTOM),
            background = background.take(MAX_BACKGROUND),
        )

    private fun persistRoles(list: List<Role>) {
        val array = JSONArray()
        list.forEach { role ->
            array.put(
                JSONObject()
                    .put("id", role.id)
                    .put("name", role.name)
                    .put("relationship", role.relationship.id)
                    .put("custom", role.custom)
                    .put("background", role.background)
            )
        }
        KvStore.putString(ROLES_KEY, array.toString())
    }
}
