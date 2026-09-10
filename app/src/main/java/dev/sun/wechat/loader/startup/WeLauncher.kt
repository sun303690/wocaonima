package dev.sun.wechat.loader.startup

import android.content.Context
import com.tencent.mm.boot.BuildConfig
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.constants.Preferences
import dev.sun.wechat.dexkit.cache.DexCacheManager
import dev.sun.wechat.features.core.FeaturesLoader
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.loader.utils.ActivityProxy
import dev.sun.wechat.loader.utils.ParcelableFixer
import dev.sun.wechat.loader.utils.ResourcesInjector
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.RuntimeConfig
import dev.sun.wechat.utils.TargetProcesses
import dev.sun.wechat.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        WeKitLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
