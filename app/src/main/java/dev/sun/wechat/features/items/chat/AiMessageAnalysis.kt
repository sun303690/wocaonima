package dev.sun.wechat.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Insights
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.data.entity.ModelEntity
import dev.sun.wechat.agent.model.LlmClient
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.WeMessage
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.VectorPathDrawable
import dev.sun.wechat.ui.utils.ShowComposeDialogScope
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 消息分析（完整移植自 FkWeChat「长按分析消息」）：
 * 长按消息 → 分析 → 弹出面板：
 *  - 时段选择：今日/昨日/本周/上周/本月/上月/今年/全部
 *  - AI 聊天总结（FkWeChat 原版 prompt + 用户额外要求，复用 WeAgent 模型库）
 *  - 智能洞察（本地统计）：五维评分/字数分布/时段分布
 *  - 10 种回复风格选择 + 生成 AI 回复建议
 * 开关：设置页「聊天」分类内，关闭后长按菜单不显示「分析」项。
 */
object AiMessageAnalysis : ClickableFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "AI消息分析"
    override val nameRes = R.string.feature_ai_message_analysis_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_message_analysis_description

    private const val TAG = "AiMessageAnalysis"
    private const val MENU_ID = 777041
    private const val DEFAULT_PROMPT = "分析当前对话氛围，给出最得体、自然的回复。"

    /** FkWeChat 原版总结 prompt */
    private const val SUMMARY_PROMPT =
        "你是一个微信聊天分析助手。请根据以下聊天记录，总结出这段时间内大家聊了哪些主要内容，" +
            "重点话题，整体氛围如何，并提取一些有趣的点。语言请幽默生动，排版清晰。如果记录较少请简短回复。"

    /** 预置回复风格 name -> prompt */
    private val STYLES = listOf(
        "智能全能" to "分析当前对话氛围，给出最得体、自然的回复。",
        "高情商" to "说话非常有艺术，能够化解尴尬，照顾对方感受，充满智慧。",
        "轻松闲聊" to "语气随性自然，带一点点幽默感，不要官方和生硬。",
        "严谨正式" to "语气礼貌、专业、客观，适用于职场或正式商务沟通。",
        "幽默/阴阳" to "说话风趣，带点俏皮甚至一点点阴阳怪气，非常有意思。",
        "同理/安慰" to "语气非常温柔，站在对方立场思考，给予对方情感上的支撑。",
        "客气周到" to "非常有礼貌，多使用敬语，保持一定的礼貌距离。",
        "霸道/冷酷" to "言简意赅，语气带有一点压迫感和冷酷的霸总风格。",
        "可爱/萌化" to "说话活泼，多用呀、哒、呢，增加适量颜文字，非常可爱。",
        "委婉拒绝" to "礼貌地拒绝对方的要求，不让对方感到难堪，语气委婉。",
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    // 该功能仅通过聊天消息长按菜单(IMenuItemsProvider)触发，
    // 不参与功能列表点击入口，故 onClick 留空实现。
    override fun onClick(context: ComponentActivity) {}

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "分析",
            drawable = AiMessageAnalysisIcon,
            imageVector = MaterialSymbols.Outlined.Insights,
            isSupported = { msg -> msg.type?.isText == true },
        ) { view, ctx, msgInfo ->
            showAnalysisDialog(ctx.activity, msgInfo)
        },
    )

    private fun showAnalysisDialog(activity: android.app.Activity, msgInfo: MessageInfo) {
        showComposeDialog(activity) {
            AnalysisDialogContent(msgInfo)
        }
    }

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ShowComposeDialogScope.AnalysisDialogContent(msgInfo: MessageInfo) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectedStyle by remember { mutableStateOf("智能全能") }
        var period by remember { mutableStateOf(Period.THIS_WEEK) }
        var extraRequest by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf<String?>(null) }
        var reply by remember { mutableStateOf<String?>(null) }
        var generating by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        // 时段消息(本地统计 + AI 总结共用)
        val messages by produceState(emptyList<WeMessage>(), period) {
            val conv = msgInfo.talker
            value = if (conv.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                try { WeDatabaseApi.getMessagesInRange(conv, period.startMs, period.endMs) } catch (e: Exception) { emptyList() }
            }
        }

        fun generate() {
            if (generating) return
            generating = true
            error = null
            summary = null
            reply = null
            scope.launch {
                val text = previewFromMessage(msgInfo)
                val chatText = buildChatText(messages)
                val sum = generateSummary(chatText, extraRequest.trim())
                val rep = if (text.isBlank()) "" else generateReply(text, selectedStyle)
                generating = false
                if (sum.isEmpty() && rep.isEmpty()) {
                    error = "生成失败，请检查模型配置"
                } else {
                    summary = sum.ifEmpty { null }
                    reply = rep.ifEmpty { null }
                }
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_message_analysis_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // 时段选择
                    Text(stringResource(R.string.ama_period), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Period.entries.forEach { p ->
                            FilterChip(
                                selected = period == p,
                                onClick = { period = p },
                                label = { Text(periodLabel(p)) },
                            )
                        }
                    }

                    // 智能洞察(本地统计)
                    val stats = remember(messages) { InsightStats.of(messages) }
                    Text(stringResource(R.string.ama_insight_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.ama_insight_summary, messages.size, stats.sendCount, stats.recvCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InsightRow(stringResource(R.string.ama_stat_activity), stats.activityScore)
                    InsightRow(stringResource(R.string.ama_stat_interaction), stats.interactionScore)
                    InsightRow(stringResource(R.string.ama_stat_expression), stats.expressionScore)
                    InsightRow(stringResource(R.string.ama_stat_gold), stats.goldScore)
                    InsightRow(stringResource(R.string.ama_stat_burst), stats.burstScore)
                    Text(
                        stringResource(R.string.ama_stat_night, stats.nightCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )

                    // 用户额外要求
                    OutlinedTextField(
                        value = extraRequest,
                        onValueChange = { extraRequest = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text(stringResource(R.string.ama_extra_request)) },
                        singleLine = true,
                    )

                    // 风格选择
                    Text(stringResource(R.string.ama_reply_style), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        STYLES.forEach { (name, _) ->
                            FilterChip(
                                selected = selectedStyle == name,
                                onClick = { selectedStyle = name },
                                label = { Text(name) },
                            )
                        }
                    }

                    // 结果
                    when {
                        generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.ama_generating))
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        else -> {
                            if (summary != null) {
                                Text(stringResource(R.string.ama_summary_title), style = MaterialTheme.typography.titleSmall)
                                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(summary!!, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (reply != null) {
                                Text(stringResource(R.string.ama_reply_suggestion), style = MaterialTheme.typography.titleSmall)
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text(reply!!, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { generate() }, enabled = !generating) {
                    Text(stringResource(R.string.ama_generate))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    @Composable
    private fun InsightRow(label: String, score: Int) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("$label  $score%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { score / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun previewFromMessage(msg: MessageInfo): String {
        val type = msg.type
        return when {
            msg.actualContent.isNotBlank() -> msg.actualContent
            type?.isText == true -> msg.content
            else -> msg.content
        }.trim().take(500)
    }

    // ---------------- 数据 & 统计 ----------------

    /** 分析时段 */
    private enum class Period(val startMs: Long, val endMs: Long) {
        TODAY(dayStart(0), Long.MAX_VALUE),
        YESTERDAY(dayStart(1), dayStart(0)),
        THIS_WEEK(weekStart(0), Long.MAX_VALUE),
        LAST_WEEK(weekStart(1), weekStart(0)),
        THIS_MONTH(monthStart(0), Long.MAX_VALUE),
        LAST_MONTH(monthStart(1), monthStart(0)),
        THIS_YEAR(yearStart(0), Long.MAX_VALUE),
        ALL(0L, Long.MAX_VALUE),
    }

    private fun periodLabel(p: Period): String = when (p) {
        Period.TODAY -> HostInfo.application.getString(R.string.ama_period_today)
        Period.YESTERDAY -> HostInfo.application.getString(R.string.ama_period_yesterday)
        Period.THIS_WEEK -> HostInfo.application.getString(R.string.ama_period_this_week)
        Period.LAST_WEEK -> HostInfo.application.getString(R.string.ama_period_last_week)
        Period.THIS_MONTH -> HostInfo.application.getString(R.string.ama_period_this_month)
        Period.LAST_MONTH -> HostInfo.application.getString(R.string.ama_period_last_month)
        Period.THIS_YEAR -> HostInfo.application.getString(R.string.ama_period_this_year)
        Period.ALL -> HostInfo.application.getString(R.string.ama_period_all)
    }

    private fun dayStart(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    private fun weekStart(weeksAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        add(Calendar.WEEK_OF_YEAR, -weeksAgo)
    }.timeInMillis

    private fun monthStart(monthsAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, -monthsAgo)
    }.timeInMillis

    private fun yearStart(yearsAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_YEAR, 1)
        add(Calendar.YEAR, -yearsAgo)
    }.timeInMillis

    /** 拼聊天记录文本(我:/对方: 格式, FkWeChat 同款) */
    private fun buildChatText(messages: List<WeMessage>): String =
        messages.filter { it.typeCode == 1 }
            .takeLast(200)
            .joinToString("\n") { msg ->
                (if (msg.isSend != 0) "我" else "对方") + "：" + msg.content
            }

    /** 本地统计洞察(纯计算, 不耗 AI) */
    private data class InsightStats(
        val total: Int,
        val sendCount: Int,
        val recvCount: Int,
        val nightCount: Int,
        val avgLen: Double,
        val maxLen: Int,
        val burstFactor: Double,
        val interactionFactor: Double,
        val expressionFactor: Double,
    ) {
        val activityScore: Int get() = ((total.coerceAtMost(200)) / 200.0 * 100).toInt().coerceIn(1, 100)
        val interactionScore: Int get() = (interactionFactor.coerceIn(0.5, 2.0) / 2.0 * 100).toInt().coerceIn(1, 100)
        val expressionScore: Int get() = (expressionFactor.coerceIn(0.0, 80.0) / 80.0 * 100).toInt().coerceIn(1, 100)
        val goldScore: Int get() = ((avgLen.coerceAtMost(60.0) / 60.0 * 60) + (burstFactor.coerceAtMost(3.0) / 3.0 * 40)).toInt().coerceIn(1, 100)
        val burstScore: Int get() = (burstFactor.coerceIn(0.0, 5.0) / 5.0 * 100).toInt().coerceIn(1, 100)

        companion object {
            fun of(messages: List<WeMessage>): InsightStats {
                val texts = messages.filter { it.typeCode == 1 }
                val send = texts.count { it.isSend != 0 }
                val recv = texts.size - send
                val lens = texts.map { it.content.length }
                val avg = if (lens.isEmpty()) 0.0 else lens.average()
                val max = lens.maxOrNull() ?: 0
                val night = texts.count {
                    val h = Calendar.getInstance().apply { timeInMillis = it.createTime }.get(Calendar.HOUR_OF_DAY)
                    h in 0..4
                }
                val burst = if (lens.isEmpty() || avg == 0.0) 0.0 else max / avg
                val interaction = if (send == 0 || recv == 0) 0.5 else (send.coerceAtMost(recv).toDouble() / send.coerceAtLeast(recv)) * 2.0
                val expression = if (send == 0) 0.0 else texts.filter { it.isSend != 0 }.map { it.content.length }.average()
                return InsightStats(texts.size, send, recv, night, avg, max, burst, interaction, expression)
            }
        }
    }

    // ---------------- AI 调用 ----------------

    /** FkWeChat 原版总结: 系统prompt + 额外要求 + 聊天记录 */
    private suspend fun generateSummary(chatText: String, extraRequest: String): String =
        withContext(Dispatchers.IO) {
            if (chatText.isBlank()) return@withContext ""
            try {
                val modelId = WeAgentRepository.firstModelId() ?: return@withContext ""
                val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
                val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
                val client = ModelProviderManager.clientFor(provider)
                val userContent = buildString {
                    if (extraRequest.isNotEmpty()) append("【用户额外要求】：$extraRequest\n")
                    append("\n聊天记录：\n$chatText")
                }
                val messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, SUMMARY_PROMPT),
                    LlmMessage(LlmRole.USER, userContent),
                )
                streamText(client, model, messages)
            } catch (e: Exception) {
                WeLogger.e(TAG, "generate summary failed", e)
                ""
            }
        }

    /** 风格化回复 */
    private suspend fun generateReply(content: String, styleName: String): String = withContext(Dispatchers.IO) {
        try {
            val modelId = WeAgentRepository.firstModelId() ?: return@withContext ""
            val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
            val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
            val client = ModelProviderManager.clientFor(provider)
            val stylePrompt = STYLES.firstOrNull { it.first == styleName }?.second ?: DEFAULT_PROMPT
            val systemPrompt = "你是微信聊天助手。$stylePrompt\n只回复消息本身，不要多余解释。"
            val messages = listOf(
                LlmMessage(LlmRole.SYSTEM, systemPrompt),
                LlmMessage(LlmRole.USER, content),
            )
            streamText(client, model, messages)
        } catch (e: Exception) {
            WeLogger.e(TAG, "generate reply failed", e)
            ""
        }
    }

    private suspend fun streamText(client: LlmClient, model: ModelEntity, messages: List<LlmMessage>): String {
        val request = ModelProviderManager.buildRequest(model, messages, emptyList(), stream = true)
        val sb = StringBuilder()
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> sb.append(event.text)
                is LlmStreamEvent.Completed -> if (sb.isEmpty()) { event.message.content?.let { sb.append(it) } }
                is LlmStreamEvent.Failed -> throw event.error
                else -> {}
            }
        }
        return sb.toString().trim()
    }
}

private object AiMessageAnalysisIcon : VectorPathDrawable(
    "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM9,17H7v-5h2V17zM13,17h-2V7h2V17zM17,17h-2v-4h2V17z"
)
