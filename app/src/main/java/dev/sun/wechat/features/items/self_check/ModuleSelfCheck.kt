package dev.sun.wechat.features.items.self_check

import android.content.Context
import android.os.Build
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.constants.Preferences
import dev.sun.wechat.constants.WeChatVersions
import dev.sun.wechat.features.items.chat.AsrEngine
import dev.sun.wechat.features.items.chat.TextToSpeech
import dev.sun.wechat.features.items.multimodal.MultimodalEngine
import dev.sun.wechat.features.items.multimodal.MultimodalFeature
import dev.sun.wechat.loader.startup.StartupInfo
import dev.sun.wechat.utils.TargetProcesses
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.fs.KnownPaths
import java.io.File
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.core.FeaturesProvider
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.contacts.HideContacts
import dev.sun.wechat.features.items.contacts.hidecontacts.BlockHiddenChatSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.BlockHiddenProfileSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideContactListSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideConversationSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideMomentsInlineSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideMomentsSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideNewFriendsSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideSearchSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideUnreadSurface
import dev.sun.wechat.features.items.contacts.hidecontacts.HideVoipSurface
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.crash.ExitReasonRecorder
import dev.sun.wechat.utils.crash.ProcessTimeline
import dev.sun.wechat.utils.MainThreadWatchdog
import dev.sun.wechat.utils.reflection.ClassLoaders

/**
 * 模块自检（最外层 / 全量）：把整个模块的公共依赖逐项扫描出来，并支持「一键修复」。
 *
 * 覆盖：各功能分类启用概况 / 关键类锚点 / 密友功能状态 /
 * 运行状态（上次退出原因 + 启动时间线 + 主线程卡顿）。
 *
 * 每一项都可点开：弹出「状态 + 含义 + 恢复建议」，可修复的项还带一个**动作按钮**
 * （如未解析的锚点可「重置 Dex 缓存重解析」、未开的密友总开关可「一键启用」）。
 *
 * [HealthItem.inactive] 用来区分「未启用」与「故障」：未启用的项不计入异常统计，也不标红。
 */
object ModuleSelfCheck {

    private const val TAG = "ModuleSelfCheck"

    /** 一个检测项。[section] 用于分组；[detail] 点开后的含义 + 建议；[action] 非空时详情弹窗带动作按钮。 */
    data class HealthItem(
        val label: String,
        val section: String,
        val value: String,
        val ok: Boolean,
        val detail: String,
        val actionLabel: String? = null,
        val action: (() -> String)? = null,
        /**
         * 该功能只是「未启用」，并非故障：不计入异常统计，界面也不标红。
         * 例如密友总开关关闭、插件总开关关闭、一个插件都没启用。
         */
        val inactive: Boolean = false,
    )

    /** 一个检测/修复步骤：产出一批 [HealthItem]。 */
    class HealthStep(val title: String, val run: () -> List<HealthItem>)

    const val SECTION_OVERVIEW = "功能清单与分类启用概况"
    const val SECTION_ENV = "运行环境与注入链路"
    const val SECTION_HOOK = "Hook 安装明细"
    const val SECTION_SERVICE = "外部服务与配置"
    const val SECTION_SCRIPT = "脚本运行时"
    const val SECTION_ANCHOR = "关键类锚点"
    const val SECTION_SECRET = "密友功能"
    const val SECTION_RUNTIME = "运行状态"

    /** 关键类锚点（类名在当前微信宿主中应存在）。 */
    private val ANCHORS: List<Pair<String, String>> = listOf(
        "聊天页 ChattingUI" to "com.tencent.mm.ui.chatting.ChattingUI",
        "资料页 ContactInfoUI" to "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        "通讯录 AddressLiveList" to "com.tencent.mm.ui.contact.address.AddressLiveList",
        "朋友圈评论 SnsCommentFooter" to "com.tencent.mm.plugin.sns.ui.SnsCommentFooter",
        "主界面 LauncherUI" to "com.tencent.mm.ui.LauncherUI",
        "wcdb SQLiteDatabase" to "com.tencent.wcdb.database.SQLiteDatabase",
        "wcdb compat SQLiteDatabase" to "com.tencent.wcdb.compat.SQLiteDatabase",
    )

