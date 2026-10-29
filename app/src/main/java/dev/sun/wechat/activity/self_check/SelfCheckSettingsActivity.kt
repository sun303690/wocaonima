package dev.sun.wechat.activity.self_check

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import dev.sun.wechat.ui.content.miuix.MiuixAlertDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.sun.wechat.ui.content.miuix.MiuixSettingsPage
import dev.sun.wechat.ui.agent.settings.AgentConfirmDialog
import dev.sun.wechat.ui.agent.settings.AgentListActionButton
import dev.sun.wechat.ui.content.miuix.MiuixBaseItemContainer
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.utils.theme.MiuixModuleTheme
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_REPORT_CHARS = 3000

/**
 * 模块自检页。入口：设置页“更新”分组的“模块自检”行，或微信侧注入的「模块自检」。
 *
 * 打开时自动跑一轮全量自检（[ModuleSelfCheck.healthSteps]），逐项展示分组结果；支持
 * 「一键自检」（跑完弹报告）、「一键修复并自检」、「刷新检测结果」、「复制检测结果」。
 * 点任意一项看详情，可修复的项在详情里带动作按钮；动作执行后会自动重新自检刷新列表。
 *
 * 关键：每一步都在 [Dispatchers.IO] 上执行，步骤之间留短暂延迟让进度上屏，避免主线程
 * 卡死导致进度条/界面冻结（旧实现把 step.run() 跑在主线程，Dex/反射重的步骤会让整页假死）。
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
                MiuixModuleTheme {
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

    var items by remember { mutableStateOf<List<ModuleSelfCheck.HealthItem>>(emptyList()) }
    var lastScan by remember { mutableStateOf("尚未检测") }
    var scanDurationMs by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runSubtitle by remember { mutableStateOf("") }
    var runLog by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    var detail by remember { mutableStateOf<ModuleSelfCheck.HealthItem?>(null) }
    var filteredList by remember { mutableStateOf<Pair<String, List<ModuleSelfCheck.HealthItem>>?>(null) }
    var reportDialog by remember { mutableStateOf<Pair<String, List<ModuleSelfCheck.HealthItem>>?>(null) }

    /** 统一的自检/修复执行器：每步在 IO 上跑，进度浮层实时刷新，跑完可选弹报告并刷新列表。 */
    fun launchRun(
        title: String,
        stepsProvider: () -> List<ModuleSelfCheck.HealthStep>,
        showOverlay: Boolean,
        reportTitle: String?,
        replaceItems: Boolean,
        afterRun: (() -> Unit)? = null,
    ) {
        if (running) return
        if (showOverlay) {
            running = true
            runTitle = title
            runProgress = 0f
            runSubtitle = "准备中…"
            runLog = emptyList()
        }
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val steps = runCatching { stepsProvider() }.getOrElse { emptyList() }
            val collected = mutableListOf<ModuleSelfCheck.HealthItem>()
            steps.forEachIndexed { index, step ->
                if (showOverlay) runSubtitle = "正在执行：${step.title}"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }.getOrElse { error ->
                        listOf(
                            ModuleSelfCheck.HealthItem(
                                label = step.title,
                                section = step.title,
                                value = "异常",
                                ok = false,
                                detail = "执行「${step.title}」时抛出异常：\n" + error.toString(),
                            ),
                        )
                    }
                }
                collected += outcome
                if (showOverlay) {
                    runLog = runLog + (step.title to outcome.all { it.ok })
                    runProgress = (index + 1).toFloat() / steps.size.toFloat().coerceAtLeast(1f)
                    delay(120)
                }
            }
            if (replaceItems) {
                items = collected
                scanDurationMs = System.currentTimeMillis() - startedAt
            }
            if (showOverlay) running = false
            lastScan = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (reportTitle != null) reportDialog = reportTitle to collected
            // 收尾动作（如「修复后重新自检刷新列表」）在 running 置 false 后再执行。
            afterRun?.invoke()
        }
    }

    fun rescan() = launchRun(
        "模块自检",
        { ModuleSelfCheck.healthSteps(context) },
        showOverlay = true,
        reportTitle = null,
        replaceItems = true,
    )

    LaunchedEffect(Unit) { rescan() }

    MiuixSettingsPage(
        title = stringResource(R.string.self_check_screen_title),
        onBack = onFinish,
    ) {
        item(key = "overview_hint") {
            Text(
                text = "最后检测：$lastScan　·　点击任意一项可看详情，可修复的项会带动作按钮",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "overview") {
            OverviewHealthCard(
                items = items,
                lastScan = lastScan,
                scanDurationMs = scanDurationMs,
                onShowList = { kind ->
                    val list = when (kind) {
                        "pass" -> items.filter { it.ok }
                        "fail" -> items.filter { !it.ok && !it.inactive }
                        "off" -> items.filter { !it.ok && it.inactive }
                        else -> items
                    }
                    if (list.isEmpty()) {
                        showToast(context, "没有符合条件的项")
                    } else {
                        val t = when (kind) {
                            "pass" -> "通过项"
                            "fail" -> "异常项"
                            "off" -> "未启用项"
                            else -> "全部检测项"
                        }
                        filteredList = t to list
                    }
                },
                onRescan = { rescan() },
                onRepair = {
                    launchRun(
                        "一键修复",
                        { ModuleSelfCheck.repairSteps(context) },
                        showOverlay = true,
                        reportTitle = "一键修复结果",
                        replaceItems = false,
                        afterRun = { rescan() },
                    )
                },
            )
        }

        val grouped = items.groupBy { it.section }
        grouped.forEach { (section, list) ->
            item(key = "sec_$section") {
                MiuixSegmentedColumn(title = section) {
                    for (h in list) {
                        item(key = h.label) { HealthRow(h) { detail = h } }
                    }
                }
            }
        }

        item(key = "actions_title") {
            Text(
                text = "一键自检与修复",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        item(key = "actions") {
            MiuixSegmentedColumn {
                item(key = "scan") {
                    MiuixBaseWidget(
                        title = "一键自检",
                        description = "逐项扫描全部公共依赖（微信 API / 脚本 / Hook 任务 / 锚点 / 密友 / 运行状态），带进度条，跑完弹报告",
                        onClick = {
                            launchRun(
                                "一键自检",
                                { ModuleSelfCheck.healthSteps(context) },
                                showOverlay = true,
                                reportTitle = "模块自检结果",
                                replaceItems = true,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        },
                    )
                }
                item(key = "repair") {
                    MiuixBaseWidget(
                        title = "一键修复",
                        description = "补脚本目录 / 开总开关 / 重置 Dex 缓存 / 推进阶段重排 Hook / 密友对账；修复后自动重新自检刷新列表",
                        onClick = {
                            launchRun(
                                "一键修复",
                                { ModuleSelfCheck.repairSteps(context) },
                                showOverlay = true,
                                reportTitle = "一键修复结果",
                                replaceItems = false,
                                afterRun = { rescan() },
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        },
                    )
                }
                item(key = "refresh") {
                    MiuixBaseWidget(
                        title = "刷新检测结果",
                        description = "重新逐项扫描并刷新上面的列表（不改动任何配置）",
                        onClick = {
                            rescan()
                            showToast(context, "已重新检测")
                        },
                    )
                }
                item(key = "copy") {
                    MiuixBaseWidget(
                        title = "复制检测结果",
                        description = "复制到剪贴板，便于反馈",
                        onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching { ModuleSelfCheck.buildReportText(context) }
                                        .getOrElse { "报告生成失败：${it.message}" }
                                }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("WeKit 模块自检", text))
                                showToast(context, "检测结果已复制")
                            }
                        },
                    )
                }
            }
        }

        item(key = "log_card") { DiagnosticLogCard(context) }

        item(key = "footer_hint") {
            Text(
                text = "说明：本页是「全量自检」——覆盖微信公共 API、脚本运行时、Hook 安装任务、关键类锚点、密友状态、运行状态与各分类启用情况。" +
                    "标红或标 ❌ 的项通常就是对应功能失效的原因；点开可看含义、恢复建议与可直接执行的动作。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    // 进度浮层
    if (running) {
        ProgressOverlay(runTitle, runProgress, runSubtitle, runLog)
    }

    // 详情弹窗
    detail?.let { h ->
        AgentConfirmDialog(
            show = true,
            title = h.label,
            message = "当前状态：${h.value}\n\n${h.detail}",
            confirmLabel = h.actionLabel ?: stringResource(R.string.self_check_ok),
            dismissLabel = if (h.actionLabel != null) stringResource(R.string.self_check_close)
            else stringResource(R.string.self_check_ok),
            onConfirm = {
                val action = h.action
                detail = null
                if (action != null) {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching { action() }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                        }
                        showToast(context, msg)
                        rescan()
                    }
                }
            },
            onDismiss = { detail = null },
        )
    }

    // 过滤列表弹窗
    filteredList?.let { (title, list) ->
        val text = buildString {
            append("共 ").append(list.size).append(" 项：\n\n")
            list.forEach {
                append(if (it.ok) "✅ " else "❌ ").append(it.label)
                    .append("\t").append(it.value).append("\n")
            }
        }
        MiuixAlertDialog(
            onDismissRequest = { filteredList = null },
            title = { Text(title) },
            text = { Text(text, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText(title, text))
                    }
                    showToast(context, "已复制")
                }) { Text("复制") }
            },
            dismissButton = { Button(onClick = { filteredList = null }) { Text("关闭") } },
        )
    }

    // 自检/修复结果报告弹窗
    reportDialog?.let { (title, list) ->
        ReportDialog(title, list, onDismiss = { reportDialog = null }, context = context)
    }
}

