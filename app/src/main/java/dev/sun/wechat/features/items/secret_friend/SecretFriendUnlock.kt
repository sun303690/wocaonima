package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import androidx.compose.runtime.Composable
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.lang.ref.WeakReference

/**
 * 临时解除与恢复组 24–27：多击标题解除、长按标题解除、锁屏隐藏、离开对话/离开微信隐藏。
 *
 * 解除统一走 [SecretFriendState.tempShowForMinutes]（默认 30 分钟，到期自动恢复）；
 * 恢复统一走 [SecretFriendState.tempOff]。多击/长按识别在 LauncherUI.dispatchTouchEvent 上
 * 观察触摸，命中屏幕顶部标题区（约 90dp 内）即计数，不依赖精确 View 定位，也不与其它功能
 * 争抢 decorView 唯一的 OnTouchListener 槽位。
 */
// ─────────────────────────── 24. 多击标题解除 ───────────────────────────

/**
 * 多击标题解除（浮云「多击标题解除」语义）：连续点击主页标题 N 次（可调，默认 3），
 * 每次点击间隔不超过 M 毫秒（可调，默认 1000）→ 临时解除隐藏 30 分钟。
 */

object MultiClickTitleUnlock : SwitchFeature() {
    override val technicalId = "多击标题解除"
    override val nameRes: Int = R.string.secret_friend_29_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "MultiClickTitleUnlock"

    /** 触发所需连续点击次数（默认 3）。 */
    var clickCount by prefOption("secret_friend_unlock_click_count", 3)

    /** 计次窗口毫秒数（默认 1000）。 */
    var clickWindowMs by prefOption("secret_friend_unlock_click_window_ms", 3000)

    private var clickCounter = 0
    private var lastClickAt = 0L

    override fun onEnable() {
        // 不依赖精确 View 定位（text1/深度搜索在 8.0.74/8.0.78 都可能选错 View）。
        // 在 LauncherUI.dispatchTouchEvent 上观察触摸：它先于任何子 View 收到全部事件。
        // decorView.setOnTouchListener 不可用——它只在没有子 View 消费 DOWN 时才回调，
        // 且返回 false 收不到后续 UP（多击永远计不到），并与长按功能争抢同一 listener 槽位。
        val dispatch = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "dispatchTouchEvent"
                    parameters(MotionEvent::class)
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.dispatchTouchEvent failed; multi-click unlock unavailable", it)
            return
        }
        dispatch.hookBefore {
            val activity = thisObject as? LauncherUI ?: return@hookBefore
            if (SecretFriendState.isEmpty()) return@hookBefore
            val event = args[0] as MotionEvent
            if (event.actionMasked != MotionEvent.ACTION_UP) return@hookBefore
            if (!isInHomeTitleBand(activity, event)) return@hookBefore
            val now = System.currentTimeMillis()
            if (now - lastClickAt > clickWindowMs.coerceAtLeast(200)) clickCounter = 1
            else clickCounter++
            lastClickAt = now
            WeLogger.i(TAG, "title top-band tap #$clickCounter/$clickCount at y=${event.rawY.toInt()}")
            if (clickCounter >= clickCount.coerceAtLeast(2)) {
                clickCounter = 0
                WeLogger.i(TAG, "title multi-click unlock triggered")
                SecretFriendState.tempShowForMinutes(activity)
            }
        }
    }
}

// ─────────────────────────── 25. 长按标题解除 ───────────────────────────

/**
 * 长按标题解除（旧版 SecretFriendTempShow 逻辑 / 浮云「长按标题解除」语义）：
 * 长按主页标题 [longPressMs] 毫秒（默认 800）→ 切换临时显示。
 * 在 LauncherUI.dispatchTouchEvent 上自计时（系统 OnLongClickListener 写死 ~500ms，无法
 * 自定义时长；decorView.setOnTouchListener 又会因未消费 DOWN 收不到后续事件）。
 */

object LongPressTitleUnlock : SwitchFeature() {
    override val technicalId = "长按标题解除"
    override val nameRes: Int = R.string.secret_friend_30_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "LongPressTitleUnlock"

    /** 长按触发时长毫秒（默认 800）。 */
    var longPressMs by prefOption("secret_friend_long_press_ms", 800)

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    private val triggerRunnable = Runnable {
        if (!pending) return@Runnable
        pending = false
        if (SecretFriendState.isTemporarilyShown()) {
            WeLogger.i(TAG, "title long-press: restoring hidden state")
            SecretFriendState.tempOff()
        } else {
            WeLogger.i(TAG, "title long-press unlock triggered")
            SecretFriendState.tempShowForMinutes()
        }
    }

    override fun onEnable() {
        val dispatch = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "dispatchTouchEvent"
                    parameters(MotionEvent::class)
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.dispatchTouchEvent failed; long-press unlock unavailable", it)
            return
        }
        dispatch.hookBefore {
            val activity = thisObject as? LauncherUI ?: return@hookBefore
            if (SecretFriendState.isEmpty()) return@hookBefore
            val event = args[0] as MotionEvent
            val inTopBand = isInHomeTitleBand(activity, event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!inTopBand) return@hookBefore
                    pending = true
                    handler.postDelayed(triggerRunnable, longPressMs.coerceIn(300, 5000).toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pending && !inTopBand) {
                        pending = false
                        handler.removeCallbacks(triggerRunnable)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending = false
                    handler.removeCallbacks(triggerRunnable)
                }
            }
        }
    }
}

// ─────────────────────────── 26. 锁屏隐藏 ───────────────────────────

