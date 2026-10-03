package dev.sun.wechat.loader.entry.common

import android.app.Application
import android.content.Context
import dalvik.system.InMemoryDexClassLoader
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sun.wechat.loader.abc.IHookBridge
import dev.sun.wechat.loader.abc.ILoaderService
import dev.sun.wechat.loader.environment.EnvironmentHider
import dev.sun.wechat.loader.startup.StartupAgent
import dev.sun.wechat.loader.startup.StartupInfo
import dev.sun.wechat.loader.utils.HybridClassLoader
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.hookAfterDirectly
import dev.sun.wechat.utils.reflection.ClassLoaders

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
            entry(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            // Do not poison this process's loader state: a later lifecycle
            // callback may have a usable host class loader.
            WeLogger.e(TAG, "startup failed", t)
            false
        }
    }

    /**
     * 完整启动入口（原 UnifiedEntryPoint.entry 全量并入，初始化不丢）。
     * 顺序必须保持：先 HybridClassLoader 初始化，再 hook attachBaseContext。
     */
    private fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        StartupInfo.hookBridge = hookBridge

        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        if (self is InMemoryDexClassLoader) {
            // The Zygisk payload's parent is the system loader. Keep the payload loader
            // separately so HybridClassLoader can search its DEX without parent delegation.
            HybridClassLoader.moduleClassLoader = self
        }
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        WeLogger.d(TAG, "hooking Application.attachBaseContext")

        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                WeLogger.d(TAG, "Application.attachBaseContext invoked, hooking Instrumentation.callApplicationOnCreate")
                val context = thisObject as Context
                EnvironmentHider.install(context, modulePath)
                val currentClassLoader = context.classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod("callApplicationOnCreate").hookAfterDirectly {
                        WeLogger.d(TAG, "Instrumentation.callApplicationOnCreate invoked, running StartupAgent")
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
