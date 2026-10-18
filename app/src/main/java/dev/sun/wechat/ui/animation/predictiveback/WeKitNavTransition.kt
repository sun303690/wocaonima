package dev.sun.wechat.ui.animation.predictiveback

import dev.sun.wechat.ui.utils.theme.PageTransitionAnimation
import top.yukonga.miuix.kmp.nav.transition.NavTransition

fun weKitNavTransition(animation: PageTransitionAnimation): NavTransition = when (animation) {
    PageTransitionAnimation.AOSP -> AospNavTransition
    PageTransitionAnimation.MIUIX -> top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault
}
