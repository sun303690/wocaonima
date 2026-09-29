package dev.sun.wechat.features.items.yanwai.core

import android.content.Context
import dev.sun.wechat.data.KvStore

/**
 * 言外配置：WeKit 化适配，用 [KvStore] 存取配置，不再依赖言外的 ContentProvider/广播设置服务。
 * 微信进程直接读本地配置；设置由 WeKit 的 YanwaiFeature UI 写入 KvStore。
 */
object ModulePrefs {
    const val KEY_EXPLORE = "yanwai_explore_mode"
    const val KEY_API_KEY = "yanwai_api_key"
    const val KEY_API_BASE = "yanwai_api_base"
    const val KEY_API_PROVIDER = "yanwai_api_provider"
    const val KEY_API_MODEL = "yanwai_api_model"
    const val KEY_ENABLED = "yanwai_enabled"
    const val KEY_CAN_ANALYZE = "yanwai_can_analyze"
    const val KEY_INTENT_ENABLED = "yanwai_intent_enabled"
    const val KEY_INTENT_BASE = "yanwai_intent_base"
    const val KEY_INTENT_KEY = "yanwai_intent_key"
    const val KEY_INTENT_PROVIDER = "yanwai_intent_provider"
    const val KEY_INTENT_MODEL = "yanwai_intent_model"

    @Volatile private var context: Context? = null
    @Volatile private var conversations: ConversationSwitches? = null
    private val manualAnalysis = ManualAnalysis()

    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        if (app.packageName == "com.tencent.mm" && conversations == null) {
            val local = app.getSharedPreferences("yanwai_conversations", Context.MODE_PRIVATE)
            conversations = ConversationSwitches(local.getStringSet("enabled_chats", emptySet()).orEmpty()) {
                local.edit().putStringSet("enabled_chats", it).commit()
            }
        }
    }

    // ---- 配置直接从 KvStore 读 ----
    var enabled: Boolean
        get() = KvStore.getBoolOrFalse(KEY_ENABLED)
        set(value) { KvStore.putBool(KEY_ENABLED, value) }

    var canAnalyze: Boolean
        get() = KvStore.getBoolOrDef(KEY_CAN_ANALYZE, true)
        set(value) { KvStore.putBool(KEY_CAN_ANALYZE, value) }

    var exploreMode: Boolean
        get() = KvStore.getBoolOrFalse(KEY_EXPLORE)
        set(value) { KvStore.putBool(KEY_EXPLORE, value) }

    var apiKey: String
        get() = KvStore.getStringOrDef(KEY_API_KEY, "")
        set(value) { KvStore.putString(KEY_API_KEY, value) }

    var apiBase: String
        get() = KvStore.getStringOrDef(KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT)
        set(value) { KvStore.putString(KEY_API_BASE, value) }

    var apiProvider: String
        get() = KvStore.getStringOrDef(KEY_API_PROVIDER, JevProvider.TYPESAFE.id)
        set(value) { KvStore.putString(KEY_API_PROVIDER, value) }

    var apiModel: String
        get() = KvStore.getStringOrDef(KEY_API_MODEL, "")
        set(value) { KvStore.putString(KEY_API_MODEL, value) }

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
    }.onFailure { MoodLog.e("CHAT_SWITCH_SAVE_FAILED 本地会话开关保存失败", it) }.getOrDefault(false)

    fun report(status: String) {
        MoodLog.i("REPORT $status")
    }

    fun replySettings(): dev.sun.wechat.features.items.yanwai.reply.ReplySettings =
        dev.sun.wechat.features.items.yanwai.reply.ReplySettings.empty()

    val replyConsent: Boolean get() = false
    val bridgeAvailable: Boolean get() = true
    var lastBridgeError: String? = null
    fun requestReload(force: Boolean = false) {}
    fun reload(force: Boolean = false) {}

    /** 分析的完整配置快照：api + intent + generation。 */
    fun analysisSettings(): RuntimeSettings {
        val api = apiSettings()
        val intentEnabled = KvStore.getBoolOrFalse(KEY_INTENT_ENABLED)
        val intent = IntentSettings(
            route = if (intentEnabled) IntentRoute.LLM else IntentRoute.JEV,
            llm = runCatching {
                dev.sun.wechat.features.items.yanwai.reply.ReplySettings.fromInput(
                    KvStore.getStringOrDef(KEY_INTENT_BASE, api.endpoint),
                    KvStore.getStringOrDef(KEY_INTENT_KEY, api.apiKey),
                    KvStore.getStringOrDef(KEY_INTENT_MODEL, api.model),
                )
            }.getOrDefault(dev.sun.wechat.features.items.yanwai.reply.ReplySettings.empty()),
        )
        return RuntimeSettings(0L, exploreMode, api, "wekit", intent = intent)
    }
}

/** 复合分析配置（WeKit 化精简版）：承载分析所需的 api/intent 快照。 */
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
