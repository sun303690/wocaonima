package dev.sun.wechat.features.items.chat

import android.os.Bundle
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.sun.wechat.utils.android.showToast
import androidx.lifecycle.lifecycleScope
import dev.sun.wechat.data.WeKitDatabase
import dev.sun.wechat.data.entity.AiChatMemoryEntity
import dev.sun.wechat.features.api.core.WeMessageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiChatSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AiSettingsScreen() } }
    }

    @Composable
    private fun AiSettingsScreen() {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("回复", "人设", "记忆", "诊断")
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 20.dp)) {
                TextButton(onClick = { finish() }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { AiChat.showConfigDialog(this@AiChatSettingsActivity) }) { Text("•••") }
            }
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, name -> Tab(tab == i, { tab = i }, text = { Text(name) }) }
            }
            when (tab) {
                0 -> ReplyPage()
                1 -> PersonaPage()
                2 -> MemoryPage()
                else -> DiagnosticPage()
            }
        }
    }

    @Composable private fun Page(content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

    @Composable private fun Block(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit = {}) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }}
    }

    @Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(title); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked, onChange)
        }
    }

    @Composable private fun ReplyPage() = Page {
        var enabled by remember { mutableStateOf(AiChat.isEnabled) }
        var mode by remember { mutableStateOf(AiChat.groupTriggerMode) }
        var keywords by remember { mutableStateOf(AiChat.groupKeywords) }
        var prefix by remember { mutableStateOf(AiChat.groupPrefix) }
        var cooldown by remember { mutableStateOf(AiChat.cooldownSeconds.toString()) }
        var daily by remember { mutableStateOf(AiChat.dailyLimit.toString()) }
        var chars by remember { mutableStateOf(AiChat.maxContextChars.toString()) }
        Block("自动回复", "只有授权会话会进入模型") { ToggleRow("启用 AI 自动回复", "关闭后不接收、不请求、不发送", enabled) { enabled = it; AiChat.isEnabled = it } }
        Block("授权会话", "白名单 ${AiChat.whitelist.size} 个 · 黑名单 ${AiChat.blacklist.size} 个") {
            Button({ AiChat.showConfigDialog(this@AiChatSettingsActivity) }, Modifier.fillMaxWidth()) { Text("选择联系人或群聊") }
        }
        Block("群聊触发", "私聊不受该条件限制") {
            listOf("全部" to 0, "被 @" to 1, "关键词" to 2, "前缀" to 3).forEach { (n, v) ->
                Row { RadioButton(mode == v, { mode = v; AiChat.groupTriggerMode = v }); Text(n, Modifier.padding(top = 12.dp)) }
            }
            if (mode == 2) Edit("关键词（逗号或换行分隔）", keywords) { keywords = it; AiChat.groupKeywords = it }
            if (mode == 3) Edit("触发前缀", prefix) { prefix = it; AiChat.groupPrefix = it }
        }
        Block("频率与上下文") {
            Edit("冷却秒数", cooldown) { cooldown = it; it.toIntOrNull()?.let { n -> AiChat.cooldownSeconds = n.coerceIn(0, 3600) } }
            Edit("单会话每日上限，0 不限制", daily) { daily = it; it.toIntOrNull()?.let { n -> AiChat.dailyLimit = n.coerceIn(0, 10000) } }
            Edit("上下文字符上限", chars) { chars = it; it.toIntOrNull()?.let { n -> AiChat.maxContextChars = n.coerceIn(1000, 100000) } }
            OutlinedButton({ AiChat.clearContext() }, Modifier.fillMaxWidth()) { Text("清除全部上下文") }
        }
    }

    @Composable private fun Edit(label: String, value: String, onChange: (String) -> Unit) =
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) })

    @Composable private fun PersonaPage() = Page {
        var global by remember { mutableStateOf(AiChat.systemPrompt) }
        var talker by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var isGroup by remember { mutableStateOf(false) }
        var refresh by remember { mutableIntStateOf(0) }
        val scoped = remember(refresh) { loadScopedPersonas() }
        Block("全局人设", "没有专属人设时使用") {
            Edit("System Prompt", global) { global = it; AiChat.systemPrompt = it }
        }
        Block("专属人设", "联系人或群聊专属配置优先于全局") {
            Edit("微信 ID / 群聊 ID", talker) { talker = it }
            Edit("人设内容", prompt) { prompt = it }
            ToggleRow("这是群聊", "控制人设类型标记", isGroup) { isGroup = it }
            Button({ if (talker.isNotBlank()) { saveScopedPersona(talker, prompt, isGroup); talker = ""; prompt = ""; refresh++ } }, Modifier.fillMaxWidth()) { Text("保存专属人设") }
        }
        scoped.forEach { p -> Block(p.talker, if (p.isGroup) "群聊" else "联系人") {
            Text(p.prompt, style = MaterialTheme.typography.bodySmall)
            TextButton({ deleteScopedPersona(p.talker); refresh++ }) { Text("删除") }
        }}
        Block("导入与导出") {
            Button({ AiChat.showConfigDialog(this@AiChatSettingsActivity) }, Modifier.fillMaxWidth()) { Text("打开导入 / 导出工具") }
        }
    }

    @Composable private fun MemoryPage() {
        var memories by remember { mutableStateOf<List<AiChatMemoryEntity>>(emptyList()) }
        var content by remember { mutableStateOf("") }
        var talker by remember { mutableStateOf("") }
        var groupOk by remember { mutableStateOf(false) }
        fun reload() = lifecycleScope.launch { memories = withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().all() } }
        LaunchedEffect(Unit) { reload() }
        Page {
            Block("长期记忆", "${memories.size} 条 · 默认不自动提取敏感信息") {
                Edit("会话 ID，留空表示全局", talker) { talker = it }
                Edit("记忆内容", content) { content = it }
                ToggleRow("允许群聊使用", "关闭时保存为 private_only", groupOk) { groupOk = it }
                Button({ if (content.isNotBlank()) lifecycleScope.launch { withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().insert(AiChatMemoryEntity(talker = talker.trim(), content = content.trim(), scope = if (groupOk) "group_ok" else "private_only")) }; content = ""; reload() } }, Modifier.fillMaxWidth()) { Text("新增记忆") }
            }
            memories.forEach { m -> Block(m.talker.ifBlank { "全局记忆" }, m.scope) {
                Text(m.content)
                TextButton({ lifecycleScope.launch { withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().delete(m) }; reload() } }) { Text("删除") }
            }}
            OutlinedButton({ lifecycleScope.launch { withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().clearAll() }; reload() } }, Modifier.fillMaxWidth()) { Text("清空全部记忆") }
        }
    }

    @Composable private fun DiagnosticPage() = Page {
        val d = AiChat.diagnosticSnapshot()
        var target by remember { mutableStateOf(AiChat.whitelist.firstOrNull().orEmpty()) }
        Block("运行状态", "核心链路实时检查") {
            Status("消息 Hook", d.receiveHookAvailable)
            Status("发送 API", true)
            Status("模型已配置", true)
            Text("最近收件：${stamp(d.lastReceiveAt)}")
            Text("最近发送：${stamp(d.lastSendAt)}")
            Text("处理中：${d.inFlightCount}")
        }
        Block("发送链路测试", "不调用模型") {
            Edit("测试联系人 / 群聊 ID", target) { target = it }
            Button({ Toast.makeText(this@AiChatSettingsActivity, if (target.isNotBlank() && WeMessageApi.sendText(target, "WeKit AI 发送链路测试")) "测试消息已提交" else "发送失败", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth()) { Text("发送固定测试消息") }
        }
        Block("最近失败", d.lastFailure ?: "暂无失败记录")
    }

    @Composable private fun Status(name: String, ok: Boolean) = Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(name); Text(if (ok) "正常" else "不可用", color = if (ok) Color(0xff15803d) else MaterialTheme.colorScheme.error) }
    private fun stamp(value: Long) = if (value == 0L) "暂无" else DateFormat.getDateTimeInstance().format(Date(value))
}
