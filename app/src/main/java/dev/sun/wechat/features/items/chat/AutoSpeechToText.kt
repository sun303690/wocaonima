package dev.sun.wechat.features.items.chat

import android.view.View
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

        // transition 取「转换条目」的方法签名随版本漂移：先按 Long 参数找，
        // 失败再按 long 基本类型找，最后退化为「返回类型在 viewitems 包里」的唯一方法。
        val transformItemGetter = runCatching {
            api.reflekt().firstMethod {
                parameters(Long::class)
                returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
            }
        }.recoverCatching {
            api.reflekt().firstMethod {
                parameters(Long::class.javaPrimitiveType!!)
                returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
            }
        }.recoverCatching {
            api.reflekt().firstMethod {
                returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
                parameterCount(1)
            }
        }.onFailure {
            WeLogger.e(TAG, "找不到转换条目方法（transform item getter），自动转文字不可用", it)
        }.getOrNull() ?: return

        val chatViewItem = runCatching { transformItemGetter.invoke(id) }
            .onFailure { WeLogger.e(TAG, "调用转换条目方法失败 id=$id", it) }
            .getOrNull() ?: return

        // 旧实现用 `toString() != "NoTransform"` 直接 return；一旦微信把条目类改名或
        // 换了 toString 实现，这里就会对每条语音都提前返回，表现就是「开关打开也不转」。
        val itemName = chatViewItem.toString()
        val looksUntransformed = itemName.contains("NoTransform", ignoreCase = true) ||
            !itemName.contains("Transform", ignoreCase = true)
        if (!looksUntransformed) {
            WeLogger.i(TAG, "跳过：条目已是转换态 name=$itemName")
            return
        }

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }
            .onFailure { WeLogger.w(TAG, "清除未读红点失败 id=$id", it) }

        val alreadyTransformed = runCatching {
            WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean
        }.onFailure {
            WeLogger.e(TAG, "读取转换状态失败，跳过 id=$id", it)
        }.getOrNull() ?: return
        if (alreadyTransformed) {
            WeLogger.i(TAG, "跳过：消息已转换 id=$id")
            return
        }

        try {
            api.reflekt()
                .firstMethod {
                    parameters(
                        WeMessageApi.classMsgInfo.clazz,
                        Boolean::class,
                        Int::class,
                        Int::class
                    )
                    returnType = Void::class.javaPrimitiveType
                }
                .invoke(msgInfo.instance, false, -1, 0)
            WeLogger.i(TAG, "已触发语音转文字 id=$id")
        } catch (ex: InvocationTargetException) {
            // WeChat 内部会抛 `NullPointerException: getImgPath(...) must not be null`，
            // 该异常不影响转文字结果，按原实现忽略。
            WeLogger.d(TAG, "转文字调用内部异常（通常可忽略）id=$id", ex)
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "转文字调用失败 id=$id", ex)
        }
    }
}
