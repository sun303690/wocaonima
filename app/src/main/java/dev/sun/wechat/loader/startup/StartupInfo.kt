package dev.sun.wechat.loader.startup

import dev.sun.wechat.loader.abc.IHookBridge
import dev.sun.wechat.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
