package dev.sun.wechat.features.items.self_check

import android.content.Context
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.cache.DexCacheManager
import dev.sun.wechat.features.api.core.WeAuthApi
import dev.sun.wechat.features.api.core.WeContactApi
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeGroupApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.core.BaseFeature
import dev.sun.wechat.features.core.FeaturesProvider
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.features.items.secret_friend.HideConversations
import dev.sun.wechat.features.items.secret_friend.SecretFriendManager
import dev.sun.wechat.features.items.secret_friend.SecretFriendState
import dev.sun.wechat.features.items.scripting_center.ScriptCenter
import dev.sun.wechat.features.items.scripting_java.JavaScriptingHook
import dev.sun.wechat.features.items.scripting_python.PythonScriptingFeature
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.fs.createDirsSafe

/**
 * 模块自检（最外层 / 全量）：把整个模块的公共依赖逐项扫描出来，并支持「一键修复」。
 *
 * 覆盖：各功能分类启用概况 / 微信公共 API 层（含完整性自检）/ 脚本运行时全链路 /
 * Dex 解析与 Hook 任务逐项 / 关键类锚点 / 密友功能状态。
 *
 * 每一项都可点开：弹出「状态 + 含义 + 恢复建议」，可修复的项还带一个**动作按钮**
 * （如未解析的锚点可「重置 Dex 缓存重解析」、未开的密友总开关可「一键启用」）。
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
    )

    /** 一个检测/修复步骤：产出一批 [HealthItem]。 */
    class HealthStep(val title: String, val run: () -> List<HealthItem>)

    const val SECTION_OVERVIEW = "功能清单与分类启用概况"
    const val SECTION_API = "微信公共 API 层"
    const val SECTION_SCRIPT = "脚本运行时"
    const val SECTION_HOOK = "Hook 安装任务与解析状态"
    const val SECTION_ANCHOR = "关键类锚点"
    const val SECTION_SECRET = "密友功能"

    /** 关键类锚点（类名在当前微信宿主中应存在）。 */
    private val ANCHORS: List<Pair<String, String>> = listOf(
        "主界面 LauncherUI" to "com.tencent.mm.ui.LauncherUI",
        "聊天页 ChattingUI" to "com.tencent.mm.ui.chatting.ChattingUI",
        "通讯录 AddressLiveList" to "com.tencent.mm.ui.contact.address.AddressLiveList",
        "资料页 ContactInfoUI" to "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        "wcdb SQLiteDatabase" to "com.tencent.wcdb.database.SQLiteDatabase",
        "wcdb compat SQLiteDatabase" to "com.tencent.wcdb.compat.SQLiteDatabase",
    )

    /** 自检步骤（全量）。 */
    fun healthSteps(context: Context): List<HealthStep> = listOf(
        HealthStep("功能清单与分类启用概况") { featureOverview() },
        HealthStep("微信公共 API 层") { wechatApiLayer() },
        HealthStep("脚本运行时") { scriptRuntime() },
        HealthStep("Hook 安装任务与解析状态") { hookTasks() },
        HealthStep("关键类锚点") { anchors() },
        HealthStep("密友功能") { secretFriendSection() },
    )

    /** 「一键修复」步骤（全量）。 */
    fun repairSteps(context: Context): List<HealthStep> = listOf(
        HealthStep("重置并重建 Dex 适配缓存") { repairDexCache() },
        HealthStep("开启脚本总开关") { repairScriptSwitch() },
        HealthStep("补建脚本运行目录") { repairScriptDirs() },
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
    //  微信公共 API 层
    // ------------------------------------------------------------------

    private fun wechatApiLayer(): List<HealthItem> {
        val repair = dexRepairAction()
        return listOf(
            apiItem("微信 API·数据库", runCatching { WeDatabaseApi }.getOrNull(), repair),
            apiItem("微信 API·账号", runCatching { WeAuthApi }.getOrNull(), repair),
            apiItem("微信 API·联系人", runCatching { WeContactApi }.getOrNull(), repair),
            apiItem("微信 API·会话", runCatching { WeConversationApi }.getOrNull(), repair),
            apiItem("微信 API·消息", runCatching { WeMessageApi }.getOrNull(), repair),
            apiItem("微信 API·群聊", runCatching { WeGroupApi }.getOrNull(), repair),
        )
    }

    private fun apiItem(label: String, obj: Any?, repair: Pair<String, () -> String>): HealthItem =
        if (obj != null) {
            HealthItem(label, SECTION_API, "已解析", true, "已成功取得该 API 对象：${obj.javaClass.name}。")
        } else {
            HealthItem(
                label,
                SECTION_API,
                "未解析",
                false,
                "未取得该 API 对象。\n通常是 DexKit 预热未完成，或当前微信版本未适配。\n点「${repair.first}」后可重试。",
                repair.first,
                repair.second,
            )
        }

    // ------------------------------------------------------------------
    //  脚本运行时
    // ------------------------------------------------------------------

    private fun scriptRuntime(): List<HealthItem> {
        val out = mutableListOf<HealthItem>()
        val repair = dexRepairAction()
        val openSwitch = openScriptSwitchAction()

        val masterOn = runCatching { ScriptCenter.isScriptingAllowed() }.getOrDefault(false)
        out += HealthItem(
            "脚本总开关",
            SECTION_SCRIPT,
            if (masterOn) "已开启" else "已关闭",
            masterOn,
            if (masterOn) "脚本总开关已开启，启动时会加载已启用的插件。"
            else "总开关未开启，所有脚本插件都不会工作。",
            if (masterOn) null else openSwitch.first,
            if (masterOn) null else openSwitch.second,
        )

        val javaOn = runCatching { JavaScriptingHook.isEnabled }.getOrDefault(false)
        out += HealthItem(
            "Java 脚本引擎",
            SECTION_SCRIPT,
            if (javaOn) "已开启" else "已关闭",
            javaOn,
            if (javaOn) "Java 脚本引擎已开启。"
            else "Java 脚本引擎已关闭——只有 Java 插件不会执行。",
        )

        val pythonOn = runCatching { PythonScriptingFeature.isEnabled }.getOrDefault(false)
        out += HealthItem(
            "Python 脚本引擎",
            SECTION_SCRIPT,
            if (pythonOn) "已开启" else "已关闭",
            pythonOn,
            if (pythonOn) "Python 脚本引擎已开启。"
            else "Python 脚本引擎已关闭——只有 Python 插件不会执行。",
        )

        val javaActive = runCatching { JavaScriptingHook.isActive }.getOrDefault(false)
        val pythonActive = runCatching { PythonScriptingFeature.isActive }.getOrDefault(false)
        val engineOk = (javaOn && javaActive) || (pythonOn && pythonActive) || !masterOn
        out += HealthItem(
            "脚本运行时就绪",
            SECTION_SCRIPT,
            if (engineOk) "已就绪" else "未就绪",
            engineOk,
            if (engineOk) "至少一个已开启的脚本引擎 Hook 已实际安装，插件可正常加载。"
            else "开关已开但没有任何引擎 Hook 安装成功——通常是 DexKit 锚点失配。\n点「${repair.first}」重建适配缓存后重试。",
            if (engineOk) null else repair.first,
            if (engineOk) null else repair.second,
        )

        val javaPlugins = runCatching { JavaScriptingHook.scripts.size }.getOrDefault(0)
        val pythonPlugins = runCatching {
            KnownPaths.moduleRoot.resolve("scripts_python").toFile().listFiles()?.count { it.isDirectory } ?: 0
        }.getOrDefault(0)
        out += HealthItem(
            "已安装插件",
            SECTION_SCRIPT,
            "Java $javaPlugins 个，Python $pythonPlugins 个",
            true,
            "本地已安装的脚本插件数量（不含未启用项）。\n可到「脚本插件中心」查看与安装。",
        )

        val marketSize = runCatching { ScriptCenter.marketEntriesSnapshot().size }.getOrDefault(0)
        out += HealthItem(
            "在线插件市场",
            SECTION_SCRIPT,
            if (marketSize > 0) "已拉取 $marketSize 个" else "未拉取（可离线使用）",
            true,
            if (marketSize > 0) "市场索引已缓存，共 $marketSize 个插件可安装。"
            else "尚未拉取市场索引或网络不可用——不影响已安装插件运行。",
        )
        return out
    }

    // ------------------------------------------------------------------
    //  Hook 安装任务与解析状态（以 Dex 缓存 + 委托解析状态为准）
    // ------------------------------------------------------------------

    private fun hookTasks(): List<HealthItem> {
        val out = mutableListOf<HealthItem>()
        val all = FeaturesProvider.ALL_FEATURES
        val dexItems = all.filterIsInstance<IResolveDex>()
        val retryAll = dexRepairAction()

        val enabled = dexItems.filter { (it as? BaseFeature)?.isActive == true }
        val failed = dexItems.filter { feature ->
            val f = feature as? BaseFeature
            f != null && (f as? SwitchFeature)?.isEnabled == true && !f.isActive
        }.mapNotNull { it as? BaseFeature }
        val cached = dexItems.count { runCatching { DexCacheManager.isItemCacheValid(it) }.getOrDefault(false) }

        out += HealthItem(
            "Dex 解析项",
            SECTION_HOOK,
            "共 ${dexItems.size} 项，缓存有效 $cached 项",
            true,
            "需要 DexKit 解析才能安装 Hook 的功能共 ${dexItems.size} 项，其中 $cached 项缓存有效（可直接安装）。\n" +
                "缓存无效项会在启动时重新扫描。",
        )
        out += HealthItem(
            "Hook 安装状态",
            SECTION_HOOK,
            "运行中 ${enabled.size} 项，失效 ${failed.size} 项",
            failed.isEmpty(),
            if (failed.isEmpty()) "所有已启用的 Dex 功能 Hook 均已安装成功。"
            else "以下已启用但未安装成功的功能（${failed.size} 项）：\n" +
                failed.joinToString("\n") { "· " + it.technicalPath } +
                "\n通常是这些功能的锚点在当前微信版本失配。",
            if (failed.isEmpty()) null else retryAll.first,
            if (failed.isEmpty()) null else retryAll.second,
        )
        dexItems.forEach { item ->
            val feature = item as? BaseFeature ?: return@forEach
            val cacheValid = runCatching { DexCacheManager.isItemCacheValid(item) }.getOrDefault(false)
            val delegates = item.dexDelegates
            val resolved = delegates.count { it.getDescriptorString() != null }
            val switchOn = (feature as? SwitchFeature)?.isEnabled == true
            val ok = feature.isActive || !switchOn
            val value = when {
                feature.isActive -> "✅ 已安装（解析 $resolved/${delegates.size}）"
                !switchOn -> "未启用（解析 $resolved/${delegates.size}）"
                cacheValid -> "⚠ 待安装（缓存有效，解析 $resolved/${delegates.size}）"
                else -> "⚠ 锚点待解析（解析 $resolved/${delegates.size}）"
            }
            out += HealthItem(
                "· ${feature.technicalId}",
                SECTION_HOOK,
                value,
                ok,
                "功能：${feature.technicalPath}\n解析锚点：$resolved/${delegates.size}\n" +
                    "缓存有效：${if (cacheValid) "是" else "否"}\n运行中：${feature.isActive}\n\n" +
                    if (feature.isActive) "该 Hook 已安装完成。"
                    else "该 Hook 尚未安装成功——点「${retryAll.first}」清除缓存并触发重新解析后重试。",
                if (ok) null else retryAll.first,
                if (ok) null else retryAll.second,
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    //  关键类锚点
    // ------------------------------------------------------------------

    private fun anchors(): List<HealthItem> = ANCHORS.map { (name, className) ->
        val found = runCatching {
            Class.forName(className, false, ModuleSelfCheck::class.java.classLoader)
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
            "密友总开关",
            section,
            if (master) "开启" else "关闭",
            master,
            "密友功能的总开关（在「密友功能」分类页顶部）。关闭后所有隐藏/拦截都不生效，名单保留。",
            if (master) null else "开启总开关",
            if (master) null else openMaster,
        )
        val count = SecretFriendState.getStoredWxIds().size
        out += HealthItem(
            "密友名单人数",
            section,
            "$count",
            count > 0,
            "当前密友名单共 $count 人。为 0 时不会有任何隐藏效果，请到「密友名单管理」添加密友。",
        )
        val temp = SecretFriendState.isTemporarilyShown()
        out += HealthItem(
            "密友临时解除",
            section,
            if (temp) "生效中" else "未激活",
            !temp,
            "临时解除期间，名单内所有人对你可见。当前：${if (temp) "生效中" else "未激活"}。",
        )
        val faces = runCatching {
            SecretFriendDiagnostics.collectDetailRows().filter { it.section == SecretFriendDiagnostics.SECTION_FACES }
        }.getOrDefault(emptyList())
        val onFaces = faces.count { it.ok }
        out += HealthItem(
            "密友隐藏面生效",
            section,
            "$onFaces/${faces.size}",
            master && onFaces == faces.size,
            "隐藏面里当前有 $onFaces 个处于「生效」状态。\n未生效的多为开关未开或总开关关闭；到「密友功能诊断」页可逐面查看。",
            "重新对账",
            {
                val a = runCatching { HideConversations.reconcileOnListChange() }.isSuccess
                runCatching { WeConversationApi.reloadConversations() }
                if (a) "已按当前名单重新对账并刷新会话列表" else "对账失败"
            },
        )
        return out
    }

    // ------------------------------------------------------------------
    //  修复步骤
    // ------------------------------------------------------------------

    private fun repairDexCache(): List<HealthItem> {
        val r = runCatching { DexCacheManager.clearAllCache() }
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
            runCatching { DexCacheManager.clearAllCache() }
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
}