    /** 自检步骤（全量）。 */
    fun healthSteps(context: Context): List<HealthStep> = listOf(
        HealthStep(SECTION_OVERVIEW) { featureOverview() },
        HealthStep(SECTION_ENV) { environmentSection(context) },
        HealthStep(SECTION_HOOK) { hookInstallSection(context) },
        HealthStep(SECTION_ANCHOR) { anchors() },
        HealthStep(SECTION_SECRET) { secretFriendSection() },
        HealthStep(SECTION_SERVICE) { serviceSection() },
        HealthStep(SECTION_RUNTIME) { runtimeStatus(context) },
    )

    /** 「一键修复」步骤（全量）。 */
    fun repairSteps(context: Context): List<HealthStep> = listOf(
        HealthStep("重置并重建 Dex 适配缓存") { repairDexCache() },
        HealthStep("密友隐藏面重新对账") { repairSecretFriend() },
    )

    // ------------------------------------------------------------------
    //  功能清单与分类启用概况
    // ------------------------------------------------------------------

    private fun featureOverview(): List<HealthItem> {
        val all = FeaturesProvider.ALL_FEATURES
        val out = mutableListOf<HealthItem>()
        out += HealthItem(
            "功能总数",
            SECTION_OVERVIEW,
            "${all.size} 个",
            true,
            "模块共注册 ${all.size} 个功能项（含各类开关与可点击功能）。",
        )
        val enabledCount = all.count { (it as? SwitchFeature)?.isEnabled == true }
        val activeCount = all.count { it.isActive }
        out += HealthItem(
            "已启用 / 运行中",
            SECTION_OVERVIEW,
            "启用 $enabledCount，运行中 $activeCount",
            true,
            "用户已打开的开关共 $enabledCount 个；其中 Hook 已实际安装（运行中）$activeCount 个。\n" +
                "「启用但未运行中」通常表示该功能的锚点在当前微信版本失配——见「Hook 安装任务」与「关键类锚点」。",
        )
        all.groupBy { it.categoryIds.firstOrNull() ?: "未分类" }
            .toSortedMap()
            .forEach { (category, list) ->
                val on = list.count { (it as? SwitchFeature)?.isEnabled == true }
                val detail = buildString {
                    append("该分类共 ${list.size} 个功能，当前已启用 $on 个。")
                    if (on == 0) {
                        append("\n该分类目前没有任何功能被启用——若你期望其中某功能生效，请到对应分类页手动开启。")
                    }
                }
                out += HealthItem("分类·$category", SECTION_OVERVIEW, "${list.size} 个，已启用 $on", true, detail)
            }
        return out
    }

    // ------------------------------------------------------------------
    //  运行环境与注入链路
    // ------------------------------------------------------------------