/**
 * 锁屏隐藏（浮云「锁屏隐藏」语义）：锁屏（SCREEN_OFF）广播 → 立即 tempOff()；
 * 若正处于密友对话内，同时关闭该对话窗口（finish ChattingUI）。
 * Receiver 动态注册于 onEnable，随 onDisable 注销。
 */

object AutoRestoreLockScreen : SwitchFeature() {
    override val technicalId = "锁屏隐藏"
    override val nameRes: Int = R.string.secret_friend_31_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "AutoRestoreLockScreen"

    /** 当前打开的 ChattingUI（onResume 捕获，onPause 清除）。 */
    private var chattingUiRef: WeakReference<ChattingUI>? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            WeLogger.i(TAG, "screen off, restoring hidden state")
            SecretFriendState.tempOff()
            // 密友对话内锁屏 → 关闭对话窗口（返回主页时列表已恢复隐藏）
            chattingUiRef?.get()?.let { activity ->
                val wxId = activity.intent?.getStringExtra("Chat_User")
                if (SecretFriendState.isSecret(wxId)) {
                    WeLogger.i(TAG, "finishing secret chat window on screen off")
                    runCatching { activity.finish() }
                        .onFailure { WeLogger.w(TAG, "finish ChattingUI failed", it) }
                }
            }
        }
    }

    override fun onEnable() {
        runCatching {
            HostInfo.application.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }.onFailure { WeLogger.e(TAG, "register screen-off receiver failed", it) }

        // ChattingUI 不一定声明 onResume/onPause（版本相关），解析失败会整体中断 onEnable
        // 导致广播都不注册——必须逐个容错，声明找不到时回退父类查找
        val resumeMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume"; superclass(true) }
        }.getOrNull()
        if (resumeMethod != null) {
            resumeMethod.hookAfter { chattingUiRef = WeakReference(thisObject as? ChattingUI) }
        } else {
            WeLogger.w(TAG, "ChattingUI.onResume not resolvable; auto-close chat on lock screen disabled")
        }

        val pauseMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause"; superclass(true) }
        }.getOrNull()
        if (pauseMethod != null) {
            pauseMethod.hookAfter { chattingUiRef = null }
        } else {
            WeLogger.w(TAG, "ChattingUI.onPause not resolvable; stale chat window tracking possible")
        }
    }

    override fun onDisable() {
        runCatching { HostInfo.application.unregisterReceiver(screenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "unregisterReceiver failed", it) }
    }
}

// ─────────────────── 27. 离开对话 / 离开微信隐藏 ───────────────────

/**
 * 离开对话/离开微信隐藏（浮云 rehideOnLeaveChat + rehideOnLeaveApp 合并开关）：
 * - 离开任意对话（ChattingUI.onPause）→ tempOff()；
 * - 离开主页（LauncherUI.onPause）→ tempOff()；
 * - 微信失焦（LauncherUI.onWindowFocusChanged(false)，即按 HOME 切走）→ tempOff()。
 * 临时显示期间进入过的密友对话，返回主页时会话列表已恢复隐藏，无需额外 finish。
 */

object AutoRestoreOnLeave : SwitchFeature(), IResolveDex {
    override val technicalId = "离开对话/离开微信隐藏"
    override val nameRes: Int = R.string.secret_friend_32_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "AutoRestoreOnLeave"

    /** LauncherUI.onWindowFocusChanged(boolean)（allowFailure：未声明时跳过，onPause 已兜底）。 */
    private val methodLauncherFocusChanged by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUI"
            name = "onWindowFocusChanged"
            paramCount = 1
        }
    }

    override fun onEnable() {
        // 离开任意对话即恢复隐藏（仅在普通隐藏状态；临时显示期间离开不清除，让用户能看完）
        ChattingUI::class.reflekt()
            .firstMethod { name = "onPause" }
            .hookAfter {
                if (SecretFriendState.isTemporarilyShown()) return@hookAfter
                WeLogger.d(TAG, "leaving conversation, restoring hidden state")
                SecretFriendState.tempOff()
            }

        // 离开主页 / 微信失焦即恢复隐藏（临时显示期间不清除）
        LauncherUI::class.reflekt()
            .firstMethod {
                name = "onPause"
                superclass()
            }
            .hookAfter {
                if (SecretFriendState.isTemporarilyShown()) return@hookAfter
                WeLogger.d(TAG, "leaving home screen, restoring hidden state")
                SecretFriendState.tempOff()
            }

        if (!methodLauncherFocusChanged.isPlaceholder) {
            methodLauncherFocusChanged.hookAfter {
                val hasFocus = args.getOrNull(0) as? Boolean ?: return@hookAfter
                if (SecretFriendState.isTemporarilyShown()) return@hookAfter
                if (!hasFocus) {
                    WeLogger.d(TAG, "WeChat lost window focus, restoring hidden state")
                    SecretFriendState.tempOff()
                }
            }
        }
    }
}

// ─────────────────── 主页标题区触摸判定（多击/长按共用） ───────────────────

/** 主页顶部标题区高度（dp，约状态栏下沿到标题栏底部）。 */
private const val TITLE_BAND_DP = 90

/** 触摸点是否落在主页顶部标题区（宽覆盖整个屏幕宽度）。 */
private fun isInHomeTitleBand(activity: LauncherUI, event: MotionEvent): Boolean {
    val dm = activity.resources.displayMetrics
    val topBandPx = (TITLE_BAND_DP * dm.density).toInt()
    return event.rawY <= topBandPx && event.rawX >= 0 && event.rawX <= dm.widthPixels
}
