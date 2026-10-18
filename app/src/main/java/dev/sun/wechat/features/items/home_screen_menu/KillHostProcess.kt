package dev.sun.wechat.features.items.home_screen_menu

import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeHomeScreenPopupMenuApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.utils.CancelIcon
import dev.sun.wechat.utils.HookParam
import dev.sun.wechat.utils.killHost

object KillHostProcess : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "强行停止"
    override val nameRes = R.string.feature_kill_host_process_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_kill_host_process_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777015, localizedHomeMenuString(R.string.home_menu_force_stop), CancelIcon
            ) {
                killHost()
            }
        )
    }
}
