package dev.sun.wechat.features.items.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Refresh
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TiaxVoicePicker"

/** 音色表的本地缓存键：一次拉到之后离线也能选。
 *  v2：早期版本把音色名当接口值（服务端不认名字，一律回落 1 号音色），改为存序号。 */
private const val TIAX_VOICES_CACHE = "tts_tiax_voices_cache_v2"

/** 天X 音色表地址，相对接口地址。 */
private const val TIAX_VOICES_PATH = "/API/ys.php"

private val tiaxMainHandler = Handler(Looper.getMainLooper())

/** 天X 的一个音色：[value] 是发给接口的值（**序号**，服务端只认数字），[label] 是列表里显示的文字（序号 + 名称）。 */
data class TiaxVoiceOption(val value: String, val label: String)

/** 读本地缓存的音色表；没拉过就返回空列表。 */
fun loadTiaxVoiceCache(): List<TiaxVoiceOption> {
    val raw = runCatching { KvStore.getStringOrDef(TIAX_VOICES_CACHE, "") }.getOrDefault("")
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) null else TiaxVoiceOption(line.substring(0, tab), line.substring(tab + 1))
    }.toList()
}

private fun saveTiaxVoiceCache(list: List<TiaxVoiceOption>) {
    runCatching {
        KvStore.putString(TIAX_VOICES_CACHE, list.joinToString("\n") { it.value + "\t" + it.label })
    }
}

/**
 * 拉天X 的音色表（`/API/ys.php` 是纯文本，一行一个「序号. 名称」）。
 *
 * 网络异常向上抛，让调用方决定显示什么；这里不做兜底，避免"静默给了空列表"。
 */
