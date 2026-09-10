package dev.sun.wechat.features.items.contacts.maskwechat

import android.content.Context
import dalvik.system.DexClassLoader
import dev.sun.wechat.utils.WeLogger
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MaskWechat (InkHide/wxmask) 运行时桥：
 *  - 从 WeKit assets 解出 maskwechat.apk 到 codeCache，DexClassLoader 加载
 *  - 反射实例化 com.lu.wxmask.MainHook，按 Xposed 生命周期转发
 *    initZygote(MODULE_PATH=WeKit apk) → handleLoadPackage(微信 lpparam)
 *  - MaskWechat 的配置/加名单/设置界面全部运行在微信进程内(#hide 指令唤起)，
 *    与宿主进程一致，可直接工作。
 */
object MaskWechatLoader {

    private const val TAG = "MaskWechatLoader"
    private const val ASSET_APK = "maskwechat/maskwechat.bin"
    private const val MAIN_HOOK = "com.lu.wxmask.MainHook"
    private const val TARGET_PACKAGE = "com.tencent.mm"

    private val started = AtomicBoolean(false)
    private var loadError: Throwable? = null

    fun isStarted(): Boolean = started.get()

    fun getLoadError(): Throwable? = loadError

    /**
     * 由 WeLauncher 在微信主进程调用（FeaturesLoader 之后，WeKit 环境已就绪）。
     * lpparam 为 Xp51HookEntry 保存的微信包加载参数。
     */
    @Synchronized
    fun start(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (started.get()) return
        if (lpparam == null) {
            WeLogger.w(TAG, "lpparam unavailable; MaskWechat bridge skipped")
            return
        }
        if (lpparam.packageName != TARGET_PACKAGE) return
        try {
            val apkFile = extractApk(context)
            val optDir = File(context.codeCacheDir, "maskwechat_opt").apply { mkdirs() }
            val loader = DexClassLoader(
                apkFile.absolutePath,
                optDir.absolutePath,
                /* libraryPath = */ null,
                MaskWechatLoader::class.java.classLoader,
            )

            val mainHookClass = loader.loadClass(MAIN_HOOK)
            val mainHook = mainHookClass.getDeclaredConstructor().newInstance()

            // 1) initZygote: 让 MaskWechat 的 MODULE_PATH 指向 WeKit 自身 apk
            //    (它的配置 UI 资源/so 都从模块 apk 读取, 合成后即 WeKit apk)
            runCatching {
                val zygoteIf = loader.loadClass("de.robv.android.xposed.IXposedHookZygoteInit")
                val startupParam = java.lang.reflect.Proxy.newProxyInstance(
                    loader,
                    arrayOf(zygoteIf),
                ) { _, method, args ->
                    when (method.name) {
                        "getModulePath" -> getWeKitApkPath(context)
                        else -> defaultReturn(method.returnType)
                    }
                }
                mainHookClass.getMethod("initZygote", zygoteIf).invoke(mainHook, startupParam)
            }.onFailure { WeLogger.w(TAG, "initZygote bridge failed (non-fatal)", it) }

            // 2) handleLoadPackage: 转发微信 lpparam
            mainHookClass.getMethod("handleLoadPackage", XC_LoadPackage.LoadPackageParam::class.java)
                .invoke(mainHook, lpparam)

            // 3) 直接驱动 initPlugin: MaskWechat 原本依赖 hook Application.onCreate 自举,
            //    但我们是在该方法已完成后的回调里才启动引擎, 它的 hook 永远不会再触发。
            //    initPlugin 幂等(hasInit), 反射直调即完成插件注册(AppUtil.attachContext 等)。
            runCatching {
                val initPlugin = mainHookClass.getDeclaredMethod(
                    "initPlugin",
                    android.content.Context::class.java,
                    XC_LoadPackage.LoadPackageParam::class.java,
                ).apply { isAccessible = true }
                initPlugin.invoke(mainHook, context.applicationContext, lpparam)
            }.onFailure { t ->
                WeLogger.w(TAG, "direct initPlugin failed: $t")
                XposedBridge.log("[WeKit] MaskWechat direct initPlugin failed: $t")
            }

            started.set(true)
            WeLogger.i(TAG, "MaskWechat bridge started (apk=${apkFile.name}, ${apkFile.length()} bytes)")
        } catch (t: Throwable) {
            loadError = t
            WeLogger.e(TAG, "MaskWechat bridge failed to start", t)
            XposedBridge.log("[WeKit] MaskWechat bridge failed: $t")
        }
    }

    private fun extractApk(context: Context): File {
        val outFile = File(context.codeCacheDir, "maskwechat.apk")
        val assetLen = context.assets.open(ASSET_APK).use { it.available().toLong() }
        // 命中缓存: 同尺寸视为同版本, 避免每次启动解压
        if (outFile.isFile && outFile.length() == assetLen) return outFile
        context.assets.open(ASSET_APK).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
        }
        WeLogger.i(TAG, "extracted $ASSET_APK -> ${outFile.absolutePath}")
        return outFile
    }

    private fun getWeKitApkPath(context: Context): String =
        context.packageCodePath

    private fun defaultReturn(type: Class<*>): Any? = when {
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.java -> false
        type == Int::class.javaPrimitiveType || type == Int::class.java -> 0
        type == Long::class.javaPrimitiveType || type == Long::class.java -> 0L
        type == Void.TYPE -> null
        else -> null
    }
}
