package dev.sun.wechat.features.api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.R
import dev.sun.wechat.activity.settings.SettingsActivity
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.features.core.ApiFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

object WeSettingsInjector : ApiFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener {

    override val technicalId = "设置模块入口"
    override val nameRes = R.string.feature_we_settings_injector_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    private const val TAG = "WeSettingsInjector"

    override fun resolveDex(dexKit: DexKitBridge) {
        // 不再注入微信设置页，无需解析微信 Preference 结构
    }

    override fun onEnable() {
        runCatching {
            hookLauncherUi()
        }.onFailure { WeLogger.w(TAG, "failed to hook LauncherUI") }

        WeChatInputBarApi.addListener(this)
    }

    override fun onDisable() {
        WeChatInputBarApi.removeListener(this)
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        if (text != "#wekit") return
        chatFooter.lastText = ""
        openSettingsDialog(chatFooter.context)
    }

    private fun hookLauncherUi() {
        LauncherUI::class.reflekt().apply {
            firstMethod { name = "onCreate" }
                .hookBefore {
                    val activity = thisObject as Activity
                    val intent = activity.intent ?: return@hookBefore
                    intent.getStringExtra(BuildConfig.TAG) ?: return@hookBefore
                    // 消费掉标记，否则 getIntent() 会一直带着它，
                    // 之后每次 onNewIntent 都会重新弹出设置界面
                    intent.removeExtra(BuildConfig.TAG)
                    // wait for resources & theme to init
                    Handler(Looper.getMainLooper()).postDelayed({
                        openSettingsDialog(activity)
                    }, 500)
                }

            firstMethod { name = "onNewIntent" }
                .hookBefore {
                    val activity = thisObject as Activity
                    // LauncherUI 是 singleTop，新的 Intent 在 args[0] 里；
                    // 此时 getIntent() 还是旧的那一个
                    val intent = args.getOrNull(0) as? Intent ?: return@hookBefore
                    intent.getStringExtra(BuildConfig.TAG) ?: return@hookBefore
                    intent.removeExtra(BuildConfig.TAG)
                    openSettingsDialog(activity)
                }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    fun openSettingsDialog(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }
}
