package dev.sun.wechat.features.items.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.sun.wechat.R
import dev.sun.wechat.features.items.AutomationKeywordMode
import dev.sun.wechat.features.items.AutomationKeywordRule
import dev.sun.wechat.features.items.AutomationTimeRangeRule
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.WeTimeOfDayField
import dev.sun.wechat.ui.content.miuix.MiuixBaseItemContainer
import dev.sun.wechat.ui.content.miuix.MiuixBaseSupportingWidget
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumnScope
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget

/**
 * Free-form value being edited through an in-dialog edit view. The payment dialogs live in a
 * single [dev.sun.wechat.ui.utils.showComposeDialog] window, so text values swap the dialog
 * content instead of opening a second window.
 */
class PaymentTextEditMode(
    val title: String,
    val initial: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val filter: (String) -> String = { it },
    val supportingText: String? = null,
    val validator: ((String) -> String?)? = null,
    val onCommit: (String) -> Unit,
)

@Composable
fun PaymentTextEditDialog(
    mode: PaymentTextEditMode,
    onClose: () -> Unit,
) {
    var draft by remember(mode) { mutableStateOf(mode.initial) }
    val validationError = mode.validator?.invoke(draft)

    AlertDialogContent(
        title = { Text(mode.title) },
        text = {
            MiuixCompatTextField(
                value = draft,
                onValueChange = { draft = mode.filter(it) },
                keyboardOptions = KeyboardOptions(keyboardType = mode.keyboardType),
                supportingText = (validationError ?: mode.supportingText)?.let { text -> { Text(text) } },
                isError = validationError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = validationError == null,
                onClick = { mode.onCommit(draft); onClose() },
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Navigation entry inside a payment settings dialog, e.g. "Global settings" with a chevron. */
@Composable
fun PaymentNavigationRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    MiuixBaseWidget(
        iconPlaceholder = false,
        title = title,
        description = description,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = MaterialSymbols.Outlined.Chevron_right,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
}

/**
 * Switch row for one automation rule. [overridden] is null in the global editor (plain toggle);
 * in a per-contact editor it drives the follow-parent semantics: a non-activated rule shows the
 * parent summary, activates on row tap, and exposes a reset action once activated.
 */
@Composable
fun PaymentRuleRow(
    title: String,
    summary: String,
    checked: Boolean,
    overridden: Boolean?,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onCheckedChange: (Boolean) -> Unit,
) {
    if (overridden == null) {
        MiuixSwitchWidget(
            iconPlaceholder = false,
            title = title,
            description = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        return
    }

    MiuixBaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden) {
            summary
        } else {
            stringResource(R.string.automation_follow_parent, parentLabel, summary)
        },
        onClick = { if (overridden) onCheckedChange(!checked) else onActivate() },
        trailingDivider = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = overridden, onClick = onReset) {
                Text(stringResource(R.string.action_reset))
            }
            Switch(
                checked = checked,
                enabled = overridden,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/** Switch-less variant of [PaymentRuleRow] for rules whose value is a choice, not a toggle. */
@Composable
fun PaymentModeRuleRow(
    title: String,
    summary: String,
    overridden: Boolean?,
    parentLabel: String,
    onActivate: () -> Unit,
    onReset: () -> Unit,
) {
    MiuixBaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden == false) {
            stringResource(R.string.automation_follow_parent, parentLabel, summary)
        } else {
            summary
        },
        onClick = if (overridden == false) onActivate else null,
        trailingContent = {
            if (overridden != null) {
                Button(enabled = overridden, onClick = onReset) {
                    Text(stringResource(R.string.action_reset))
                }
            }
        },
    )
}

/** Row showing a stored value that is edited through an in-dialog [PaymentTextEditMode]. */
@Composable
fun PaymentValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    valueHint: String? = null,
    trailingIcon: ImageVector = MaterialSymbols.Outlined.Edit,
) {
    MiuixBaseWidget(
        iconPlaceholder = false,
        title = title,
        description = value.ifBlank { valueHint },
        enabled = enabled,
        onClick = onClick,
        trailingContent = { Icon(trailingIcon, null) },
    )
}

@Composable
fun PaymentErrorRow(message: String) {
    MiuixBaseWidget(iconPlaceholder = false, title = message, isError = true)
}

/** Time-range fields shown while the owning rule is enabled. */
fun MiuixSegmentedColumnScope.timeRangeItems(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    visible: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit,
) {
    item(key = "time_range_fields", animatedVisibility = visible) {
        MiuixBaseItemContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.automation_start),
                    minuteOfDay = rule.startMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(startMinute = it)) }
                )
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.automation_end),
                    minuteOfDay = rule.endMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(endMinute = it)) }
                )
            }
        }
    }
}

