package dev.sun.wechat.features.items.yanwai.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储：WeChat 进程与言外 UI 共用 `wechatmood_config` SharedPreferences。
 * 不再依赖言外的 ContentProvider/广播设置服务；单进程直接读写同一文件。
 */
class SettingsProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean { context?.let(MoodLog::init); return true }
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val URI_AUTHORITY = "dev.sun.wechat.features.items.yanwai.settings"
        val URI: android.net.Uri = android.net.Uri.parse("content://$URI_AUTHORITY/config")

        @Synchronized fun save(context: Context, edit: SharedPreferences.Editor.() -> Unit): Boolean {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            return runCatching {
                prefs.edit().apply(edit).commit()
            }.onFailure { MoodLog.e("SETTINGS_DISK_SAVE_FAILED", it) }.getOrDefault(false)
        }
    }
}
