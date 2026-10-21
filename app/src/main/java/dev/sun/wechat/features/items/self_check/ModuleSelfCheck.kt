package dev.sun.wechat.features.items.self_check

import android.content.Context
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.Hchat.hooks.api.core.WeChatApis
import dev.sun.wechat.Hchat.hooks.core.DexInstallScheduler
import dev.sun.wechat.Hchat.hooks.core.HookRegistry
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.core.FeaturesProvider
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.secret_friend.HideConversations
import dev.sun.wechat.features.items.secret_friend.SecretFriendManager
import dev.sun.wechat.features.items.secret_friend.SecretFriendState
import dev.sun.wechat.features.items.scripting_center.ScriptCenter
import dev.sun.wechat.features.items.scripting_java.JavaScriptingHook
import dev.sun.wechat.features.items.scripting_python.PythonScriptingFeature
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.crash.ExitReasonRecorder
import dev.sun.wechat.utils.crash.ProcessTimeline
import dev.sun.wechat.utils.MainThreadWatchdog
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.reflection.ClassLoaders
import kotlin.io.path.div
import dev.sun.wechat.utils.fs.createDirsSafe

/**
 * 模块自检（最外层 / 全量）：把整个模块的公共依赖逐项扫描出来，并支持「一键修复」。
 *
 * 覆盖：各功能分类启用概况 / 脚本运行时全链路 /
 * Hook 安装任务逐项 / 关键类锚点 / 密友功能状态 / 运行状态（上次退出原因 + 启动时间线 + 主线程卡顿）。
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
    const val SECTION_SCRIPT = "脚本运行时"
    const val SECTION_HOOK = "Hook 安装任务与就绪阶段"
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
        HealthStep(SECTION_SCRIPT) { scriptRuntime() },
        HealthStep(SECTION_HOOK) { hookTasks() },
        HealthStep(SECTION_ANCHOR) { anchors() },
        HealthStep(SECTION_SECRET) { secretFriendSection() },
        HealthStep(SECTION_RUNTIME) { runtimeStatus(context) },
    )

    /** 「一键修复」步骤（全量）。 */
    fun repairSteps(context: Context): List<HealthStep> = listOf(
        HealthStep("补建脚本运行目录") { repairScriptDirs() },
        HealthStep("开启脚本总开关") { repairScriptSwitch() },
        HealthStep("重置并重建 Dex 适配缓存") { repairDexCache() },
        HealthStep("推进就绪阶段并重排全部 Hook 任务") { repairHookSchedule() },
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
    //  脚本运行时（使用 WeKit 自己的脚本体系：ScriptCenter / Java / Python）
    // ------------------------------------------------------------------

    private fun scriptRuntime(): List<HealthItem> {
        val section = SECTION_SCRIPT
        val out = mutableListOf<HealthItem>()
        val repair = dexRepairAction()
        val openSwitch = openScriptSwitchAction()

        val masterOn = runCatching { ScriptCenter.isScriptingAllowed() }.getOrDefault(false)
        out += HealthItem(
            "脚本总开关", section,
            if (masterOn) "已开启" else "已关闭", masterOn,
            if (masterOn) "脚本总开关已开启，启动时会加载已启用的脚本插件。"
            else "脚本总开关未开启，所有脚本插件都不会工作。",
            actionLabel = if (masterOn) null else openSwitch.first,
            action = if (masterOn) null else openSwitch.second,
            inactive = !masterOn,
        )

        val javaOn = runCatching { JavaScriptingHook.isEnabled }.getOrDefault(false)
        out += HealthItem(
            "Java 脚本引擎", section,
            if (javaOn) "已开启" else "已关闭", javaOn,
            if (javaOn) "Java 脚本引擎已开启。"
            else "Java 脚本引擎已关闭——只有 Java 插件不会执行。",
            inactive = !javaOn,
        )

        val pythonOn = runCatching { PythonScriptingFeature.isEnabled }.getOrDefault(false)
        out += HealthItem(
            "Python 脚本引擎", section,
            if (pythonOn) "已开启" else "已关闭", pythonOn,
            if (pythonOn) "Python 脚本引擎已开启。"
            else "Python 脚本引擎已关闭——只有 Python 插件不会执行。",
            inactive = !pythonOn,
        )

        val javaActive = runCatching { JavaScriptingHook.isActive }.getOrDefault(false)
        val pythonActive = runCatching { PythonScriptingFeature.isActive }.getOrDefault(false)
        val engineOk = (javaOn && javaActive) || (pythonOn && pythonActive) || !masterOn
        out += HealthItem(
            "脚本运行时就绪", section,
            if (engineOk) "已就绪" else "未就绪", engineOk,
            if (engineOk) "至少一个已开启的脚本引擎 Hook 已实际安装，插件可正常加载。"
            else "开关已开但没有任何引擎 Hook 安装成功——通常是 DexKit 锚点失配。\n点「${repair.first}」重建适配缓存后重试。",
            actionLabel = if (engineOk) null else repair.first,
            action = if (engineOk) null else repair.second,
        )

        val hookCount = runCatching { HookRegistry.get().size() }.getOrDefault(0)
        out += HealthItem(
            "脚本 Hook 注册", section,
            "$hookCount 个", hookCount > 0,
            if (hookCount > 0) "已注册 $hookCount 个 Hook（脚本的消息/菜单等监听）。"
            else "0 个：脚本引导链路可能未完成。",
            actionLabel = if (hookCount > 0) null else repair.first,
            action = if (hookCount > 0) null else repair.second,
        )

        val javaPlugins = runCatching { JavaScriptingHook.scripts.size }.getOrDefault(0)
        val pythonPlugins = runCatching {
            KnownPaths.moduleRoot.resolve("scripts_python").toFile().listFiles()?.count { it.isDirectory } ?: 0
        }.getOrDefault(0)
        out += HealthItem(
            "本地插件", section,
            "Java $javaPlugins 个，Python $pythonPlugins 个", true,
            "本地已安装的脚本插件数量（不含未启用项）。\n可到「脚本插件中心」查看与安装。",
        )

        val marketSize = runCatching { ScriptCenter.marketEntriesSnapshot().size }.getOrDefault(0)
        out += HealthItem(
            "在线插件市场", section,
            if (marketSize > 0) "已拉取 $marketSize 个" else "未拉取（可离线使用）", true,
            if (marketSize > 0) "市场索引已缓存，共 $marketSize 个插件可安装。"
            else "尚未拉取市场索引或网络不可用——不影响已安装插件运行。",
        )
        return out
    }

    // ------------------------------------------------------------------
    //  Hook 安装任务与就绪阶段（以 DexInstallScheduler 快照为准）
    // ------------------------------------------------------------------

    private fun hookTasks(): List<HealthItem> {
        val section = SECTION_HOOK
        val snapshot = runCatching { DexInstallScheduler.snapshot() }.getOrDefault(emptyList())
        val pending = snapshot.count { !it.installed }
        val out = mutableListOf<HealthItem>()

        val retryAll: Pair<String, () -> String> = "重排重试" to {
            runCatching {
                DexInstallScheduler.ensureWarmupReady()
                DexInstallScheduler.retryAllPending()
            }
            "已触发重排重试，当前待装 " + runCatching { DexInstallScheduler.pendingCount() }.getOrDefault(0) + " 个"
        }
        out += HealthItem(
            "Hook 安装任务", section, "共 ${snapshot.size} 个，待装 $pending", pending == 0,
            "模块共有 ${snapshot.size} 个需要 DexKit 解析后安装的 Hook 任务，当前待装 $pending 个。\n待装/失败的任务会让对应功能暂时失效。",
            actionLabel = if (pending == 0) null else retryAll.first,
            action = if (pending == 0) null else retryAll.second,
        )
        snapshot.forEach { task ->
            val ok = task.installed
            val value = if (ok) "✅ 已安装" else "⚠ ${if (task.paused) "已暂停" else "待重试"}（第 ${task.retryIndex} 次）"
            out += HealthItem(
                "· ${task.label}", section, value, ok,
                "key=${task.key}\n阶段=${task.stage}\n运行中=${task.running}\n已暂停=${task.paused}\n重试次数=${task.retryIndex}\n\n" +
                    if (ok) "该 Hook 已安装完成。"
                    else "该 Hook 尚未安装成功——点「重试安装」立即重排重试（推进到 WARMUP 后再执行一轮）。",
                actionLabel = if (ok) null else "重试安装",
                action = if (ok) null else retryAll.second,
            )
        }
        val level = runCatching { DexInstallScheduler.readyLevelName() }.getOrDefault("未知")
        out += HealthItem(
            "就绪阶段", section, level, level.contains("WARMUP"),
            "阶段顺序：EARLY < BRIDGE < WARMUP。\n只有推进到 WARMUP 后，脚本级 Hook（菜单/消息监听等）才会执行。当前为 $level。",
            actionLabel = if (level.contains("WARMUP")) null else retryAll.first,
            action = if (level.contains("WARMUP")) null else retryAll.second,
        )
        return out
    }

    // ------------------------------------------------------------------
    //  关键类锚点
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
        val master = SecretFriendState.isSecretFriendMasterOn()
        val openMaster: () -> String = {
            runCatching { SecretFriendManager.applyToggle(true) }
            "密友功能总开关已开启；若名单为空请到「密友名单管理」添加密友"
        }
        out += HealthItem(
            "密友总开关", section, if (master) "开启" else "关闭", master,
            "密友功能的总开关（在「密友功能」分类页顶部）。关闭后所有隐藏/拦截都不生效，名单保留。",
            actionLabel = if (master) null else "开启总开关",
            action = if (master) null else openMaster,
            inactive = !master,
        )
        val count = SecretFriendState.getStoredWxIds().size
        out += HealthItem(
            "密友名单人数", section, "$count", true,
            "当前密友名单共 $count 人。为 0 时不会有任何隐藏效果，请到「密友名单管理」添加密友。",
        )
        val temp = SecretFriendState.isTemporarilyShown()
        out += HealthItem(
            "密友临时解除", section, if (temp) "生效中" else "未激活", !temp,
            "临时解除期间，名单内所有人对你可见。当前：${if (temp) "生效中" else "未激活"}。",
            inactive = temp,
        )
        val faces = runCatching {
            SecretFriendDiagnostics.collectDetailRows().filter { it.section == SecretFriendDiagnostics.SECTION_FACES }
        }.getOrDefault(emptyList())
        val onFaces = faces.count { it.ok }
        out += HealthItem(
            "密友隐藏面生效", section, "$onFaces/${faces.size}", master && onFaces == faces.size,
            "隐藏面里当前有 $onFaces 个处于「生效」状态。\n未生效的多为开关未开或总开关关闭；到「密友功能诊断」页可逐面查看。",
            actionLabel = "重新对账",
            action = {
                val a = runCatching { HideConversations.reconcileOnListChange() }.isSuccess
                runCatching { WeConversationApi.reloadConversations() }
                if (a) "已按当前名单重新对账并刷新会话列表" else "对账失败"
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

    private fun repairScriptDirs(): List<HealthItem> {
        val out = mutableListOf<HealthItem>()
        listOf("scripts_java" to "Java 插件目录", "scripts_python" to "Python 插件目录").forEach { (name, label) ->
            val ok = runCatching {
                val dir = (KnownPaths.moduleRoot / name).createDirsSafe().toFile()
                dir.isDirectory
            }.getOrDefault(false)
            out += HealthItem(
                label,
                "补建脚本运行目录",
                if (ok) "就绪" else "失败",
                ok,
                "目录 $name：${if (ok) "已就绪" else "创建失败（可能是存储权限问题）"}",
            )
        }
        return out
    }

    private fun repairScriptSwitch(): List<HealthItem> {
        val on = runCatching { ScriptCenter.isScriptingAllowed() }.getOrDefault(false)
        if (on) {
            return listOf(HealthItem("脚本总开关", "开启脚本总开关", "已是开启状态", true, "脚本总开关本来就开着，无需修复。"))
        }
        val r = runCatching { ScriptCenter.applyToggle(true) }
        return listOf(
            HealthItem(
                "脚本总开关",
                "开启脚本总开关",
                if (r.isSuccess) "已开启" else "失败",
                r.isSuccess,
                if (r.isSuccess) "脚本总开关已开启。" else "开启失败：" + (r.exceptionOrNull()?.message ?: "未知原因"),
            ),
        )
    }

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

    private fun repairHookSchedule(): List<HealthItem> {
        runCatching {
            DexInstallScheduler.ensureWarmupReady()
            DexInstallScheduler.retryAllPending()
        }
        val level = runCatching { DexInstallScheduler.readyLevelName() }.getOrDefault("未知")
        val pending = runCatching { DexInstallScheduler.pendingCount() }.getOrDefault(0)
        return listOf(
            HealthItem(
                "Hook 任务",
                "推进就绪阶段并重排全部 Hook 任务",
                "阶段=$level，待装 $pending 个", true,
                "已强制推进到 WARMUP，并把所有未装任务的计数清零后重排重试。",
            ),
        )
    }

    private fun repairSecretFriend(): List<HealthItem> {
        val ok1 = runCatching { HideConversations.reconcileOnListChange() }.isSuccess
        val ok2 = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        return listOf(
            HealthItem(
                "密友名单",
                "密友隐藏面重新对账",
                "对账=${if (ok1) "成功" else "失败"}，刷新会话=${if (ok2) "成功" else "失败"}",
                ok1,
                "按当前密友名单重新隐藏/恢复会话行，并刷新会话列表。",
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

    private fun openScriptSwitchAction(): Pair<String, () -> String> =
        "开启总开关" to {
            runCatching { ScriptCenter.applyToggle(true) }
                .fold({ "脚本总开关已开启" }, { "开启失败：" + (it.message ?: "未知原因") })
        }

    /** 收集一份完整文本（用于复制反馈）。 */
    fun buildReportText(context: Context): String =
        healthSteps(context).flatMap { step ->
            runCatching { step.run() }.getOrElse { e ->
                listOf(HealthItem(step.title, step.title, "异常", false, "执行「${step.title}」时抛出异常：\n$e"))
            }
        }.joinToString("\n") { it.label + "：" + it.value }

    /** 概览卡片用的微信版本展示串。 */
    fun wechatVersionDisplay(): String =
        runCatching { WeChatApis.version()?.current()?.displayVersion() }.getOrNull()
            ?: "${HostInfo.versionName}(${HostInfo.versionCode})"

    /** 概览卡片用的模块版本展示串。 */
    fun moduleVersionDisplay(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