fun fetchTiaxVoices(baseUrl: String, timeoutMs: Int = 20_000): List<TiaxVoiceOption> {
    val base = baseUrl.trim().ifBlank { "https://www.tiax.pw" }.trimEnd('/')
    val conn = (URL(base + TIAX_VOICES_PATH).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val pattern = Regex("""^\s*(\d+)\.\s*(.+?)\s*$""")
        return text.lineSequence().mapNotNull { line ->
            val match = pattern.find(line) ?: return@mapNotNull null
            val index = match.groupValues[1]
            val name = match.groupValues[2]
            TiaxVoiceOption(index, "$index. $name")
        }.toList()
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** 把接口值（序号）翻成给人看的文字；缓存里没有就原样返回。 */
fun tiaxVoiceLabel(value: String): String {
    if (value.isBlank()) return ""
    val hit = loadTiaxVoiceCache().firstOrNull { it.value == value }
    return hit?.label ?: value
}

/**
 * 归一化音色参数：接口只认序号，传名字会被静默忽略（回落 1 号音色）。
 *
 * 老版本存的是音色名，这里按缓存表反查出序号；查不到就原样返回，交给服务端回落。
 */
fun normalizeTiaxVoice(value: String, baseUrl: String): String {
    val raw = value.trim()
    if (raw.isEmpty() || raw.all { it.isDigit() }) return raw
    loadTiaxVoiceCache().firstOrNull { it.label.endsWith(raw) || it.label == raw }
        ?.let { return it.value }
    return runCatching {
        fetchTiaxVoices(baseUrl).firstOrNull { it.label.endsWith(raw) || it.label == raw }?.value
    }.getOrNull() ?: raw
}

/**
 * 天X 的「语气」并不是接口参数（`yuyin2.php` 只认 text/voice/apikey，
 * 外加 emotion/speed/pitch 实测都被忽略），语气体现在音色名上，
 * 所以这里按音色名归类，给选择器做筛选标签。
 */
data class TiaxVoiceTone(val title: String, val keywords: List<String>)

/** 音色表里出现过的语气/角色分类；顺序即界面上的顺序。 */
val TIAX_TONES: List<TiaxVoiceTone> = listOf(
    TiaxVoiceTone("温柔", listOf("温柔")),
    TiaxVoiceTone("可爱", listOf("可爱", "萝莉", "萌", "童声", "小孩")),
    TiaxVoiceTone("甜", listOf("甜", "撒娇", "少女")),
    TiaxVoiceTone("磁性", listOf("磁性", "低沉")),
    TiaxVoiceTone("御姐", listOf("御姐", "女王", "成熟女")),
    TiaxVoiceTone("冷酷", listOf("冷酷", "冷漠", "霸道")),
    TiaxVoiceTone("大叔", listOf("大叔", "中年", "老者", "爷爷")),
    TiaxVoiceTone("少年", listOf("少年", "青年", "男孩")),
    TiaxVoiceTone("方言", listOf("方言", "粤语", "东北", "四川", "台湾", "河南", "天津", "陕西")),
    TiaxVoiceTone("外语", listOf("英语", "日语", "韩语", "英文", "俄语")),
)

/** 取音色名命中的第一个语气分类；没命中返回 null（归到「全部」）。 */
fun toneOf(label: String): String? =
    TIAX_TONES.firstOrNull { tone -> tone.keywords.any { label.contains(it) } }?.title

/**
 * 天X 音色选择弹窗。
 *
 * 458 个音色塞不进下拉框，所以开一个可搜索的列表；先铺本地缓存，再后台刷新一次。
 * 选中即回调并关闭，[onPicked] 的两个参数分别是接口值（序号）与显示文字。
 */
fun showTiaxVoicePicker(
    context: Context,
    currentVoice: String,
    baseUrl: String,
    onPicked: (voice: String, label: String) -> Unit,
) {
    val listState = mutableStateOf(loadTiaxVoiceCache())
    val loadingState = mutableStateOf(false)
    val statusState = mutableStateOf("")
    var generation = 0

    fun refresh() {
        if (loadingState.value) return
        val mine = ++generation
        loadingState.value = true
        statusState.value = ""
        Thread {
            val outcome = runCatching { fetchTiaxVoices(baseUrl) }
            tiaxMainHandler.post {
                if (mine != generation) return@post
                loadingState.value = false
                outcome.fold(
                    onSuccess = { voices ->
                        if (voices.isEmpty()) {
                            statusState.value = "音色表是空的，点右侧刷新重试"
                        } else {
                            listState.value = voices
                            saveTiaxVoiceCache(voices)
                            statusState.value = "共 ${voices.size} 个音色"
                        }
                    },
                    onFailure = {
                        val reason = it.message ?: "网络异常"
                        statusState.value = "拉取失败：$reason"
                        WeLogger.w(TAG, "fetch tiax voices failed: $reason")
                    },
                )
            }
        }.start()
    }

    val cached = listState.value
    if (cached.isEmpty()) {
        refresh()
    } else {
        statusState.value = "共 ${cached.size} 个音色"
    }

    showComposeDialog(context) {
        val voices by listState
        val loading by loadingState
        val status by statusState
        var query by remember { mutableStateOf("") }
        var tone by remember { mutableStateOf<String?>(null) }
        val filtered = voices
            .filter { tone == null || toneOf(it.label) == tone }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true)
            }

        AlertDialogContent(
            title = { Text("选择天X 音色") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = "搜索音色名或序号",
                            useLabelAsPlaceholder = true,
                        )
                        IconButton(onClick = { refresh() }) {
                            Icon(
                                MaterialSymbols.Outlined.Refresh,
                                contentDescription = "重新拉取音色表",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToneChip(title = "全部", selected = tone == null) { tone = null }
                        TIAX_TONES.forEach { item ->
                            ToneChip(
                                title = item.title,
                                selected = tone == item.title,
                            ) { tone = if (tone == item.title) null else item.title }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(status.ifBlank { "共 ${voices.size} 个音色" })
                        Text("当前：${tiaxVoiceLabel(currentVoice).ifBlank { "未选择" }}")
                    }

                    Spacer(Modifier.height(6.dp))

                    if (loading && voices.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(filtered) { voice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onPicked(voice.value, voice.label)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        voice.label,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (voice.value == currentVoice) {
                                        Icon(
                                            MaterialSymbols.Outlined.Check,
                                            contentDescription = "当前音色",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("刷新", modifier = Modifier.clickable { refresh() })
                    }
                }
            },
            dismissButton = {
                    Text("关闭", modifier = Modifier.clickable { onDismiss() })
                },
        )
    }
}

/** 语气筛选标签：选中态用实心主色，未选中用半透明黑底，不依赖主题语义色。 */
@Composable
private fun ToneChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF6750A4) else Color(0x14000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = if (selected) Color.White else Color(0xFF49454F),
                fontSize = 14.sp,
            ),
        )
    }
}
