package dev.sun.wechat.features.items.secret_friend

import dev.sun.wechat.R
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.sun.wechat.ui.content.miuix.MiuixListItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.dexkit.abc.IResolveDex
import org.luckypray.dexkit.DexKitBridge
import dev.sun.wechat.dexkit.dsl.DexMethodDelegate
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.ui.WeMomentsContextMenuApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.VisibilityOffIcon
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast

/**
 * 社交隐藏组 15 / 16 / 17：隐藏我的朋友圈（含已隐藏列表管理）、发现页朋友圈入口、发现页入口。
 */

// ─────────────────────────── 15. 隐藏我的朋友圈 ───────────────────────────

/**
 * 隐藏我的朋友圈（浮云语义）：长按相册页/时间线任意一条朋友圈加入隐藏，feed 查询层按
 * snsId 过滤（独立存储键，与密友名单无关）。入口复用工程「朋友圈菜单增强扩展」——
 * TimelineOnCreateContextMenuListener 同时覆盖时间线与相册页列表。
 */

object HideMyMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IQueryListener {

    override val technicalId = "隐藏我的朋友圈"
    override val nameRes: Int = R.string.secret_friend_19_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null
    override val defaultEnabled: Boolean = true

    private const val TAG = "HideMyMoments"

    /** 已隐藏朋友圈 snsId 列表（独立存储键）。 */
    private const val KEY_HIDDEN_MOMENTS = "my_moments_hidden"

    private val MENU_ID = "my_moments_hide".hashCode()

    fun getHiddenMoments(): Set<String> = KvStore.getStringSetOrDef(KEY_HIDDEN_MOMENTS, emptySet())

    fun addHiddenMoment(snsId: Long) {
        if (snsId == 0L) return
        KvStore.putStringSet(KEY_HIDDEN_MOMENTS, getHiddenMoments() + snsId.toString())
        WeLogger.i(TAG, "moment $snsId added to hidden list")
    }

    fun removeHiddenMoment(snsId: String) {
        KvStore.putStringSet(KEY_HIDDEN_MOMENTS, getHiddenMoments() - snsId)
    }

    fun clearHiddenMoments() {
        KvStore.putStringSet(KEY_HIDDEN_MOMENTS, emptySet())
    }

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> = listOf(
        WeMomentsContextMenuApi.MenuItem(
            MENU_ID,
            "加入朋友圈隐藏",
            VisibilityOffIcon,
            { context, _ -> context.snsInfo != null },
        ) { context ->
            val snsId = context.snsInfo?.reflekt()
                ?.firstFieldOrNull {
                    name = "field_snsId"
                    superclass()
                }?.get()?.let { it as? Number }?.toLong() ?: 0L
            if (snsId == 0L) {
                WeLogger.w(TAG, "failed to resolve snsId from snsInfo=${context.snsInfo?.javaClass?.name}")
                showToast(context.activity, "该朋友圈暂不支持加入隐藏")
                return@MenuItem
            }
            addHiddenMoment(snsId)
            SecretFriendState.showToastIfEnabled(context.activity, SecretFriendState.toastMomentHidden)
        }
    )

    /**
     * 时间线主信息流按 snsId 注入 `SnsInfo.snsId NOT IN (...)`（复用朋友圈 feed 的两种
     * 标记形态；个人主页 userName= 查询跳过，否则相册页整页变空）。
     */
    override fun onQuery(sql: String): String? {
        val hidden = getHiddenMoments()
        if (hidden.isEmpty()) return null
        if (!sql.contains("from SnsInfo", ignoreCase = true)) return null
        if (sql.contains("SnsInfo.userName=", ignoreCase = false)) return null
        if (sql.contains("SnsInfo.snsId NOT IN", ignoreCase = true)) return null

        val snsIds = hidden.mapNotNull { it.toLongOrNull() }
        if (snsIds.size != hidden.size) return null
        val list = snsIds.joinToString(",")
        val filter = " AND SnsInfo.snsId NOT IN ($list) "

        return when {
            sql.contains("(sourceType & 2 != 0 )") ->
                sql.replaceFirst("(sourceType & 2 != 0 )", "(sourceType & 2 != 0 )" + filter)
            sql.contains("(1=1)") ->
                sql.replaceFirst("(1=1)", "(1=1)" + filter)
            else -> null
        }
    }
}

/** 管理已隐藏的朋友圈列表（浮云「管理已隐藏列表」入口）。 */

