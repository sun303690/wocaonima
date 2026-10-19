package dev.sun.wechat.Hchat.event

fun interface EventHandler<E> {
    fun handleEvent(event: E)
}
