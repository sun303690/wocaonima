package dev.sun.wechat.features.items.chat_mood

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeChatMessageContextMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.items.chat.AiSmartReply
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger

/**
 * 情绪分析：文字气泡下方显示情绪 / 潜台词 / 沟通建议。
 * 对应 Yanwai 的 MessageSniffer + BubbleDecorator。走 KSP FeaturesScanner 自动注册。
 * 点击可配置 Jev/TypeSafe 渠道。
 */
object MoodFeature : ClickableFeature() {

    override val technicalId = "情绪分析"
    override val nameRes = R.string.mood_feature_name
    override val descriptionRes = R.string.mood_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override fun onEnable() {
        MoodAnalyzer.enabled = true
        MessageSniffer.ensureSubscribed()
        // 情绪分析入口：＋/发送键长按菜单条目 + 帮我回长按消息菜单
        WeChatInputBarMenuApi.addProvider(ChatMoodHostUi)
        WeChatMessageContextMenuApi.addProvider(AiSmartReply)
        // 帮我回 · 找话题入口（长按消息 / 输入栏 ＋ 菜单）
        WeChatInputBarMenuApi.addProvider(ReplyEntry)
        WeChatMessageContextMenuApi.addProvider(ReplyEntry)
        ReplyHistory.ensureSubscribed()
        WeLogger.i(TAG, "情绪分析已启用")
    }

    override fun onDisable() {
        MoodAnalyzer.enabled = false
        BubbleDecorator.clearAll()
        WeChatInputBarMenuApi.removeProvider(ChatMoodHostUi)
        WeChatMessageContextMenuApi.removeProvider(AiSmartReply)
        WeChatInputBarMenuApi.removeProvider(ReplyEntry)
        WeChatMessageContextMenuApi.removeProvider(ReplyEntry)
        ReplyAuto.clear()
        MoodStore.clear()
        WeLogger.i(TAG, "情绪分析已停用")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            var jevEnabled by remember { mutableStateOf(MoodTransport.jevEnabled) }
            var jevProvider by remember { mutableStateOf(MoodTransport.jevProviderId) }
            var jevKey by remember { mutableStateOf(MoodTransport.jevKey) }
            var jevEndpoint by remember { mutableStateOf(MoodTransport.jevEndpoint) }
            var jevModel by remember { mutableStateOf(MoodTransport.jevModel) }

            var autoReply by remember { mutableStateOf(ReplyAuto.enabled) }
            val models by remember { WeAgentRepository.observeModels() }
                .collectAsState(initial = emptyList())
            var defaultModelId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { defaultModelId = WeAgentRepository.resolvedDefaultModelId() }
            val activeModelName = models.firstOrNull { it.id == defaultModelId }
                ?.let { it.displayName.ifBlank { it.modelIdRemote } }

            AlertDialogContent(
                title = { Text("言外 · 情绪分析 & 帮我回") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // ===== Jev 情绪分析 =====
                        Text("情绪分析（Jev）", style = MiuixTheme.textStyles.title3)
                        MiuixSwitchWidget(
                            title = "启用 Jev 情绪分析",
                            checked = jevEnabled,
                            onCheckedChange = { jevEnabled = it },
                            summary = "关闭后不再调用 Jev 模型，消息下方不会显示情绪分析",
                        )
                        val jevOpts = JevProvider.entries.map { DropdownOption(it.id, it.label) }
                        MiuixDropDownMenuWidget(
                            title = "渠道",
                            description = JevProvider.entries.firstOrNull { it.id == jevProvider }?.label ?: jevProvider,
                            value = jevProvider,
                            options = jevOpts,
                            enabled = jevEnabled,
                            onValueChange = { jevProvider = it },
                        )
                        // 显示当前渠道的官网注册链接
                        val currentProvider = JevProvider.entries.firstOrNull { it.id == jevProvider }
                        if (currentProvider != null && currentProvider.website.isNotBlank()) {
                            val url = currentProvider.website
                            Button(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            }, enabled = jevEnabled) {
                                Text("👉 去 ${currentProvider.label} 注册获取 API Key", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                            }
                        }
                        TextField(
                            value = jevKey,
                            onValueChange = { jevKey = it },
                            label = "API Key",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = jevEnabled,
                        )
                        if (jevProvider == "custom") {
                            TextField(
                                value = jevEndpoint,
                                onValueChange = { jevEndpoint = it },
                                label = "接口地址",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = jevEnabled,
                            )
                            TextField(
                                value = jevModel,
                                onValueChange = { jevModel = it },
                                label = "模型名",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = jevEnabled,
                            )
                        }
                        HorizontalDivider()

                        // ===== 帮我回 · 找话题 =====
                        Text("帮我回 · 找话题", style = MiuixTheme.textStyles.title3)
                        Text("回复和话题生成使用 WeAgent 已配置的聊天模型", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(
                            if (activeModelName != null) "使用 WeAgent 模型：$activeModelName（可在 WeAgent 设置里切换）"
                            else "尚未在 WeAgent 里配置模型，请先去 WeAgent 设置添加",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (activeModelName != null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
                        )
                        MiuixSwitchWidget(
                            title = "对方新消息自动帮我回",
                            checked = autoReply,
                            onCheckedChange = { autoReply = it },
                            summary = "在已开启分析的聊天里，对方发来消息后，消息下方直接给出可点击填入输入框的回复；不会自动发送",
                        )
                        Button(onClick = {
                            AiSmartReply.openSmartReply(context, WeCurrentConversationApi.value)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("打开帮我回")
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        MoodTransport.jevEnabled = jevEnabled
                        MoodTransport.jevProviderId = jevProvider
                        MoodTransport.jevKey = jevKey.trim()
                        MoodTransport.jevEndpoint = jevEndpoint.trim()
                        MoodTransport.jevModel = jevModel.trim()
                        ReplyAuto.enabled = autoReply
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { Button(onClick = { onDismiss() }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private const val TAG = "MoodFeature"
}
