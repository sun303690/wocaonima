package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import android.view.MotionEvent
import android.view.View
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
 * 恢复统一走 [SecretFriendState.tempOff]。标题定位采用浮云实测方案（见
 * [SecretFriendState.findHomeTitleTextView]）：LauncherUI onResume 后 decorView.post 定位
 * 顶部 25% 区域内文本恰为「微信」的 TextView，直接挂 listener（View 自身接收触摸，
 * 规避澎湃 OS 全面屏手势吞事件；重复 set 是替换语义，不叠加）。
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
    var clickWindowMs by prefOption("secret_friend_unlock_click_window_ms", 1000)

    private var clickCounter = 0
    private var lastClickAt = 0L
    private var warnedTitleMissing = false

    override fun onEnable() {
        // 不依赖精确 View 定位（text1/深度搜索在 8.0.74 都可能选错 View → 多击不触发）。
        // 改为在主页 decorView 上拦截触摸：点中屏幕**顶部区域**（标题栏所在）即计一次点击，
        // 连续点击 [clickCount] 次触发临时显示。顶部阈值取状态栏下沿 ~90dp，足够宽以命中标题，
        // 又不至于覆盖整个页面。
        val resumeHook = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "onResume"
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.onResume failed; multi-click unlock unavailable", it)
            return
        }
        resumeHook.hookAfter {
                val activity = thisObject as? android.app.Activity ?: return@hookAfter
                val root = activity.window?.decorView ?: return@hookAfter
                root.post {
                    val density = runCatching { activity.resources.displayMetrics.density }.getOrDefault(2.0f)
                    val topBandPx = (90 * density).toInt()
                    WeLogger.i(TAG, "multi-click top-band unlock armed (band=${topBandPx}px, times=$clickCount)")
                    root.setOnTouchListener { _, event ->
                        if (SecretFriendState.isEmpty()) return@setOnTouchListener false
                        if (event.action != android.view.MotionEvent.ACTION_UP) return@setOnTouchListener false
                        val screenW = runCatching { activity.resources.displayMetrics.widthPixels }.getOrDefault(0)
                        val inTopBand = event.rawY <= topBandPx &&
                            event.rawX >= 0 && event.rawX <= screenW
                        if (!inTopBand) return@setOnTouchListener false
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
                        true
                    }
                }
            }
    }
}

// ─────────────────────────── 25. 长按标题解除 ───────────────────────────

/**
 * 长按标题解除（旧版 SecretFriendTempShow 逻辑 / 浮云「长按标题解除」语义）：
 * 长按主页标题 [longPressMs] 毫秒（默认 800）→ 切换临时显示。
 * 用 setOnTouchListener 自计时（系统 OnLongClickListener 写死 ~500ms，无法自定义时长；
 * 且澎湃 OS 的 ACTION_CANCEL 会杀掉 View 内部的长按 postDelayed）。
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
    private var warnedTitleMissing = false

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
        val resumeHook = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "onResume"
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.onResume failed; long-press unlock unavailable", it)
            return
        }
        resumeHook.hookAfter {
                val activity = thisObject as? android.app.Activity ?: return@hookAfter
                val root = activity.window?.decorView ?: return@hookAfter
                root.post {
                    // 顶部区域长按手势：在 decorView 拦截触摸，长按屏幕顶部标题区 [longPressMs] 毫秒触发。
                    // 不依赖精确 View 定位（text1/深度搜索在 8.0.78 都可能选错 View）。
                    val density = runCatching { activity.resources.displayMetrics.density }.getOrDefault(2.0f)
                    val topBandPx = (90 * density).toInt()
                    val screenW = runCatching { activity.resources.displayMetrics.widthPixels }.getOrDefault(0)
                    WeLogger.i(TAG, "long-press top-band unlock armed (band=${topBandPx}px, ${longPressMs}ms)")
                    root.setOnTouchListener { _, event ->
                        if (SecretFriendState.isEmpty()) return@setOnTouchListener false
                        val inTopBand = event.rawY <= topBandPx &&
                            event.rawX >= 0 && event.rawX <= screenW
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                if (!inTopBand) return@setOnTouchListener false
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
                        false
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
