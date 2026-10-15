package dev.sun.wechat.features.items.scripting_center

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.activity.scripting_center.ScriptCenterActivity
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.scripting_java.JavaScriptingHook
import dev.sun.wechat.features.items.scripting_python.PythonScriptingFeature
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 脚本插件中心 = Java/Python 两套脚本子系统的**总开关** + 管理聚合入口 + 在线插件市场。
 *
 * - 总开关（本功能开关，缺省开启）：关闭时立即停用两个引擎（卸载 hook、释放解释器、停用
 *   Python 插件），但**保留各自的用户开关偏好**与本地插件文件；重新打开按原状态恢复。
 *   两个引擎启动前经 [isScriptingAllowed] 闸门校验，任何绕过中心 UI 的启动路径都会被拦下。
 * - 聚合入口：点击打开 [ScriptCenterActivity]，集中展示引擎开关、本地管理入口与市场。
 * - 在线插件市场：从 [MARKET_URL] 拉取 market.json 索引，下载 zip 安装包，
 *   由 [ScriptMarketDownloader] 完成校验与落位。
 */
object ScriptCenter : ClickableFeature() {
    override val technicalId = "脚本插件中心"
    override val nameRes = R.string.feature_script_center_name
    override val categoryIds =
        listOf(FeatureCategoryIds.SCRIPTING_JAVA, FeatureCategoryIds.SCRIPTING_PYTHON)
    override val descriptionRes = R.string.feature_script_center_description

    private const val TAG = "ScriptCenter"

    /** 在线市场索引源；404/缺失时市场展示为空，不影响本地功能。 */
    private const val MARKET_URL =
        "https://raw.githubusercontent.com/sun303690/wocaonima/main/scripts-market/market.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 在线市场条目缓存（market.json 的 entries），空 = 未拉取或拉取失败。 */
    private val marketEntries = CopyOnWriteArrayList<MarketEntry>()

    /** 市场状态摘要（诊断展示）。 */
    @Volatile
    var marketStatus: String = "未拉取"
        private set

    override val defaultEnabled: Boolean = true

    override fun onEnable() {
        KvStore.putBool(KEY_MASTER_ENABLED, true)
        // 总开关重新打开：按存储偏好恢复两个引擎（enable 幂等，已活跃时不重复）。
        // 直接读 KvStore 而非引擎内存态：启动早期引擎偏好可能尚未 loadPersistedState。
        if (KvStore.getBoolOrDef(JavaScriptingHook.technicalId, false)) JavaScriptingHook.enable()
        if (KvStore.getBoolOrDef(PythonScriptingFeature.technicalId, false)) {
            PythonScriptingFeature.enable()
        }
        WeLogger.i(TAG, "script center enabled")
    }

    override fun onDisable() {
        KvStore.putBool(KEY_MASTER_ENABLED, false)
        // 总开关关闭：硬停两个引擎（卸载 hook、释放解释器、停用插件）。
        // 引擎各自的 _isEnabled 偏好保持不动，重开总开关时按原状态恢复。
        runCatching { JavaScriptingHook.disable() }
            .onFailure { WeLogger.e(TAG, "stop java engine failed", it) }
        runCatching { PythonScriptingFeature.disable() }
            .onFailure { WeLogger.e(TAG, "stop python engine failed", it) }
        WeLogger.i(TAG, "script center disabled: engines stopped")
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, ScriptCenterActivity::class.java))
    }

    /** 子系统是否被总开关放行（两个引擎启动前调用；缺省开启，保持未接触总开关的旧行为）。 */
    fun isScriptingAllowed(): Boolean = KvStore.getBoolOrDef(KEY_MASTER_ENABLED, true)

    // --- 在线插件市场 ---

    fun marketEntriesSnapshot(): List<MarketEntry> = marketEntries.toList()

    /** 拉取在线市场索引（挂起，IO 线程）。返回最新条目；失败返回空列表并更新 [marketStatus]。 */
    suspend fun refreshMarketSuspend(): List<MarketEntry> = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            val text = httpGet(MARKET_URL)
                ?: return@withContext emptyList<MarketEntry>().also { marketStatus = "拉取失败（源不可达）" }
            parseMarket(text)
        }.getOrElse {
            WeLogger.w(TAG, "market parse failed: ${it.message}")
            marketStatus = "market.json 解析失败"
            return@withContext emptyList()
        }
        marketEntries.clear()
        marketEntries.addAll(parsed)
        marketStatus = "已加载 ${parsed.size} 个插件"
        parsed
    }

    private fun parseMarket(text: String): List<MarketEntry> {
        val arr = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<MarketEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            val download = o.optString("download", "").trim()
            if (id.isEmpty() || download.isEmpty()) continue
            out.add(
                MarketEntry(
                    id = id,
                    name = o.optString("name", id),
                    version = o.optString("version", ""),
                    author = o.optString("author", ""),
                    description = o.optString("description", ""),
                    runtime = when (o.optString("runtime", "java")) {
                        "python" -> MarketRuntime.PYTHON
                        else -> MarketRuntime.JAVA
                    },
                    download = download,
                    sha256 = o.optString("sha256", "").trim(),
                ),
            )
        }
        return out
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            input = conn.inputStream
            input.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            WeLogger.w(TAG, "market fetch failed: ${t.message}")
            null
        } finally {
            runCatching { input?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /** 总开关的存储键（与 [isScriptingAllowed] 配套；本功能开关自身走 technicalId 键）。 */
    const val KEY_MASTER_ENABLED = "script_center_enabled"
}

/** 在线市场的一个插件条目。 */
data class MarketEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val runtime: MarketRuntime,
    val download: String,
    val sha256: String,
)

/** 插件运行时类型。 */
enum class MarketRuntime { JAVA, PYTHON }
