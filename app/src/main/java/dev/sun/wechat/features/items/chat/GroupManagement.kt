package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeGroupApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.preferences.WePrefs
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 群链接/小程序守卫（白名单群自动处理）。
 *
 * 规格（按需求确认）：
 *  - 只处理**白名单群**：不在名单里的群完全忽略
 *  - 检测四类内容：纯文本里的 URL、链接卡片、小程序卡片、联系人名片（可分别开关）
 *  - 夜间禁言时段（默认 23:00–07:00，可关可改）：时段内在该群发**任何**消息的成员直接移出
 *  - **每人冷却**：同一人在同一群 cooldownMs 内只处理一次
 *  - **自动发提示**：踢人后在群里发一条提示（文本可改，可关）
 *  - **被踢者加入黑名单**：已在黑名单的人再次命中时跳过冷却，直接踢
 *  - 动作可关：只检测不踢（`kickEnabled = false` 时仅发提示/记日志）
 *
 * 说明：微信没有"管理员撤回他人消息"的通道（8.0.74 里只有自己的 NetSceneRevokeMsg），
 * 所以这里只能移出群聊；[WeGroupApi.delMembers] 是 fire-and-forget，无结果回调，
 * 只能以"是否抛异常"判断是否已发出。
 */
object GroupManagement : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "群管理"
    override val nameRes = R.string.feature_glg_name
    override val descriptionRes = R.string.feature_glg_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    private const val TAG = "GroupManagement"

    private const val ACTION_KICK_AND_HINT = 0
    private const val ACTION_KICK_ONLY = 1
    private const val ACTION_HINT_ONLY = 2

    private const val REASON_NIGHT = "night_silence"

    private const val DEFAULT_HINT = "群内禁止发送链接和小程序，已自动移出群聊。"

    // ---- 配置 ----
    var groups by WePrefs.prefOption("glg_groups_json", "")
    var banned by WePrefs.prefOption("glg_banned_json", "")
    var exempt by WePrefs.prefOption("glg_exempt_json", "")
    var cooldownMs by WePrefs.prefOption("glg_cooldown_ms", 60_000L)
    var action by WePrefs.prefOption("glg_action", ACTION_KICK_AND_HINT)
    var hintText by WePrefs.prefOption("glg_hint_text", DEFAULT_HINT)
    var detectTextLink by WePrefs.prefOption("glg_detect_text_link", true)
    var detectCardLink by WePrefs.prefOption("glg_detect_card_link", true)
    var detectMiniApp by WePrefs.prefOption("glg_detect_miniapp", true)
    var detectContactCard by WePrefs.prefOption("glg_detect_contact_card", true)
    var nightEnabled by WePrefs.prefOption("glg_night_enabled", false)
    var nightStartHour by WePrefs.prefOption("glg_night_start_hour", 23)
    var nightEndHour by WePrefs.prefOption("glg_night_end_hour", 7)
    var nightHintText by WePrefs.prefOption("glg_night_hint_text", "禁言时段内发言，已自动移出群聊。")

    // 名单统一以 
 连接存成字符串：SharedPreferences 的 StringSet 返回的是共享实例，
    // 直接改会踩到"编辑后集合未生效"的老坑，所以这里自己序列化。
    private fun loadSet(raw: String): Set<String> =
        raw.lineSequence().filter { it.isNotBlank() }.toSet()

    private fun saveSet(values: Set<String>): String = values.joinToString("\n")

    private val groupIds get() = loadSet(groups)
    private val bannedIds get() = loadSet(banned)
    private val exemptIds get() = loadSet(exempt)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastHandledAt = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        lastHandledAt.clear()
    }

    // ---------------- 检测 ----------------

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (groupIds.isEmpty()) return

        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.endsWith("@chatroom")) return
        if (talker !in groupIds) return

        val type = values.getAsInteger("type") ?: return
        val content = values.getAsString("content") ?: return

        val sender = senderOf(content) ?: return
        if (sender == WeApi.selfWxId || sender.isBlank()) return
        if (sender in exemptIds) return

        // 夜间禁言时段内不看内容, 发任何消息都算违规
        val reason = (if (nightEnabled && inSilenceWindow()) REASON_NIGHT else null) ?: classify(type, content) ?: return
        if (!allowHandle(talker, sender)) return

        scope.launch { handle(talker, sender, reason) }
    }

    /** 群消息在 DB 里是 `发送者wxId:\n正文`；取不到发送者就没法踢人，直接放弃。 */
    private fun senderOf(content: String): String? {
        val index = content.indexOf(":\n")
        if (index <= 0 || index > 80) return null
        val candidate = content.substring(0, index)
        return candidate.takeIf { it.none { c -> c.isWhitespace() || c == '<' } }
    }

    /** 返回命中原因；null 表示不违规。 */
    private fun classify(type: Int, content: String): String? {
        val body = content.substringAfter(":\n")
        return when (type) {
            1 -> if (detectTextLink && URL_PATTERN.containsMatchIn(body)) "text_url" else null

            33 -> if (detectMiniApp) "miniapp" else null

            42 -> if (detectContactCard) "contact_card" else null

            5, 49, 16777265 -> when {
                detectMiniApp && (body.contains("<weappinfo") || body.contains("weapp")) -> "miniapp"
                detectCardLink && (body.contains("<url>") || body.contains("http://") || body.contains("https://")) -> "link_card"
                else -> null
            }

            else -> null
        }
    }

    /** 跨午夜窗口: start=23,end=7 → [23,24) ∪ [0,7)。start==end 视为全天禁言。 */
    private fun inSilenceWindow(): Boolean {
        val start = nightStartHour.coerceIn(0, 23)
        val end = nightEndHour.coerceIn(0, 23)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end
    }

    /** 冷却：黑名单里的人（惯犯）不受冷却限制。 */
    private fun allowHandle(talker: String, sender: String): Boolean {
        if (sender in bannedIds) return true
        val now = System.currentTimeMillis()
        val key = "$talker|$sender"
        val last = lastHandledAt[key] ?: 0L
        if (now - last < cooldownMs.coerceAtLeast(0L)) return false
        lastHandledAt[key] = now
        return true
    }

    // ---------------- 处理 ----------------

    private fun handle(talker: String, sender: String, reason: String) {
        runCatching {
            if (action != ACTION_HINT_ONLY) {
                WeGroupApi.delMember(talker, sender)
                banned = saveSet(bannedIds + sender)
                WeLogger.i(TAG, "kicked $sender from $talker (reason=$reason)")
            }
            val hint = if (reason == REASON_NIGHT) nightHintText else hintText
            if (action != ACTION_KICK_ONLY && hint.isNotBlank()) {
                WeMessageApi.sendText(talker, hint.trim())
            }
        }.onFailure { WeLogger.e(TAG, "guard handling failed for $sender in $talker", it) }
    }

    // ---------------- 设置界面 ----------------

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var groupsInput by remember { mutableStateOf(groupIds) }
            var bannedInput by remember { mutableStateOf(bannedIds) }
            var exemptInput by remember { mutableStateOf(exemptIds) }
            var cooldownInput by remember { mutableStateOf(cooldownMs.toString()) }
            var actionInput by remember { mutableStateOf(action) }
            var hintInput by remember { mutableStateOf(hintText) }
            var textLink by remember { mutableStateOf(detectTextLink) }
            var cardLink by remember { mutableStateOf(detectCardLink) }
            var miniApp by remember { mutableStateOf(detectMiniApp) }
            var contactCard by remember { mutableStateOf(detectContactCard) }
            var night by remember { mutableStateOf(nightEnabled) }
            var nightStart by remember { mutableStateOf(nightStartHour.toString()) }
            var nightEnd by remember { mutableStateOf(nightEndHour.toString()) }
            var nightHint by remember { mutableStateOf(nightHintText) }

            fun openGroupPicker() {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(R.string.glg_pick_groups),
                        contacts = WeDatabaseApi.getGroups(),
                        initialSelectedWxIds = groupsInput,
                        onDismiss = onDismiss,
                    ) { selected -> groupsInput = groupsInput + selected }
                }
            }

            fun openPersonPicker(targetIsBanned: Boolean) {
                showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(
                            if (targetIsBanned) R.string.glg_configure_banned else R.string.glg_configure_exempt,
                        ),
                        contacts = WeDatabaseApi.getFriends(),
                        initialSelectedWxIds = if (targetIsBanned) bannedInput else exemptInput,
                        onDismiss = onDismiss,
                    ) { selected ->
                        if (targetIsBanned) bannedInput = bannedInput + selected else exemptInput = exemptInput + selected
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.glg_config_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_whitelisted_groups),
                                    description = stringResource(R.string.glg_groups_desc, groupsInput.size),
                                    onClick = { openGroupPicker() },
                                )
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_hint_text),
                                    value = hintInput,
                                    onValueChange = { hintInput = it },
                                    description = stringResource(R.string.glg_hint_text_desc),
                                    singleLine = false,
                                )
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_cooldown),
                                    value = cooldownInput,
                                    onValueChange = { cooldownInput = it.filter { c -> c.isDigit() } },
                                    description = stringResource(R.string.glg_cooldown_desc),
                                )
                            }
                            item {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_action),
                                    description = null,
                                    value = actionInput,
                                    options = listOf(
                                        DropdownOption(ACTION_KICK_AND_HINT, stringResource(R.string.glg_action_kick_hint)),
                                        DropdownOption(ACTION_KICK_ONLY, stringResource(R.string.glg_action_kick)),
                                        DropdownOption(ACTION_HINT_ONLY, stringResource(R.string.glg_action_hint)),
                                    ),
                                    onValueChange = { actionInput = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_detect_text),
                                    description = stringResource(R.string.glg_detect_text_desc),
                                    checked = textLink,
                                    onCheckedChange = { textLink = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_detect_card),
                                    checked = cardLink,
                                    onCheckedChange = { cardLink = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_detect_miniapp),
                                    checked = miniApp,
                                    onCheckedChange = { miniApp = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_detect_contact_card),
                                    description = stringResource(R.string.glg_detect_contact_card_desc),
                                    checked = contactCard,
                                    onCheckedChange = { contactCard = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_night_title),
                                    description = stringResource(R.string.glg_night_desc),
                                    checked = night,
                                    onCheckedChange = { night = it },
                                )
                            }
                            item {
                                Row(Modifier.fillMaxWidth()) {
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_night_start),
                                            value = nightStart,
                                            onValueChange = { v -> nightStart = v.filter { c -> c.isDigit() }.take(2) },
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        FieldRow(
                                            label = stringResource(R.string.glg_night_end),
                                            value = nightEnd,
                                            onValueChange = { v -> nightEnd = v.filter { c -> c.isDigit() }.take(2) },
                                        )
                                    }
                                }
                            }
                            item {
                                FieldRow(
                                    label = stringResource(R.string.glg_night_hint),
                                    value = nightHint,
                                    onValueChange = { nightHint = it },
                                    description = stringResource(R.string.glg_night_hint_desc),
                                    singleLine = false,
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_configure_banned),
                                    description = stringResource(R.string.glg_banned_desc, bannedInput.size),
                                    onClick = { openPersonPicker(true) },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.glg_configure_exempt),
                                    description = stringResource(R.string.glg_exempt_desc, exemptInput.size),
                                    onClick = { openPersonPicker(false) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button({
                        val cooldown = cooldownInput.toLongOrNull()?.coerceIn(0L, 3_600_000L) ?: cooldownMs
                        groups = saveSet(groupsInput)
                        banned = saveSet(bannedInput)
                        exempt = saveSet(exemptInput)
                        cooldownMs = cooldown
                        action = actionInput
                        hintText = hintInput
                        detectTextLink = textLink
                        detectCardLink = cardLink
                        detectMiniApp = miniApp
                        detectContactCard = contactCard
                        nightEnabled = night
                        nightStartHour = (nightStart.toIntOrNull() ?: nightStartHour).coerceIn(0, 23)
                        nightEndHour = (nightEnd.toIntOrNull() ?: nightEndHour).coerceIn(0, 23)
                        nightHintText = nightHint
                        lastHandledAt.clear()
                        WeLogger.i(TAG, "config saved: groups=${groupsInput.size} cooldown=$cooldown action=$actionInput")
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        description: String? = null,
        singleLine: Boolean = true,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private val URL_PATTERN = Regex("""https?://\S+|www\.[^\s]+""")
}
