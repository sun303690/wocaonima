package dev.sun.wechat.features.items.contacts

import android.app.Activity
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeContactPrefsScreenApi
import dev.sun.wechat.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.contacts.maskwechat.MaskWechatConfig
import dev.sun.wechat.features.items.contacts.maskwechat.MaskWechatLoader
import dev.sun.wechat.preferences.WePrefs
import dev.sun.wechat.utils.android.currentWxId
import dev.sun.wechat.utils.android.showToast

/**
 * 密友隐藏（MaskWechat 引擎桥）：
 *  - 引擎：内嵌开源项目 MaskWechat (InkHide) 的完整 hook 逻辑（assets/maskwechat/maskwechat.apk），
 *    微信进程内独立 ClassLoader 加载，负责隐藏/临时解除/防撤回提示/朋友圈隐藏等全部能力
 *  - 本 Feature 提供 WeKit 侧的开关、状态展示与「加入名单」入口：
 *      · 联系人资料页一键 加入/移出 密友名单
 *      · 名单数据与引擎共用同一份配置(mask_wechat_config)，微信内 #hide 指令界面同样可见
 *  - 开启本功能时加载引擎；关闭即彻底不加载（不留任何 hook）
 */
object MaskWechatBridge : ClickableFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "密友隐藏"
    override val nameRes = R.string.feature_mask_wechat_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_mask_wechat_description

    private const val KEY_PREF_ITEM = "mask_wechat_toggle"

    /** 资料页入口标题（可自定义，默认「加入名单」） */
    var quickAddMenuTitle by WePrefs.prefOption("mask_quick_add_title", "加入名单")

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
        // 引擎加载由 WeLauncher 在微信进程统一触发（需 lpparam），这里只挂 UI 入口
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        if (!MaskWechatLoader.isStarted()) {
            val err = MaskWechatLoader.getLoadError()
            showToast(
                context,
                context.getString(
                    R.string.mask_engine_error,
                    err?.message ?: context.getString(R.string.mask_engine_pending),
                ),
            )
            return
        }
        // 拉起 MaskWechat 微信内管理面板：
        // 其 WXConfigPlugin 会 hook LauncherUI.onCreate/onNewIntent，
        // 带标记的 Intent 触发 showManagerConfigUI()。
        runCatching {
            val intent = android.content.Intent(context, context.javaClass).apply {
                putExtra("KEY_INTENT_FROM_MASK", true)
                putExtra("KEY_INTENT_PLUGIN_MODE", 1) // VALUE_INTENT_PLUGIN_MODE_MANAGER
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
        }.onFailure {
            showToast(context, context.getString(R.string.mask_engine_error, it.message ?: "open failed"))
        }
    }

    // ---------------- 联系人资料页「加入名单」 ----------------

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxid = activity.currentWxId ?: return emptyList()
        if (wxid.isBlank()) return emptyList()
        val inList = wxid in MaskWechatConfig.getMaskIds()
        return listOf(
            PreferenceItem(
                key = KEY_PREF_ITEM,
                title = if (inList) activity.getString(R.string.mask_remove_from_list)
                else quickAddMenuTitle.ifBlank { activity.getString(R.string.mask_add_to_list) },
                summary = if (inList) activity.getString(R.string.mask_in_list_summary)
                else activity.getString(R.string.mask_add_list_summary),
                position = 1,
            ),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != KEY_PREF_ITEM) return false
        val wxid = activity.currentWxId ?: return true
        val changed = if (wxid in MaskWechatConfig.getMaskIds()) {
            MaskWechatConfig.removeMaskId(wxid)
            showToast(activity, activity.getString(R.string.mask_removed_toast, wxid))
        } else {
            MaskWechatConfig.addMaskId(wxid)
            showToast(activity, activity.getString(R.string.mask_added_toast, wxid))
        }
        return changed || true
    }
}
