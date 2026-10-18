package dev.sun.wechat.features.items.contacts

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedContactsString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedContactsString(id, *formatArgs)

fun Context.localizedContactsString(@StringRes id: Int, vararg formatArgs: Any): String =
    contactsLocalizedContext().getString(id, *formatArgs)

fun localizedContactsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedContactsQuantity(id, quantity, *formatArgs)

fun Context.localizedContactsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = contactsLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.contactsLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
