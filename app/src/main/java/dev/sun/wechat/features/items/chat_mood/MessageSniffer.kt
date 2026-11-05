package dev.sun.wechat.features.items.chat_mood

import android.view.View
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque

/**
 * 消息挂钩（对应 Yanwai MessageSniffer，改用 WeKit 的 WeChatMessageViewApi）：
 * 监听聊天消息行 View 绑定，逐条提交情绪分析并装饰卡片；按会话维护最近文字消息做上下文。
 */
object MessageSniffer {
    private val TAG = "MessageSniffer"
    private val subscribed = AtomicBoolean(false)
    private val recentByTalker = HashMap<String, ArrayDeque<Pair<String, String>>>()

    private val lifecycle = object : WeChatMessageViewApi.IMessageViewLifecycleListener {
        override fun onMessageViewAttached(view: View, message: MessageInfo) {
            try {
                // 只开「自动帮我回」时也要跑，否则消息下方不会有回复建议卡片
                if (!MoodAnalyzer.enabled && !ReplyAuto.enabled) return
                // 文字、语音、表情包参与；图片/视频/文件/卡片/链接等跳过
                if (!ReplyMessageSupport.isReplyEligible(message)) return
                val isPlainText = ReplyMessageSupport.isPlainText(message)
                val talker = message.talker
                val text = if (isPlainText) {
                    // 空/超长不算正确文字
                    MessagePolicy.textOrNull(ReplyMessageSupport.replyText(message) ?: return) ?: return
                } else {
                    // 语音/表情没有可读文字，用占位符代表“对方发来了语音/表情”
                    ReplyMessageSupport.replyText(message) ?: return
                }
                val speaker = if (message.isSend != 0) "我" else "对方"
                val deque = recentByTalker.getOrPut(talker) { ArrayDeque() }
                deque.addLast(speaker to text)
                while (deque.size > 24) deque.removeFirst()
                // 情绪分析只分析/装饰对方的消息；自己发的消息仅进上下文，不出卡、不分析
                if (message.isSend != 0) return
                // 会话级开关是唯一门槛：只有显式开启过「情绪分析」的会话才出卡片、才跑帮我回
                if (!ConversationSwitches.isEnabled(talker)) return
                val context = deque.dropLast(1).takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES)
                    .map { ContextMessage(it.first, it.second) }
                val input = AnalysisInput(
                    text = text,
                    talker = talker,
                    context = context,
                    messageId = message.createTime,
                    speaker = speaker,
                )
                // 情绪分析只对文字有意义；语音/表情只出回复建议卡（MoodAnalyzer 内部自判全局开关）
                if (isPlainText) {
                    MoodAnalyzer.submit(input) { view.isShown }
                }
                BubbleDecorator.show(view, input, analysisEnabled = true)
                // 自动帮我回：对方消息到达时触发；打开开关时也会对最近一条补触发（见 refresh）
                ReplyAuto.request(talker, input.key)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "onMessageViewAttached failed", t)
            }
        }

        override fun onMessageViewDetached(view: View, message: MessageInfo) = BubbleDecorator.clear(view)
        override fun onMessageViewRecycled(view: View, message: MessageInfo) = BubbleDecorator.clear(view)
    }

    /**
     * 打开开关时对当前会话立即补一次分析：取该会话最近一条对方文字消息重新提交，
     * 并给当前已绑定的历史消息行补挂卡片（不必等对方新消息）。
     * 情绪分析与「帮我回」都只补最近一条，避免批量请求撞限流。
     */
    fun refresh() {
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank()) return
        if (!ConversationSwitches.isEnabled(talker)) return
        val deque = recentByTalker[talker] ?: ArrayDeque()
        val context = deque.dropLast(1).takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES)
            .map { ContextMessage(it.first, it.second) }
        // 给当前已绑定的历史消息行补挂卡片（仅对方消息），打开开关立刻可见
        val bound = WeChatMessageViewApi.findBoundViews { m ->
            m.talker == talker && m.isSend == 0 && ReplyMessageSupport.isReplyEligible(m)
        }
        var lastInput: AnalysisInput? = null
        for ((view, message) in bound) {
            val isPlainText = ReplyMessageSupport.isPlainText(message)
            val text = if (isPlainText) {
                MessagePolicy.textOrNull(ReplyMessageSupport.replyText(message) ?: continue) ?: continue
            } else {
                ReplyMessageSupport.replyText(message) ?: continue
            }
            val input = AnalysisInput(
                text = text,
                talker = talker,
                context = context,
                messageId = message.createTime,
                speaker = "对方",
            )
            BubbleDecorator.show(view, input, analysisEnabled = true)
            if (isPlainText) lastInput = input
        }
        // 当前没有已绑定文字消息时，回退到最近一次进过上下文的对方文字消息
        if (lastInput == null) {
            val last = deque.lastOrNull { it.first == "对方" && !it.second.startsWith("[") } ?: return
            lastInput = AnalysisInput(
                text = last.second,
                talker = talker,
                context = context,
                messageId = System.currentTimeMillis(),
                speaker = "对方",
            )
        }
        // 补最近一条的情绪分析与帮我回（键与卡片一致，结果会刷到对应卡片上）
        val target = lastInput ?: return
        MoodAnalyzer.submit(target) { true }
        ReplyAuto.request(talker, target.key)
    }

    /** 幂等订阅一次；由 MoodFeature 在启用时调用。 */
    fun ensureSubscribed() {
        if (subscribed.compareAndSet(false, true)) {
            WeChatMessageViewApi.addLifecycleListener(lifecycle)
        }
    }
}