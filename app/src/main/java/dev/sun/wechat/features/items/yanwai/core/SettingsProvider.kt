package dev.sun.wechat.features.items.yanwai.core

import android.content.Context
import android.content.SharedPreferences

class SettingsProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean { context?.let(MoodLog::init); return true }
    override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, t: String?): android.database.Cursor? = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?): Int = 0
    companion object {
        @Synchronized fun save(context: Context, edit: SharedPreferences.Editor.() -> Unit): Boolean {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            return runCatching { prefs.edit().apply(edit).commit() }.getOrDefault(false)
        }
    }
}
