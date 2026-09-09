package dev.sun.wechat.features.items.contacts.maskwechat

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import org.json.JSONArray

/**
 * MaskWechat 配置读取器（微信进程内直读其配置）。
 *
 * MaskWechat(ConfigUtil) 把名单存在微信进程 shared_prefs 的 `mask_wechat_config`
 * (KEY_MASK_LIST = JSON 数组, 每项含 maskId)。这里做轻量缓存 + 变更监听，
 * 供 WeKit 侧需要名单的 SQL 改写(ConversationGrouping 等)复用，避免双引擎各存一份。
 */
object MaskWechatConfig {

    private const val TAG = "MaskWechatConfig"
    private const val PREF_NAME = "mask_wechat_config"
    private const val KEY_MASK_LIST = "maskList"

    @Volatile
    private var cachedIds: Set<String>? = null

    private val changeListener = OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_MASK_LIST) {
            cachedIds = null
        }
    }

    /** 当前 MaskWechat 名单里的 wxid 集合（maskId 字段）。 */
    @Synchronized
    fun getMaskIds(): Set<String> {
        cachedIds?.let { return it }
        val ids = try {
            val sp = hostPrefs()
            sp.registerOnSharedPreferenceChangeListener(changeListener)
            val jsonText = sp.getString(KEY_MASK_LIST, "[]").orEmpty().ifBlank { "[]" }
            val arr = JSONArray(jsonText)
            buildSet {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("maskId").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (t: Throwable) {
            WeLogger.w(TAG, "read maskList failed: $t")
            emptySet()
        }
        cachedIds = ids
        return ids
    }

    /** 把 wxid 加入 MaskWechat 名单（保留既有字段），返回是否发生变更。 */
    @Synchronized
    fun addMaskId(wxid: String, tagName: String? = null): Boolean {
        if (wxid.isBlank()) return false
        val current = readRawArray()
        if (current.any { it.optString("maskId") == wxid }) return false
        val entry = org.json.JSONObject()
        entry.put("maskId", wxid)
        entry.put("tagName", tagName ?: wxid)
        entry.put("mapId", "")
        entry.put("tipMode", 0)
        current.put(entry)
        writeRawArray(current)
        cachedIds = null
        return true
    }

    /** 把 wxid 从 MaskWechat 名单移除，返回是否发生变更。 */
    @Synchronized
    fun removeMaskId(wxid: String): Boolean {
        val current = readRawArray()
        val kept = org.json.JSONArray()
        var changed = false
        for (i in 0 until current.length()) {
            val obj = current.optJSONObject(i) ?: continue
            if (obj.optString("maskId") == wxid) changed = true else kept.put(obj)
        }
        if (changed) {
            writeRawArray(kept)
            cachedIds = null
        }
        return changed
    }

    private fun readRawArray(): org.json.JSONArray = try {
        JSONArray(hostPrefs().getString(KEY_MASK_LIST, "[]").orEmpty().ifBlank { "[]" })
    } catch (t: Throwable) {
        WeLogger.w(TAG, "read raw maskList failed: $t")
        org.json.JSONArray()
    }

    private fun writeRawArray(arr: org.json.JSONArray) {
        hostPrefs().edit().putString(KEY_MASK_LIST, arr.toString()).apply()
    }

    private fun hostPrefs(): SharedPreferences =
        HostInfo.application.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
}
