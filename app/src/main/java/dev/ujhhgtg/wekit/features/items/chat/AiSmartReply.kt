package dev.ujhhgtg.wekit.features.items.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能回复（移植自 FkWeChat"AI回复"）：
 * 长按消息 → 生成多条可编辑的回复候选 → 发送。
 *  - 参考上下文条数：从该会话取最近 N 条消息作为上下文（默认 10）
 *  - 生成备选数：一次生成几条候选（默认 5）
 *  - 候选可编辑后发送
 *  AI 调用复用 WeAgent 模型库。
 */
object AiSmartReply : ClickableFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "智能回复"
    override val nameRes = R.string.feature_ai_smart_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_smart_reply_description

    private const val TAG = "AiSmartReply"
    private const val MENU_ID = 777042

    /** 参考上下文条数 */
    var contextLimit by prefOption("ai_reply_context_limit", 10)
    /** 生成备选数 */
    var replyCount by prefOption("ai_reply_count", 20)

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        // 设置面板：改上下文条数和备选数
        showComposeDialog(context) {
            SettingsDialogContent()
        }
    }

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ID,
            text = "智能回复",
            drawable = VectorPathDrawable(
                "M19,8l-4,4h3c0,3.31 -2.69,6 -6,6c-1.01,0 -1.97,-0.25 -2.8,-0.7l-1.46,1.46C8.97,19.54 10.43,20 12,20c4.42,0 8,-3.58 8,-8h3L19,8zM6,12c0,-3.31 2.69,-6 6,-6c1.01,0 1.97,0.25 2.8,0.7l1.46,-1.46C15.03,4.46 13.57,4 12,4c-4.42,0 -8,3.58 -8,8H1l4,4l4,-4H6z",
            ),
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = { msg -> msg.type?.isText == true || msg.type?.isQuote == true },
        ) { view, ctx, msgInfo ->
            showSmartReplyDialog(ctx.activity, msgInfo)
        },
    )

    @Composable
    private fun SettingsDialogContent() {
        var contextInput by remember { mutableStateOf(contextLimit.toString()) }
        var countInput by remember { mutableStateOf(replyCount.toString()) }
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_context_limit), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = contextInput,
                                    onValueChange = { contextInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_count), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = countInput,
                                    onValueChange = { countInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    contextLimit = contextInput.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    replyCount = countInput.toIntOrNull()?.coerceIn(1, 20) ?: 20
                    onDismiss()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private fun showSmartReplyDialog(activity: android.app.Activity, msgInfo: MessageInfo) {
        showComposeDialog(activity) {
            SmartReplyDialogContent(msgInfo)
        }
    }

    @Composable
    private fun SmartReplyDialogContent(msgInfo: MessageInfo) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var firstGenerate by remember { mutableStateOf(true) }

        if (firstGenerate) {
            firstGenerate = false
            scope.launch {
                loading = true
                val list = generateCandidates(msgInfo.talker, msgMessageText(msgInfo))
                loading = false
                if (list.isEmpty()) error = "生成失败，请检查模型配置" else candidates = list
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.ama_generating))
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        candidates.isEmpty() -> Text(stringResource(R.string.ama_no_content))
                        else -> {
                            candidates.forEach { text ->
                                var editable by remember(text) { mutableStateOf(text) }
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    OutlinedTextField(
                                        value = editable,
                                        onValueChange = { editable = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 5,
                                    )
                                    Button(
                                        onClick = {
                                            val finalText = editable.trim()
                                            if (finalText.isEmpty()) {
                                                showToast(context, context.getString(R.string.ama_tts_empty_v2))
                                                return@Button
                                            }
                                            val ok = WeMessageApi.sendText(msgInfo.talker, finalText)
                                            showToast(context, context.getString(if (ok) R.string.ama_sent else R.string.ama_send_failed))
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    ) { Text(stringResource(R.string.ama_send)) }
                                }
                            }
                            Button(onClick = {
                                scope.launch {
                                    loading = true
                                    val list = generateCandidates(msgInfo.talker, msgMessageText(msgInfo))
                                    loading = false
                                    if (list.isEmpty()) error = "生成失败，请检查模型配置" else { error = null; candidates = list }
                                }
                            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(stringResource(R.string.ama_regenerate))
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private fun msgMessageText(msg: MessageInfo): String =
        msg.actualContent.ifBlank { msg.content }.trim()

    /** 读取该会话最近 [contextLimit] 条消息作为上下文（含当前消息原文本）。 */
    private fun loadRecentContext(talker: String, limit: Int): String {
        if (talker.isEmpty()) return ""
        return try {
            val now = System.currentTimeMillis()
            val messages = WeDatabaseApi.getMessagesInRange(talker, now - 7L * 86400000L, now)
                .filter { it.content.isNotBlank() }
                .takeLast(limit)
            messages.joinToString("\n") { msg ->
                val prefix = if (msg.isSend != 0) "我：" else "对方："
                prefix + msg.content
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "load context failed", e)
            ""
        }
    }

    /** 调用 WeAgent 模型生成多条候选回复，附加上下文。 */
    private suspend fun generateCandidates(talker: String, content: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = WeAgentRepository.firstModelId() ?: return@withContext emptyList()
            val model = WeAgentRepository.getModel(modelId) ?: return@withContext emptyList()
            val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext emptyList()
            val client = ModelProviderManager.clientFor(provider)

            val count = replyCount.coerceIn(1, 20)
            val contextText = loadRecentContext(talker, contextLimit.coerceIn(1, 50))
            val systemPrompt = buildString {
                append("你是微信聊天助手。根据对方的最后一条消息，生成$count条得体的回复。")
                append("每条回复单独一行，不要序号，不要多余解释。")
                if (contextText.isNotBlank()) {
                    append("\n\n以下是最近聊天记录作参考：\n$contextText")
                }
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
            sb.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("1.") && !it.startsWith("2.") }
                .take(count)
                .ifEmpty { listOf(sb.toString().trim()) }
        } catch (e: Exception) {
            WeLogger.e(TAG, "generate candidates failed", e)
            emptyList()
        }
    }
}