object ManageHiddenMoments : ClickableFeature() {
    override val technicalId = "已隐藏朋友圈管理"
    override val nameRes: Int = R.string.secret_friend_20_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "ManageHiddenMoments"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hidden by remember { mutableStateOf(HideMyMoments.getHiddenMoments().sorted()) }

            AlertDialogContent(
                title = { Text("已隐藏的朋友圈（${hidden.size}）") },
                text = {
                    if (hidden.isEmpty()) {
                        Text("暂无隐藏条目。长按相册页/时间线的朋友圈即可加入隐藏。")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            items(hidden, key = { it }) { snsId ->
                                MiuixListItem(
                                    headlineContent = { Text("朋友圈 snsId: $snsId") },
                                    trailingContent = {
                                        Button(onClick = {
                                            HideMyMoments.removeHiddenMoment(snsId)
                                            hidden = hidden - snsId
                                        }) { Text("移除") }
                                    }
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
                confirmButton = {
                    if (hidden.isNotEmpty()) {
                        TextButton(onClick = {
                            HideMyMoments.clearHiddenMoments()
                            hidden = emptyList()
                        }) { Text("清空") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("确定") }
                    }
                }
            )
        }
    }
}

// ─────────────── 16 / 17. 发现页朋友圈入口 / 发现页入口 ───────────────

/**
 * 发现页入口隐藏的共用实现（结构式 view 树过滤，allowFailure）：
 * DiscoverUI.onCreate/onResume 后在 view 树里按行标题文本定位列表行并隐藏。
 * DiscoverUI 类名每版可能漂移 → dexMethod allowFailure，解析失败整段跳过。
 * 行定位/隐藏全部空安全，页面重建时重复执行幂等。
 */
private fun hideDiscoverRowsByTitle(activity: Activity, titles: Set<String>) {
    val root = activity.window?.decorView ?: return
    // post 到下一帧执行：onCreate 时机列表行尚未绑定
    root.post {
        hideRowsMatching(root, titles)
    }
}

/**
 * 发现页容器类名（按优先级）。微信内部把「发现页」命名为 FindMore：8.0.77 起稳定为
 * `com.tencent.mm.ui.FindMoreFriendsUI`；老版本或 discover 插件包为 `DiscoverUI`。
 * 逐个探测，命中即用，全部落空才整段跳过。
 */
private val DISCOVER_UI_CLASSES = listOf(
    "com.tencent.mm.ui.FindMoreFriendsUI",
    "com.tencent.mm.ui.discover.DiscoverUI",
    "com.tencent.mm.plugin.discover.ui.DiscoverUI",
)

/**
 * 从候选委托组里取第一个已解析的容器。
 *
 * 判定用 `!isPlaceholder` 之前必须确认委托已参与解析：框架在
 * `resolveInlineDex` 里遍历的是**构造期就注册**的 `dexDelegates`，并用
 * `check(all SUCCESS || EXPECTED_FAILURE)` 收尾。因此候选委托一律用 `by dexMethod`
 * 声明（构造期注册、allowFailure 兜底为 EXPECTED_FAILURE），绝不能 lazy/按需创建，
 * 否则要么赶不上内联查找、要么被 check 判为 INCOMPLETE 抛异常。
 */
private fun pickDiscoverContainer(
    candidates: List<Pair<DexMethodDelegate, DexMethodDelegate>>,
): Pair<DexMethodDelegate, DexMethodDelegate>? =
    candidates.firstOrNull { (onCreate, onResume) ->
        onCreate.isAvailable || onResume.isAvailable
    }

private fun hideRowsMatching(view: View, titles: Set<String>) {
    if (view is TextView && view.text?.toString().orEmpty() in titles) {
        // 沿父链找到列表行容器（RecyclerView/ListView/GridView 的直接子 view）后整行隐藏；
        // 行内可能有多处命中标题的子 view，重复隐藏幂等
        var row: View = view
        var parent = row.parent
        while (parent is ViewGroup && parent !is androidx.recyclerview.widget.RecyclerView &&
            parent !is android.widget.ListView && parent !is android.widget.GridView
        ) {
            row = parent
            parent = row.parent
        }
        row.visibility = View.GONE
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            view.getChildAt(i)?.let { hideRowsMatching(it, titles) }
        }
    }
}

/**
 * 发现页朋友圈入口隐藏（浮云语义）：发现页列表中的「朋友圈」入口行隐藏。
 * DiscoverUI 类名漂移 → dexMethod allowFailure + isPlaceholder 守卫，解析失败整段跳过。
 */

object HideDiscoverMoments : SwitchFeature(), IResolveDex {
    override val technicalId = "发现页朋友圈入口隐藏"
    override val nameRes: Int = R.string.secret_friend_21_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "HideDiscoverMoments"

