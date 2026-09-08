package dev.sun.wechat.features.core

abstract class ApiFeature : BaseFeature() {

    final override fun startup() {
        enable()
    }
}
