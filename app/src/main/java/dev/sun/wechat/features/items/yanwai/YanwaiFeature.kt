package dev.sun.wechat.features.items.yanwai

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialogContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.yanwai.core.ModulePrefs
import dev.sun.wechat.features.items.yanwai.hook.MessageSniffer
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger

class YanwaiFeature : ClickableFeature() {
    private val tag = "YanwaiFeature"

    override val technicalId = "yanwai"
    override val nameRes = R.string.yanwai_feature_name
    override val descriptionRes = R.string.yanwai_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override fun onEnable() {
        runCatching {
            val ctx = HostInfo.application
            ModulePrefs.init(ctx)
            MessageSniffer.install(ctx)
            WeLogger.i(tag, "yanwai analysis enabled")
        }.onFailure { WeLogger.e(tag, "yanwai enable failed", it) }
    }

    override fun onDisable() {
        runCatching {
            MessageSniffer.pause(HostInfo.application ?: return@runCatching)
        }.onFailure { WeLogger.e(tag, "yanwai disable failed", it) }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.yanwai_feature_name)) },
                text = { Text(stringResource(R.string.yanwai_feature_description)) },
                confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.dialog_confirm)) } },
                dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }
}
