package dev.sun.wechat.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 消息分析（移植自 FkWeChat"分析"功能）：
 * 长按消息 → 分析 → 弹出面板：
 *  - 内容预览
 *  - 10 种回复风格选择
 *  - 生成 AI 回复建议（复用 WeAgent 模型库）
 *  - 今日消息统计（发送条数/活跃度）
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
    private fun ShowComposeDialogScope.AnalysisDialogContent(msgInfo: MessageInfo) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectedStyle by remember { mutableStateOf("智能全能") }
        var reply by remember { mutableStateOf<String?>(null) }
        var generating by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val preview = remember(msgInfo.id) { previewFromMessage(msgInfo) }

        // 今日该会话消息统计（活跃度/条数）
        val todayStats by produceState(emptyList<WeMessage>()) {
            val conv = msgInfo.talker
            if (conv.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val dayStart = now - (now % 86400000L)
                value = withContext(Dispatchers.IO) {
                    try { WeDatabaseApi.getMessagesInRange(conv, dayStart, now) } catch (e: Exception) { emptyList() }
                }
            }
        }

        fun generate() {
            if (generating) return
            val text = preview
            if (text.isBlank()) { error = "无可分析内容"; return }
            generating = true
            error = null
            reply = null
            scope.launch {
                val r = generateReply(text, selectedStyle)
                generating = false
                if (r.isEmpty()) error = "生成失败，请检查模型配置" else reply = r
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_message_analysis_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // 内容预览
                    Text(stringResource(R.string.ama_content_preview), style = MaterialTheme.typography.titleSmall)
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(preview.ifBlank { stringResource(R.string.ama_no_content) }, style = MaterialTheme.typography.bodyMedium)
                    }

                    // 今日统计
                    Text(stringResource(R.string.ama_today_stats), style = MaterialTheme.typography.titleSmall)
                    Text("${todayStats.size} 条", style = MaterialTheme.typography.bodyMedium)

                    // 风格选择
                    Text(stringResource(R.string.ama_reply_style), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        STYLES.take(5).forEach { (name, _) ->
                            FilterChip(
                                selected = selectedStyle == name,
                                onClick = { selectedStyle = name },
                                label = { Text(name) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        STYLES.drop(5).forEach { (name, _) ->
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
                        reply != null -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(reply!!, style = MaterialTheme.typography.bodyMedium)
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

    private fun previewFromMessage(msg: MessageInfo): String {
        val type = msg.type
        return when {
            msg.actualContent.isNotBlank() -> msg.actualContent
            type?.isText == true -> msg.content
            else -> msg.content
        }.trim().take(500)
    }

    /** 调用 WeAgent 模型生成回复。 */
    private suspend fun generateReply(content: String, styleName: String): String = withContext(Dispatchers.IO) {
        try {
            val modelId = WeAgentRepository.firstModelId()
                ?: return@withContext ""
            val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
            val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
            val client = ModelProviderManager.clientFor(provider)

            val stylePrompt = STYLES.firstOrNull { it.first == styleName }?.second ?: DEFAULT_PROMPT
            val systemPrompt = buildString {
                append("你是微信聊天助手。$stylePrompt\n")
                append("只回复消息本身，不要多余解释。")
            }
            val messages = listOf(
                LlmMessage(LlmRole.SYSTEM, systemPrompt),
                LlmMessage(LlmRole.USER, content),
            )
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
            sb.toString().trim()
        } catch (e: Exception) {
            WeLogger.e(TAG, "generate reply failed", e)
            ""
        }
    }
}

private object AiMessageAnalysisIcon : VectorPathDrawable(
    "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM9,17H7v-5h2V17zM13,17h-2V7h2V17zM17,17h-2v-4h2V17z"
)