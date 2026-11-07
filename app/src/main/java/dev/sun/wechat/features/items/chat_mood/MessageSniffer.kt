package dev.sun.wechat.features.items.chat_mood

import android.view.View
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.items.chat.VoiceTranscriptStore
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

    /** 打开会话开关时，历史消息补挂卡片的最大条数（只补最近几条可见的，避免批量挂卡卡顿）。 */
    private const val MAX_REFRESH_CARDS = 10

    /** 每会话只保留最新一条待分析消息，滑到底部时补触发。 */
    private val pendingByTalker = HashMap<String, AnalysisInput>()

    /** 已挂过滚动监听的聊天列表容器（防止重复 addOnScrollChangedListener）。 */
    private val scrollWatched = HashSet<Int>()

    private val lifecycle = object : WeChatMessageViewApi.IMessageViewLifecycleListener {
        override fun onMessageViewAttached(view: View, message: MessageInfo) {
            try {
                // 只开「自动帮我回」时也要跑，否则消息下方不会有回复建议卡片
                if (!MoodAnalyzer.enabled && !ReplyAuto.enabled) return
                // 除撤回/系统提示外全部参与（图片、视频、文件、链接、卡片用占位描述入上下文）
                if (!ReplyMessageSupport.isReplyEligible(message)) return
                // 语音消息：转写文本只活在微信内存 bean 里，绑定顺手记进缓存，
                // 供 AI 回复（AiChat）被语音触发时按消息 id 取用
                if (message.typeCode == MessageType.VOICE.code) {
                    ReplyMessageSupport.voiceTranscript(message)?.let {
                        VoiceTranscriptStore.put(message.id, it)
                    }
                }
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
                // 会话级开关只管「情绪分析」；自动帮我回不受它限制，否则只开自动回复的聊天不出建议卡
                val chatAnalysisEnabled = ConversationSwitches.isEnabled(talker)
                if (!chatAnalysisEnabled && !ReplyAuto.enabled) return
                val context = deque.dropLast(1).takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES)
                    .map { ContextMessage(it.first, it.second) }
                val input = AnalysisInput(
                    text = text,
                    talker = talker,
                    context = context,
                    messageId = message.createTime,
                    speaker = speaker,
                )
                // 挂卡是重量级 UI 操作（找气泡、强制布局、addView），延迟到下一帧执行，
                // 避免进入聊天页批量绑定时在主线程同步改 View 树导致点击卡顿。
                view.post {
                    if (view.isAttachedToWindow) {
                        BubbleDecorator.show(view, input, analysisEnabled = chatAnalysisEnabled)
                    }
                }
                // 滑到底部才真正请求分析/帮我回；未在底部时暂存，等滚动到底再补
                maybeAnalyze(view, input, isPlainText)
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
        var lastView: View? = null
        var shown = 0
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
            // 同样延迟到下一帧，且只补最近若干条可见消息，避免一次性全量挂卡卡顿
            view.post {
                if (view.isAttachedToWindow) {
                    BubbleDecorator.show(view, input, analysisEnabled = true)
                }
            }
            if (isPlainText) {
                lastInput = input
                lastView = view
                if (++shown >= MAX_REFRESH_CARDS) break
            }
        }
        // 当前没有已绑定文字消息时，回退到最近一次进过上下文的对方文字消息
        if (lastInput == null || lastView == null) {
            val last = deque.lastOrNull { it.first == "对方" && !it.second.startsWith("[") } ?: return
            lastInput = AnalysisInput(
                text = last.second,
                talker = talker,
                context = context,
                messageId = System.currentTimeMillis(),
                speaker = "对方",
            )
            lastView = bound.firstOrNull()?.first
        }
        // 补最近一条的情绪分析与帮我回（键与卡片一致，结果会刷到对应卡片上）；同样受滑到底部约束
        val target = lastInput ?: return
        val anchor = lastView
        val targetIsPlainText = anchor
            ?.let { WeChatMessageViewApi.getBoundMessage(it) }
            ?.let { ReplyMessageSupport.isPlainText(it) }
            ?: !target.text.startsWith("[")
        maybeAnalyze(anchor, target, targetIsPlainText)
    }

    /** 幂等订阅一次；由 MoodFeature 在启用时调用。 */
    fun ensureSubscribed() {
        if (subscribed.compareAndSet(false, true)) {
            WeChatMessageViewApi.addLifecycleListener(lifecycle)
        }
    }

    /**
     * 滑到底部（最新消息可见）才真正提交情绪分析与「帮我回」；
     * 否则把该消息记为待分析，并给聊天列表挂滚动监听，等用户滑到底部再补。
     */
    private fun maybeAnalyze(view: View?, input: AnalysisInput, isPlainText: Boolean) {
        try {
            val container = view?.let(::findScrollContainer)
            if (view == null || container == null || !container.canScrollVertically(1)) {
                // 找不到列表容器（非 RecyclerView 场景）或已在底部：直接分析
                submitAndRequest(input, isPlainText)
            } else {
                pendingByTalker[input.talker] = input
                watchScroll(container)
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "maybeAnalyze failed", t)
        }
    }

    /** 沿消息行向上找聊天列表滚动容器（RecyclerView 系列）。 */
    private fun findScrollContainer(view: View): View? {
        var parent: View? = view.parent as? View
        while (parent != null) {
            val name = parent.javaClass.name
            if (name.contains("RecyclerView") || name.contains("ListView")) return parent
            parent = parent.parent as? View
        }
        return null
    }

    /** 给列表容器挂滚动监听，滑到底部时把暂存的消息补触发。 */
    private fun watchScroll(container: View) {
        val id = System.identityHashCode(container)
        if (!scrollWatched.add(id)) return
        container.viewTreeObserver.addOnScrollChangedListener {
            try {
                if (container.canScrollVertically(1)) return@addOnScrollChangedListener
                flushPending()
            } catch (t: Throwable) {
                WeLogger.e(TAG, "onScrollChanged failed", t)
            }
        }
    }

    /** 补触发所有暂存的待分析消息（每个会话只保留最新一条）。 */
    private fun flushPending() {
        if (pendingByTalker.isEmpty()) return
        val snapshot = pendingByTalker.values.toList()
        pendingByTalker.clear()
        for (input in snapshot) {
            submitAndRequest(input, !input.text.startsWith("["))
        }
    }

    private fun submitAndRequest(input: AnalysisInput, isPlainText: Boolean) {
        try {
            // 情绪分析只对文字有意义；语音/表情只出回复建议卡（MoodAnalyzer 内部自判全局开关）
            if (isPlainText) {
                MoodAnalyzer.submit(input) { true }
            }
            ReplyAuto.request(input.talker, input.key)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "submitAndRequest failed", t)
        }
    }
}