// ---------------------------------------------------------------------------
//  概览卡片
// ---------------------------------------------------------------------------

@Composable
private fun OverviewHealthCard(
    items: List<ModuleSelfCheck.HealthItem>,
    lastScan: String,
    scanDurationMs: Long,
    onShowList: (String) -> Unit,
    onRescan: () -> Unit,
    onRepair: () -> Unit,
) {
    val total = items.size
    val pass = items.count { it.ok }
    val fail = items.count { !it.ok && !it.inactive }
    val off = items.count { !it.ok && it.inactive }
    val noFail = fail == 0 && total > 0
    val statusTitle = when {
        total == 0 -> "尚未检测"
        fail > 0 -> "存在缺失项"
        else -> "适配检查完成"
    }
    val statusColor = if (total == 0) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else if (noFail) MiuixTheme.colorScheme.onSurface
    else MiuixTheme.colorScheme.error

    BaseItemContainer {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "模块自检",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "DEX 适配",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = statusTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = statusColor)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    total == 0 -> "尚未扫描，点击下方按钮立即开始。"
                    noFail -> "已检查 $total / $total 项，通过 $pass，无异常" +
                        if (off > 0) "（另有 $off 项未启用）" else "。"
                    else -> "已检查 $total / $total 项，通过 $pass，异常 $fail" +
                        if (off > 0) "，未启用 $off。" else "。"
                },
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "模块版本：${ModuleSelfCheck.moduleVersionDisplay()}",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "当前微信：${ModuleSelfCheck.wechatVersionDisplay()}",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "内部版本：${ModuleSelfCheck.moduleVersionDisplay().substringAfter("(").substringBefore(")")}",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            // 蓝色分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MiuixTheme.colorScheme.primary),
            )
            Spacer(Modifier.height(16.dp))
            // 4 格统计
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(formatDuration(scanDurationMs), "耗时", Modifier.weight(1f).padding(end = 6.dp))
                StatCell(pass.toString(), "通过", Modifier.weight(1f).padding(horizontal = 3.dp)) { onShowList("pass") }
                StatCell(fail.toString(), "异常", Modifier.weight(1f).padding(horizontal = 3.dp)) { onShowList("fail") }
                StatCell(off.toString(), "未启用", Modifier.weight(1f).padding(start = 6.dp)) { onShowList("off") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "通过表示公共能力可用，不等于所有使用场景已验证。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            // 主按钮：立即重新自检
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable { onRescan() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "立即重新自检",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 次按钮：一键修复（描边式）
            Button(
                onClick = onRepair,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "一键修复（重建引导链路）",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 把毫秒格式化为「1.2s」/「350ms」。 */
private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1000L -> "${ms}ms"
    else -> String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
}

/** 4 格统计单元格：[value] 是大数字，[label] 在下方；[onClick] 非空时可点。 */
@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val bg = MiuixTheme.colorScheme.surfaceContainer
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(text = label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

// ---------------------------------------------------------------------------
//  进度浮层（百分比 + 副标题 + 最近 6 步勾选清单）
// ---------------------------------------------------------------------------

@Composable
private fun ProgressOverlay(
    title: String,
    progress: Float,
    subtitle: String,
    log: List<Pair<String, Boolean>>,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MiuixTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${(progress * 100).toInt()}%  $subtitle",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                log.takeLast(6).forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = if (entry.second) "✅ " else "❌ ", fontSize = 12.sp)
                        Text(
                            text = entry.first,
                            fontSize = 12.sp,
                            color = if (entry.second) MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  检测项行
// ---------------------------------------------------------------------------

@Composable
private fun HealthRow(h: ModuleSelfCheck.HealthItem, onShowDetail: () -> Unit) {
    val icon = if (h.ok) MaterialSymbols.Outlined.Check_circle else MaterialSymbols.Outlined.Error
    val iconColor = when {
        h.ok -> MiuixTheme.colorScheme.primary
        h.inactive -> Color(0xFFB26A00)
        else -> MiuixTheme.colorScheme.error
    }
    MiuixBaseWidget(
        title = h.label,
        description = h.value,
        icon = icon,
        iconColor = iconColor,
        onClick = onShowDetail,
        trailingContent = {
            if (h.actionLabel != null) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
//  报告弹窗（一键自检/一键修复跑完弹出，可复制）
// ---------------------------------------------------------------------------

@Composable
private fun ReportDialog(
    title: String,
    items: List<ModuleSelfCheck.HealthItem>,
    onDismiss: () -> Unit,
    context: Context,
) {
    val okCount = items.count { it.ok }
    val failItems = items.filter { !it.ok && !it.inactive }
    val offItems = items.filter { !it.ok && it.inactive }
    val body = buildString {
        append("共 ").append(items.size).append(" 项：通过 ").append(okCount)
            .append("，异常 ").append(failItems.size).append("，未启用 ").append(offItems.size).append("。\n\n")
        if (failItems.isNotEmpty()) {
            append("【异常】需要处理\n")
            failItems.forEach { append("❌ ").append(it.label).append("：").append(it.value).append("\n") }
            append("\n")
        }
        if (offItems.isNotEmpty()) {
            append("【未启用】不是故障\n")
            offItems.forEach { append("○ ").append(it.label).append("：").append(it.value).append("\n") }
            append("\n")
        }
        if (failItems.isEmpty() && offItems.isEmpty()) append("全部通过。\n")
    }.let { if (it.length > MAX_REPORT_CHARS) it.take(MAX_REPORT_CHARS) + "\n…（报告过长已截断，可在列表里逐项查看）" else it }

    MiuixAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, fontSize = 13.sp) },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText(title, body))
                }
                showToast(context, "已复制")
            }) { Text("复制") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("知道了") } },
    )
}

