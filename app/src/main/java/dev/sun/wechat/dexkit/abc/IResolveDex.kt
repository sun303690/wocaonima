package dev.sun.wechat.dexkit.abc

import dev.sun.wechat.dexkit.dsl.BaseDexDelegate
import org.luckypray.dexkit.DexKitBridge

/**
 * 表示一个需要通过 DexKit 查找符号的 Feature。
 *
 * 实现类通过 [dev.sun.wechat.dexkit.dsl.dexClass] / [dev.sun.wechat.dexkit.dsl.dexMethod] / [dev.sun.wechat.dexkit.dsl.dexConstructor] 工厂函数声明委托属性，
 * 这些委托在构造时自动注册到 [dexDelegates]。
 */
interface IResolveDex {

    companion object {
        /** 缓存中代表「解析过但未找到、且未设置占位符」的委托的哨兵值。 */
        const val UNRESOLVED_MARKER = "__wekit_unresolved__"
    }

    /**
     * 当前 Feature 持有的所有 Dex 委托。
     * 由 BaseFeature 维护，由委托工厂函数自动填充。
     */
    val dexDelegates: List<BaseDexDelegate>

    /**
     * 执行 DexKit 查找，将结果写入各委托自身。
     * 调用方通过 [collectDescriptors] 读取结果后持久化。
     */
    fun resolveDex(dexKit: DexKitBridge) {}

    /**
     * 将所有委托的当前状态收集为 key → descriptor 字符串映射，
     * 供 [dev.sun.wechat.dexkit.cache.DexCacheManager] 持久化。
     *
     * 解析失败且未设置占位符的委托（descriptor 仍为 null）会写入 [UNRESOLVED_MARKER]，
     * 而不是空串——否则缓存会被判为不完整，导致每次启动都重新解析同一个找不到的锚点。
     */
    fun collectDescriptors(): Map<String, String> =
        dexDelegates.associate { it.key to (it.getDescriptorString() ?: UNRESOLVED_MARKER) }

    /**
     * 从缓存 Map 中逐个恢复委托状态。
     * 每个委托独立加载，某个 key 缺失不会影响其他委托。
     *
     * [UNRESOLVED_MARKER] 会恢复为占位符（视为已缓存，不要求重新扫描），
     * 避免「永远找不到的锚点」每次启动都触发重新解析。
     *
     * @return 缓存中不存在或值为空的 key 集合（需要重新 DexKit 扫描的委托）
     */
    fun loadFromCache(cache: Map<String, Any>): Set<String> {
        val missingKeys = mutableSetOf<String>()
        for (delegate in dexDelegates) {
            val value = cache[delegate.key] as? String
            when {
                value == UNRESOLVED_MARKER -> {
                    delegate.setPlaceholderDescriptor(
                        expectedFailure = true,
                        reason = "cached as unresolved on a previous run",
                    )
                }
                !value.isNullOrEmpty() -> delegate.loadDescriptor(value)
                else -> missingKeys += delegate.key
            }
        }
        return missingKeys
    }
}
