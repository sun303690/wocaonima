package dev.sun.wechat.features.items.self_check

import android.content.Context
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.constants.Preferences
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
        HealthStep(SECTION_ANCHOR) { anchors() },
        HealthStep(SECTION_SECRET) { secretFriendSection() },
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
    fun buildReportText(context: Context): String =
        healthSteps(context).flatMap { step ->
            runCatching { step.run() }.getOrElse { e ->
                listOf(HealthItem(step.title, step.title, "异常", false, "执行「${step.title}」时抛出异常：\n$e"))
            }
        }.joinToString("\n") { it.label + "：" + it.value }

    /** 概览卡片用的微信版本展示串。 */
    fun wechatVersionDisplay(): String =
        "${HostInfo.versionName}(${HostInfo.versionCode})"

    /** 概览卡片用的模块版本展示串。 */
    fun moduleVersionDisplay(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
