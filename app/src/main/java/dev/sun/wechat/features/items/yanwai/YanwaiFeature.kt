package dev.sun.wechat.features.items.yanwai

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialogContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
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
        // pause/resume 绑定 Activity 生命周期，不由功能开关直接调用
        WeLogger.i(tag, "yanwai analysis disabled")
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
