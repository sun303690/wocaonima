package dev.sun.wechat.features.items.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.sun.wechat.data.WeKitDatabase
import dev.sun.wechat.data.entity.AiChatMemoryEntity
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.utils.theme.ModuleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiChatSettingsActivity : ComponentActivity() {
 override fun onCreate(state: Bundle?) { super.onCreate(state); enableEdgeToEdge(); setContent { WeKitLocaleProvider(mode=LocaleResourceMode.InjectedHost){ ModuleTheme { AiSettings(::finish) } } } }
}

@Composable private fun AiSettings(back:()->Unit) {
 var tab by remember { mutableIntStateOf(0) }; val titles=listOf("回复","人设","记忆","诊断")
 Column(Modifier.fillMaxSize().statusBarsPadding()) {
  Row(Modifier.fillMaxWidth().height(44.dp),horizontalArrangement=Arrangement.SpaceBetween){ TextButton(onClick=back){Text("‹")} ; TextButton(onClick={}){Text("•••")} }
  PrimaryTabRow(selectedTabIndex=tab){ titles.forEachIndexed{i,t->Tab(selected=tab==i,onClick={tab=i},text={Text(t)})} }
  when(tab){0->ReplyPage();1->PersonaPage();2->MemoryPage();else->DiagnosticPage()}
 }
}
@Composable private fun Page(content:@Composable ColumnScope.()->Unit)=LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Column(verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable private fun CardBlock(title:String,sub:String="",body:@Composable ColumnScope.()->Unit={})=Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);if(sub.isNotBlank())Text(sub,style=MaterialTheme.typography.bodySmall);body()}}
@Composable private fun ReplyPage()=Page{CardBlock("AI 自动回复","只在授权会话中工作"){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("总开关");Switch(false,{})}};CardBlock("授权会话","3 个联系人 · 2 个群聊"){Button({},Modifier.fillMaxWidth()){Text("管理授权名单")}};CardBlock("群聊触发"){Text("被 @ 时");Text("关键词 / 指定前缀")} ;CardBlock("回复限制","冷却、每日上限与上下文长度");OutlinedButton({},Modifier.fillMaxWidth()){Text("清除全部上下文")}}
@Composable private fun PersonaPage()=Page{CardBlock("全局人设","所有未配置专属人设的会话使用"){OutlinedButton({},Modifier.fillMaxWidth()){Text("编辑全局人设")}};CardBlock("专属人设","联系人 2 · 群聊 1"){Button({},Modifier.fillMaxWidth()){Text("新增专属人设")}};CardBlock("导入与导出"){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({}){Text("导入")};Button({}){Text("导出")}}}}
@Composable private fun MemoryPage(){val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current;var list by remember{mutableStateOf(emptyList<AiChatMemoryEntity>())};LaunchedEffect(Unit){withContext(Dispatchers.IO){list=WeKitDatabase.instance.aiChatMemoryDao().all()}};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{CardBlock("长期记忆","private_only 不会进入群聊；默认不自动提取")};items(list,key={it.id}){m->CardBlock(if(m.scope=="group_ok")"可用于群聊" else "仅私聊",m.talker){Text(m.content)}};item{Button({},Modifier.fillMaxWidth()){Text("新增记忆")};OutlinedButton({owner.lifecycleScope.launch(Dispatchers.IO){WeKitDatabase.instance.aiChatMemoryDao().clearAll();list=emptyList()}},Modifier.fillMaxWidth()){Text("清空全部记忆")}}}}
@Composable private fun DiagnosticPage()=Page{CardBlock("运行状态","核心链路实时检查"){Status("消息 Hook",WeMessageApi.methodMsgInfoStorageInsertMessage.isAvailable);Status("发送 API",true);Status("模型接口",true);Status("视觉能力",false)};Button({},Modifier.fillMaxWidth()){Text("发送固定测试消息")};OutlinedButton({},Modifier.fillMaxWidth()){Text("测试模型接口")};CardBlock("最近失败","暂无失败记录")}
@Composable private fun Status(name:String,ok:Boolean)=Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name);Text(if(ok)"正常" else "待配置",color=if(ok)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
