package dev.sun.wechat.features.items.yanwai.core

import android.content.Context

object ModulePrefs {
    const val FILE_NAME = "wechatmood_config"
    const val KEY_EXPLORE = "explore_mode"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_BASE = "api_base"
    const val KEY_API_PROVIDER = "api_provider"
    const val KEY_API_MODEL = "api_model"

    @Volatile private var context: Context? = null
    @Volatile private var conversations: ConversationSwitches? = null
    private val manualAnalysis = ManualAnalysis()
    @Volatile var lastBridgeError: String? = null

    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        if (app.packageName == "com.tencent.mm" && conversations == null) {
            val local = app.getSharedPreferences("yanwai_conversations", Context.MODE_PRIVATE)
            conversations = ConversationSwitches(local.getStringSet("enabled_chats", emptySet()).orEmpty()) {
                local.edit().putStringSet("enabled_chats", it).commit()
            }
        }
        MoodLog.init(app)
    }

    fun read(key: String): String? = context?.getSharedPreferences(FILE_NAME, 0)?.getString(key, null)
    fun read(key: String, def: String): String = read(key) ?: def
    private fun prefs() = context?.getSharedPreferences(FILE_NAME, 0)

    val exploreMode: Boolean get() = read(KEY_EXPLORE, "false") == "true"
    var apiKey: String
        get() = read(KEY_API_KEY, "")
        set(v) { prefs()?.edit()?.putString(KEY_API_KEY, v)?.commit() }
    var apiBase: String
        get() = read(KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT)
        set(v) { prefs()?.edit()?.putString(KEY_API_BASE, v)?.commit() }
    var apiProvider: String
        get() = read(KEY_API_PROVIDER, JevProvider.TYPESAFE.id)
        set(v) { prefs()?.edit()?.putString(KEY_API_PROVIDER, v)?.commit() }
    var apiModel: String
        get() = read(KEY_API_MODEL, "")
        set(v) { prefs()?.edit()?.putString(KEY_API_MODEL, v)?.commit() }

    fun apiSettings(): ApiSettings = runCatching {
        ApiSettings.fromInput(apiBase, apiKey, apiProvider, apiModel)
    }.getOrDefault(ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, ""))

    fun isChatEnabled(talker: String?): Boolean = conversations?.isEnabled(talker) == true
    fun canAnalyze(talker: String?): Boolean = isChatEnabled(talker) && canAnalyze

    val canGenerateReply: Boolean get() = read("reply_consent", "false") == "true"
    val canAnalyze: Boolean get() = prefs()?.getBoolean("can_analyze", true) ?: true
    val bridgeAvailable: Boolean get() = true

    fun replySettings(): dev.sun.wechat.features.items.yanwai.reply.ReplySettings {
        val rs = dev.sun.wechat.features.items.yanwai.reply.ReplySettings
        return rs.fromInput(read(rs.KEY_ENDPOINT, ""), read(rs.KEY_API_KEY, ""), read(rs.KEY_MODEL, ""))
    }

    fun analysisInput(input: AnalysisInput): AnalysisInput = manualAnalysis.selectedInput(input) ?: input
    fun shouldDisplay(input: AnalysisInput): Boolean = manualAnalysis.allows(input, isChatEnabled(input.talker))
    fun canAnalyze(input: AnalysisInput): Boolean = shouldDisplay(input) && canAnalyze
    fun selectMessage(input: AnalysisInput): Boolean = manualAnalysis.select(input)
    fun setChatEnabled(talker: String?, value: Boolean): Boolean = runCatching {
        val saved = conversations?.setEnabled(talker, value) == true
        if (saved && !value && talker != null) manualAnalysis.clearConversation(talker)
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_SAVE_FAILED", it) }.getOrDefault(false)

    fun report(status: String) { MoodLog.i("REPORT $status") }
    fun requestReload(force: Boolean = false) {}
    fun reload(force: Boolean = false) {}

    fun analysisSettings(): RuntimeSettings {
        val api = apiSettings()
        val intent = IntentSettings(
            route = IntentRoute.resolve(read(IntentSettings.KEY_ROUTE)),
            llm = dev.sun.wechat.features.items.yanwai.reply.ReplySettings.fromInput(
                read("reply_endpoint", ""), read("reply_api_key", ""), read("reply_model", ""),
            ),
        )
        return RuntimeSettings(0L, exploreMode, api, "wechat-local", intent = intent)
    }
}

class RuntimeSettings(
    val revision: Long, val exploreMode: Boolean, val api: ApiSettings, val generation: String,
    val reply: dev.sun.wechat.features.items.yanwai.reply.ReplySettings = dev.sun.wechat.features.items.yanwai.reply.ReplySettings.empty(),
    val replyConsent: Boolean = false,
    val intent: IntentSettings = IntentSettings(),
    val canGenerateReply: Boolean = replyConsent,
) {
    val canAnalyze: Boolean get() = api.isConfigured
    fun sameAnalysis(other: RuntimeSettings): Boolean =
        generation == other.generation && api.endpoint == other.api.endpoint &&
            api.apiKey == other.api.apiKey && api.model == other.api.model && intent.sameAs(other.intent)
}
