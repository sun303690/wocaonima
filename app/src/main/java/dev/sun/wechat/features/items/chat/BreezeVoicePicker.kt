package dev.sun.wechat.features.items.chat

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import dev.sun.wechat.features.items.chat.panel.listPanelTreeFiles
import dev.sun.wechat.features.items.chat.panel.pickPanelDirectory
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.BreezeTtsClient
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.openInSystem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Breeze 的收藏与豆包那套分开存——两边音色 id 不在同一个空间里。 */
object BreezeVoiceFavorites {
    private const val PREF_KEY = "breeze_voice_favorites"

    fun load(): Set<String> = KvStore.getStringSetOrDef(PREF_KEY, emptySet())

    /** 切换收藏，返回切换后的状态。 */
    fun toggle(id: String): Boolean {
        val current = load()
        val added = id !in current
        KvStore.putStringSet(PREF_KEY, if (added) current + id else current - id)
        return added
    }
}

private enum class BreezeVoiceTab(val label: String) {
    OFFICIAL("官方"),
    MINE("我的"),
    FAVORITE("收藏"),
}

private val breezeMainHandler = Handler(Looper.getMainLooper())

/** 克隆预览的试听音频只用来听，落在这个目录，可随时清。 */
private fun breezePreviewDir(): File =
    File(KnownPaths.moduleCache.toFile(), "breeze_previews").apply { mkdirs() }