    /**
     * 注入链路本身是否正常：加载器、Hook 桥、进程、宿主/模块版本、设备、可写目录。
     *
     * 这一组回答的是「模块到底有没有被装进微信进程」——功能大面积失效时先看这里：
     * 加载器为空 = 没注入；Hook 桥为空 = 注入方式不支持 Hook；进程不对 = 当前功能本来就不该生效。
     */
    private fun environmentSection(context: Context): List<HealthItem> {
        val section = SECTION_ENV
        val out = mutableListOf<HealthItem>()

        val loader = runCatching { StartupInfo.loaderService }.getOrNull()
        out += if (loader != null) {
            HealthItem(
                label = "注入加载器",
                section = section,
                value = "${loader.loaderName} ${loader.loaderVersionName}(${loader.loaderVersionCode})",
                ok = true,
                detail = "模块由 ${loader.loaderName} 注入，入口 ${loader.entryPointName}。\n" +
                    "加载器负责把模块 DEX 与资源塞进微信进程。加载器版本过低或与框架不匹配时，" +
                    "钩子会静默装不上，模块内部看不出原因——这一项有值说明注入链路本身是通的。",
            )
        } else {
            HealthItem(
                label = "注入加载器",
                section = section,
                value = "未初始化",
                ok = false,
                detail = "StartupInfo 还没有被填充：模块没有注入到当前进程，或当前页面不在被注入的微信进程里。\n" +
                    "这种情况下所有 Hook 都不会生效。请检查框架里模块是否勾选、作用域是否包含微信、改完是否重启过微信。",
            )
        }

        val bridge = runCatching { StartupInfo.hookBridge }.getOrNull()
        out += HealthItem(
            label = "Hook 桥",
            section = section,
            value = bridge?.javaClass?.simpleName ?: "未知",
            ok = bridge != null,
            detail = if (bridge != null) {
                "当前 Hook 桥：${bridge.javaClass.name}\n" +
                    "Hook 桥是模块调用框架 API 的唯一出口；它为 null 时功能开关能打开但不会真正生效。"
            } else {
                "StartupInfo.hookBridge 为 null：当前注入方式没有提供 Hook 能力（例如只做资源注入的加载器）。\n" +
                    "换用支持 Hook 的加载器（LSPosed / libxposed）后重启微信。"
            },
        )

        val process = runCatching { "${TargetProcesses.currentType.name}（${TargetProcesses.currentName}）" }
            .getOrDefault("未知")
        out += HealthItem(
            label = "当前进程",
            section = section,
            value = process,
            ok = true,
            detail = "模块按进程分别加载：主进程（MAIN）才是完整功能；PUSH / TOOLS / SANDBOX 等进程只装该进程需要的功能。\n" +
                "若某些功能在设置里开着却「未安装 Hook」，先确认它是否是给别的进程用的。",
        )

        val hostName = knownHostVersionName(HostInfo.versionCode)
        out += HealthItem(
            label = "微信版本",
            section = section,
            value = "${HostInfo.versionName}(${HostInfo.versionCode})" + (hostName?.let { " · $it" } ?: ""),
            ok = true,
            detail = "宿主包名：${HostInfo.packageName}\n" +
                "渠道：${if (HostInfo.isHostGooglePlay) "Google Play" else "国内版"}\n" +
                "模块按锚点适配而非硬编码版本号：同一个版本号下国内版/Play 版的 DEX 布局可能不同，" +
                "所以「关键类锚点」全部命中才代表这个宿主真的适配好了。",
        )

        out += HealthItem(
            label = "模块版本",
            section = section,
            value = moduleVersionDisplay(),
            ok = true,
            detail = "包名：${BuildConfig.APPLICATION_ID}\n如果是覆盖安装升级，旧进程里的模块版本可能还是旧的——升级后必须重启微信。",
        )

        out += HealthItem(
            label = "设备与系统",
            section = section,
            value = deviceLine(),
            ok = true,
            detail = "部分功能依赖系统能力：Android 11 以下没有进程退出原因记录；" +
                "Android 13+ 需要通知权限；分屏/小窗会影响界面类 Hook 的锚点命中。",
        )

        val modulePath = runCatching { StartupInfo.modulePath }.getOrNull()
        val moduleFile = modulePath?.let { runCatching { File(it) }.getOrNull() }
        out += HealthItem(
            label = "模块安装包",
            section = section,
            value = when {
                modulePath == null -> "未初始化"
                moduleFile?.exists() == true -> "可读 · ${moduleFile.length() / 1024}KB"
                else -> "路径存在性未知"
            },
            ok = modulePath != null && moduleFile?.exists() != false,
            detail = "路径：${modulePath ?: "（空）"}\n模块包被卸载/移动后，资源与扩展包会读不到，界面可能显示空白或回落成默认样式。",
        )

        val writable = runCatching {
            val probe = File(context.filesDir, ".selfcheck_probe")
            probe.writeText("ok")
            val text = probe.readText()
            probe.delete()
            text == "ok"
        }.getOrDefault(false)
        out += HealthItem(
            label = "私有目录可写",
            section = section,
            value = if (writable) "正常" else "不可写",
            ok = writable,
            detail = "用来存配置、识别缓存、Dex 适配缓存。\n目录：${context.filesDir.absolutePath}\n" +
                "不可写时表现为：开关打开后重启就丢、识别结果每次重算、Dex 缓存无法落盘（每次启动都重新解析）。",
        )

        val downloadDir = runCatching { KnownPaths.downloads.toFile() }.getOrNull()
        out += HealthItem(
            label = "导出目录",
            section = section,
            value = if (downloadDir?.exists() == true) "可用" else "不可用",
            ok = downloadDir?.exists() == true,
            inactive = downloadDir?.exists() != true,
            detail = "诊断报告、语音/图片导出都写在这里：\n${downloadDir?.absolutePath ?: "（无法创建，通常是没给存储权限）"}",
        )
        return out
    }

