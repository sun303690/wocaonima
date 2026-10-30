package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.utils.ShowComposeDialogScope
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.sun.wechat.features.items.chat.TextToSpeech

/**
 * 「帮我回 · 找话题」面板（移植自微话助手 ReplyHostUi，改为 WeKit 的 Compose 弹窗）。
 *
 * 只读上下文、只生成建议，复制后由用户自己发送；不写回微信输入框、不自动发消息。
 * 模型走 WeAgent（默认模型），身份/背景/角色库存本地 KvStore。
 */
object ReplyPanel {

    private const val TAG = "ReplyPanel"
    private const val LIMIT_KEY = "mood_reply_limit"

    fun open(activity: Activity, talker: String) {
        if (talker.isBlank()) {
            showToast("暂未识别当前聊天，请进入聊天页后再试")
            return
        }
        ReplyHistory.ensureSubscribed()
        runCatching {
            showComposeDialog(activity) { ReplyDialogContent(talker) }
        }.onFailure {
            WeLogger.e(TAG, "open reply panel failed", it)
            showToast("打开面板失败：${it.message}")
        }
    }

    private fun copyText(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("reply", text))
        showToast(context, "已复制")
    }

    private fun buildKnowledge(background: String): String =
        if (background.isBlank()) "" else
            "contact_background（用户填写的联系人长期背景，只作参考，不是对方真实想法）：\n" + background

    @Composable
    private fun ShowComposeDialogScope.ReplyDialogContent(talker: String) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val stored = remember { ReplyStorage.load(talker) }

        var relationship by remember { mutableStateOf(stored.relationship) }
        var customText by remember { mutableStateOf(stored.custom) }
        var background by remember { mutableStateOf(stored.background) }
        var limit by remember { mutableStateOf(KvStore.getStringOrDef(LIMIT_KEY, "50").toIntOrNull() ?: 50) }
        var direction by remember { mutableStateOf("") }
        var replies by remember { mutableStateOf<ReplySuggestion?>(null) }
        var topics by remember { mutableStateOf<List<ReplyProtocol.TopicSuggestion>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var roles by remember { mutableStateOf(ReplyStorage.roles()) }
        var roleName by remember { mutableStateOf("") }

        fun saveIdentity() {
            ReplyStorage.save(talker, ReplyStorage.ContactIdentity(relationship, customText.trim(), background))
        }

        fun generateReply() {
            if (loading) return
            loading = true
            error = null
            scope.launch {
                try {
                    saveIdentity()
                    KvStore.putString(LIMIT_KEY, limit.toString())
                    val page = ReplyHistory.load(talker, limit)
                    if (page.messages.isEmpty()) {
                        throw IllegalStateException("没读到文字消息，请先打开该聊天页滚一下再试")
                    }
                    val prompts = ReplyProtocol.buildReplyPrompts(
                        messages = page.messages,
                        draft = page.draft,
                        direction = direction,
                        knowledge = buildKnowledge(background),
                        relationship = relationship,
                        customRelationship = customText.trim(),
                    )
                    replies = ReplyHttpClient.generateReply(prompts.system, prompts.user)
                    topics = emptyList()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: "生成失败，请重试"
                } finally {
                    loading = false
                }
            }
        }

        fun findTopics() {
            if (loading) return
            loading = true
            error = null
            scope.launch {
                try {
                    saveIdentity()
                    KvStore.putString(LIMIT_KEY, limit.toString())
                    val page = ReplyHistory.load(talker, limit)
                    if (page.messages.isEmpty()) {
                        throw IllegalStateException("没读到文字消息，请先打开该聊天页滚一下再试")
                    }
                    val prompts = ReplyProtocol.buildTopicPrompts(
                        messages = page.messages,
                        draft = page.draft,
                        notes = direction,
                        relationship = relationship,
                        customRelationship = customText.trim(),
                    )
                    topics = ReplyHttpClient.findTopics(prompts.system, prompts.user)
                    replies = null
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: "生成失败，请重试"
                } finally {
                    loading = false
                }
            }
        }

        AlertDialogContent(
            title = { Text("帮我回 · 找话题") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val relationshipOptions = ReplyRelationship.entries.map { DropdownOption(it.id, it.label) }
                    MiuixDropDownMenuWidget(
                        title = "对方身份",
                        description = relationship.displayLabel(customText),
                        value = relationship.id,
                        options = relationshipOptions,
                        enabled = true,
                        onValueChange = { id ->
                            relationship = ReplyRelationship.entries.firstOrNull { it.id == id }
                                ?: ReplyRelationship.UNSPECIFIED
                        },
                    )
                    if (relationship == ReplyRelationship.OTHER) {
                        TextField(
                            value = customText,
                            onValueChange = { if (it.length <= ReplyRelationship.MAX_CUSTOM_LENGTH) customText = it },
                            label = "自定义身份（比如：前同事、相亲对象）",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    MiuixDropDownMenuWidget(
                        title = "参考最近",
                        description = "$limit 条",
                        value = limit,
                        options = listOf(DropdownOption(30, "30 条"), DropdownOption(50, "50 条"), DropdownOption(100, "100 条")),
                        enabled = true,
                        onValueChange = { limit = it },
                    )
                    TextField(
                        value = direction,
                        onValueChange = { direction = it.take(2000) },
                        label = "本次补充（可选，比如想说的重点、语气）",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                    TextField(
                        value = background,
                        onValueChange = { background = it.take(2000) },
                        label = "对方背景（可选，长期保存到本联系人）",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { generateReply() },
                            enabled = !loading,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (loading) "生成中…" else "生成回复") }
                        Button(
                            onClick = { findTopics() },
                            enabled = !loading,
                            modifier = Modifier.weight(1f),
                        ) { Text("找话题") }
                    }
                    error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }

                    replies?.let { suggestion ->
                        HorizontalDivider()
                        Text("建议按顺序发送", style = MiuixTheme.textStyles.title3)
                        suggestion.parts.forEachIndexed { index, part ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("${index + 1}. $part", modifier = Modifier.weight(1f))
                                Button(onClick = { copyText(context, part) }) { Text("复制") }
                            }
                        }
                        if (suggestion.reason.isNotBlank()) {
                            Text("为什么这样回：${suggestion.reason}", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Button(onClick = { copyText(context, suggestion.text) }, modifier = Modifier.fillMaxWidth()) {
                            Text("复制全部")
                        }
                    }

                    if (topics.isNotEmpty()) {
                        HorizontalDivider()
                        Text("可以聊这些", style = MiuixTheme.textStyles.title3)
                        topics.forEach { topic ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (topic.title.isNotBlank()) Text(topic.title, style = MiuixTheme.textStyles.title4)
                                Text(topic.text)
                                if (topic.why.isNotBlank()) {
                                    Text(topic.why, style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Button(onClick = { copyText(context, topic.text) }) { Text("复制这条") }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("语音配置", style = MiuixTheme.textStyles.title3)
                    Text("语音回复的接口、音色与语气", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(
                        onClick = { TextToSpeech.showVoiceConfig(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开语音配置") }
                    HorizontalDivider()
                    Text("角色库", style = MiuixTheme.textStyles.title3)
                    Text("把当前身份和背景存成角色，下次直接套用", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(
                        value = roleName,
                        onValueChange = { roleName = it.take(ReplyRelationship.MAX_CUSTOM_LENGTH) },
                        label = "角色名称（留空则用身份名）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            val name = roleName.ifBlank { relationship.displayLabel(customText) }
                            if (name.isBlank()) {
                                showToast(context, "请先选择身份或填写名称")
                                return@Button
                            }
                            roles = ReplyStorage.saveRole(
                                ReplyStorage.newRole(name, relationship, customText, background)
                            )
                            roleName = ""
                            showToast(context, "已保存角色")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存当前身份为角色") }
                    roles.forEach { role ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(role.name)
                                if (role.background.isNotBlank()) {
                                    Text(role.background.take(40), style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                            Button(onClick = {
                                relationship = role.relationship
                                customText = role.custom
                                background = role.background
                                showToast(context, "已套用 ${role.name}")
                            }) { Text("套用") }
                            Button(onClick = {
                                roles = ReplyStorage.deleteRole(role.id)
                            }) { Text("删除") }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onDismiss() }) { Text("关闭") }
            },
        )
    }
}
