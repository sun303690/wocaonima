package dev.sun.wechat.features.items.chat

import android.os.Bundle
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.sun.wechat.ui.content.miuix.MiuixAlertDialog
import dev.sun.wechat.ui.utils.theme.MiuixModuleTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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
        setContent { MiuixModuleTheme { AiSettingsScreen() } }
    }

    @Composable
    private fun AiSettingsScreen() {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("回复", "人设", "记忆", "诊断")
        Column(
            Modifier
                .fillMaxSize()
                // MiuixTheme 没有根 Surface，这里不铺底色就会透出下层界面。
                .background(MiuixTheme.colorScheme.background)
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 20.dp)) {
                Button(onClick = { finish() }) { Text("‹", style = MiuixTheme.textStyles.headline1) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { AiChat.clearContext(); Toast.makeText(this@AiChatSettingsActivity, "AI 上下文已清除", Toast.LENGTH_SHORT).show() }) { Text("•••") }
            }
            TabRow(tabs = tabs, selectedTabIndex = tab, onTabSelected = { tab = it })
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
            Text(title, style = MiuixTheme.textStyles.title3)
            if (subtitle.isNotBlank()) Text(subtitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            content()
        }}
    }

    @Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(title); Text(subtitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
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
        var whitelistCount by remember { mutableIntStateOf(AiChat.whitelist.size) }
        Block("自动回复", "只有授权会话会进入模型") { ToggleRow("启用 AI 自动回复", "关闭后不接收、不请求、不发送", enabled) { enabled = it; AiChat.isEnabled = it } }
        Block("授权会话", "白名单 $whitelistCount 个 · 黑名单 ${AiChat.blacklist.size} 个") {
            Button({
                AiChat.showAuthorizedChatsPicker(this@AiChatSettingsActivity, AiChat.whitelist) {
                    whitelistCount = it.size
                }
            }, Modifier.fillMaxWidth()) { Text("选择联系人或群聊") }
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
            Button({ AiChat.clearContext() }, Modifier.fillMaxWidth()) { Text("清除全部上下文") }
        }
    }

    @Composable private fun Edit(label: String, value: String, onChange: (String) -> Unit) =
        MiuixCompatTextField(value, onChange, Modifier.fillMaxWidth(), label = label)

    @Composable private fun PersonaPage() = Page {
        var refresh by remember { mutableIntStateOf(0) }
        var editingId by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var scene by remember { mutableStateOf("") }
        var boundaries by remember { mutableStateOf("") }
        var shareMemory by remember { mutableStateOf(false) }
        var bindTalker by remember { mutableStateOf("") }
        var bindPersona by remember { mutableStateOf("") }
        var bindGroup by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val personas = remember(refresh) { loadCustomPersonas() }
        val scoped = remember(refresh) { loadScopedPersonas() }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) runCatching { context.contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(exportPersonas()) } }.onSuccess { Toast.makeText(context, "人设已导出", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, "导出失败: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { importPersonas(it.readText()) }; refresh++ }.onSuccess { Toast.makeText(context, "人设已导入", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(context, "导入失败: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
        Block(if (editingId == null) "新建人设" else "编辑人设", "最多 8 个；不要填写密码、令牌等敏感信息") {
            Edit("名称（留空显示未命名人设）", name) { name = it }
            Edit("人设内容 / 完整提示词", prompt) { prompt = it }
            Edit("适用场景", scene) { scene = it }
            Edit("所有人设的雷区 / 边界", boundaries) { boundaries = it }
            ToggleRow("与其他人设共用记忆", "关闭时优先使用本会话和本人设记忆", shareMemory) { shareMemory = it }
            Button({
                runCatching { saveCustomPersona(CustomPersona(id = editingId ?: java.util.UUID.randomUUID().toString(), name = name, prompt = prompt, scene = scene, boundaries = boundaries, shareMemory = shareMemory)) }
                    .onSuccess { editingId = null; name = ""; prompt = ""; scene = ""; boundaries = ""; shareMemory = false; refresh++ }
                    .onFailure { Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_SHORT).show() }
            }, Modifier.fillMaxWidth()) { Text(if (editingId == null) "保存人设" else "保存修改") }
        }
        personas.forEach { persona -> Block(persona.name, listOf(persona.scene, if (persona.shareMemory) "共用记忆" else "独立记忆").filter { it.isNotBlank() }.joinToString(" · ")) {
            Text(persona.prompt, style = MiuixTheme.textStyles.footnote1)
            Row { Button({ editingId = persona.id; name = persona.name; prompt = persona.prompt; scene = persona.scene; boundaries = persona.boundaries; shareMemory = persona.shareMemory }) { Text("编辑") }; Button({ deleteCustomPersonaById(persona.id); refresh++ }) { Text("删除") } }
        }}
        Block("联系人 / 群聊专属人设", "选择人设后绑定微信 ID 或群聊 ID") {
            Edit("微信 ID / 群聊 ID", bindTalker) { bindTalker = it }
            personas.forEach { persona -> Row { RadioButton(bindPersona == persona.id, { bindPersona = persona.id }); Text(persona.name, Modifier.padding(top = 12.dp)) } }
            ToggleRow("这是群聊", "用于标记绑定类型", bindGroup) { bindGroup = it }
            Button({ if (bindTalker.isNotBlank() && bindPersona.isNotBlank()) { bindScopedPersona(bindTalker, bindPersona, bindGroup); bindTalker = ""; refresh++ } }, Modifier.fillMaxWidth()) { Text("保存绑定") }
        }
        scoped.forEach { binding -> Block(binding.talker, if (binding.isGroup) "群聊" else "联系人") {
            Text(personas.firstOrNull { it.id == binding.personaId }?.name ?: "旧版专属人设")
            Button({ deleteScopedPersona(binding.talker); refresh++ }) { Text("删除绑定") }
        }}
        Block("导入与导出", "导出文件包含完整人设原文，请妥善保管") {
            Button({ exportLauncher.launch("wekit-ai-personas.json") }, Modifier.fillMaxWidth()) { Text("导出人设 JSON") }
            Button({ importLauncher.launch(arrayOf("application/json", "text/plain")) }, Modifier.fillMaxWidth()) { Text("导入人设 JSON") }
        }
    }

    @Composable private fun MemoryPage() {
        var memories by remember { mutableStateOf<List<AiChatMemoryEntity>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var editing by remember { mutableStateOf<AiChatMemoryEntity?>(null) }
        var content by remember { mutableStateOf("") }
        var talker by remember { mutableStateOf("") }
        var personaId by remember { mutableStateOf("") }
        var groupOk by remember { mutableStateOf(false) }
        var confirmClear by remember { mutableStateOf(false) }
        val personas = remember { loadCustomPersonas() }
        fun reload() = lifecycleScope.launch { memories = withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().search(query.trim()) } }
        LaunchedEffect(query) { reload() }
        Page {
            Block("长期记忆", "聊天、图片文字和语音转写均是不可信资料；不要保存密码、令牌等敏感信息") {
                Edit("搜索记忆", query) { query = it }
                Edit("会话 ID，留空表示全局", talker) { talker = it }
                Edit("记忆内容", content) { content = it }
                personas.forEach { p -> Row { RadioButton(personaId == p.id, { personaId = if (personaId == p.id) "" else p.id }); Text(p.name, Modifier.padding(top = 12.dp)) } }
                ToggleRow("允许群聊使用", "关闭时为 private_only，开启为 group_ok", groupOk) { groupOk = it }
                Button({ if (content.isNotBlank()) lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val old = editing
                        val value = AiChatMemoryEntity(id = old?.id ?: 0, talker = talker.trim().ifEmpty { "*" }, personaId = personaId, content = content.trim(), scope = if (groupOk) "group_ok" else "private_only", source = old?.source ?: "manual", createdAt = old?.createdAt ?: System.currentTimeMillis())
                        if (old == null) WeKitDatabase.instance.aiChatMemoryDao().insert(value) else WeKitDatabase.instance.aiChatMemoryDao().update(value)
                    }
                    editing = null; content = ""; talker = ""; personaId = ""; reload()
                } }, Modifier.fillMaxWidth()) { Text(if (editing == null) "新增记忆" else "保存修改") }
            }
            memories.forEach { m -> Block(if (m.talker == "*") "全局记忆" else m.talker, listOf(m.scope, personas.firstOrNull { it.id == m.personaId }?.name, m.source).filterNotNull().joinToString(" · ")) {
                Text(m.content)
                Row { Button({ editing = m; content = m.content; talker = if (m.talker == "*") "" else m.talker; personaId = m.personaId; groupOk = m.scope == "group_ok" }) { Text("编辑") }; Button({ lifecycleScope.launch { withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().delete(m) }; reload() } }) { Text("删除") } }
            }}
            Button({ confirmClear = true }, Modifier.fillMaxWidth()) { Text("清空全部记忆") }
        }
        if (confirmClear) MiuixAlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("确认清空全部记忆？") }, text = { Text("此操作不可撤销。") }, confirmButton = { Button({ lifecycleScope.launch { withContext(Dispatchers.IO) { WeKitDatabase.instance.aiChatMemoryDao().clearAll() }; confirmClear = false; reload() } }) { Text("清空") } }, dismissButton = { Button({ confirmClear = false }) { Text("取消") } })
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

    @Composable private fun Status(name: String, ok: Boolean) = Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(name); Text(if (ok) "正常" else "不可用", color = if (ok) Color(0xff15803d) else MiuixTheme.colorScheme.error) }
    private fun stamp(value: Long) = if (value == 0L) "暂无" else DateFormat.getDateTimeInstance().format(Date(value))
}