    // ------------------------------------------------------------------
    //  Hook 安装明细
    // ------------------------------------------------------------------

    /**
     * 逐项对比「开关已打开」与「Hook 真的装上了」。
     *
     * 这是比分类启用概况更细的一层：开关只是用户的意愿，[BaseFeature.isActive] 才是运行事实。
     * 差异项按当前进程过滤——只在别的进程生效的功能不算失败，避免误报。
     */
    private fun hookInstallSection(context: Context): List<HealthItem> {
        val section = SECTION_HOOK
        val out = mutableListOf<HealthItem>()
        val all = runCatching { FeaturesProvider.ALL_FEATURES }.getOrDefault(emptyList())
        val switches = all.filterIsInstance<SwitchFeature>()
        val enabled = switches.filter { it.isEnabled }
        val active = enabled.filter { it.isActive }
        val currentType = runCatching { TargetProcesses.currentType }.getOrNull()
        val broken = enabled.filter {
            !it.isActive && (currentType == null || it.targetProcesses.contains(currentType))
        }
        val otherProcess = enabled.count {
            !it.isActive && currentType != null && !it.targetProcesses.contains(currentType)
        }

        out += HealthItem(
            label = "功能注册总数",
            section = section,
            value = "${all.size} 个",
            ok = true,
            detail = "其中可开关的功能 ${switches.size} 个；当前已启用 ${enabled.size} 个。",
        )
        out += HealthItem(
            label = "已启用且已装 Hook",
            section = section,
            value = "${active.size}/${enabled.size}",
            ok = true,
            detail = "开关打开且 Hook 实际安装成功的功能数。\n" +
                "Hook 安装发生在功能启用瞬间（或启动时统一恢复），装不上会被回滚，开关回到关闭状态——" +
                "所以这里长期偏低通常意味着有一个功能每次开启都失败。",
        )
        out += HealthItem(
            label = "启用但 Hook 未安装",
            section = section,
            value = if (broken.isEmpty()) "无" else "${broken.size} 个",
            ok = broken.isEmpty(),
            inactive = broken.isEmpty(),
            detail = if (broken.isEmpty()) {
                "当前进程里，所有已启用的功能都装上了 Hook。"
            } else {
                "这些功能开关是开的，但 Hook 没装上（已按当前进程过滤，只在别的进程生效的不计入）：\n" +
                    "· " + broken.take(20).joinToString("\n· ") { nameWithId(context, it) } +
                    (if (broken.size > 20) "\n…共 ${broken.size} 个" else "") +
                    "\n\n常见原因：锚点在当前微信版本漂移（先看「关键类锚点」并试「重置 Dex 缓存」）、" +
                    "功能依赖的界面还没出现过（延迟挂载类功能会在进入对应页面后自动补装）。"
            },
        )
        if (otherProcess > 0) {
            out += HealthItem(
                label = "仅在其他进程生效",
                section = section,
                value = "$otherProcess 个",
                ok = true,
                detail = "这些功能已启用，但它们的目标进程不是当前进程（当前 ${currentType?.name ?: "未知"}），" +
                    "所以这里看不到 Hook。属于正常现象，不需要处理。",
            )
        }

        val digest = runCatching { WeLogger.digest(80) }.getOrNull()
        val failures = digest?.problems
            ?.filter { it.header.contains("failed to enable feature") }
            .orEmpty()
        out += HealthItem(
            label = "Hook 安装失败日志",
            section = section,
            value = if (failures.isEmpty()) "无" else "${failures.size} 条",
            ok = failures.isEmpty(),
            inactive = failures.isEmpty(),
            detail = if (failures.isEmpty()) {
                "最近的运行日志里没有「failed to enable feature」。"
            } else {
                "运行日志里最近 ${failures.size} 条 Hook 安装失败（新→旧）：\n" +
                    failures.take(8).joinToString("\n\n") {
                        it.header + "\n" + it.detail.take(4).joinToString("\n")
                    }
            },
        )
        return out
    }