    // 发现页容器类名逐版漂移：微信内部叫 FindMore（8.0.77 = com.tencent.mm.ui.FindMoreFriendsUI），
    // 老版本才是 DiscoverUI。三个候选各声明一组 onCreate/onResume，取第一组解析到的。
    // 必须用 by dexMethod 在构造期注册：框架 resolveInlineDex 只遍历已注册的委托，
    // 且以 check(all SUCCESS/EXPECTED_FAILURE) 收尾，懒创建会被判 INCOMPLETE。
    private val findMoreOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[0]; name = "onCreate"; paramCount = 1 }
    }
    private val findMoreOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[0]; name = "onResume"; paramCount = 0 }
    }
    private val discoverOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[1]; name = "onCreate"; paramCount = 1 }
    }
    private val discoverOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[1]; name = "onResume"; paramCount = 0 }
    }
    private val pluginDiscoverOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[2]; name = "onCreate"; paramCount = 1 }
    }
    private val pluginDiscoverOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[2]; name = "onResume"; paramCount = 0 }
    }

    private val candidates
        get() = listOf(
            findMoreOnCreate to findMoreOnResume,
            discoverOnCreate to discoverOnResume,
            pluginDiscoverOnCreate to pluginDiscoverOnResume,
        )

    override fun resolveDex(dexKit: DexKitBridge) {
        if (pickDiscoverContainer(candidates) == null) {
            dumpDiscoverCandidates(dexKit, TAG)
        }
    }

    override fun onEnable() {
        val (onCreate, onResume) = pickDiscoverContainer(candidates) ?: run {
            WeLogger.w(TAG, "DiscoverUI 未解析, 发现页入口不过滤")
            return
        }
        val rowTitles = setOf("朋友圈")
        if (onCreate.isAvailable) {
            onCreate.hookAfter {
                hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
            }
        }
        // onResume 重走一遍：页面重建后行会重新绑定。新版可能未在本类声明，缺失时跳过。
        if (onResume.isAvailable) {
            onResume.hookAfter {
                hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
            }
        }
    }
}

/**
 * 发现页入口隐藏（浮云语义）：发现页列表中的视频号 / 看一看 / 小程序入口行隐藏。
 * 实现同 HideDiscoverMoments。
 */

object HideDiscoverEntries : SwitchFeature(), IResolveDex {
    override val technicalId = "发现页入口隐藏"
    override val nameRes: Int = R.string.secret_friend_22_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SECRET_FRIEND)
    override val descriptionRes: Int? = null


    private const val TAG = "HideDiscoverEntries"

    private val findMoreOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[0]; name = "onCreate"; paramCount = 1 }
    }
    private val findMoreOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[0]; name = "onResume"; paramCount = 0 }
    }
    private val discoverOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[1]; name = "onCreate"; paramCount = 1 }
    }
    private val discoverOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[1]; name = "onResume"; paramCount = 0 }
    }
    private val pluginDiscoverOnCreate by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[2]; name = "onCreate"; paramCount = 1 }
    }
    private val pluginDiscoverOnResume by dexMethod(allowFailure = true) {
        matcher { declaredClass = DISCOVER_UI_CLASSES[2]; name = "onResume"; paramCount = 0 }
    }

    private val candidates
        get() = listOf(
            findMoreOnCreate to findMoreOnResume,
            discoverOnCreate to discoverOnResume,
            pluginDiscoverOnCreate to pluginDiscoverOnResume,
        )

    override fun resolveDex(dexKit: DexKitBridge) {
        if (pickDiscoverContainer(candidates) == null) {
            dumpDiscoverCandidates(dexKit, TAG)
        }
    }

    override fun onEnable() {
        val (onCreate, onResume) = pickDiscoverContainer(candidates) ?: run {
            WeLogger.w(TAG, "DiscoverUI 未解析, 发现页入口不过滤")
            return
        }
        val rowTitles = setOf("视频号", "看一看", "小程序")
        if (onCreate.isAvailable) {
            onCreate.hookAfter {
                hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
            }
        }
        if (onResume.isAvailable) {
            onResume.hookAfter {
                hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
            }
        }
    }
}
