package dev.sun.wechat.features.items.chat_mood

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.android.showToast

/**
 * 「自动帮我回」的常驻设置入口（独立于「情绪分析」功能行）。
 *
 * 这里只负责两件事：
 *  - 常驻注册输入栏菜单里的逐聊天开关 [ReplyAutoHostUi]，并保证消息监听已订阅；
 *    这样即使「情绪分析」整行停用，帮回也照常按聊天生效。
 *  - 提供一个设置页：全局开关 + 关闭所有已开启聊天的按钮。
 * 具体某个好友/群要不要出回复建议，还是用聊天页里的「自动帮我回」开关决定。
 */
object AutoReplyEntry : ClickableFeature() {

    override val technicalId = "自动帮我回"
    override val nameRes = R.string.feature_auto_reply_entry_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_reply_entry_description

    /** 常驻启用：设置行只是入口，不靠开关控制。 */
    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override fun onEnable() {
        // 情绪分析关掉后，这条监听就是卡片唯一的来源
        MessageSniffer.ensureSubscribed()
        WeChatInputBarMenuApi.addProvider(ReplyAutoHostUi)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(ReplyAutoHostUi)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            var autoReply by remember { mutableStateOf(ReplyAuto.enabled) }
            AlertDialogContent(
                title = { Text("自动帮我回") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiuixSwitchWidget(
                            title = "对方新消息自动帮我回",
                            checked = autoReply,
                            onCheckedChange = { autoReply = it },
                            summary = "打开后，在目标聊天里长按输入栏 ＋（或发送键）→「自动帮我回」开关当前聊天；只在这些聊天里出回复建议，不会自动发送",
                        )
                        Text(
                            "「情绪分析」可以整行关掉，这个功能独立生效；想管控某个好友或群，就用上面说的逐聊天开关。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Button(
                            onClick = {
                                ConversationSwitches.clearReplyEnabled()
                                BubbleDecorator.clearAll()
                                showToast(context, "已关闭所有聊天的自动帮我回")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("关闭所有已开启的聊天") }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        ReplyAuto.enabled = autoReply
                        if (autoReply) {
                            MessageSniffer.ensureSubscribed()
                        } else {
                            // 关掉时清掉缓存的建议并重画，别让消息下方继续挂着旧的回复建议
                            ReplyAuto.clear()
                            BubbleDecorator.refreshCards()
                        }
                        showToast(
                            context,
                            if (autoReply) "已开启，请在目标聊天里用「自动帮我回」开关该聊天" else "已关闭自动帮我回",
                        )
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }
}
