package dev.sun.wechat.activity.scripting_center

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.Cloud_download
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Refresh
import dev.sun.wechat.R
import dev.sun.wechat.features.items.scripting_center.MarketRuntime
import dev.sun.wechat.features.items.scripting_center.ScriptCenter
import dev.sun.wechat.features.items.scripting_center.ScriptMarketDownloader
import dev.sun.wechat.features.items.scripting_java.JavaScriptingHook
import dev.sun.wechat.features.items.scripting_python.PythonScriptingFeature
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.agent.settings.AgentConfirmDialog
import dev.sun.wechat.ui.agent.settings.AgentSettingsScaffold
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.theme.EngineModuleTheme
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.fs.createDirsSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.path.div

@Keep
class ScriptCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                EngineModuleTheme { ScriptCenterScreen(this@ScriptCenterActivity) }
            }
        }
    }
}

/** 本地已安装插件的一行摘要（中心聚合展示用，与引擎内部数据结构解耦）。 */
private data class LocalPluginRow(val id: String, val runtime: MarketRuntime, val dir: File)

@Composable
private fun ScriptCenterScreen(activity: ComponentActivity) {
    var masterOn by remember { mutableStateOf(ScriptCenter.isScriptingAllowed()) }
    var javaOn by remember { mutableStateOf(JavaScriptingHook.isEnabled) }
    var pythonOn by remember { mutableStateOf(PythonScriptingFeature.isEnabled) }
    var localRows by remember { mutableStateOf<List<LocalPluginRow>>(emptyList()) }
    var marketEntries by remember { mutableStateOf(ScriptCenter.marketEntriesSnapshot()) }
    var marketLoading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LocalPluginRow?>(null) }
    val scope = rememberCoroutineScope()

    fun scriptsJavaDir() = (KnownPaths.moduleRoot / "scripts_java").createDirsSafe().toFile()
    fun scriptsPythonDir() = (KnownPaths.moduleRoot / "scripts_python").createDirsSafe().toFile()

    fun loadLocalList() {
        scope.launch(Dispatchers.IO) {
            val rows = buildList {
                scriptsJavaDir().listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name }
                    ?.forEach { add(LocalPluginRow(it.name, MarketRuntime.JAVA, it)) }
                scriptsPythonDir().listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name }
                    ?.forEach { add(LocalPluginRow(it.name, MarketRuntime.PYTHON, it)) }
            }
            withContext(Dispatchers.Main) { localRows = rows }
        }
    }

    remember { loadLocalList() }

    AgentSettingsScaffold(stringResource(R.string.script_center_title), onBack = { activity.finish() }) {
        item {
            MiuixSegmentedColumn(title = stringResource(R.string.script_center_master_section)) {
                item {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.script_center_master_toggle),
                        summary = stringResource(R.string.script_center_master_desc),
                        checked = masterOn,
                        onCheckedChange = { newState ->
                            masterOn = newState
                            if (newState) ScriptCenter.enable() else ScriptCenter.disable()
                            javaOn = JavaScriptingHook.isEnabled
                            pythonOn = PythonScriptingFeature.isEnabled
                            showToast(
                                activity,
                                activity.getString(
                                    if (newState) R.string.script_center_enabled_toast
                                    else R.string.script_center_disabled_toast,
                                ),
                            )
                        },
                    )
                }
            }
        }
        item {
            MiuixSegmentedColumn(title = stringResource(R.string.script_center_engines_section)) {
                item {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.script_center_java_engine),
                        summary = stringResource(R.string.script_center_java_engine_desc),
                        checked = javaOn,
                        enabled = masterOn,
                        onCheckedChange = { newState ->
                            javaOn = newState
                            JavaScriptingHook.applyToggle(newState)
                        },
                    )
                }
                item {
                    MiuixSwitchWidget(
                        title = stringResource(R.string.script_center_python_engine),
                        summary = stringResource(R.string.script_center_python_engine_desc),
                        checked = pythonOn,
                        enabled = masterOn,
                        onCheckedChange = { newState ->
                            pythonOn = newState
                            PythonScriptingFeature.applyToggle(newState)
                        },
                    )
                }
                item {
                    MiuixBaseWidget(
                        title = stringResource(R.string.script_center_python_manage),
                        summary = stringResource(R.string.script_center_python_manage_desc),
                        enabled = masterOn,
                        onClick = {
                            runCatching {
                                activity.startActivity(
                                    Intent(
                                        activity,
                                        dev.sun.wechat.activity.scripting_python.PythonScriptsSettingsActivity::class.java,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
        item {
            MiuixSegmentedColumn(title = stringResource(R.string.script_center_local_section)) {
                if (localRows.isEmpty()) {
                    item {
                        MiuixBaseWidget(
                            title = stringResource(R.string.script_center_local_empty),
                            enabled = false,
                        )
                    }
                }
                for (row in localRows) {
                    item(key = "${row.runtime}-${row.id}") {
                        MiuixBaseWidget(
                            title = row.id,
                            summary = activity.getString(
                                if (row.runtime == MarketRuntime.PYTHON) {
                                    R.string.script_center_runtime_python
                                } else {
                                    R.string.script_center_runtime_java
                                },
                            ),
                            icon = MaterialSymbols.Outlined.Folder,
                            onTrailingClick = { pendingDelete = row },
                        )
                    }
                }
            }
        }
        item {
            MiuixSegmentedColumn(title = stringResource(R.string.script_center_market_section)) {
                item {
                    MiuixBaseWidget(
                        title = stringResource(R.string.script_center_market_refresh),
                        summary = ScriptCenter.marketStatus,
                        icon = MaterialSymbols.Outlined.Refresh,
                        onClick = {
                            if (!marketLoading) {
                                marketLoading = true
                                scope.launch {
                                    val entries = ScriptCenter.refreshMarketSuspend()
                                    marketEntries = entries
                                    marketLoading = false
                                }
                            }
                        },
                        foreContent = {
                            if (marketLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            }
                        },
                    )
                }
                for (entry in marketEntries) {
                    item(key = "market-${entry.runtime}-${entry.id}") {
                        MiuixBaseWidget(
                            title = entry.name,
                            summary = listOfNotNull(
                                entry.id,
                                entry.version.takeIf { it.isNotBlank() },
                                entry.author.takeIf { it.isNotBlank() },
                                entry.description.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            icon = MaterialSymbols.Outlined.Cloud_download,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val result = ScriptMarketDownloader.downloadAndInstall(
                                        entry,
                                        scriptsJavaDir(),
                                        scriptsPythonDir(),
                                    )
                                    withContext(Dispatchers.Main) {
                                        showToast(activity, result)
                                        loadLocalList()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    AgentConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.script_center_uninstall_title),
        message = stringResource(R.string.script_center_uninstall_text, pendingDelete?.id ?: ""),
        confirmLabel = stringResource(R.string.script_center_uninstall_confirm),
        dismissLabel = stringResource(R.string.script_center_cancel),
        destructive = true,
        onConfirm = {
            val row = pendingDelete
            pendingDelete = null
            if (row != null) {
                scope.launch(Dispatchers.IO) {
                    val ok = ScriptMarketDownloader.uninstall(row.dir)
                    withContext(Dispatchers.Main) {
                        showToast(
                            activity,
                            activity.getString(
                                if (ok) R.string.script_center_uninstalled_toast
                                else R.string.script_center_uninstall_failed_toast,
                            ),
                        )
                        loadLocalList()
                    }
                }
            }
        },
        onDismiss = { pendingDelete = null },
    )
}