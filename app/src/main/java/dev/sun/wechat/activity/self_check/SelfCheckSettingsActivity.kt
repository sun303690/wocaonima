package dev.sun.wechat.activity.self_check

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.Refresh
import dev.sun.wechat.R
import dev.sun.wechat.features.items.self_check.ModuleSelfCheck
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.activity.settings.M3ListScaffold
import dev.sun.wechat.ui.agent.settings.AgentConfirmDialog
import dev.sun.wechat.ui.agent.settings.AgentListActionButton
import dev.sun.wechat.ui.content.m3.BaseItemContainer
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.ExpressiveBackButton
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.utils.theme.ModuleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块自检页。入口：设置页“更新”分组的“模块自检”行。
 *
 * 打开时自动跑一轮全量自检（[ModuleSelfCheck.healthSteps]），逐项展示
 * 分组结果；支持手动重新自检、「一键修复并自检」（[ModuleSelfCheck.repairSteps]）、
 * 复制纯文本报告。点任意一项看详情，可修复的项在详情里带动作按钮。
 *
 * 命名以 SettingsActivity 结尾，使 ActivityProxy 走透明启动代理。
 */
@Keep
class SelfCheckSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    SelfCheckRoot(onFinish = ::finish)
                }
            }
        }
    }
}

@Composable
private fun SelfCheckRoot(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ModuleSelfCheck.HealthItem>>(emptyList()) }
    var lastScan by remember { mutableStateOf<String?>(null) }
    var copyDone by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ModuleSelfCheck.HealthItem?>(null) }

    // run(rerun) 跑一轮步骤；repair=true 时先跑修复步骤再全量自检刷新结果
    suspend fun run(repair: Boolean) {
        running = true
        copyDone = false
        try {
            val steps =
                if (repair) ModuleSelfCheck.repairSteps(context) + ModuleSelfCheck.healthSteps(context)
                else ModuleSelfCheck.healthSteps(context)
            val out = mutableListOf<ModuleSelfCheck.HealthItem>()
            steps.forEachIndexed { index, step ->
                progressText = "(${index + 1}/${steps.size}) ${step.title}"
                yield() // 让进度先上屏
                out += runCatching { step.run() }.getOrElse { emptyList() }
            }
            items = out
            lastScan =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        } finally {
            running = false
            progressText = ""
        }
    }

    LaunchedEffect(Unit) { run(repair = false) }

    M3ListScaffold(
        title = stringResource(R.string.self_check_screen_title),
        navigationIcon = { ExpressiveBackButton(onClick = onFinish) },
    ) {
        item(key = "summary") {
            SummaryCard(items, lastScan, running, progressText, copyDone)
        }
        item(key = "actions") {
            SegmentedColumn {
                item(key = "rescan") {
                    AgentListActionButton(
                        label = stringResource(R.string.self_check_action_rescan),
                        icon = MaterialSymbols.Outlined.Refresh,
                        loading = running,
                        enabled = !running,
                        onClick = { scope.launch { run(repair = false) } },
                    )
                }
                item(key = "repair") {
                    AgentListActionButton(
                        label = stringResource(R.string.self_check_action_repair),
                        icon = MaterialSymbols.Outlined.Build_circle,
                        loading = running,
                        enabled = !running,
                        onClick = { scope.launch { run(repair = true) } },
                    )
                }
                item(key = "copy") {
                    AgentListActionButton(
                        label = stringResource(R.string.self_check_action_copy),
                        icon = MaterialSymbols.Outlined.Content_copy,
                        enabled = items.isNotEmpty() && !running,
                        onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching { ModuleSelfCheck.buildReportText(context) }
                                        .getOrElse { "报告生成失败：${it.message}" }
                                }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("self_check_report", text))
                                copyDone = true
                            }
                        },
                    )
                }
            }
        }
        for ((section, list) in items.groupBy { it.section }) {
            item(key = "sec_$section") {
                SegmentedColumn(title = section) {
                    for (h in list) {
                        item(key = h.label) { HealthRow(h) { detail = h } }
                    }
                }
            }
        }
        if (items.isEmpty() && !running) {
            item(key = "empty") {
                BaseItemContainer {
                    Text(
                        text = stringResource(R.string.self_check_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    detail?.let { h ->
        AgentConfirmDialog(
            show = true,
            title = h.label,
            message = stringResource(R.string.self_check_detail_status, h.value) + "\n\n" + h.detail,
            confirmLabel = h.actionLabel ?: stringResource(R.string.self_check_ok),
            dismissLabel = if (h.actionLabel != null) stringResource(R.string.self_check_close)
            else stringResource(R.string.self_check_ok),
            onConfirm = {
                val action = h.action
                detail = null
                if (action != null) {
                    scope.launch {
                        runCatching { action.invoke() }
                        run(repair = false) // 动作执行后刷新结果
                    }
                }
            },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun SummaryCard(
    items: List<ModuleSelfCheck.HealthItem>,
    lastScan: String?,
    running: Boolean,
    progressText: String,
    copyDone: Boolean,
) {
    BaseItemContainer {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.self_check_summary_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.self_check_summary_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val ok = items.count { it.ok }
                Text(
                    text = stringResource(R.string.self_check_summary_counts, items.size, ok, items.size - ok),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            lastScan?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.self_check_summary_last, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (copyDone) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.self_check_copy_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(h: ModuleSelfCheck.HealthItem, onShowDetail: () -> Unit) {
    BaseWidget(
        title = h.label,
        description = h.value,
        icon = if (h.ok) MaterialSymbols.Outlined.Check_circle else MaterialSymbols.Outlined.Error,
        iconColor = if (h.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        onClick = onShowDetail,
        trailingContent = {
            if (h.actionLabel != null) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun detailMessage(h: ModuleSelfCheck.HealthItem): String =
    buildString {
        appendLine("当前状态：${h.value}")
        appendLine()
        append(h.detail)
    }