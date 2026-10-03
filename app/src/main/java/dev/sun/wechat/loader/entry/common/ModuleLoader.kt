package dev.sun.wechat.loader.entry.common

import android.app.Application
import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sun.wechat.loader.abc.IHookBridge
import dev.sun.wechat.loader.abc.ILoaderService
import dev.sun.wechat.loader.environment.EnvironmentHider
import dev.sun.wechat.loader.startup.StartupAgent
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.hookAfterDirectly

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private val initLock = Any()

    @Volatile
    private var isInitialized = false

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ): Boolean = synchronized(initLock) {
        if (isInitialized) return@synchronized true

        try {
            WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
            hookApplication(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            WeLogger.e(TAG, "startup failed", t)
            false
        }
    }

    /**
     * 接线方式对齐作者新版：在 Application.attachBaseContext 之后安装 EnvironmentHider，
     * 再 hook Instrumentation.callApplicationOnCreate 以运行完整的 StartupAgent。
     */
    private fun hookApplication(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                val context = thisObject as Context
                EnvironmentHider.install(context, modulePath)
                val currentClassLoader = context.classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod("callApplicationOnCreate").hookAfterDirectly {
                        runCatching {
                            StartupAgent.startup(
                                loaderService,
                                hookBridge,
                                modulePath,
                                args[0] as Application
                            )
                        }.onFailure { WeLogger.e(TAG, "StartupAgent failed", it) }
                    }
            }
    }
}