    // ------------------------------------------------------------------
    //  外部服务与配置
    // ------------------------------------------------------------------

    /**
     * 需要自己填地址/Key 的能力：视觉、语音识别、语音合成。
     *
     * 这里只做「配置齐不齐」的静态判断，不发网络请求——自检跑在 IO 线程且要快，
     * 联网探测放到各设置页的「测试连通性」里做。
     */
    private fun serviceSection(): List<HealthItem> {
        val section = SECTION_SERVICE
        val out = mutableListOf<HealthItem>()

        val mmOn = runCatching { MultimodalFeature.isEnabled }.getOrDefault(false)
        out += HealthItem(
            label = "多模态识别总开关",
            section = section,
            value = if (mmOn) "开启" else "关闭",
            ok = mmOn,
            inactive = !mmOn,
            detail = "总开关关闭时，图片/表情/语音识别全部不工作（也不产生任何请求）。\n在「多模态识别」设置页顶部打开。",
        )

        val visionReady = runCatching { MultimodalEngine.visionReady() }.getOrDefault(false)
        val visionUsable = runCatching { MultimodalEngine.visionUsable() }.getOrDefault(false)
        out += HealthItem(
            label = "视觉接口",
            section = section,
            value = when {
                visionUsable -> "已配置 · ${MultimodalEngine.visionModel}"
                visionReady -> "缺模型"
                else -> "未配置"
            },
            ok = visionUsable || !visionReady,
            inactive = !visionReady,
            detail = buildString {
                appendLine("地址：${MultimodalEngine.visionBaseUrl.ifBlank { "未填" }}")
                appendLine("Key：${maskSecret(MultimodalEngine.visionApiKey)}")
                appendLine("模型：${MultimodalEngine.visionModel.ifBlank { "未选" }}")
                append("OpenAI 兼容接口，路径要能拼出 /chat/completions，模型列表来自 /models。")
                if (visionReady && !visionUsable) append("\n地址和 Key 有了但模型是空的：后台识别会被直接跳过，图片/表情都不会有描述。")
            },
        )

        out += HealthItem(
            label = "图片 / 表情识别",
            section = section,
            value = "图片${onOff(MultimodalEngine.imageEnabled)} · 表情${onOff(MultimodalEngine.stickerEnabled)}",
            ok = true,
            detail = "两个子开关独立于总开关；关掉后对应消息不再调用视觉接口（省流量、省钱）。\n" +
                "表情解不出来只解一次：失败过的表情会落盘标记，之后不再重试；语音/图片是 10 分钟冷却。\n" +
                "想立刻重试全部失败项，用「刷新检测结果」下方设置页里的「清空识别缓存」。",
        )

        val asrOn = runCatching { AsrEngine.enabled }.getOrDefault(false)
        val asrReady = runCatching {
            AsrEngine.baseUrl.isNotBlank() && AsrEngine.apiKey.isNotBlank()
        }.getOrDefault(false)
        out += HealthItem(
            label = "语音识别（ASR）",
            section = section,
            value = when {
                !asrOn -> "关闭"
                asrReady -> AsrEngine.model.ifBlank { "whisper-1" }
                else -> "缺地址或 Key"
            },
            ok = !asrOn || asrReady,
            inactive = !asrOn,
            detail = buildString {
                appendLine("地址：${AsrEngine.baseUrl.ifBlank { "未填" }}")
                appendLine("Key：${maskSecret(AsrEngine.apiKey)}")
                appendLine("模型：${AsrEngine.model.ifBlank { "whisper-1" }}")
                appendLine("SILK 解密 Key 1：${maskSecret(AsrEngine.aesKey)}")
                append("SILK 解密 Key 2：${maskSecret(AsrEngine.authKey)}")
                if (AsrEngine.aesKey.isBlank() && AsrEngine.authKey.isBlank()) {
                    append("\n两个解密 Key 都空着：会回落到微信自身的 auth_key。若对方语音消息转不出文字，先确认这里。")
                }
            },
        )

        val backend = runCatching { TextToSpeech.backend }.getOrDefault(TextToSpeech.BACKEND_BREEZE)
        val backendLabel = when (backend) {
            TextToSpeech.BACKEND_BREEZE -> "Breeze"
            TextToSpeech.BACKEND_SILICON -> "硅基流动"
            TextToSpeech.BACKEND_DOUBAO -> "豆包"
            TextToSpeech.BACKEND_MOFA -> "魔方"
            else -> "未知($backend)"
        }
        val ttsReady = runCatching {
            when (backend) {
                TextToSpeech.BACKEND_SILICON -> TextToSpeech.siliconApiKey.isNotBlank()
                TextToSpeech.BACKEND_DOUBAO -> TextToSpeech.doubaoCookie.isNotBlank()
                TextToSpeech.BACKEND_BREEZE -> TextToSpeech.breezeKey.isNotBlank()
                TextToSpeech.BACKEND_MOFA -> TextToSpeech.apiKey.isNotBlank()
                else -> true
            }
        }.getOrDefault(true)
        out += HealthItem(
            label = "语音合成（TTS）",
            section = section,
            value = "$backendLabel · " + if (ttsReady) "已配置" else "缺凭据",
            ok = ttsReady,
            detail = "语音回复、文字转语音都用这个后端。\n" +
                "Breeze 有内置默认 Key，通常不用填；硅基流动需要自己的 API Key；" +
                "豆包需要网页版 Cookie（过期后合成会失败，重新粘贴即可）。\n" +
                "当前后端：$backendLabel。",
        )
        return out
    }

