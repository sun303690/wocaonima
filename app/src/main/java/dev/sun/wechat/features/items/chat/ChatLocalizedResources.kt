package dev.sun.wechat.features.items.chat

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatString(id, *formatArgs)

fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    chatLocalizedContext().getString(id, *formatArgs)

fun localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedChatQuantity(id, quantity, *formatArgs)

fun Context.localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
