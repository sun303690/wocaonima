package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import android.app.Activity
import androidx.compose.runtime.Composable
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 指令自定义组 31：临时解除指令（浮云 SearchCommandPluginPart 语义）。
 *
 * 拦截点：主页顶部搜索框（FTSBaseMainUI）的逐字搜索回调——`void f(String query)`。
 * 回调方法名每版漂移，且 8.0.77 上该类有多个 `void (String)` 方法（DexKit 取首个
 * 曾命中错误方法导致指令无效），故改为运行时按形状全量 hook：
 * 类名稳定（FTSBaseMainUI）+ 单 String 入参 + void 返回；指令比对不匹配的 hook
 * 直接返回，误挂无副作用。指令命中即临时解除并退出搜索页。
 */
private const val TAG_COMMANDS = "SecretFriend.Commands"

/** 主页 FTS 搜索 UI（稳定类名，浮云同款拦截点）。 */
private const val FTS_MAIN_SEARCH_UI = "com.tencent.mm.plugin.fts.ui.FTSBaseMainUI"

// ─────────────────────────── 31. 临时解除指令 ───────────────────────────

/**
 * 临时解除指令：在主页搜索框输入指令（默认 `#mm#`）→ 临时解除隐藏
 * （[SecretFriendState.tempShowForMinutes]，时长可在本行下方调整，到期自动恢复），
 * 并退出搜索页。
 */

object SearchCommandTempUnhide : SwitchFeature() {
    override val technicalId = "临时解除指令"
    override val nameRes: Int = R.string.secret_friend_02_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = TAG_COMMANDS

    /** 临时解除的搜索指令（默认 #mm#，可自定义）。 */
    var tempUnhideCommand by prefOption("secret_friend_cmd_temp_unhide", "#mm#")

    override fun onEnable() {
        val ftsClass = runCatching { FTS_MAIN_SEARCH_UI.toClass() }.getOrNull()
        if (ftsClass == null) {
            WeLogger.w(TAG, "FTSBaseMainUI not found; temp-unhide command unavailable")
            return
        }

        val methods = runCatching {
            ftsClass.reflekt().methods {
                parameters(String::class)
                returnType(Void.TYPE)
            }
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            WeLogger.w(TAG, "no void(String) method on FTSBaseMainUI; temp-unhide command unavailable")
            return
        }

        methods.forEach { method ->
            method.hookBefore {
                val query = args.getOrNull(0) as? String ?: return@hookBefore
                if (query != tempUnhideCommand) return@hookBefore
                val activity = thisObject as? Activity ?: return@hookBefore
                WeLogger.i(TAG, "temp-unhide search command matched")
                SecretFriendState.tempShowForMinutes(activity)
                runCatching { activity.finish() }
            }
        }
        WeLogger.i(TAG, "temp-unhide command hooks installed on ${methods.size} void(String) methods")
    }

    @Composable
    override fun Ui() {
        TextFieldDialogWidget(
            title = "临时解除指令",
            value = tempUnhideCommand,
            onValueChange = { tempUnhideCommand = it },
            dialogTitle = "临时解除指令文字",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
        TextFieldDialogWidget(
            title = "临时显示时长(分钟)",
            value = SecretFriendState.tempShowMinutes.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(4).toIntOrNull()?.let {
                    SecretFriendState.tempShowMinutes = it
                }
            },
            dialogTitle = "临时显示时长（分钟，1–1440）",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
    }
}


// ─────────────────────────── 输入栏 #show / #hide 临时显示 ───────────────────────────

/**
 * 聊天输入栏指令（旧版隐藏联系人验证过的可靠方式）：
 * - 输入 `#show` → 临时显示隐藏的好友（[SecretFriendState.tempShowForMinutes]）
 * - 输入 `#hide` → 立即恢复隐藏（[SecretFriendState.tempOff]）
 *
 * 移植自旧版 HideContacts 的 onTextChanged 指令，比搜索 `#mm#` 可靠（微信 8.0.74
 * 搜索 FTSBaseMainUI 的 hook 路径不稳定，但输入栏文本变化 hook 稳定）。
 */
object InputBarTempShowCommand : SwitchFeature(), dev.sun.wechat.features.api.ui.WeChatInputBarApi.IInputBarListener {

    override val technicalId = "输入栏#show临时显示"
    override val nameRes: Int = R.string.secret_friend_02_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null

    private const val TAG = "SecretFriend.InputBar"

    override fun onEnable() {
        dev.sun.wechat.features.api.ui.WeChatInputBarApi.addListener(this)
    }

    override fun onDisable() {
        runCatching { dev.sun.wechat.features.api.ui.WeChatInputBarApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onTextChanged(chatFooter: com.tencent.mm.pluginsdk.ui.chat.ChatFooter, text: String) {
        when (text.trim()) {
            "#show" -> {
                runCatching { chatFooter.lastText = "" }
                val context = chatFooter.context
                if (SecretFriendState.isTemporarilyShown()) {
                    dev.sun.wechat.utils.android.showToast(context, "密友已处于临时显示状态")
                    return
                }
                SecretFriendState.tempShowForMinutes(context)
            }
            "#hide" -> {
                runCatching { chatFooter.lastText = "" }
                if (!SecretFriendState.isTemporarilyShown()) {
                    dev.sun.wechat.utils.android.showToast(chatFooter.context, "密友当前未在临时显示")
                    return
                }
                SecretFriendState.tempOff(chatFooter.context)
                dev.sun.wechat.utils.android.showToast(chatFooter.context, "密友已恢复隐藏")
            }
        }
    }
}
