package dev.sun.wechat.features.items.debug

import android.content.Context
import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.debugLocalizedContext().getString(id, *args)

fun Context.localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    debugLocalizedContext().getString(id, *args)

private fun Context.debugLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    WeKitLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
