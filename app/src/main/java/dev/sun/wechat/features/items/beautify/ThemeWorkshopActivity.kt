package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sun.wechat.activity.settings.M3ListScaffold
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.content.m3.BaseItemContainer
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.utils.theme.ModuleTheme
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.restartHost

@Keep
class ThemeWorkshopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme { ThemeWorkshopScreen(this) }
            }
        }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, ThemeWorkshopActivity::class.java)
    }
}

@Composable
private fun ThemeWorkshopScreen(activity: ComponentActivity) {
    val builtins = remember { ThemeStore.builtinThemeItems() }
    var selected by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var opacity by remember { mutableIntStateOf(ThemeStore.overlayOpacity()) }
    var diagnostic by remember { mutableStateOf(ThemeStore.diagnose()) }

    M3ListScaffold(title = "主题工坊") {
        item {
            SegmentedColumn(title = "主题") {
                builtins.forEach { (id, fallback) ->
                    item(key = id) {
                        val name = ThemeStore.builtinDisplayName(id, fallback)
                        BaseWidget(
                            title = name,
                            description = when {
                                active == id -> "当前生效"
                                selected == id -> "已选中，点击下方应用"
                                else -> "内置主题"
                            },
                            onClick = { selected = id },
                        )
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = "显示") {
                item(key = "opacity") {
                    BaseItemContainer {
                        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                            Text("壁纸透明度：$opacity%", style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = opacity.toFloat(),
                                onValueChange = { opacity = it.toInt().coerceIn(5, 70) },
                                onValueChangeFinished = { ThemeStore.setOverlayOpacity(opacity) },
                                valueRange = 5f..70f,
                            )
                        }
                    }
                }
                item(key = "renderer") {
                    BaseWidget(
                        title = "壁纸渲染方式",
                        description = "${ThemeStore.renderModeName()}｜${ThemeStore.maskBrief()}",
                        onClick = {
                            ThemeStore.reapplyNow()
                            diagnostic = ThemeStore.diagnose()
                            showToast(activity, "已重写主题遮罩")
                        },
                    )
                }
            }
        }
        item {
            BaseItemContainer {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ThemeStore.restoreDefaultTheme()
                            selected = ""
                            active = ""
                            diagnostic = ThemeStore.diagnose()
                        },
                    ) { Text("恢复默认") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = selected.isNotBlank(),
                        onClick = {
                            ThemeStore.applyThemeById(selected)
                            active = selected
                            diagnostic = ThemeStore.diagnose()
                            restartHost()
                        },
                    ) { Text("应用并重启") }
                }
            }
        }
        item {
            SegmentedColumn(title = "主题诊断") {
                item(key = "refresh_diagnostic") {
                    BaseWidget(
                        title = "刷新诊断",
                        description = "检查开关、素材、遮罩、分页 Hook、聊天页判定和最近事件",
                        onClick = { diagnostic = ThemeStore.diagnose() },
                    )
                }
                item(key = "diagnostic") {
                    BaseItemContainer {
                        Text(
                            text = diagnostic,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item(key = "clear_cache") {
                    BaseWidget(
                        title = "清除主题缓存",
                        description = "移除残留气泡、内置素材缓存和背景遮罩，不删除用户主题包",
                        onClick = {
                            ThemeStore.clearThemeCache()
                            diagnostic = ThemeStore.diagnose()
                            showToast(activity, "主题缓存已清除")
                        },
                    )
                }
            }
        }
    }
}
