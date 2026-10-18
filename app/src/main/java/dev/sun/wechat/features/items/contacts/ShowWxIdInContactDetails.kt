package dev.sun.wechat.features.items.contacts

import android.app.Activity
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeContactHeaderApi
import dev.sun.wechat.features.api.ui.WeContactPrefsScreenApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.android.copyToClipboard
import dev.sun.wechat.utils.android.currentWxId
import dev.sun.wechat.utils.android.showToast

object ShowWxIdInContactDetails : SwitchFeature(),
    WeContactHeaderApi.Provider,
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "显示微信 ID"
    override val nameRes = R.string.feature_show_wx_id_in_contact_details_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_show_wx_id_in_contact_details_description

    override fun getHeaderText(activity: Activity): String? {
        val wxId = activity.currentWxId ?: return null
        return activity.localizedContactsString(R.string.contacts_wechat_id_value, wxId)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        // Ordinary contacts use the profile header. Groups and official accounts use
        // the preference list because their profile layouts do not share that header.
        val wxId = activity.currentWxId
        if (wxId == null || activity.javaClass.name != ChatroomInfoUI::class.java.name) return emptyList()
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(
                    R.string.contacts_wechat_id_value,
                    wxId,
                ),
                position = 1,
                onClick = onClick@{ itemActivity ->
                    val id = itemActivity.currentWxId ?: return@onClick
                    copyToClipboard(itemActivity, id)
                    showToast(itemActivity, itemActivity.localizedContactsString(R.string.contacts_copied))
                },
            )
        )
    }

    override fun onEnable() {
        WeContactHeaderApi.addProvider(this)
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactHeaderApi.removeProvider(this)
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
