package dev.sun.wechat.features.items.shortvideos

import android.content.Context
import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedShortVideoString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.shortVideoLocalizedContext().getString(id, *formatArgs)

private fun Context.shortVideoLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
