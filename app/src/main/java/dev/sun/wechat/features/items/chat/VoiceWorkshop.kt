package dev.sun.wechat.features.items.chat

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Music_note
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeChatInputBarMenuApi
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.items.chat.panel.pickPanelFile
import dev.sun.wechat.features.items.chat_mood.VoiceReply
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixFilterChip
import dev.sun.wechat.ui.utils.ShowComposeDialogScope
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.BgmMixer
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 语音工作台：输入文字 → TTS 合成 → 可选叠加背景音乐 → 试听 / 直接发到当前聊天。
 *
 * 入口两处（随「文字转语音」特性启用）：
 * - 聊天页右上角悬浮的 🎵 小按钮（hook 聊天 Fragment / 独立 ChattingUI 出现时挂上）；
 * - 输入栏 ＋/发送键长按菜单的「语音工作台」条目（兜底入口，必定可用）。
 *
 * 只有这里的语音带 BGM；气泡卡与帮我回面板的语音保持原样。
 */
object VoiceWorkshop : WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "VoiceWorkshop"
    private const val CORNER_TAG = "wekit_voice_workshop_corner"

    private val main = Handler(Looper.getMainLooper())
    private val corners = WeakHashMap<Activity, TextView>()

    @Volatile
    private var enabled = false

    // ===== ＋/发送键长按菜单入口 =====

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "voice_workshop",
            icon = MaterialSymbols.Outlined.Music_note,
            label = "语音工作台",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                val activity = context as? Activity
                if (activity == null) showToast(context, "请在微信聊天页里使用")
                else open(activity)
            },
        ),
    )

    fun open(activity: Activity) {
        runCatching {
            showComposeDialog(activity) { WorkshopContent() }
        }.onFailure {
            WeLogger.e(TAG, "open voice workshop failed", it)
            showToast(activity, "打开语音工作台失败：${it.message}")
        }
    }

    // ===== 面板 =====

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ShowComposeDialogScope.WorkshopContent() {
        val context = LocalContext.current
        var text by remember { mutableStateOf("") }
        var bgmKind by remember { mutableStateOf("none") } // none | b1 | b2 | file
        var fileName by remember { mutableStateOf("") }
        var localPath by remember { mutableStateOf("") }
        var gain by remember { mutableStateOf(0.2f) }
        var status by remember { mutableStateOf("") }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        val handler = remember { Handler(Looper.getMainLooper()) }

        DisposableEffect(Unit) {
            onDispose {
                player?.release()
                player = null
            }
        }

        fun toast(s: String) = showToast(context, s)

        fun play(path: String) {
            runCatching {
                player?.release()
                val mp = MediaPlayer()
                mp.setDataSource(path)
                mp.prepare()
                mp.start()
                mp.setOnCompletionListener {
                    it.release()
                    handler.post { player = null }
                }
                player = mp
            }.onFailure { toast("播放失败：${it.message}") }
        }

        fun resolveBgm(): BgmMixer.Bgm = when (bgmKind) {
            "b1" -> BgmMixer.Bgm.Builtin(R.raw.bgm_builtin_1)
            "b2" -> BgmMixer.Bgm.Builtin(R.raw.bgm_builtin_2)
            "file" -> if (localPath.isNotBlank()) BgmMixer.Bgm.LocalFile(localPath) else BgmMixer.Bgm.None
            else -> BgmMixer.Bgm.None
        }

        fun deliver(send: Boolean, talker: String, path: String) {
            if (send) {
                TextToSpeech.sendVoiceTo(talker, path) { ok ->
                    main.post {
                        status = ""
                        toast(if (ok) "语音已发送" else "发送失败")
                    }
                }
            } else {
                status = ""
                play(path)
            }
        }

        fun produce(send: Boolean) {
            val talker = WeCurrentConversationApi.value
            val body = text.trim()
            if (body.isEmpty()) { toast("先输入要合成的文字"); return }
            if (send && talker.isBlank()) { toast("没识别到当前聊天，重进聊天页再试"); return }
            if (!VoiceReply.ready()) { toast("语音未配置：请到「设置 → AI 回复配置」填写 API Key / Cookie"); return }
            if (bgmKind == "file" && localPath.isBlank()) { toast("先选择本地音乐文件"); return }
            status = "正在合成…"
            val bgm = resolveBgm()
            TextToSpeech.synthesizeByBackend(body) { path, err ->
                if (path == null) {
                    main.post { status = ""; toast("合成失败：$err") }
                    return@synthesizeByBackend
                }
                if (bgm is BgmMixer.Bgm.None) {
                    deliver(send, talker, path)
                    return@synthesizeByBackend
                }
                Thread {
                    val out = File(context.cacheDir, "ws_${System.currentTimeMillis()}.wav")
                    val ok = BgmMixer.mix(context, path, bgm, gain, out.absolutePath)
                    main.post {
                        if (ok) deliver(send, talker, out.absolutePath)
                        else { status = ""; toast("混音失败") }
                    }
                }.start()
            }
        }

        AlertDialogContent(
            title = { Text("语音工作台") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "输入要合成的文字",
                        minLines = 2,
                        maxLines = 5,
                    )

                    Text("背景音乐", style = MiuixTheme.textStyles.title3)
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MiuixFilterChip(
                            selected = bgmKind == "none",
                            onClick = { bgmKind = "none" },
                            label = { Text("无") },
                        )
                        MiuixFilterChip(
                            selected = bgmKind == "b1",
                            onClick = { bgmKind = "b1" },
                            label = { Text("内置 1") },
                        )
                        MiuixFilterChip(
                            selected = bgmKind == "b2",
                            onClick = { bgmKind = "b2" },
                            label = { Text("内置 2") },
                        )
                        MiuixFilterChip(
                            selected = bgmKind == "file",
                            onClick = {
                                bgmKind = "file"
                                if (localPath.isBlank()) {
                                    pickPanelFile(context, arrayOf("audio/*")) { name, uri, activity ->
                                        runCatching {
                                            val dst = File(context.cacheDir, "ws_bgm_${System.currentTimeMillis()}.bin")
                                            activity.contentResolver.openInputStream(uri)?.use { input ->
                                                dst.outputStream().use { input.copyTo(it) }
                                            }
                                            localPath = dst.absolutePath
                                            fileName = name
                                        }.onFailure { toast("读取文件失败：${it.message}") }
                                    }
                                }
                            },
                            label = { Text(if (bgmKind == "file" && fileName.isNotBlank()) "本地：$fileName" else "本地文件…") },
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BGM 音量 ${(gain * 100).roundToInt()}%", modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = gain,
                            onValueChange = { gain = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (status.isNotBlank()) {
                        Text(status, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { produce(false) },
                            enabled = status.isBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("试听") }
                        Button(
                            onClick = { produce(true) },
                            enabled = status.isBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("发送到聊天") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )
    }

    // ===== 聊天页右上角 🎵 悬浮按钮 =====

    /** 聊天页出现（Fragment 或独立 Activity）时调用。 */
    fun onChatViewShown(view: View) {
        if (!enabled) return
        val activity = view.context as? Activity ?: return
        main.post { attachCorner(activity) }
    }

    /** 离开聊天页：LauncherUI 常驻，按钮必须手动藏。 */
    fun onChatViewHidden() {
        main.post { corners.values.forEach { it.visibility = View.GONE } }
    }

    private fun attachCorner(activity: Activity) {
        if (!enabled) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        corners[activity]?.let { it.visibility = View.VISIBLE; return }
        val d = activity.resources.displayMetrics.density
        val size = (40 * d).toInt()
        val sbId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (sbId > 0) activity.resources.getDimensionPixelSize(sbId) else 0
        // 让开微信自己的标题栏（右上角有「…」），落在标题栏正下方仍属于界面右上角
        val abAttr = activity.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val actionBar = runCatching { abAttr.getDimensionPixelSize(0, (48 * d).toInt()) }
            .getOrDefault((48 * d).toInt()).also { abAttr.recycle() }
        val button = TextView(activity).apply {
            text = "🎵"
            textSize = 17f
            gravity = Gravity.CENTER
            tag = CORNER_TAG
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x59000000)
            }
            setOnClickListener { open(activity) }
        }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            topMargin = statusBar + actionBar + (6 * d).toInt()
            marginEnd = (10 * d).toInt()
        }
        runCatching { decor.addView(button, lp) }
            .onFailure {
                WeLogger.w(TAG, "attach corner failed: ${it.message}")
                return
            }
        corners[activity] = button
    }

    /**
     * 特性启用/停用。真正的 Xposed hook 装在 [TextToSpeech]（Feature 内才有 hookAfter 成员扩展），
     * 这里只做开关门禁：关掉后已有 hook 全部失效、按钮隐藏。
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) hideCorner()
    }

    fun hideCorner() {
        corners.values.forEach { it.visibility = View.GONE }
    }
}
