package dev.sun.wechat.features.items.system

import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.R
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.TargetProcess
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.reflection.ClassLoaders
import dev.ujhhgtg.reflekt.reflekt
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 诊断探针：把「已释放的 SurfaceControl 被再次 setPosition」写进日志，用于定位根因。
 *
 * 崩溃栈（46 个 tombstone 全部相同，见 /data/tombstones/tombstone_*）：
 *   hwuiTask0/1  SIGABRT ← SIGSEGV fault addr 0x8
 *   libgui  SurfaceComposerClient::Transaction::registerSurfaceControlForCallback(sp<SurfaceControl>)
 *   libgui  SurfaceComposerClient::Transaction::setPosition(sp<SurfaceControl> const&, float, float)
 *   android.view.SurfaceControl$Transaction.setPosition
 *   android.view.SurfaceView.onSetSurfacePositionAndScale
 *   android.view.SurfaceView$SurfaceViewPositionUpdateListener.positionChanged
 *   android.graphics.RenderNode$PositionUpdateListener.callPositionChanged
 *
 * 机理：某个模块对「含 SurfaceView 的子树」做了 removeView + addView（reparent），这会销毁并
 * 重建该 SurfaceView 的 SurfaceControl。而 SurfaceView 的位置更新回调异步跑在 hwui 线程上
 * （RenderNode.PositionUpdateListener），它仍持有旧 SurfaceControl；当 native 句柄已被释放
 * （mNativeObject == 0）时，setPosition 进入 libgui 后对空指针取成员，偏移 +0x8 处解引用
 * → SIGSEGV。
 *
 * 本模块**只写日志，不做兜底拦截**（不设置 result=null）。hookBefore 先于 native 执行，因此
 * 日志会在进程 SIGSEGV 之前写入 logcat 与模块日志文件，保留证据。它回答两个问题：
 *   1) 崩溃瞬间是否真的发生了「handle == 0 的 setPosition」（确认机理）；
 *   2) 崩溃前最近一次「含 Surface 的子树被 reparent」是哪条调用路径（定位元凶）。
 *
 * 性能：遍历 hook 只在检测到第一个 SurfaceView 之后安装（SurfaceView 一旦出现才可能有此崩溃），
 * 且遍历本身很短（多数视图不是 ViewGroup / 无子视图）。
 */
object NativeViewGuard : SwitchFeature() {
    private const val TAG = "NativeViewGuard"

    /** 不同 ROM/版本里 SurfaceControl 的 native 句柄字段名不完全一致。 */
    private val nativeHandleCandidates = listOf("mNativeObject", "mNativeHandle", "mNativeSurfaceControl")

    override val technicalId = "主界面原生视图崩溃日志探针"
    override val nameRes = R.string.feature_native_view_guard_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_native_view_guard_description

    /** 崩溃发生在主进程与小程序进程（appbrand0/1），sandbox 进程也会挂载 SurfaceView。 */
    override val targetProcesses = setOf(
        TargetProcess.MAIN,
        TargetProcess.APPBRAND,
        TargetProcess.SANDBOX,
    )

    /**
     * 默认开启：这是一次主动排查，需要「下一次崩溃」就自动留下证据，不能指望用户先记得手动开。
     * 开销量级很小（仅在真正搬运含 Surface 的子树时写日志），排查结束后可在设置里关掉。
     */
    override val defaultEnabled = true

    /** 高频回调去重，避免刷爆日志文件。 */
    @Volatile
    private var reportedZeroHandle = false

    private val reparentHooksInstalled = AtomicBoolean(false)

    /** 缓存 SurfaceControl.mNativeObject，避免每次 setPosition 都反射查找字段。 */
    @Volatile
    private var nativeObjectField: Field? = null

    override fun onEnable() {
        hookSetPosition()
        installReparentHooksWhenFirstSurfaceAppears()
    }

