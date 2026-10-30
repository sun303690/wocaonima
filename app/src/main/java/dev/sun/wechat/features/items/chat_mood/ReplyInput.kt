package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter

/**
 * 把建议回复写进微信原生输入框（移植自微话助手 ReplyInput）。
 * 只 setText，不改输入栏结构、不触发发送；用户仍需自己点发送。
 */
object ReplyInput {

    private const val FOOTER_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"

    fun fill(anchor: View, text: String): Boolean {
        if (text.isBlank()) return false
        val activity = anchor.context as? Activity ?: return false
        val footer = findFooter(activity.window.decorView) ?: return false
        val editor = findEditor(footer) ?: return false
        return runCatching {
            editor.setText(text)
            editor.setSelection(editor.text.length)
            editor.requestFocus()
            true
        }.getOrDefault(false)
    }

    private fun findFooter(root: View): View? {
        if ((root is ChatFooter || root.javaClass.name == FOOTER_CLASS) && root.isShown) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findFooter(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findEditor(root: View): EditText? {
        if (root is EditText && root.isShown && root.isEnabled) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findEditor(root.getChildAt(index))?.let { return it }
        }
        return null
    }
}