    private fun onOff(value: Boolean): String = if (value) "开" else "关"

    private fun nameWithId(context: Context, feature: Any): String {
        val f = feature as? dev.sun.wechat.features.core.BaseFeature ?: return feature.toString()
        val name = runCatching { f.localizedName(context) }.getOrDefault(f.technicalId)
        return "$name（${f.technicalId}）"
    }

    /** 把已知引擎版本号翻译成人读的版本名；未知版本返回 null。 */
    private fun knownHostVersionName(code: Long): String? = runCatching {
        WeChatVersions::class.java.declaredFields.firstNotNullOfOrNull { field ->
            if (field.type != Int::class.javaPrimitiveType || !Modifier.isStatic(field.modifiers)) {
                null
            } else {
                field.isAccessible = true
                if (field.getInt(null).toLong() == code) field.name else null
            }
        }
    }.getOrNull()
        ?.removePrefix("MM_")
        ?.replace("_PLAY", " Play")
        ?.replace('_', '.')

    private fun deviceLine(): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append(" · ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
    }

    /** Key / Cookie 一律脱敏后再进报告。 */
    private fun maskSecret(value: String): String = when {
        value.isBlank() -> "未填"
        value.length <= 8 -> "****"
        else -> value.take(4) + "****" + value.takeLast(4)
    }

    // ------------------------------------------------------------------
    //  微信公共 API 层
    // ------------------------------------------------------------------

    private fun anchors(): List<HealthItem> = ANCHORS.map { (name, className) ->
        val found = runCatching {
            Class.forName(className, false, ClassLoaders.MODULE)
        }.isSuccess
        HealthItem(
            "锚点·$name",
            SECTION_ANCHOR,
            if (found) "已找到" else "未找到",
            found,
            "类 $className\n" +
                if (found) "在当前微信版本中存在，依赖它的功能有锚点可用。"
                else "未找到——依赖它的功能在当前微信版本可能已漂移/失效，需要适配（无法自动修复）。",
        )
    }

    // ------------------------------------------------------------------
    //  密友功能（概览；完整诊断在密友诊断页）
    // ------------------------------------------------------------------

