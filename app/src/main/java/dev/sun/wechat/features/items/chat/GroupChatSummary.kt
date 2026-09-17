package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.preferences.WePrefs
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊 AI 总结：定时或按条数阈值，把群聊最近的消息发给 AI 生成摘要，可选发回群里。
 * 移植自 WeChatDataAnalysis 的「聊天 AI 总结」思路，用 WeKit 的 WeAgent 模型库。
 */
object GroupChatSummary : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "群聊AI总结"
    override val nameRes = R.string.feature_gcs_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_gcs_description

    private const val TAG = "GroupChatSummary"
    private const val DEFAULT_PROMPT =
        "请根据以下群聊消息，用简洁的中文总结讨论要点和结论。" +
            "列出主要话题和关键信息，忽略闲聊。输出不超过 300 字。"

    // 触发模式
    private const val MODE_TIMER = 0
    private const val MODE_THRESHOLD = 1
    private const val MODE_BOTH = 2

    // ---- 配置 ----
    var groups by WePrefs.prefOption("gcs_groups", "")
    var triggerMode by WePrefs.prefOption("gcs_trigger_mode", MODE_TIMER)
    var intervalMinutes by WePrefs.prefOption("gcs_interval_min", 60)
    var messageThreshold by WePrefs.prefOption("gcs_msg_threshold", 50)
    var summaryRange by WePrefs.prefOption("gcs_summary_range", 100)
    var prompt by WePrefs.prefOption("gcs_prompt", "")
    var sendToGroup by WePrefs.prefOption("gcs_send_to_group", true)

    // ---- 运行时 ----
    private val msgCounters = ConcurrentHashMap<String, Int>()
    private val summaryJob = Job()
    private val scope = CoroutineScope(SupervisorJob() + summaryJob + Dispatchers.IO)
    @Volatile private var timerJob: Job? = null

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        startTimer()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        stopTimer()
        msgCounters.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) { SettingsDialog(context) }
    }

    // ---------------- 消息计数（阈值触发） ----------------

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (groups.isEmpty()) return
        val talker = values.getAsString("talker") ?: return
        if (!talker.endsWith("@chatroom") || talker !in loadSet(groups)) return
        val isSend = values.getAsInteger("isSend") ?: return
        if (isSend != 0) return

        val mode = triggerMode
        if (mode != MODE_THRESHOLD && mode != MODE_BOTH) return

        val count = (msgCounters[talker] ?: 0) + 1
        msgCounters[talker] = count
        if (count >= messageThreshold.coerceAtLeast(1)) {
            msgCounters[talker] = 0
            scope.launch { summarize(talker) }
        }
    }

    // ---------------- 定时触发 ----------------

    private fun startTimer() {
        stopTimer()
        val mode = triggerMode
        if (mode != MODE_TIMER && mode != MODE_BOTH) return
        timerJob = scope.launch {
            while (isActive) {
                delay(intervalMinutes.coerceAtLeast(5) * 60_000L)
                loadSet(groups).forEach { groupId ->
                    runCatching { summarize(groupId) }
                        .onFailure { WeLogger.w(TAG, "scheduled summary failed: $groupId", it) }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ---------------- AI 总结 ----------------

    private suspend fun summarize(groupId: String) {
        val messages = runCatching { WeDatabaseApi.getMessages(groupId, 1, summaryRange.coerceIn(10, 500)) }
            .onFailure { WeLogger.e(TAG, "failed to read messages for $groupId", it) }
            .getOrNull()
            ?: return

        if (messages.isEmpty()) return
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = messages.mapNotNull { m ->
            val raw = m.content.trim()
            if (raw.isBlank()) return@mapNotNull null
            val idx = raw.indexOf(":\n")
            val senderId = if (idx in 1..80) raw.substring(0, idx) else ""
            val text = if (idx in 1..80) raw.substring(idx + 2).trim() else raw
            if (text.isBlank()) return@mapNotNull null
            val senderName = WeDatabaseApi.getGroupMemberDisplayName(groupId, senderId)
                .ifBlank { senderId.ifBlank { "未知" } }
            val time = fmt.format(Date(m.createTime))
            "[$time] $senderName: $text"
        }
        if (lines.isEmpty()) return

        val sysPrompt = prompt.ifBlank { DEFAULT_PROMPT }
        val userContent = "以下是群聊「${WeDatabaseApi.getDisplayName(groupId)}」最近的消息记录，请总结：\n\n${lines.joinToString("\n")}"

        val summary = generateAI(sysPrompt, userContent)
        if (summary.isBlank()) {
            WeLogger.w(TAG, "AI summary empty for $groupId")
            return
        }

        WeLogger.i(TAG, "group summary generated for $groupId, ${summary.length} chars")
        if (sendToGroup) {
            WeMessageApi.sendText(groupId, "📋 群聊总结\n\n$summary")
        }
    }

    private suspend fun generateAI(systemPrompt: String, userContent: String): String =
        withContext(Dispatchers.IO) {
            try {
                val modelId = WeAgentRepository.firstModelId() ?: return@withContext ""
                val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
                val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
                val client = ModelProviderManager.clientFor(provider)
                val request = ModelProviderManager.buildRequest(
                    model = model,
                    messages = listOf(
                        LlmMessage(LlmRole.SYSTEM, systemPrompt),
                        LlmMessage(LlmRole.USER, userContent),
                    ),
                    tools = emptyList(),
                    stream = true,
                )
                val sb = StringBuilder()
                client.stream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> sb.append(event.text)
                        is LlmStreamEvent.Completed -> if (sb.isEmpty()) { event.message.content?.let { sb.append(it) } }
                        is LlmStreamEvent.Failed -> throw event.error
                        else -> {}
                    }
                }
                sb.toString().trim()
            } catch (e: Exception) {
                WeLogger.e(TAG, "AI generation failed", e)
                ""
            }
        }

    // ---------------- 名单工具 ----------------

    private fun loadSet(raw: String): Set<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun saveSet(items: Set<String>): String = items.joinToString("\n")

    // ---------------- 设置界面 ----------------

    @Composable
    private fun SettingsDialog(context: ComponentActivity) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        var groupsInput by remember { mutableStateOf(groups) }
        var modeInput by remember { mutableStateOf(triggerMode) }
        var intervalInput by remember { mutableStateOf(intervalMinutes.toString()) }
        var thresholdInput by remember { mutableStateOf(messageThreshold.toString()) }
        var rangeInput by remember { mutableStateOf(summaryRange.toString()) }
        var promptInput by remember { mutableStateOf(prompt) }
        var sendInput by remember { mutableStateOf(sendToGroup) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.gcs_config_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.gcs_pick_groups),
                        description = stringResource(R.string.gcs_groups_desc, loadSet(groupsInput).size),
                        onClick = {
                            showComposeDialog(context) {
                                ContactsSelector(
                                    title = stringResource(R.string.gcs_pick_groups),
                                    contacts = WeDatabaseApi.getGroups(),
                                    initialSelectedWxIds = loadSet(groupsInput),
                                    onDismiss = onDismiss,
                                ) { selected ->
                                    groupsInput = saveSet(selected)
                                    onDismiss()
                                }
                            }
                        },
                    )
                    FieldRow(
                        label = stringResource(R.string.gcs_trigger_mode),
                        value = when (modeInput) {
                            MODE_TIMER -> context.getString(R.string.gcs_mode_timer)
                            MODE_THRESHOLD -> context.getString(R.string.gcs_mode_threshold)
                            else -> context.getString(R.string.gcs_mode_both)
                        },
                        onValueChange = { },
                    )
                    if (modeInput == MODE_TIMER || modeInput == MODE_BOTH) {
                        FieldRow(
                            label = stringResource(R.string.gcs_interval),
                            value = intervalInput,
                            onValueChange = { v -> intervalInput = v.filter { c -> c.isDigit() }.take(4) },
                        )
                    }
                    if (modeInput == MODE_THRESHOLD || modeInput == MODE_BOTH) {
                        FieldRow(
                            label = stringResource(R.string.gcs_threshold),
                            value = thresholdInput,
                            onValueChange = { v -> thresholdInput = v.filter { c -> c.isDigit() }.take(4) },
                        )
                    }
                    FieldRow(
                        label = stringResource(R.string.gcs_range),
                        value = rangeInput,
                        onValueChange = { v -> rangeInput = v.filter { c -> c.isDigit() }.take(4) },
                    )
                    FieldRow(
                        label = stringResource(R.string.gcs_prompt),
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        singleLine = false,
                    )
                    SwitchWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.gcs_send_to_group),
                        description = stringResource(R.string.gcs_send_desc),
                        checked = sendInput,
                        onCheckedChange = { sendInput = it },
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    groups = groupsInput
                    triggerMode = modeInput
                    intervalMinutes = (intervalInput.toIntOrNull() ?: intervalMinutes).coerceIn(5, 1440)
                    messageThreshold = (thresholdInput.toIntOrNull() ?: messageThreshold).coerceIn(5, 9999)
                    summaryRange = (rangeInput.toIntOrNull() ?: summaryRange).coerceIn(10, 500)
                    prompt = promptInput
                    sendToGroup = sendInput
                    stopTimer(); startTimer()
                    showToast(localizedContext.getString(R.string.gcs_saved))
                    onDismiss()
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }

    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        singleLine: Boolean = true,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
        }
    }
}
