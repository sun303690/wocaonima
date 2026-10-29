package dev.sun.wechat.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Checkbox
import dev.sun.wechat.ui.content.miuix.MiuixFilterChip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button as WeButton
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton as WeTextButton
import dev.sun.wechat.ui.content.WeTimeOfDayField
import dev.sun.wechat.ui.content.formatMinuteOfDay
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.android.showToast

/**
 * 定时群发：
 * - 标签把群打包，发消息按标签一键选定目标群
 * - 任务 = 消息内容 + 每天多个发送时间点(HH:mm，可添加多个) + 目标(标签/手选群)
 * - 每个时间点每天到点自动发一次，消息随时可改
 */
object MassSendMessage : ClickableFeature() {

    override val technicalId = "定时群发"
    override val nameRes = R.string.feature_mass_send_message_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_mass_send_message_description

    private const val TAG = "MassSendMessage"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        MassTaskStore.reload()
        MassTaskStore.startScheduler()
        showComposeDialog(context) {
            TaskListPage(
                context = context,
                onDismiss = onDismiss,
                onChanged = { MassTaskStore.saveTasks() },
            )
        }
    }

    // ================= 页面1：任务列表 =================

    @Composable
    private fun TaskListPage(
        context: Context,
        onDismiss: () -> Unit,
        onChanged: () -> Unit,
    ) {
        val tasks = MassTaskStore.tasks
        val tags = MassTaskStore.tags

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_mass_send_message_name)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (tags.isEmpty() && tasks.isEmpty()) {
                        Text(
                            stringResource(R.string.mass_task_empty_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    if (tags.isNotEmpty()) {
                        Text(stringResource(R.string.mass_task_tags_section), style = MiuixTheme.textStyles.title3)
                        tags.forEach { tag ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tag.name, style = MiuixTheme.textStyles.body2)
                                    Text(
                                        stringResource(R.string.mass_task_tag_group_count, tag.wxids.size),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Button(onClick = {
                                    showComposeDialog(context) {
                                        TagEditorPage(context, tag, onDismiss) { MassTaskStore.reload() }
                                    }
                                }) { Text(stringResource(R.string.mass_task_edit)) }
                                Button(onClick = {
                                    MassTaskStore.removeTag(tag.name)
                                    onChanged()
                                }) { Text(stringResource(R.string.mass_task_delete)) }
                            }
                        }
                    }

                    if (tasks.isNotEmpty()) {
                        Text(stringResource(R.string.mass_task_tasks_section), style = MiuixTheme.textStyles.title3)
                        tasks.forEach { task ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        task.sortedMinutes.joinToString(" ") { formatMinuteOfDay(it) } +
                                            " · " + task.content.take(20) + if (task.content.length > 20) "…" else "",
                                        style = MiuixTheme.textStyles.body2,
                                    )
                                    Text(
                                        stringResource(R.string.mass_task_targets_count, MassTaskStore.resolveTargets(task).size) +
                                            (if (!task.enabled) " · " + stringResource(R.string.mass_task_paused) else ""),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Button(onClick = {
                                    task.enabled = !task.enabled
                                    MassTaskStore.saveTasks()
                                    MassTaskStore.reload()
                                }) {
                                    Text(if (task.enabled) stringResource(R.string.mass_task_pause) else stringResource(R.string.mass_task_resume))
                                }
                                Button(onClick = {
                                    showComposeDialog(context) {
                                        TaskEditorPage(context, task, onDismiss) { MassTaskStore.reload() }
                                    }
                                }) { Text(stringResource(R.string.mass_task_edit)) }
                                Button(onClick = {
                                    MassTaskStore.removeTask(task.id)
                                    onChanged()
                                }) { Text(stringResource(R.string.mass_task_delete)) }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            showComposeDialog(context) {
                                TaskEditorPage(context, null, onDismiss) { MassTaskStore.reload() }
                            }
                        }) { Text(stringResource(R.string.mass_task_new_task)) }
                        Button(onClick = {
                            showComposeDialog(context) {
                                TagEditorPage(context, null, onDismiss) { MassTaskStore.reload() }
                            }
                        }) { Text(stringResource(R.string.mass_task_new_tag)) }
                    }
                }
            },
            dismissButton = { WeButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {},
        )
    }

    // ================= 页面2：任务编辑 =================

    @Composable
    private fun TaskEditorPage(
        context: Context,
        editing: MassTaskStore.MassTask?,
        onDismiss: () -> Unit,
        onDone: () -> Unit,
    ) {
        var mode by remember { mutableStateOf(editing?.mode ?: MassTaskStore.MODE_TEXT) }
        var content by remember { mutableStateOf(editing?.content ?: "") }
        var minutes by remember { mutableStateOf(editing?.minutes?.toSet() ?: setOf(8 * 60)) }
        var pendingMinute by remember { mutableStateOf(8 * 60) }
        var selectedTags by remember { mutableStateOf(editing?.tags?.toSet() ?: emptySet()) }
        var selectedWxids by remember { mutableStateOf(editing?.wxids?.toSet() ?: emptySet()) }

        AlertDialogContent(
            title = { Text(stringResource(if (editing == null) R.string.mass_task_new_task else R.string.mass_task_edit_task)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 消息类型
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            MassTaskStore.MODE_TEXT to R.string.batch_mass_send_text_mode,
                            MassTaskStore.MODE_CARD to R.string.batch_mass_send_card_mode,
                        ).forEach { (m, label) ->
                            MiuixFilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }

                    TextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.batch_mass_send_text_label),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8,
                    )

                    // 每天多个发送时间点：选时间 → 添加；chip 显示，可删
                    Text(stringResource(R.string.mass_task_daily_time_label), style = MiuixTheme.textStyles.title3)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeTimeOfDayField(
                            minuteOfDay = pendingMinute,
                            onMinuteChange = { pendingMinute = it },
                            label = stringResource(R.string.mass_task_daily_time_picker_label),
                            modifier = Modifier.weight(1f),
                        )
                        WeButton(onClick = {
                            minutes = minutes + pendingMinute
                        }) { Text(stringResource(R.string.mass_task_add_time)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        minutes.sorted().forEach { m ->
                            MiuixFilterChip(
                                selected = false,
                                onClick = { minutes = minutes - m },
                                label = { Text(formatMinuteOfDay(m) + " ✕") },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.mass_task_times_count, minutes.size),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    // 标签选择
                    Text(stringResource(R.string.mass_task_pick_tags), style = MiuixTheme.textStyles.title3)
                    if (MassTaskStore.tags.isEmpty()) {
                        Text(
                            stringResource(R.string.mass_task_no_tags_hint),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        MassTaskStore.tags.forEach { tag ->
                            Row(
                                modifier = Modifier.clickable {
                                    selectedTags = if (tag.name in selectedTags) {
                                        selectedTags - tag.name
                                    } else {
                                        selectedTags + tag.name
                                    }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = tag.name in selectedTags,
                                    onCheckedChange = null,
                                )
                                Column {
                                    Text(tag.name)
                                    Text(
                                        stringResource(R.string.mass_task_tag_group_count, tag.wxids.size),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }

                    // 手选群/好友
                    Text(stringResource(R.string.mass_task_pick_groups), style = MiuixTheme.textStyles.title3)
                    WeButton(onClick = {
                        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                        showComposeDialog(context) {
                            ContactsSelector(
                                title = context.localizedBatchString(R.string.batch_mass_send_select_title),
                                contacts = contacts,
                                initialSelectedWxIds = selectedWxids,
                                onDismiss = onDismiss,
                                onConfirm = { picked ->
                                    selectedWxids = picked
                                    onDismiss()
                                },
                            )
                        }
                    }) {
                        Text(stringResource(R.string.mass_task_pick_groups_button, selectedWxids.size))
                    }
                }
            },
            dismissButton = { WeButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                WeButton(onClick = {
                    if (content.isBlank()) {
                        showToast(context.localizedBatchString(R.string.batch_mass_send_enter_content))
                        return@WeButton
                    }
                    if (minutes.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.mass_task_need_time))
                        return@WeButton
                    }
                    val task = (editing ?: MassTaskStore.MassTask()).apply {
                        this.mode = mode
                        this.content = content
                        this.minutes = minutes.toMutableSet()
                        this.tags = selectedTags.toMutableSet()
                        this.wxids = selectedWxids.toMutableSet()
                        this.enabled = true
                    }
                    MassTaskStore.upsertTask(task)
                    MassTaskStore.startScheduler()
                    showToast(context.localizedBatchString(R.string.mass_task_saved))
                    onDismiss()
                    onDone()
                }) { Text(stringResource(R.string.mass_task_save)) }
            },
        )
    }

    // ================= 页面3：标签编辑 =================

    @Composable
    private fun TagEditorPage(
        context: Context,
        editing: MassTaskStore.MassTag?,
        onDismiss: () -> Unit,
        onDone: () -> Unit,
    ) {
        var name by remember { mutableStateOf(editing?.name ?: "") }
        var wxids by remember { mutableStateOf(editing?.wxids?.toSet() ?: emptySet()) }

        AlertDialogContent(
            title = { Text(stringResource(if (editing == null) R.string.mass_task_new_tag else R.string.mass_task_edit_tag)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.mass_task_tag_name_label),
                        singleLine = true,
                    )
                    WeButton(onClick = {
                        val groups = WeDatabaseApi.getGroups()
                        showComposeDialog(context) {
                            ContactsSelector(
                                title = context.localizedBatchString(R.string.mass_task_tag_pick_title),
                                contacts = groups,
                                initialSelectedWxIds = wxids,
                                onDismiss = onDismiss,
                                onConfirm = { picked ->
                                    wxids = picked
                                    onDismiss()
                                },
                            )
                        }
                    }) {
                        Text(stringResource(R.string.mass_task_tag_pick_groups_button, wxids.size))
                    }
                }
            },
            dismissButton = { WeButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            confirmButton = {
                WeButton(onClick = {
                    if (name.isBlank()) {
                        showToast(context.localizedBatchString(R.string.mass_task_tag_name_required))
                        return@WeButton
                    }
                    MassTaskStore.upsertTag(MassTaskStore.MassTag(name = name.trim(), wxids = wxids.toMutableSet()))
                    showToast(context.localizedBatchString(R.string.mass_task_saved))
                    onDismiss()
                    onDone()
                }) { Text(stringResource(R.string.mass_task_save)) }
            },
        )
    }
}
