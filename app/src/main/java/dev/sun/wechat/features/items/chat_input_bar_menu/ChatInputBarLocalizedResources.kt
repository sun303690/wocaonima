package dev.sun.wechat.features.items.chat_input_bar_menu

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.LocalizedContextFactory
import dev.sun.wechat.i18n.WeKitLocaleController
import dev.sun.wechat.utils.HostInfo

fun localizedChatInputString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatInputString(id, *formatArgs)

fun Context.localizedChatInputString(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String = chatInputLocalizedContext().getString(id, *formatArgs)

fun Context.localizedChatInputQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatInputLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatInputLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