// ---------------------------------------------------------------------------
//  运行日志卡片（精简可复制版）
// ---------------------------------------------------------------------------

@Composable
private fun DiagnosticLogCard(context: Context) {
    var digest by remember { mutableStateOf<WeLogger.LogDigest?>(null) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var onlyProblems by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            runCatching {
                WeLogger.digest() to WeLogger.readRecentLines().second
            }
        }.fold(
            onSuccess = {
                digest = it.first
                recent = it.second
                readError = null
            },
            onFailure = { readError = it.message ?: it.javaClass.simpleName },
        )
    }

    val d = digest
    val summary = when {
        readError != null -> "读取日志失败：$readError"
        d == null -> "正在读取…"
        d.fileName == null -> "暂无运行日志"
        else -> "${d.fileName}｜${d.fileSizeText}｜记录 ${d.totalRecords} 条｜错误 ${d.errorCount}｜警告 ${d.warnCount}" +
            if (d.droppedCount > 0L) "｜丢弃 ${d.droppedCount}" else ""
    }

    MiuixSegmentedColumn(title = "运行日志") {
        item(key = "refresh") {
            MiuixBaseWidget(title = "刷新日志", description = summary, onClick = { refreshKey++ })
        }
        item(key = "toggle") {
            MiuixBaseWidget(
                title = "显示内容",
                description = if (onlyProblems) "仅问题（错误/警告）· 默认，最简洁" else "全部（最近 300 行原始日志）",
                onClick = { onlyProblems = !onlyProblems },
            )
        }
        item(key = "copy_prob") {
            MiuixBaseWidget(
                title = "复制问题摘要",
                description = "只复制错误/警告 + 计数（紧凑、可读），适合直接粘贴反馈",
                onClick = { copyLogToClipboard(context, d?.toShareText() ?: "（暂无日志）", "问题摘要") },
            )
        }
        item(key = "copy_full") {
            MiuixBaseWidget(
                title = "复制完整日志",
                description = "复制最近 ${recent.size} 行原始运行日志（超长自动截断）",
                onClick = {
                    val full = recent.joinToString("\n")
                    val capped = if (full.length > 8000) {
                        full.take(8000) + "\n…（已截断，完整内容见日志文件）"
                    } else full
                    copyLogToClipboard(context, capped, "完整日志")
                },
            )
        }
    }

    val preview = if (onlyProblems) buildProblemLines(d) else recent
    if (preview.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        BaseItemContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = preview.joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun buildProblemLines(d: WeLogger.LogDigest?): List<String> {
    if (d == null) return emptyList()
    if (d.problems.isEmpty()) return listOf("最近没有错误或警告。")
    return d.problems.flatMap { problem ->
        listOf(problem.header) + problem.detail.take(8).map { "    $it" }
    }
}

private fun copyLogToClipboard(context: Context, text: String, label: String) {
    val ok = runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching false
        cb.setPrimaryClip(ClipData.newPlainText("WeKit 日志", text))
        true
    }.getOrDefault(false)
    showToast(context, if (ok) "已复制$label（${text.length} 字）" else "复制失败")
}
