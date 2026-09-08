package dev.sun.wechat.application

import android.app.Application
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        WeKitLocaleController.initializeModuleProcess(this)
    }
}