    /**
     * 崩溃栈里最后一层 Java 帧 `SurfaceControl$Transaction.setPosition(SurfaceControl, float, float)`。
     * hookBefore 先于 native 执行，因此这里的日志一定能在 SIGSEGV 之前落盘。
     */
    private fun hookSetPosition() {
        val transactionClass = "android.view.SurfaceControl\$Transaction".classOrNull() ?: run {
            WeLogger.w(TAG, "未解析 SurfaceControl\$Transaction, setPosition 探针未安装")
            return
        }
        val controlClass = "android.view.SurfaceControl".classOrNull() ?: run {
            WeLogger.w(TAG, "未解析 SurfaceControl")
            return
        }
        nativeObjectField = nativeHandleCandidates.firstNotNullOfOrNull { name ->
            runCatching { controlClass.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        } ?: run {
            WeLogger.w(TAG, "未找到 SurfaceControl 的 native 句柄字段, setPosition 探针未安装")
            return
        }
        val setPosition = transactionClass.reflekt().firstMethodOrNull {
            name = "setPosition"
            parameterCount = 3
        } ?: run {
            WeLogger.w(TAG, "未解析 setPosition, 探针未安装")
            return
        }
        setPosition.hookBefore {
            val sc = args.getOrNull(0) ?: return@hookBefore
            if (nativeHandleOf(sc) != 0L) return@hookBefore
            val line = "命中 handle==0 的 setPosition: sc=${sc.javaClass.name} " +
                "thread=${Thread.currentThread().name}"
            // logcat 立即落盘（liblog 同步），随后紧跟的 SIGSEGV 会带着它一起进入 tombstone。
            Log.e(BuildConfig.TAG, line)
            if (!reportedZeroHandle) {
                reportedZeroHandle = true
                WeLogger.e(TAG, line)
                WeLogger.e(TAG, Log.getStackTraceString(Throwable("setPosition(handle=0)")))
            }
        }
    }

    /**
     * SurfaceView 一旦出现，才可能有该崩溃。等到第一个 SurfaceView attach 时再安装 addView /
     * removeView 遍历 hook，避免在整个进程生命周期里给每一次 addView 增加开销。
     */
    private fun installReparentHooksWhenFirstSurfaceAppears() {
        // 用 public 构造器触发：第一次出现 SurfaceView 时才安装遍历 hook。
        SurfaceView::class.java.reflekt().constructors().forEach { ctor ->
            runCatching { ctor.hookBefore { installReparentHooks() } }
        }
    }

    /**
     * 记录「含 SurfaceView/TextureView 的子树被移除 / 重新挂载」的调用路径。
     * 崩溃是异步回调，无法从崩溃栈直接看出是谁搬的树；这份记录把时间上更早的搬运与随后的
     * handle==0 崩溃串起来。
     */
    private fun installReparentHooks() {
        if (!reparentHooksInstalled.compareAndSet(false, true)) return
        val vg = ViewGroup::class.java
        runCatching {
            vg.reflekt().firstMethodOrNull {
                name = "addView"
                parameterCount = 2
            }?.hookBefore {
                val child = args.getOrNull(0) as? View ?: return@hookBefore
                if (containsSurface(child)) {
                    Log.w(BuildConfig.TAG, "[Reparent] addView 含 Surface 子树: ${child.javaClass.name}")
                }
            }
        }.onFailure { WeLogger.w(TAG, "addView hook 安装失败", it) }
        runCatching {
            vg.reflekt().firstMethodOrNull {
                name = "removeView"
                parameterCount = 1
            }?.hookBefore {
                val child = args.getOrNull(0) as? View ?: return@hookBefore
                if (containsSurface(child)) {
                    Log.w(
                        BuildConfig.TAG,
                        "[Reparent] removeView 含 Surface 子树: ${child.javaClass.name}\n" +
                            Log.getStackTraceString(Throwable("removeView")),
                    )
                }
            }
        }.onFailure { WeLogger.w(TAG, "removeView hook 安装失败", it) }
        WeLogger.i(TAG, "reparent 遍历 hook 已安装（首个 SurfaceView 出现）")
    }

    private fun nativeHandleOf(sc: Any): Long = runCatching {
        nativeObjectField?.getLong(sc) ?: -1L
    }.getOrDefault(-1L)

    private fun String.classOrNull(): Class<*>? =
        runCatching { Class.forName(this, false, ClassLoaders.BOOT) }.getOrNull()

    /** 子树内是否含硬件 Surface。跨父级搬运这类子树会重建 SurfaceControl，是本崩溃的根因约束。 */
    fun containsSurface(view: View?): Boolean {
        if (view == null) return false
        if (view is SurfaceView || view is TextureView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsSurface(view.getChildAt(i))) return true
            }
        }
        return false
    }

    /** 供其它模块的 reparent 点调用，记录「这次搬运动了含 Surface 的子树」。 */
    @JvmStatic
    fun noteReparent(where: String, child: View?) {
        if (child == null || !containsSurface(child)) return
        Log.w(BuildConfig.TAG, "[Reparent] $where 搬动含 Surface 子树: ${child.javaClass.name}")
    }
}