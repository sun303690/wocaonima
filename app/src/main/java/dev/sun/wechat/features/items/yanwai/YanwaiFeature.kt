package dev.sun.wechat.features.items.yanwai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Context
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.yanwai.core.ModulePrefs
import dev.sun.wechat.features.items.yanwai.hook.MessageSniffer
import dev.sun.wechat.features.items.yanwai.hook.MessageSniffer
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
        // pause/resume 绑定 Activity 生命周期，不由功能开关直接调用
        WeLogger.i(TAG, "yanwai analysis disabled")
    }

    override fun onClick(context: ComponentActivity) {
        val ctx = context.applicationContext ?: context
        val p = ctx.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
        var enabled by remember { mutableStateOf(p.getBoolean(ModulePrefs.KEY_ENABLED, false)) }
        var apiProvider by remember { mutableStateOf(p.getString(ModulePrefs.KEY_API_PROVIDER, ModulePrefs.JevProvider.TYPESAFE.id)) }
        var apiKey by remember { mutableStateOf(p.getString(ModulePrefs.KEY_API_KEY, "")) }
        var apiBase by remember { mutableStateOf(p.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT)) }
        var apiModel by remember { mutableStateOf(p.getString(ModulePrefs.KEY_API_MODEL, "")) }
        var jevEnabled by remember { mutableStateOf(p.getBoolean(ModulePrefs.KEY_JEV_ENABLED, false)) }
        var jevProvider by remember { mutableStateOf(p.getString(ModulePrefs.KEY_JEV_PROVIDER, JevProvider.TYPESAFE.id)) }
        var jevKey by remember { mutableStateOf(p.getString(ModulePrefs.KEY_JEV_KEY, "")) }
        var jevBase by remember { mutableStateOf(p.getString(ModulePrefs.KEY_JEV_BASE, JevProvider.DEFAULT_ENDPOINT)) }
        var jevModel by remember { mutableStateOf(p.getString(ModulePrefs.KEY_JEV_MODEL, "")) }
        var intentRoute by remember { mutableStateOf(p.getString(ModulePrefs.KEY_INTENT_ROUTE, "jev") ?: "jev") }
        var helpMeBackEnabled by remember { mutableStateOf(p.getBoolean(ModulePrefs.KEY_HELP_ME_BACK_ENABLED, true)) }
        var showBubble by remember { mutableStateOf(p.getBoolean(ModulePrefs.KEY_SHOW_BUBBLE, true)) }

        showComposeContext = remember { mutableStateOf(false) }
        showComposeDialog(context, directlyDismissable = false) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Left side: Analysis Controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text("分析控制", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        label = { Text("启用情绪分析") }
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key (JEV/LLM)") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiBase,
                        onValueChange = { apiBase = it },
                        label = { Text("API 基础地址") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiModel,
                        onValueChange = { apiModel = it },
                        label = { Text("模型 ID") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = jevKey,
                        onValueChange = { jevKey = it },
                        label = { Text("JEV API Key") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = jevBase,
                        onValueChange = { jevBase = it },
                        label = { Text("JEV 基础地址") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = jevModel,
                        onValueChange = { jevModel = it },
                        label = { Text("JEV 模型 ID") },
                        labelPosition = androidx.compose.material3.LabelPosition.End,
                        singleLine = true
                    )
                    Row(
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("意图解读路由")
                        Switch(
                            checked = intentRoute == "llm",
                            onCheckedChange = { intentRoute = if (it) "llm" else "jev" },
                            label = { Text("LLM (通用大模型)") }
                        )
                    }
                    Switch(
                        checked = helpMeBackEnabled,
                        onCheckedChange = { helpMeBackEnabled = it },
                        label = { Text("允许帮我回") }
                    )
                    Switch(
                        checked = showBubble,
                        onCheckedChange = { showBubble = it },
                        label = { Text("显示情绪气泡") }
                    )
                }
                Divider(color = androidx.compose.material3.MaterialTheme.colorScheme.outline, thickness = 1.dp)
                // Right side: 帮我回 · 找话题
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text("帮我回 · 扳话题", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { /* TODO: 跳转到帮我回设置 */ },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true
                    ) {
                        Text("帮我回设置")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { /* TODO: 跳转到话题建议设置 */ },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true
                    ) {
                        Text("话题建议设置")
                    }
                }
            }
        }
    }
}
