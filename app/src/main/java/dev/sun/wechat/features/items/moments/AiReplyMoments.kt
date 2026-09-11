package dev.sun.wechat.features.items.moments

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.ui.WeMomentsApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.preferences.WePrefs
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 回复朋友圈（独立功能，移植自 Nuke，不挂到 WeKit 朋友圈自动化框架）：
 *  - 监听朋友圈动态(SnsInfo 表 insert/update)，也扫描本地缓存
 *  - 名单模式：全部 / 白名单(仅回复选中) / 黑名单(跳过选中)
 *  - 用 WeAgent 模型库生成评论(系统提示词可自定义)，截断到最大评论长度
 *  - 通过 WeMomentsApi.comment() 发送(type=2 评论)
 */
object AiReplyMoments : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "AI回复朋友圈"
    override val nameRes = R.string.feature_ai_reply_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_ai_reply_moments_description

    private const val TAG = "AiReplyMoments"
    private const val RETRY_INTERVAL_MS = 30_000L

    private const val DEFAULT_PROMPT =
        "你是微信朋友圈评论助手。根据用户的朋友圈内容，生成一句自然、贴切、有礼貌的中文评论。" +
            "只输出评论本身，不要解释，不要引号。"

    // ---- 配置 ----
    var prompt by WePrefs.prefOption("ai_reply_moments_prompt", "")
    var temperature by WePrefs.prefOption("ai_reply_moments_temperature", 0.7f)
    var maxTokens by WePrefs.prefOption("ai_reply_moments_max_tokens", 128)
    var maxCommentLength by WePrefs.prefOption("ai_reply_moments_max_comment_length", 200)
    var listMode by WePrefs.prefOption("ai_reply_moments_list_mode", 0) // 0=全部 1=白名单 2=黑名单
    var replyIntervalMs by WePrefs.prefOption("ai_reply_moments_interval_ms", 0L)

    private var whitelist: Set<String>
        get() = WePrefs.getStringSetOrDef(KEY_WHITELIST, emptySet())
        set(v) = WePrefs.putStringSet(KEY_WHITELIST, v)

    private var blacklist: Set<String>
        get() = WePrefs.getStringSetOrDef(KEY_BLACKLIST, emptySet())
        set(v) = WePrefs.putStringSet(KEY_BLACKLIST, v)

    private const val KEY_WHITELIST = "ai_reply_moments_whitelist"
    private const val KEY_BLACKLIST = "ai_reply_moments_blacklist"

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastActionSentAt = 0L

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        handledSnsIds.clear()
        lastAttemptAt.clear()
        scanCachedMoments()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        handledSnsIds.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    // ---------------- 检测 ----------------

    override fun onInsert(table: String, values: ContentValues) = processSnsInfoValues(table, values)

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) = processSnsInfoValues(table, values)

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processAsync(snsInfo)
    }

    private fun scanCachedMoments() {
        scope.launch {
            runCatching {
                WeMomentsApi.rawQuerySnsInfo(
                    """
                    SELECT snsId FROM SnsInfo
                    WHERE snsId != 0
                      AND (sourceType & ${WeMomentsApi.ACTIVE_SOURCE_MASK}) != 0
                      AND (sourceType & ${WeMomentsApi.AD_SOURCE_FLAG}) = 0
                    ORDER BY createTime DESC
                    """.trimIndent()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val snsId = cursor.getLong(0)
                        WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { processAsync(it) }
                    }
                }
            }.onFailure { WeLogger.w(TAG, "scan cached moments failed", it) }
        }
    }

    private fun processAsync(snsInfo: Any) {
        scope.launch {
            runCatching { processSnsInfo(snsInfo) }
                .onFailure { WeLogger.w(TAG, "process moments failed", it) }
        }
    }

    private suspend fun processSnsInfo(snsInfo: Any) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (!matchesListMode(owner)) return
        if (WeMomentsApi.isDeleted(snsInfo)) return
        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        if (snsTableId in handledSnsIds) return
        if (!canAttempt(snsTableId)) return

        val content = WeMomentsApi.getContentText(snsInfo).orEmpty().trim()
        if (content.isBlank()) {
            // 纯图片/视频动态：没有文字，用占位说明
            // 仍可评论，但交给 AI 用空内容兜底
        }

        val text = runCatching { generateComment(content) }.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return

        val result = synchronized(actionLock) {
            if (replyIntervalMs > 0L) {
                val wait = replyIntervalMs - (System.currentTimeMillis() - lastActionSentAt)
                if (wait > 0L) Thread.sleep(wait)
            }
            val r = WeMomentsApi.comment(snsInfo, text)
            if (r.sent) lastActionSentAt = System.currentTimeMillis()
            r
        }
        if (result.success) {
            handledSnsIds.add(snsTableId)
            WeLogger.i(TAG, "AI commented moments owner=$owner sns=$snsTableId")
        } else {
            WeLogger.w(TAG, "AI comment failed owner=$owner sns=$snsTableId msg=${result.message}")
        }
    }

    private fun matchesListMode(owner: String): Boolean = when (listMode) {
        1 -> owner in whitelist
        2 -> owner !in blacklist
        else -> true
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    // ---------------- AI ----------------

    private suspend fun generateComment(content: String): String = withContext(Dispatchers.IO) {
        try {
            val modelId = WeAgentRepository.firstModelId() ?: return@withContext ""
            val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
            val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
            val client = ModelProviderManager.clientFor(provider)
            val sysPrompt = prompt.ifBlank { DEFAULT_PROMPT }
            val userContent = if (content.isBlank()) {
                "用户发了一条朋友圈（无文字内容），请生成一条合适的评论。"
            } else {
                "朋友圈内容：$content"
            }
            val messages = listOf(
                LlmMessage(LlmRole.SYSTEM, sysPrompt),
                LlmMessage(LlmRole.USER, userContent),
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
            sb.toString().trim().take(maxCommentLength.coerceIn(1, 2000))
        } catch (e: Exception) {
            WeLogger.e(TAG, "generate comment failed", e)
            ""
        }
    }

    // ---------------- 配置 UI ----------------

    private fun showConfigDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val scope = rememberCoroutineScope()
            var promptInput by remember { mutableStateOf(prompt) }
            var tempInput by remember { mutableStateOf(temperature.toString()) }
            var tokensInput by remember { mutableStateOf(maxTokens.toString()) }
            var lengthInput by remember { mutableStateOf(maxCommentLength.toString()) }
            var modeInput by remember { mutableStateOf(listMode) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_ai_reply_moments_name)) },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = { Text(stringResource(R.string.aim_prompt_label)) },
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                        )
                        OutlinedTextField(
                            value = lengthInput,
                            onValueChange = { lengthInput = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = { Text(stringResource(R.string.aim_max_comment_length_label)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = tokensInput,
                            onValueChange = { tokensInput = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = { Text(stringResource(R.string.aim_max_tokens_label)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = tempInput,
                            onValueChange = { tempInput = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            label = { Text(stringResource(R.string.aim_temperature_label)) },
                            singleLine = true,
                        )
                        Text(stringResource(R.string.aim_list_mode_title))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "全部", 1 to "白名单", 2 to "黑名单").forEach { (mode, label) ->
                                FilterChip(
                                    selected = modeInput == mode,
                                    onClick = { modeInput = mode },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        prompt = promptInput.trim()
                        maxCommentLength = lengthInput.toIntOrNull()?.coerceIn(1, 2000) ?: 200
                        maxTokens = tokensInput.toIntOrNull()?.coerceIn(1, 32768) ?: 128
                        temperature = tempInput.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f
                        listMode = modeInput.coerceIn(0, 2)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }
}