fun showBreezeVoicePicker(
    context: Context,
    currentId: String,
    apiKey: String,
    onPicked: (id: String, name: String) -> Unit,
) {
    // 列表状态和拉取动作都提到 composable 外面：函数一被调用就立刻发请求，
    // 不依赖 LaunchedEffect —— 自建 Dialog 里的副作用启动时机不可靠，
    // 之前的表现就是打开后必须手动点一下刷新才会有数据。
    val officialState = mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList())
    val mineState = mutableStateOf<List<BreezeTtsClient.Voice>>(emptyList())
    val loadingState = mutableStateOf(false)
    val statusState = mutableStateOf("")
    val key = apiKey.trim()

    fun refresh(onlyMine: Boolean) {
        if (key.isBlank()) {
            statusState.value = "先在文字转语音设置里填写 Breeze API Key"
            return
        }
        loadingState.value = true
        statusState.value = ""
        Thread {
            val failure = StringBuilder()
            val officialList = if (onlyMine) null else runCatching { BreezeTtsClient.voices(key, false) }
                .onFailure { failure.append("官方音色：").append(it.message).append(' ') }
                .getOrNull()
            val mineList = runCatching { BreezeTtsClient.voices(key, true) }
                .onFailure { failure.append("我的音色：").append(it.message).append(' ') }
                .getOrNull()
            breezeMainHandler.post {
                loadingState.value = false
                officialList?.let { officialState.value = it }
                mineList?.let { mineState.value = it }
                if (failure.isNotEmpty()) statusState.value = "拉取失败：$failure"
            }
        }.start()
    }

    // 打开即拉取，不等用户点刷新
    refresh(onlyMine = false)

    showComposeDialog(context) {
        val official by officialState
        val mine by mineState
        val loading by loadingState
        val status by statusState
        var tab by remember { mutableStateOf(BreezeVoiceTab.OFFICIAL) }
        var query by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf(currentId) }
        var favorites by remember { mutableStateOf(BreezeVoiceFavorites.load()) }
        var playing by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }

        DisposableEffect(Unit) {
            onDispose { player?.release() }
        }

        fun stopPreview() {
            player?.let { runCatching { it.stop() } }
            player?.release()
            player = null
            playing = null
        }

        fun playUrl(url: String, tag: String) {
            stopPreview()
            if (url.isBlank()) {
                showToast(context, "这个音色没有试听音频")
                return
            }
            val next = MediaPlayer()
            player = next
            playing = tag
            next.setOnPreparedListener { it.start() }
            next.setOnErrorListener { _, _, _ ->
                playing = null
                showToast(context, "试听失败，链接可能已过期")
                true
            }
            next.setOnCompletionListener { playing = null }
            runCatching {
                next.setDataSource(url)
                next.prepareAsync()
            }.onFailure {
                playing = null
                showToast(context, "试听失败：${it.message}")
            }
        }

        val pool = when (tab) {
            BreezeVoiceTab.OFFICIAL -> official
            BreezeVoiceTab.MINE -> mine
            BreezeVoiceTab.FAVORITE -> (official + mine).filter { it.id in favorites }
        }
        val shown = pool.filter { voice ->
            query.isBlank() || (voice.name + " " + voice.id + " " + voice.meta)
                .contains(query, ignoreCase = true)
        }
        // 选中项可能在别的页签上，取名字要跨两边找
        val pickedName = (official + mine).firstOrNull { it.id == picked }?.name ?: picked.ifBlank { "未选择" }

        AlertDialogContent(
            title = { Text("选择 Breeze 音色") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BreezeVoiceTab.entries.forEach { entry ->
                            Chip(
                                selected = entry == tab,
                                onClick = {
                                    tab = entry
                                    query = ""
                                    // 切到还没拉过的页签时自动补拉，不用再手动点刷新
                                    val empty = when (entry) {
                                        BreezeVoiceTab.OFFICIAL -> officialState.value.isEmpty()
                                        BreezeVoiceTab.MINE -> mineState.value.isEmpty()
                                        BreezeVoiceTab.FAVORITE ->
                                            officialState.value.isEmpty() && mineState.value.isEmpty()
                                    }
                                    if (empty && !loadingState.value) {
                                        refresh(onlyMine = entry == BreezeVoiceTab.MINE)
                                    }
                                },
                            ) { Text(entry.label) }
                        }
                        if (tab == BreezeVoiceTab.MINE) {
                            Chip(
                                selected = false,
                                onClick = {
                                    showBreezeCloneDialog(context, apiKey) { refresh(onlyMine = true) }
                                },
                            ) { Text("＋ 克隆音色") }
                        }
                        Chip(
                            selected = false,
                            onClick = { refresh(onlyMine = tab == BreezeVoiceTab.MINE) },
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Refresh,
                                contentDescription = "重新拉取",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null) },
                        label = "搜索名称、ID 或标签",
                        useLabelAsPlaceholder = true,
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        when {
                            status.isNotBlank() -> status
                            loading -> "正在拉取音色列表…"
                            else -> "官方 ${official.size} 个 · 我的 ${mine.size} 个"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    Spacer(Modifier.height(4.dp))

                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(shown, key = { it.id }) { voice ->
                            BreezeVoiceRow(
                                voice = voice,
                                selected = voice.id == picked,
                                playing = playing == voice.id,
                                favorite = voice.id in favorites,
                                onClick = { picked = voice.id },
                                onPlay = {
                                    if (playing == voice.id) stopPreview() else playUrl(voice.previewUrl, voice.id)
                                },
                                onFavorite = {
                                    favorites = if (BreezeVoiceFavorites.toggle(voice.id)) {
                                        favorites + voice.id
                                    } else {
                                        favorites - voice.id
                                    }
                                },
                            )
                        }
                        if (loading) {
                            item {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (shown.isEmpty() && !loading) {
                            item {
                                Text(
                                    when {
                                        tab == BreezeVoiceTab.FAVORITE && favorites.isEmpty() && query.isBlank() ->
                                            "还没有收藏，点每个音色右侧的心形把它收进来"

                                        tab == BreezeVoiceTab.MINE && query.isBlank() ->
                                            "还没有自建音色，点上面的「＋ 克隆音色」用一条参考音频克隆一个"

                                        else -> "没有匹配的音色"
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("当前选择：$pickedName")
                }
            },
            dismissButton = { TextButton(text = "关闭", onClick = onDismiss) },
            confirmButton = {
                Button(onClick = {
                    stopPreview()
                    onPicked(picked, (official + mine).firstOrNull { it.id == picked }?.name ?: picked)
                    onDismiss()
                }) { Text("确定") }
            },
        )
    }
}

@Composable
private fun BreezeVoiceRow(
    voice: BreezeTtsClient.Voice,
    selected: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MiuixTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(voice.name.take(1), color = MiuixTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
            Text(
                voice.meta.ifBlank { voice.id },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Icon(
                MaterialSymbols.Outlined.Check,
                contentDescription = "已选择",
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onPlay, enabled = voice.previewUrl.isNotBlank()) {
            Icon(
                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                contentDescription = if (playing) "停止试听" else "试听",
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                MaterialSymbols.Outlined.Favorite,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 克隆音色：一条参考样本换一次预览，试听脚本由服务端按检测到的参考语言自动生成。
 * 文档写明 consent required，所以授权勾选做成硬门槛；预览本身不占槽位，保存才占。
 */
private fun showBreezeCloneDialog(
    context: Context,
    apiKey: String,
    onSaved: () -> Unit,
) {
    showComposeDialog(context) {
        var voiceName by remember { mutableStateOf("") }
        var language by remember { mutableStateOf(CLONE_LANGUAGES.first().first) }
        var denoise by remember { mutableStateOf(false) }
        var consent by remember { mutableStateOf(false) }
        // null = 默认目录 Download/WcS/；换成别的目录时这里是 SAF 的 tree uri
        var sampleTree by remember { mutableStateOf<Uri?>(null) }
        var samples by remember { mutableStateOf(defaultSamples()) }
        var sample by remember { mutableStateOf<CloneSample?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<BreezeTtsClient.ClonePreview?>(null) }
        var playing by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }

        DisposableEffect(Unit) {
            onDispose { player?.release() }
        }

        fun stop() {
            player?.let { runCatching { it.stop() } }
            player?.release()
            player = null
            playing = false
        }

        /** 换到用户自选目录：走和语音面板同一个系统目录选择器，列完就 finish 掉透明 Activity。 */
        fun browseSamples() {
            pickPanelDirectory(context) { treeUri, activity ->
                busy = true
                status = "正在列目录…"
                Thread {
                    val outcome = runCatching {
                        listPanelTreeFiles(activity.contentResolver, treeUri)
                            .filter {
                                it.name.substringAfterLast('.', "").lowercase() in BreezeTtsClient.CLONE_SUFFIXES
                            }
                            .map { CloneSample(it.name, "", null, it.uri) }
                    }
                    breezeMainHandler.post {
                        busy = false
                        activity.finish()
                        outcome.fold(
                            onSuccess = {
                                sampleTree = treeUri
                                samples = it
                                sample = null
                                status = if (it.isEmpty()) "这个目录里没有合规的音频文件" else ""
                            },
                            onFailure = { status = "列目录失败：${it.message}" },
                        )
                    }
                }.start()
            }
        }

        fun useDefaultSamples() {
            sampleTree = null
            samples = defaultSamples()
            sample = null
        }

        fun audition(target: BreezeTtsClient.ClonePreview) {
            if (playing) {
                stop()
                return
            }
            busy = true
            status = "正在取试听音频…"
            Thread {
                val outcome = runCatching {
                    val file = File(breezePreviewDir(), "clone_${target.generatedId}.mp3")
                    if (!file.exists()) BreezeTtsClient.previewAudio(apiKey, target.generatedId, file)
                    file.absolutePath
                }
                breezeMainHandler.post {
                    busy = false
                    val path = outcome.getOrElse {
                        status = "试听音频取不到：${it.message}"
                        return@post
                    }
                    val next = MediaPlayer()
                    player = next
                    playing = true
                    next.setOnErrorListener { _, _, _ ->
                        playing = false
                        status = "试听失败"
                        true
                    }
                    next.setOnCompletionListener { playing = false }
                    runCatching {
                        next.setDataSource(path)
                        next.prepare()
                        next.start()
                    }.onFailure {
                        playing = false
                        status = "试听失败：${it.message}"
                    }
                }
            }.start()
        }

        fun clone() {
            val chosen = sample
            if (chosen == null) {
                status = "先选一条参考音频"
                return
            }
            if (!consent) {
                status = "先勾选声音授权确认"
                return
            }
            stop()
            busy = true
            status = "正在克隆…（接口同步处理，可能要十几秒）"
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.clonePreview(
                        apiKey, fileFor(context, chosen), voiceName.trim(), language, denoise,
                    )
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            preview = it
                            status = if (it.requiresVerification) {
                                "预览已生成，但这个样本需要先在 breezeblue.ai 完成本人验证，否则保存会被拒"
                            } else {
                                "预览已生成，先试听再决定保存"
                            }
                        },
                        onFailure = { status = "克隆失败：${it.message}" },
                    )
                }
            }.start()
        }

        fun save(target: BreezeTtsClient.ClonePreview) {
            stop()
            busy = true
            status = "正在保存到账号…"
            val name = voiceName.trim().ifBlank { sample?.stem ?: "Cloned Voice" }
            Thread {
                val outcome = runCatching {
                    BreezeTtsClient.savePreview(apiKey, target.generatedId, name, language)
                }
                breezeMainHandler.post {
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            showToast(context, "已保存音色「${it.name}」")
                            WeLogger.i("BreezeClone", "saved voice ${it.id}")
                            onSaved()
                            onDismiss()
                        },
                        onFailure = { status = "保存失败：${it.message}" },
                    )
                }
            }.start()
        }

        val done = preview
        AlertDialogContent(
            title = { Text(if (done == null) "克隆音色" else "克隆结果") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (done == null) {
                        TextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = "音色名字",
                            useLabelAsPlaceholder = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        DropDownMenuWidget(
                            title = "参考语言",
                            description = "中文样本必须选中文，否则识别不到内容",
                            value = language,
                            options = CLONE_LANGUAGES.map { DropdownOption(it.first, it.second) },
                            onValueChange = { language = it },
                        )
                        SwitchWidget(
                            iconPlaceholder = false,
                            title = "先做降噪",
                            description = "样本里有底噪时打开",
                            checked = denoise,
                            onCheckedChange = { denoise = it },
                        )

                        Spacer(Modifier.height(10.dp))
                        Text("参考音频", style = MiuixTheme.textStyles.subtitle)
                        Text(
                            "单人声、无背景音乐、3 秒以上 5 MB 以内。接口只分析前 60 秒，会自动截成不超过 30 秒的片段。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (sampleTree == null) "默认目录 Download/WcS/" else "来自自选目录",
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(text = "浏览其他目录", onClick = { browseSamples() }, enabled = !busy)
                            if (sampleTree != null) {
                                TextButton(text = "回默认", onClick = { useDefaultSamples() }, enabled = !busy)
                            }
                        }
                        if (samples.isEmpty()) {
                            Text(
                                if (sampleTree == null) {
                                    "默认目录里还没有可用的音频文件。可以先用「保存语音到本地」导出 mp3，或点「浏览其他目录」。"
                                } else {
                                    "这个目录里没有合规的音频文件。"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                        samples.forEach { item ->
                            val chosen = item.key == sample?.key
                            Row(
                                Modifier.fillMaxWidth().clickable { sample = item }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.detail.isNotBlank()) {
                                        Text(
                                            item.detail,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                                if (chosen) {
                                    Icon(
                                        MaterialSymbols.Outlined.Check,
                                        contentDescription = "已选择",
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (consent) ToggleableState.On else ToggleableState.Off,
                                onClick = { consent = !consent },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "我确认这段声音属于我自己，或已获得声音本人的明确同意用于克隆。",
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(voiceName.ifBlank { sample?.stem ?: "Cloned Voice" })
                                Text(
                                    "预览 ${done.generatedId} · 还没有占用音色槽位",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            IconButton(onClick = { audition(done) }, enabled = !busy) {
                                Icon(
                                    if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                    contentDescription = "试听克隆预览",
                                )
                            }
                        }
                        Text(
                            "试听脚本由服务端自动生成，不由我们提供文本。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (done.requiresVerification) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "需要本人验证：到 breezeblue.ai 完成后再回来保存。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                            TextButton(text = "打开 breezeblue.ai", onClick = {
                                Uri.parse("https://breezeblue.ai").openInSystem(context)
                            })
                        }
                    }

                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(status, style = MiuixTheme.textStyles.footnote1)
                    }
                }
            },
            dismissButton = {
                TextButton(text = if (done == null) "取消" else "重新克隆", onClick = {
                    stop()
                    if (done == null) onDismiss() else preview = null
                })
            },
            confirmButton = {
                if (done == null) {
                    Button(onClick = { clone() }, enabled = !busy && consent && sample != null) { Text("开始克隆") }
                } else {
                    Button(onClick = { save(done) }, enabled = !busy) { Text("保存到账号") }
                }
            },
        )
    }
}

/** 参考语言只放文档点名的两种加自动，不在这里铺一长串猜不出来的代码。 */
private val CLONE_LANGUAGES = listOf(
    "" to "自动检测",
    "zh" to "中文",
    "en" to "英文",
)

/** 一条候选样本：默认目录里是 File，自选目录里是 SAF 的 uri。 */
private data class CloneSample(
    val name: String,
    val detail: String,
    val file: File?,
    val uri: Uri?,
) {
    val key: String get() = file?.absolutePath ?: uri.toString()
    val stem: String get() = name.substringBeforeLast('.')
}

/** 默认目录 Download/WcS/：只列格式与体积合规的音频，最新的排前面。 */
private fun defaultSamples(): List<CloneSample> =
    KnownPaths.downloads.toFile().listFiles()
        ?.filter {
            it.isFile &&
                it.extension.lowercase() in BreezeTtsClient.CLONE_SUFFIXES &&
                it.length() <= BreezeTtsClient.CLONE_MAX_BYTES
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { file ->
            CloneSample(
                name = file.name,
                detail = "${file.length() / 1024} KB · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                file = file,
                uri = null,
            )
        }
        .orEmpty()

/** 自选目录里的文件要先落到本地才能进 multipart；扩展名沿用原文件名。 */
private fun fileFor(context: Context, sample: CloneSample): File {
    sample.file?.let { return it }
    val uri = sample.uri ?: throw BreezeTtsClient.BreezeError("样本既没有路径也没有 uri")
    val suffix = sample.name.substringAfterLast('.', "mp3").lowercase()
    val dest = File(breezePreviewDir(), "sample-${System.currentTimeMillis()}.$suffix")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use(input::copyTo)
    } ?: throw BreezeTtsClient.BreezeError("读不到选中的文件")
    return dest
}

/** 页签/操作的 Miuix 风格小胶囊：选中用主色，未选中用容器色。 */
@Composable
private fun Chip(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
