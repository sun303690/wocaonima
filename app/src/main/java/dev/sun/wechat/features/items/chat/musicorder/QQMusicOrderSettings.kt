package dev.sun.wechat.features.items.chat.musicorder

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.data.KvStore.prefOption
import org.json.JSONArray

/**
 * QQ点歌完整设置（对照 Hchat musicorder 配置，全部保留）。
 */
object QQMusicOrderSettings {

    private const val TAG = "QQMusicOrderSettings"
    private const val DEFAULT_ENABLE = false
    private const val DEFAULT_INTERCEPT_OWN_COMMAND = false
    private const val DEFAULT_SEND_AS_CARD = true
    private const val DEFAULT_SEND_AS_VOICE = false
    private const val DEFAULT_CUSTOM_SINGER = false
    private const val DEFAULT_SINGER = ""
    private const val DEFAULT_APP_ID = "wx485a97c844086dc9"
    private const val DEFAULT_TRIGGERS = "点歌"
    private const val DEFAULT_REPLACE_COVER_WITH_AVATAR = false
    private const val DEFAULT_REPLACE_SINGER_WITH_NICKNAME = false

    private val KEY_ENABLE = "qq_music_order_enable"
    private val KEY_INTERCEPT_OWN_COMMAND = "qq_music_order_intercept_own_command"
    private val KEY_SEND_AS_CARD = "qq_music_order_send_as_card"
    private val KEY_SEND_AS_VOICE = "qq_music_order_send_as_voice"
    private val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
    private val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
    private val KEY_APP_ID = "qq_music_order_app_id"
    private val KEY_TRIGGERS = "qq_music_order_triggers"
    private val KEY_REPLACE_COVER_WITH_AVATAR = "qq_music_order_replace_cover_with_avatar"
    private val KEY_REPLACE_SINGER_WITH_NICKNAME = "qq_music_order_replace_singer_with_nickname"
    private val KEY_ALLOWED_TALKERS = "qq_music_order_allowed_talkers"

    private var _enable by prefOption(KEY_ENABLE, DEFAULT_ENABLE)
    private var _interceptOwnCommand by prefOption(KEY_INTERCEPT_OWN_COMMAND, DEFAULT_INTERCEPT_OWN_COMMAND)
    private var _sendAsCard by prefOption(KEY_SEND_AS_CARD, DEFAULT_SEND_AS_CARD)
    private var _sendAsVoice by prefOption(KEY_SEND_AS_VOICE, DEFAULT_SEND_AS_VOICE)
    private var _customSingerEnabled by prefOption(KEY_CUSTOM_SINGER, DEFAULT_CUSTOM_SINGER)
    private var _defaultSinger by prefOption(KEY_DEFAULT_SINGER, DEFAULT_SINGER)
    private var _appId by prefOption(KEY_APP_ID, DEFAULT_APP_ID)
    private var _triggers by prefOption(KEY_TRIGGERS, DEFAULT_TRIGGERS)
    private var _replaceCoverWithAvatar by prefOption(KEY_REPLACE_COVER_WITH_AVATAR, DEFAULT_REPLACE_COVER_WITH_AVATAR)
    private var _replaceSingerWithNickname by prefOption(KEY_REPLACE_SINGER_WITH_NICKNAME, DEFAULT_REPLACE_SINGER_WITH_NICKNAME)

    fun isEnabled(): Boolean = _enable
    fun interceptOwnCommand(): Boolean = _interceptOwnCommand
    fun sendAsCard(): Boolean = _sendAsCard
    fun sendAsVoice(): Boolean = _sendAsVoice
    fun customSingerEnabled(): Boolean = _customSingerEnabled
    fun defaultSinger(): String = _defaultSinger.trim()
    fun appId(): String = _appId.trim().ifBlank { DEFAULT_APP_ID }
    fun replaceCoverWithAvatar(): Boolean = _replaceCoverWithAvatar
    fun replaceSingerWithNickname(): Boolean = _replaceSingerWithNickname

    fun setEnabled(v: Boolean) { _enable = v }
    fun setInterceptOwnCommand(v: Boolean) { _interceptOwnCommand = v }
    fun setSendAsCard(v: Boolean) { _sendAsCard = v }
    fun setSendAsVoice(v: Boolean) { _sendAsVoice = v }
    fun setCustomSingerEnabled(v: Boolean) { _customSingerEnabled = v }
    fun setDefaultSinger(v: String) { _defaultSinger = v }
    fun setAppId(v: String) { _appId = v }
    fun setReplaceCoverWithAvatar(v: Boolean) { _replaceCoverWithAvatar = v }
    fun setReplaceSingerWithNickname(v: Boolean) { _replaceSingerWithNickname = v }

    fun setTriggers(v: String) { _triggers = v }

    fun triggers(): List<String> = _triggers.split(',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { listOf(DEFAULT_TRIGGERS) }

    fun allowedTalkers(): Set<String> = parseStringSet(
        KvStore.getString(KEY_ALLOWED_TALKERS) ?: ""
    )

    fun saveAllowedTalkers(values: Set<String>) {
        KvStore.putString(KEY_ALLOWED_TALKERS, encodeStringSet(values))
    }

    private fun parseStringSet(value: String): Set<String> {
        if (value.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(value)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun encodeStringSet(values: Set<String>): String {
        return JSONArray().apply {
            values.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach(::put)
        }.toString()
    }
}