    private fun secretFriendSection(): List<HealthItem> {
        val section = SECTION_SECRET
        val out = mutableListOf<HealthItem>()
        val master = Preferences.hiddenContactsMasterEnabled
        val openMaster: () -> String = {
            Preferences.hiddenContactsMasterEnabled = true
            "密友功能总开关已开启；若名单为空请到「隐藏联系人」添加联系人"
        }
        out += HealthItem(
            "密友总开关", section, if (master) "开启" else "关闭", master,
            "密友功能的总开关（在「密友功能」分类页顶部，即「隐藏联系人」主控）。关闭后所有隐藏/拦截都不生效，名单保留。",
            actionLabel = if (master) null else "开启总开关",
            action = if (master) null else openMaster,
            inactive = !master,
        )
        val count = HideContacts.hiddenContacts.size
        out += HealthItem(
            "密友名单人数", section, "$count", true,
            "当前隐藏名单共 $count 人。为 0 时不会有任何隐藏效果，请到「隐藏联系人」添加联系人。",
        )
        val temp = HideContacts.isTemporarilyShown
        out += HealthItem(
            "密友临时解除", section, if (temp) "生效中" else "未激活", !temp,
            "临时解除期间，名单内所有人对你可见（#wm# / 三击标题触发）。当前：${if (temp) "生效中" else "未激活"}。",
            inactive = temp,
        )
        // 用新「隐藏联系人」的各隐藏面开关聚合生效状态。
        val faces = listOf(
            Triple("主页会话隐藏", HideConversationSurface.isEnabled, HideConversationSurface.isEnabled),
            Triple("通讯录隐藏", HideContactListSurface.isEnabled, HideContactListSurface.isEnabled),
            Triple("搜索结果隐藏", HideSearchSurface.isEnabled, HideSearchSurface.isEnabled),
            Triple("朋友圈隐藏", HideMomentsSurface.isEnabled, HideMomentsSurface.isEnabled),
            Triple("朋友圈互动隐藏", HideMomentsInlineSurface.isEnabled, HideMomentsInlineSurface.isEnabled),
            Triple("音视频通话隐藏", HideVoipSurface.isEnabled, HideVoipSurface.isEnabled),
            Triple("新的朋友隐藏", HideNewFriendsSurface.isEnabled, HideNewFriendsSurface.isEnabled),
            Triple("未读角标隐藏", HideUnreadSurface.isEnabled, HideUnreadSurface.isEnabled),
            Triple("禁止进入聊天", BlockHiddenChatSurface.isEnabled, BlockHiddenChatSurface.isEnabled),
            Triple("禁止查看资料", BlockHiddenProfileSurface.isEnabled, BlockHiddenProfileSurface.isEnabled),
        )
        val onFaces = faces.count { it.third }
        out += HealthItem(
            "密友隐藏面生效", section, "$onFaces/${faces.size}", master && onFaces == faces.size,
            "隐藏面里当前有 $onFaces 个处于「开启」状态。\n未开启的多为开关未开或总开关关闭；到「密友功能」分类页可逐面开启。",
            actionLabel = "刷新会话列表",
            action = {
                val a = runCatching { WeConversationApi.reloadConversations() }.isSuccess
                if (a) "已刷新会话列表" else "刷新失败"
            },
            inactive = !master,
        )
        return out
    }

    // ------------------------------------------------------------------
    //  运行状态：上次退出原因 + 启动时间线 + 主线程卡顿
    // ------------------------------------------------------------------

