package dev.sun.wechat.utils

/**
 * 「回复内容一行一条，随机发送」的取值逻辑。
 *
 * 回复文本按换行拆成多条，每次发送随机取一条非空行；
 * 只有一条时直接返回该条，全为空行时返回空串（调用方据此跳过发送）。
 */
object MultiLineReply {

    fun pick(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.split('\n')
        if (lines.size == 1) return lines[0].trim()
        val candidates = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates[0]
        return candidates[kotlin.random.Random.nextInt(candidates.size)]
    }
}
