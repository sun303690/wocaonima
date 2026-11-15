package dev.sun.wechat.utils.android

import android.content.Context
import android.util.AttributeSet
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.sun.wechat.reflekt.reflected.ReflectedConstructor
import dev.sun.wechat.reflekt.reflekt
import dev.sun.wechat.utils.reflection.int
import kotlin.reflect.KClass

val KClass<ChatFooter>.constructor: ReflectedConstructor<ChatFooter>
    get() {
        return reflekt().firstConstructor {
            parameters(Context::class, AttributeSet::class, int)
        }
    }
