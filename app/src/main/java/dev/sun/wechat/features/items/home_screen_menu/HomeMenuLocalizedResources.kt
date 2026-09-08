package dev.sun.wechat.features.items.home_screen_menu

import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedHomeMenuString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
