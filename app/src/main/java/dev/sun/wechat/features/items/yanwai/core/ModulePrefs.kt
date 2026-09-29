package dev.sun.wechat.features.items.yanwai.core

import android.content.Context

/**
 * 言外配置：WeChat 进程从 `wechatmood_config` SharedPreferences 读取，
 * 与言外 UI（IntentSettingsUi/ReplySettingsUi）写入同一个文件，单进程互通。
 */
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

    /** 从 wechatmood_config 读取字符串配置（与言外 UI 写入一致）。 */
    fun read(key: String): String? = runCatching {
        context?.getSharedPreferences(FILE_NAME, 0)?.getString(key, null)
    }.getOrNull()

    fun read(key: String, def: String): String = read(key) ?: def

    private fun prefs(): android.content.SharedPreferences? =
        context?.getSharedPreferences(FILE_NAME, 0)

    val exploreMode: Boolean get() = read(KEY_EXPLORE, "false") == "true"

    var apiKey: String
        get() = read(KEY_API_KEY, "")
        set(value) { prefs()?.edit()?.putString(KEY_API_KEY, value)?.commit() }

    var apiBase: String
        get() = read(KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT)
        set(value) { prefs()?.edit()?.putString(KEY_API_BASE, value)?.commit() }

    var apiProvider: String
        get() = read(KEY_API_PROVIDER, JevProvider.TYPESAFE.id)
        set(value) { prefs()?.edit()?.putString(KEY_API_PROVIDER, value)?.commit() }

    var apiModel: String
        get() = read(KEY_API_MODEL, "")
        set(value) { prefs()?.edit()?.putString(KEY_API_MODEL, value)?.commit() }

    fun apiSettings(): ApiSettings = runCatching {
        ApiSettings.fromInput(apiBase, apiKey, apiProvider, apiModel)
    }.getOrDefault(ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, ""))

    fun isChatEnabled(talker: String?): Boolean = conversations?.isEnabled(talker) == true
    fun canAnalyze(talker: String?): Boolean = isChatEnabled(talker) && canAnalyze
    fun analysisInput(input: AnalysisInput): AnalysisInput = manualAnalysis.selectedInput(input) ?: input
    fun shouldDisplay(input: AnalysisInput): Boolean = manualAnalysis.allows(input, isChatEnabled(input.talker))
    fun canAnalyze(input: AnalysisInput): Boolean = shouldDisplay(input) && canAnalyze
    fun selectMessage(input: AnalysisInput): Boolean = manualAnalysis.select(input)
    fun setChatEnabled(talker: String?, value: Boolean): Boolean = runCatching {
        val saved = conversations?.setEnabled(talker, value) == true
        if (saved && !value && talker != null) manualAnalysis.clearConversation(talker)
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_SAVE_FAILED", it) }.getOrDefault(false)

    val canAnalyze: Boolean get() = prefs()?.getBoolean(KEY_CAN_ANALYZE, true) ?: true
    private const val KEY_CAN_ANALYZE = "can_analyze"

    fun replySettings(): dev.sun.wechat.features.items.yanwai.reply.ReplySettings {
        val rs = dev.sun.wechat.features.items.yanwai.reply.ReplySettings
        return rs.fromInput(read(rs.KEY_ENDPOINT, ""), read(rs.KEY_API_KEY, ""), read(rs.KEY_MODEL, ""))
    }

    val replyConsent: Boolean get() = read(dev.sun.wechat.features.items.yanwai.reply.ReplySettings.KEY_CONSENT, "false") == "true"
    val bridgeAvailable: Boolean get() = true

    fun requestReload(force: Boolean = false) {}
    fun reload(force: Boolean = false) {}

    fun report(status: String) { MoodLog.i("REPORT $status") }

    /** 分析的完整配置快照：api + intent。 */
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

/** 复合分析配置（WeChat 化精简版）：承载分析所需的 api/intent 快照。 */
class RuntimeSettings(
    val revision: Long,
    val exploreMode: Boolean,
    val api: ApiSettings,
    val generation: String,
    val reply: dev.sun.wechat.features.items.yanwai.reply.ReplySettings =
        dev.sun.wechat.features.items.yanwai.reply.ReplySettings.empty(),
    val replyConsent: Boolean = false,
    val intent: IntentSettings = IntentSettings(),
) {
    val canAnalyze: Boolean get() = api.isConfigured
    fun sameAnalysis(other: RuntimeSettings): Boolean =
        generation == other.generation && api.endpoint == other.api.endpoint &&
            api.apiKey == other.api.apiKey && api.model == other.api.model && intent.sameAs(other.intent)
}