    private fun runtimeStatus(context: Context): List<HealthItem> {
        val out = mutableListOf<HealthItem>()

        val summary = runCatching { ExitReasonRecorder.summary(context) }.getOrNull()
        val diagnosis = runCatching { ExitReasonRecorder.diagnosis(context) }.getOrNull()
        out += HealthItem(
            "上次进程退出", SECTION_RUNTIME,
            summary ?: "无记录（需 Android 11 及以上）", true,
            diagnosis ?: ("Android 11 起系统会记录进程退出原因（内存不足回收 / Java 或原生崩溃 / ANR / 强行停止）。\n" +
                "当前还没有可用记录：可能是首次安装、系统版本过低，或系统未保留记录。"),
        )

        val starts = runCatching { ProcessTimeline.todayStartCount(context) }.getOrDefault(0)
        val pss = runCatching { ProcessTimeline.currentPssMb(context) }.getOrDefault(0)
        out += HealthItem(
            "主进程启动次数", SECTION_RUNTIME,
            if (starts > 0) "今日 " + starts + " 次" else "无记录", true,
            "每次微信进程启动会记 1 次。\n" +
                "若明显多于你手动打开微信的次数，说明进程在后台被反复杀掉或被系统反复拉起。\n" +
                "当前进程内存（PSS）：" + (if (pss > 0) pss.toString() + "MB" else "未知"),
        )

        val recent = runCatching { ProcessTimeline.recent(context, 5) }.getOrDefault(emptyList())
        if (recent.isNotEmpty()) {
            out += HealthItem(
                "最近启动记录", SECTION_RUNTIME, recent.first(), true,
                recent.joinToString("\n"),
            )
        }

        // 主线程卡顿：ANR 的直接来源，记录卡住时主线程在跑谁。
        val janks = runCatching { MainThreadWatchdog.recent(context) }.getOrDefault(emptyList())
        if (janks.isNotEmpty()) {
            out += HealthItem(
                "主线程卡顿", SECTION_RUNTIME, janks.first(), true,
                "主线程单条消息超过 2 秒即记录（ANR 阈值为 5 秒）：\n" + janks.joinToString("\n"),
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    //  修复步骤
    // ------------------------------------------------------------------

    private fun repairDexCache(): List<HealthItem> {
        val r = runCatching { dev.sun.wechat.dexkit.cache.DexCacheManager.clearAllCache() }
        return listOf(
            HealthItem(
                "Dex 适配缓存",
                "重置并重建 Dex 适配缓存",
                if (r.isSuccess) "已清除" else "失败",
                r.isSuccess,
                "已清除全部 DexKit 适配缓存。\n下次微信启动时将重新扫描全部锚点（首次可能较慢）。\n" +
                    "若仍失败，说明该锚点在当前微信版本确实已漂移，需要版本适配。",
            ),
        )
    }

    private fun repairSecretFriend(): List<HealthItem> {
        val ok = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        return listOf(
            HealthItem(
                "密友名单",
                "密友隐藏面重新对账",
                "刷新会话=${if (ok) "成功" else "失败"}",
                ok,
                "按当前隐藏名单重新过滤/恢复会话行，并刷新会话列表。",
            ),
        )
    }

    // ------------------------------------------------------------------
    //  可复用动作
    // ------------------------------------------------------------------

    private fun dexRepairAction(): Pair<String, () -> String> =
        "重置 Dex 缓存" to {
            runCatching { dev.sun.wechat.dexkit.cache.DexCacheManager.clearAllCache() }
                .fold({ "Dex 适配缓存已清除；下次启动微信将重新解析全部锚点" }) { "清除失败：" + (it.message ?: "未知原因") }
        }

    /** 收集一份完整文本（用于复制反馈）。 */
    fun buildReportText(context: Context): String = buildString {
        appendLine("【WeKit 模块自检报告】")
        appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        appendLine("模块：" + moduleVersionDisplay())
        appendLine("微信：" + wechatVersionDisplay() + " · " + (knownHostVersionName(HostInfo.versionCode) ?: "未收录"))
        appendLine("进程：" + runCatching { TargetProcesses.currentType.name }.getOrDefault("未知"))
        appendLine("设备：" + deviceLine())
        appendLine()
        var lastSection: String? = null
        healthSteps(context).flatMap { step ->
            runCatching { step.run() }.getOrElse { e ->
                listOf(HealthItem(step.title, step.title, "异常", false, "执行「${step.title}」时抛出异常：\n$e"))
            }
        }.forEach { item ->
            if (item.section != lastSection) {
                appendLine("── " + item.section + " ──")
                lastSection = item.section
            }
            val mark = when {
                item.ok -> "✅"
                item.inactive -> "○"
                else -> "❌"
            }
            appendLine("$mark " + item.label + "：" + item.value)
        }
    }

    /**
     * 把完整报告写到 `Download/WeKit/selfcheck-<时间>.txt` 并返回绝对路径。
     *
     * 与「复制检测结果」的区别：不截断、带环境头，适合直接发给开发者。
     * 失败时抛异常，由调用方提示（通常是没给存储权限）。
     */
    fun exportReportFile(context: Context): String {
        val text = buildReportText(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = KnownPaths.downloads.toFile()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建导出目录：" + dir.absolutePath)
        }
        val file = File(dir, "selfcheck-$stamp.txt")
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }

    /** 概览卡片用的微信版本展示串。 */
    fun wechatVersionDisplay(): String =
        "${HostInfo.versionName}(${HostInfo.versionCode})"

    /** 概览卡片用的模块版本展示串。 */
    fun moduleVersionDisplay(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
