package dev.sun.wechat.features.items.beautify

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore.prefOption

/**
 * 主界面底部四个 Tab 的共享排版模型。
 *
 * 微信主界面（LauncherUI 内的 MainTabUI）把「聊天/通讯录/发现/我」四个页面交给同一个
 * FragmentStatePagerAdapter 托管，页面的顺序与显隐完全由导航栏功能的
 * `nav_bar_tab_order` / `nav_bar_enabled_tabs` 两个偏好项决定：
 *
 * - Tab 顺序被改动时，pager 的第 0 页未必是「聊天」（wechatIndex 0）；
 * - Tab 被隐藏时，getCount() 会小于 4，pager 位置与 wechatIndex 不再相等。
 *
 * 因此任何需要「pager 位置 ↔ wechatIndex」映射的功能（导航栏自身、全局背景的分页层）
 * 都必须共用同一套换算。这里作为唯一事实来源，供 [ReplaceNavigationBar] 与
 * [ApplyGlobalBackground] 共用，避免两处判断不一致导致背景图与真实页面对不上。
 */
object MainTabLayout {

    data class NavItem(
        val wechatIndex: Int,
        val outlined: ImageVector,
        val filled: ImageVector,
        @StringRes val labelRes: Int,
    )

    @Stable
    val TAB_ITEMS = listOf(
        NavItem(0, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, R.string.nav_tab_home),
        NavItem(1, MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, R.string.nav_tab_contacts),
        NavItem(2, MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, R.string.nav_tab_discover),
        NavItem(3, MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, R.string.nav_tab_me),
    )

    /** 四个 Tab 的默认显示顺序（即微信原生顺序），用作偏好项默认值。 */
    val DEFAULT_ORDER = TAB_ITEMS.joinToString(",") { it.wechatIndex.toString() }

    /** 默认全部可见。 */
    val DEFAULT_ENABLED = TAB_ITEMS.map { it.wechatIndex.toString() }.toSet()

    var tabOrder by prefOption("nav_bar_tab_order", DEFAULT_ORDER)
    var enabledTabs by prefOption("nav_bar_enabled_tabs", DEFAULT_ENABLED)

    fun normalizedTabOrder(rawOrder: String = tabOrder): List<NavItem> {
        val orderedIndices = rawOrder.split(",")
            .mapNotNull(String::toIntOrNull)
            .filter { index -> TAB_ITEMS.any { it.wechatIndex == index } }
            .distinct()
            .toMutableList()
        TAB_ITEMS.forEach { item ->
            if (item.wechatIndex !in orderedIndices) orderedIndices += item.wechatIndex
        }
        return orderedIndices.map { index -> TAB_ITEMS.first { it.wechatIndex == index } }
    }

    fun normalizedEnabledTabIndices(rawEnabled: Set<String> = enabledTabs): Set<Int> {
        val validIndices = TAB_ITEMS.mapTo(mutableSetOf(), NavItem::wechatIndex)
        return rawEnabled.mapNotNull(String::toIntOrNull)
            .filterTo(linkedSetOf()) { it in validIndices }
    }

    /** 当前实际可见的 Tab，按显示顺序排列（对应导航栏内部的 visibleTabItems）。 */
    fun visibleTabItems(): List<NavItem> {
        val enabledIndices = normalizedEnabledTabIndices()
        return normalizedTabOrder().filter { it.wechatIndex in enabledIndices }
    }

    /**
     * 可见 Tab 的 wechatIndex，按显示顺序；下标即 pager 的物理位置。
     *
     * 当导航栏功能被关闭时，微信自己不会重排/隐藏页面，pager 保持原生四页，
     * 此时映射恒等，必须按 [TAB_ITEMS] 的原生顺序返回，否则会与真实页面错位。
     */
    fun visibleWechatIndices(navBarEnabled: Boolean): List<Int> =
        if (navBarEnabled) visibleTabItems().map(NavItem::wechatIndex)
        else TAB_ITEMS.map(NavItem::wechatIndex)
}
