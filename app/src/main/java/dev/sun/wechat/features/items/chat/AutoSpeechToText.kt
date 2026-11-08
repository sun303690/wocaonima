package dev.sun.wechat.features.items.chat

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.WeServiceApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.HookParam
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException

object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "自动语音转文字"
    override val nameRes = R.string.feature_auto_speech_to_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_speech_to_text_description

    private const val TAG = "AutoSpeechToText"

    private val processedMessages = LruCache<Long, Boolean>()

    /**
     * 运行时探测命中的「转换条目」getter 缓存。
     *
     * 探测要枚举组件类几十个方法并逐个用消息 id 调用，代价不小；同进程里类结构不会变，
     * 命中一次就记住，后续语音直接复用，避免每条语音都探测一遍。
     */
    @Volatile
    private var cachedGetter: java.lang.reflect.Method? = null

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = runCatching { WeChatMessageViewApi.getMsgInfoFromParam(param) }
            .onFailure { WeLogger.e(TAG, "无法从绑定参数取到消息，getMsgInfoFromParam 失败", it) }
            .getOrNull() ?: return
        if (msgInfo.typeCode != MessageType.VOICE.code) return
        // 只转「对方」的语音：自己发的语音微信本就不提供转写，转了也无意义
        if (msgInfo.isSend != 0) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val api = runCatching {
            val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
            val apiMan = chattingContext.reflekt()
                .firstField {
                    type = WeServiceApi.apiManagerClass
                }
                .get()!!
            WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        }.onFailure {
            WeLogger.e(TAG, "取不到聊天上下文/API 管理器，8.0.78 上字段位置可能已变", it)
        }.getOrNull() ?: return

        // 取「转换条目」的方法在 8.0.78 上签名与返回类型都漂移过（旧实现按
        // `parameters(Long) + 返回类型在 viewitems 包` 找不到，直接抛
        // NoSuchElementException: No method matching conditions in
        // com.tencent.mm.ui.chatting.component.vo）。故先试常规签名，再退化为
        // 运行时探测：枚举该 api 对象里所有「单参 long/Long、返回非 void 非基本类型」
        // 的方法，逐个用消息 id 调用，取结果 toString 含 Transform 的那一个。
        var chatViewItem: Any? = null
        var probedName: String? = null

        // 先用上次探测命中的方法；调用失败（类结构变了）才清掉缓存、重新走一遍。
        cachedGetter?.let { remembered ->
            chatViewItem = runCatching { remembered.invoke(api, id) }.getOrNull()
            if (chatViewItem == null) cachedGetter = null
        }

        if (chatViewItem == null) {
            val getter = runCatching {
                api.reflekt().firstMethod {
                    parameters(Long::class)
                    returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
                }
            }.recoverCatching {
                api.reflekt().firstMethod {
                    parameters(Long::class.javaPrimitiveType!!)
                    returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
                }
            }.getOrNull()

            if (getter != null) {
                chatViewItem = runCatching { getter.invoke(id) }
                    .onFailure { WeLogger.e(TAG, "调用转换条目方法失败 id=$id", it) }
                    .getOrNull()
            }
        }

        if (chatViewItem == null) {
            val candidates = runCatching { probeTransformItemGetters(api, id) }.getOrElse { emptyList() }
            if (candidates.isEmpty()) {
                WeLogger.e(TAG, "找不到转换条目方法（transform item getter），自动转文字不可用")
                return
            }
            chatViewItem = candidates.first().second
            probedName = candidates.first().first
            WeLogger.i(TAG, "转换条目方法运行时探测命中 name=$probedName")
        }

        val item = chatViewItem ?: return

        // 旧实现用 `toString() != "NoTransform"` 直接 return；一旦微信把条目类改名或
        // 换了 toString 实现，这里就会对每条语音都提前返回，表现就是「开关打开也不转」。
        // 8.0.78 的转换状态枚举是 com.tencent.mm.ui.chatting.viewitems.cp，
        // 常量依次为 d=NoTransform / e=PreTransform / f=Transforming / g=Transformed
        // （取自 `<clinit>` 常量顺序与字符串表）。只有 NoTransform 需要发起转换，
        // 其余三态要么已经在转、要么已有文本，重复触发会让微信反复清缓存。
        val itemName = item.toString()
        val statusName = itemName.substringAfterLast('.').substringAfterLast('$')
        val needTransform = when {
            statusName.equals("NoTransform", ignoreCase = true) -> true
            statusName.equals("PreTransform", ignoreCase = true) -> false
            statusName.equals("Transforming", ignoreCase = true) -> false
            statusName.equals("Transformed", ignoreCase = true) -> false
            // 认不出枚举名时退回旧判据：含 NoTransform 或压根不含 Transform，才认为没转过。
            else -> itemName.contains("NoTransform", ignoreCase = true) ||
                !itemName.contains("Transform", ignoreCase = true)
        }
        if (!needTransform) {
            WeLogger.i(TAG, "跳过：条目已是转换态 name=$itemName")
            return
        }

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }
            .onFailure { WeLogger.w(TAG, "清除未读红点失败 id=$id", it) }

        // 触发转文字会让微信把语音气泡换成文本条目，也就是改行内视图层级。绑定回调本身
        // 跑在 RecyclerView 的布局流程里，此时改层级会让回收 scrap 时
        // `ViewGroup.dispatchDetachedFromWindow` 撞上 null 子视图（8.0.78 实机崩溃）。
        // 故整段推迟到下一帧，并校验这一行仍在窗口上、没被回收复用。
        val boundTag = view.tag
        view.post {
            if (!view.isAttachedToWindow || view.tag !== boundTag) {
                // 没真的发起转写就把标记撤掉，否则这条语音这辈子都不会再试一次
                processedMessages.remove(id)
                WeLogger.d(TAG, "行已回收，放弃转文字 id=$id")
                return@post
            }
            runCatching {
                // 8.0.78 真入口是 vo.b(MsgInfo, boolean, int, int, int)（五参）：
                // 用户点「转文字」按钮 = b(msg, true, position, 1, 0)、长按菜单 = 同签名。
                // 旧代码按四参 (msg, false, -1, 0) 匹配永远落空，
                // 导致自动转文字从未真正触发（日志只有 getter 的 NoSuchElementException）。
                val position = findAdapterPosition(view)
                val trigger = runCatching {
                    api.javaClass.methods.firstOrNull { m ->
                        m.parameterCount == 5 &&
                            m.parameterTypes[0] == WeMessageApi.classMsgInfo.clazz &&
                            m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                            m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[3] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[4] == Int::class.javaPrimitiveType &&
                            m.returnType == Void.TYPE
                    }
                }.getOrNull()
                if (trigger == null) {
                    WeLogger.e(TAG, "找不到触发转文字的方法（五参 b），自动转文字不可用 id=$id")
                    return@runCatching
                }
                // trigger 是 vo 实例（api）的成员方法：receiver 必须是 api（vo），
                // 第一个实参才是消息对象（e9）。旧写法把消息对象当 receiver，
                // 直接抛 IllegalArgumentException: Expected receiver of type vo, but got e9。
                trigger.invoke(api, msgInfo.instance, true, position, 1, 0)
                WeLogger.i(TAG, "已触发语音转文字 id=$id position=$position")
            }.onFailure { ex ->
                if (ex is InvocationTargetException) {
                    // WeChat 内部会抛 `NullPointerException: getImgPath(...) must not be null`，
                    // 该异常不影响转文字结果，按原实现忽略。
                    WeLogger.d(TAG, "转文字调用内部异常（通常可忽略）id=$id", ex)
                } else {
                    WeLogger.e(TAG, "转文字调用失败 id=$id", ex)
                }
            }
        }
    }

    /**
     * 8.0.78 上「转换条目」getter 的返回类型已不在 `com.tencent.mm.ui.chatting.viewitems`，
     * 静态签名匹配失效。这里枚举 api 对象自身声明的所有方法，挑出「单参 long/Long、
     * 返回非 void 非基本类型」的候选，按返回类型是否在 chatting 包里排序，逐个用消息 id
     * 调用，取第一个 toString 含 `Transform` 的结果。
     *
     * 之所以只留这类候选：setter 一般为 void，避免误调产生副作用。
     */
    private fun probeTransformItemGetters(api: Any, id: Long): List<Pair<String, Any>> {
        val clazz = api.javaClass
        // 公开方法（含继承/接口实现）与自身声明的方法都算上，按签名去重。
        val all = (clazz.methods.toList() + clazz.declaredMethods.toList())
            .distinctBy { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.name } + ")" }
        val candidates = all.filter { m ->
            m.parameterCount == 1 &&
                (m.parameterTypes[0] == Long::class.javaPrimitiveType || m.parameterTypes[0] == Long::class) &&
                m.returnType != Void.TYPE &&
                !m.returnType.isPrimitive &&
                m.returnType != String::class.java
        }.sortedByDescending { m ->
            val n = m.returnType.name
            if (n.startsWith("com.tencent.mm.ui.chatting")) 1 else 0
        }.take(30)

        val hits = ArrayList<Pair<String, Any>>()
        for (m in candidates) {
            runCatching { if (!m.isAccessible) m.isAccessible = true }
            val out = runCatching { m.invoke(api, id) }.getOrNull() ?: continue
            val text = runCatching { out.toString() }.getOrNull().orEmpty()
            if (text.contains("Transform", ignoreCase = true)) {
                cachedGetter = m
                hits += (m.name + "#" + m.returnType.name) to out
            }
        }
        WeLogger.d(TAG, "转换条目getter探测 候选=${candidates.size} 命中=${hits.size}")
        return hits
    }

    /**
     * 找出 `view` 在其所属 RecyclerView（聊天列表）里的 adapter position。
     * view 可能是列表的直接子视图，也可能包在其它容器里，这里沿 parent 链向上
     * 找到 RecyclerView 与它的直接子视图。找不到就返回 -1，微信对 -1 也能容忍
     * （`b` 内部只用位置做滚动定位，失败不影响转换本身）。
     */
    private fun findAdapterPosition(view: View): Int {
        var child: View = view
        var parent = view.parent
        while (parent is ViewGroup && parent !is RecyclerView) {
            child = parent
            parent = parent.parent
        }
        return (parent as? RecyclerView)?.getChildAdapterPosition(child) ?: -1
    }
}
