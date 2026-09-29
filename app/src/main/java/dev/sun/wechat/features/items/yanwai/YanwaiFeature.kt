package dev.sun.wechat.features.items.yanwai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.yanwai.core.ModulePrefs
import dev.sun.wechat.features.items.yanwai.hook.MessageSniffer
import dev.sun.wechat.features.items.yanwai.core.JevProvider
import dev.sun.wechat.features.items.yanwai.reply.ReplyProvider
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger

object YanwaiFeature : ClickableFeature() {
    private const val TAG = "YanwaiFeature"

    override val technicalId = "yanwai"
    override val nameRes = R.string.yanwai_feature_name
    override val descriptionRes = R.string.yanwai_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override fun onEnable() {
        runCatching {
            val ctx = HostInfo.application
            ModulePrefs.init(ctx)
            MessageSniffer.install(ctx)
            WeLogger.i(TAG, "yanwai analysis enabled")
        }.onFailure { WeLogger.e(TAG, "yanwai enable failed", it) }
    }

    override fun onDisable() {
        WeLogger.i(TAG, "yanwai analysis disabled")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)

    override fun onClick(context: ComponentActivity) {
        val ctx = context.applicationContext ?: context
        val p = prefs(ctx)
        var provider by remember { mutableStateOf(p.getString("reply_provider", ReplyProvider.DEEPSEEK.id) ?: ReplyProvider.DEEPSEEK.id) }
        var endpoint by remember { mutableStateOf(p.getString("reply_endpoint", "") ?: "") }
        var apiKey by remember { mutableStateOf(p.getString("reply_api_key", "") ?: "") }
        var model by remember { mutableStateOf(p.getString("reply_model", "") ?: "") }
        var consent by remember { mutableStateOf(p.getString("reply_consent", "false") == "true") }
        var route by remember { mutableStateOf(p.getString("intent_route", "jev") ?: "jev") }
        var jevProvider by remember { mutableStateOf(p.getString("api_provider", JevProvider.TYPESAFE.id) ?: JevProvider.TYPESAFE.id) }
        var jevEndpoint by remember { mutableStateOf(p.getString("api_base", "") ?: "") }
        var jevKey by remember { mutableStateOf(p.getString("api_key", "") ?: "") }
        var jevModel by remember { mutableStateOf(p.getString("api_model", "") ?: "") }

        showComposeDialog(context, directlyDismissable = false) {
            AlertDialogContent(
                title = { Text("言外 - 情绪分析设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("模型渠道")
                        val providerNames = ReplyProvider.entries.map { "${it.label} (${it.id})" }
                        OutlinedTextField(
                            value = providerNames.getOrElse(ReplyProvider.entries.indexOfFirst { it.id == provider }) { 0 },
                            onValueChange = { sel ->
                                val idx = providerNames.indexOfFirst { it == sel }
                                if (idx >= 0) { provider = ReplyProvider.entries[idx].id; endpoint = ReplyProvider.entries[idx].endpoint }
                            },
                            label = { Text("渠道") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                            label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                            label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = model, onValueChange = { model = it },
                            label = { Text("模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        HorizontalDivider()
                        Text("JEV 模型（情绪概率）")
                        val jevNames = JevProvider.entries.map { "${it.label} (${it.id})" }
                        OutlinedTextField(
                            value = jevNames.getOrElse(JevProvider.entries.indexOfFirst { it.id == jevProvider }) { 0 },
                            onValueChange = { sel ->
                                val idx = jevNames.indexOfFirst { it == sel }
                                if (idx >= 0) { jevProvider = JevProvider.entries[idx].id; jevEndpoint = JevProvider.entries[idx].endpoint }
                            },
                            label = { Text("JEV 渠道") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(value = jevEndpoint, onValueChange = { jevEndpoint = it },
                            label = { Text("JEV API 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = jevKey, onValueChange = { jevKey = it },
                            label = { Text("JEV API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = jevModel, onValueChange = { jevModel = it },
                            label = { Text("JEV 模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        HorizontalDivider()
                        Text("意图解读路由")
                        OutlinedTextField(value = if (route == "llm") "通用大模型 LLM" else "JEV 决策模型",
                            onValueChange = { route = if (it.contains("LLM")) "llm" else "jev" },
                            label = { Text("意图解读") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        androidx.compose.foundation.layout.Row(modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("允许手动生成回复")
                            Switch(checked = consent, onCheckedChange = { consent = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            p.edit().putString("reply_provider", provider).putString("reply_endpoint", endpoint)
                                .putString("reply_api_key", apiKey).putString("reply_model", model)
                                .putString("reply_consent", consent.toString())
                                .putString("intent_route", route)
                                .putString("api_provider", jevProvider).putString("api_base", jevEndpoint)
                                .putString("api_key", jevKey).putString("api_model", jevModel).commit()
                            ModulePrefs.init(ctx)
                        }.onFailure { WeLogger.e(TAG, "save yanwai config failed", it) }
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { onDismiss() }) { Text("取消") } },
            )
        }
    }
}
