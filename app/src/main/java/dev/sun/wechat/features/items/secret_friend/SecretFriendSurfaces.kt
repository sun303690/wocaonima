package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.contacts.hidecontacts.injectCondition
import dev.sun.wechat.features.items.contacts.hidecontacts.toSqlList
import dev.sun.wechat.utils.WeLogger

/**
 * 外围界面组 7 / 10 / 12 / 13：最近转发、状态页、存储空间聊天记录、存储空间缓存。
 *
 * 浮云闭源功能，MaskWechat 无对应实现，本项目按浮云语义以「SQL 规则 + 结构式 adapter
 * 过滤」新写：DEX 无稳定锚点的 hook 全部 allowFailure + isPlaceholder 守卫，解析失败
 * 该段跳过（用户无感知）；SQL 规则仅在确认列名出现于查询文本时注入，避免 SQL 语法错误。
 */
// ─────────────────────────── 7. 隐藏最近转发 ───────────────────────────

/**
 * 隐藏最近转发（浮云语义）：转发选择器顶部的「最近转发」列表不再出现密友。
 * - SQL：`recentforward` 表的列表查询（确认 talker 列出现在查询文本中才注入）；
 * - 结构式兜底：转发选择器 ui.transmit 包内 List 入参的列表装配方法（allowFailure + 形状守卫）。
 */

object HideRecentForward : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IQueryListener {
    override val technicalId = "隐藏最近转发"
    override val nameRes: Int = R.string.secret_friend_23_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int? = null


    private const val TAG = "HideRecentForward"

    private val methodTransmitListInstaller by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.transmit")
        matcher {
            paramCount(1)
            paramTypes("java.util.List")
            returnType(Void.TYPE)
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        hookSecretStringArgListFilter(methodTransmitListInstaller, "RecentForwardList")
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        val secrets = SecretFriendState.getWxIds()
        if (secrets.isEmpty()) return null

        val lower = sql.lowercase()
        if (!lower.contains("recentforward")) return null
        // 只在 talker 列确实出现在查询文本中时注入，避免列名漂移导致 SQL 语法错误
        if (!lower.contains("talker")) return null
        if (lower.contains("talker not in")) return null
        return injectCondition(sql, "talker NOT IN (${secrets.toSqlList()})")
    }
}

// ─────────────────────────── 10. 状态页隐藏 ───────────────────────────

/**
 * 状态页隐藏（浮云语义）：状态页（我的状态 / 朋友的状态列表）不再出现密友内容。
 * 状态数据以网络下发为主、本地无稳定可重写 SQL → 结构式 adapter 过滤
 * （plugin.status 包内 List 入参方法，allowFailure + 形状守卫；包名漂移时整段跳过）。
 */

object HideStatusPage : SwitchFeature(), IResolveDex {
    override val technicalId = "状态页隐藏"
    override val nameRes: Int = R.string.secret_friend_24_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int? = null


    private const val TAG = "HideStatusPage"

    private val methodStatusListInstaller by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.status")
        matcher {
            paramCount(1)
            paramTypes("java.util.List")
            returnType(Void.TYPE)
        }
    }

    override fun onEnable() {
        hookSecretStringArgListFilter(methodStatusListInstaller, "StatusList")
    }
}

// ─────────────────── 12 / 13. 存储空间聊天记录 / 缓存 ───────────────────

/**
 * 存储空间聊天记录隐藏（浮云语义）：存储空间清理页的按联系人聊天记录目录不再出现密友。
 * 该页数据以本地统计缓存驱动，走 clean 包内 List 入参的列表装配方法结构式过滤
 * （allowFailure + 形状守卫；锚点缺失时整段跳过）。
 */

object HideStorageRecords : SwitchFeature(), IResolveDex {
    override val technicalId = "存储空间聊天记录隐藏"
    override val nameRes: Int = R.string.secret_friend_25_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int? = null


    private const val TAG = "HideStorageRecords"

    private val methodCleanRecordListInstaller by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.clean")
        matcher {
            paramCount(1)
            paramTypes("java.util.List")
            returnType(Void.TYPE)
        }
    }

    override fun onEnable() {
        hookSecretStringArgListFilter(methodCleanRecordListInstaller, "StorageRecordList")
    }
}

/** 存储空间缓存隐藏（同页缓存条目，实现与聊天记录同款）。 */

object HideStorageCache : SwitchFeature(), IResolveDex {
    override val technicalId = "存储空间缓存隐藏"
    override val nameRes: Int = R.string.secret_friend_26_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes: Int? = null


    private const val TAG = "HideStorageCache"

    private val methodCleanCacheListInstaller by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.clean")
        matcher {
            paramCount(1)
            paramTypes("java.util.List")
            returnType(Void.TYPE)
        }
    }

    override fun onEnable() {
        hookSecretStringArgListFilter(methodCleanCacheListInstaller, "StorageCacheList")
    }
}