/** Keyword rule controls: match mode, ignore-case, keyword list / regex. */
fun MiuixSegmentedColumnScope.keywordItems(
    keyPrefix: String,
    rule: AutomationKeywordRule,
    editable: Boolean,
    visible: Boolean,
    modes: List<AutomationKeywordMode>,
    onChange: (AutomationKeywordRule) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
    inlineTextFields: Boolean = false,
) {
    item(key = "${keyPrefix}_mode", animatedVisibility = visible) {
        MiuixDropDownMenuWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.automation_keyword_mode_label),
            description = null,
            value = rule.mode,
            options = modes.map { mode ->
                DropdownOption(
                    value = mode,
                    label = stringResource(
                        when (mode) {
                            AutomationKeywordMode.STRING_LIST -> R.string.automation_keyword_mode_contains
                            AutomationKeywordMode.EXACT -> R.string.automation_keyword_mode_exact
                            AutomationKeywordMode.REGEX -> R.string.automation_keyword_mode_regex
                        }
                    ),
                )
            },
            enabled = editable,
            onValueChange = { onChange(rule.copy(mode = it)) },
        )
    }
    item(key = "${keyPrefix}_ignore_case", animatedVisibility = visible) {
        MiuixSwitchWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.automation_ignore_case),
            checked = rule.ignoreCase,
            enabled = editable,
            onCheckedChange = { onChange(rule.copy(ignoreCase = it)) },
        )
    }
    if (rule.mode == AutomationKeywordMode.REGEX) {
        item(key = "${keyPrefix}_regex", animatedVisibility = visible) {
            val regexTitle = stringResource(R.string.automation_keyword_mode_regex)
            val invalidRegexMessage = stringResource(R.string.automation_regex_invalid)
            val regexError = invalidRegexMessage.takeIf { runCatching { Regex(rule.regex) }.isFailure }
            if (inlineTextFields) {
                MiuixBaseSupportingWidget(
                    title = regexTitle,
                    description = stringResource(R.string.automation_regex_empty_summary),
                ) {
                    MiuixCompatTextField(
                        value = rule.regex,
                        enabled = editable,
                        onValueChange = { onChange(rule.copy(regex = it)) },
                        supportingText = regexError?.let { error -> { Text(error) } },
                        isError = regexError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            } else {
                PaymentValueRow(
                    title = regexTitle,
                    value = rule.regex,
                    enabled = editable,
                    valueHint = stringResource(R.string.automation_regex_empty_summary),
                    onClick = {
                        onEditText(
                            PaymentTextEditMode(
                                title = regexTitle,
                                initial = rule.regex,
                                validator = { input ->
                                    invalidRegexMessage.takeIf { runCatching { Regex(input) }.isFailure }
                                },
                                onCommit = { onChange(rule.copy(regex = it)) },
                            )
                        )
                    },
                )
            }
        }
        return
    }
    rule.strings.forEach { keyword ->
        item(key = "${keyPrefix}_entry_$keyword", animatedVisibility = visible) {
            MiuixBaseWidget(
                iconPlaceholder = false,
                title = keyword,
                enabled = editable,
                trailingContent = {
                    IconButton(enabled = editable, onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }) {
                        Icon(MaterialSymbols.Outlined.Delete, null)
                    }
                },
            )
        }
    }
    item(key = "${keyPrefix}_add", animatedVisibility = visible) {
        val addTitle = stringResource(R.string.automation_new_keyword)
        if (inlineTextFields) {
            var pendingKeyword by remember { mutableStateOf("") }
            MiuixBaseSupportingWidget(title = addTitle) {
                MiuixCompatTextField(
                    value = pendingKeyword,
                    enabled = editable,
                    onValueChange = { pendingKeyword = it },
                    trailingIcon = {
                        IconButton(
                            enabled = editable && pendingKeyword.trim().isNotEmpty(),
                            onClick = {
                                val keyword = pendingKeyword.trim()
                                if (keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                                pendingKeyword = ""
                            },
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, null)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else {
            PaymentValueRow(
                title = addTitle,
                value = "",
                enabled = editable,
                onClick = {
                    onEditText(
                        PaymentTextEditMode(
                            title = addTitle,
                            initial = "",
                            onCommit = { input ->
                                val keyword = input.trim()
                                if (keyword.isNotEmpty() && keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                            },
                        )
                    )
                },
                trailingIcon = MaterialSymbols.Outlined.Add,
            )
        }
    }
}

/** Delay value rows shown while the delay rule is enabled. */
fun MiuixSegmentedColumnScope.delayItems(
    baseMs: String,
    randomRangeMs: String,
    editable: Boolean,
    visible: Boolean,
    maxDigits: Int,
    onBaseChange: (String) -> Unit,
    onRandomRangeChange: (String) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
) {
    val digitFilter: (String) -> String = { it.filter(Char::isDigit).take(maxDigits) }

    item(key = "delay_base", animatedVisibility = visible) {
        val baseTitle = stringResource(R.string.automation_base_delay_ms)
        PaymentValueRow(
            title = baseTitle,
            value = baseMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
                        title = baseTitle,
                        initial = baseMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onBaseChange,
                    )
                )
            },
        )
    }
    item(key = "delay_random", animatedVisibility = visible) {
        val randomTitle = stringResource(R.string.automation_random_delay_ms)
        PaymentValueRow(
            title = randomTitle,
            value = randomRangeMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
                        title = randomTitle,
                        initial = randomRangeMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onRandomRangeChange,
                    )
                )
            },
        )
    }
}
