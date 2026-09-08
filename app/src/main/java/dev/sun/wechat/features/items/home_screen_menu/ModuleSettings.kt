package dev.sun.wechat.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import dev.sun.wechat.R
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.features.api.ui.WeHomeScreenPopupMenuApi
import dev.sun.wechat.features.api.ui.WeSettingsInjector
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.utils.ExtensionIcon
import dev.sun.wechat.utils.HookParam

object ModuleSettings : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "模块设置"
    override val nameRes = R.string.feature_module_settings_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_module_settings_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> =
        listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0, BuildConfig.TAG, ExtensionIcon
            ) { WeSettingsInjector.openSettingsDialog(LauncherUI.getInstance()!!) }
        )
}
