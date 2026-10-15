package dev.sun.wechat.features.items.self_check

import dev.sun.wechat.features.items.secret_friend.BlockChat
import dev.sun.wechat.features.items.secret_friend.BlockProfile
import dev.sun.wechat.features.items.secret_friend.HideConversations
import dev.sun.wechat.features.items.secret_friend.HideMoments
import dev.sun.wechat.features.items.secret_friend.HideMyMoments
import dev.sun.wechat.features.items.secret_friend.HideRecentForward
import dev.sun.wechat.features.items.secret_friend.HideSearch
import dev.sun.wechat.features.items.secret_friend.HideSecretContacts
import dev.sun.wechat.features.items.secret_friend.HideStatusPage
import dev.sun.wechat.features.items.secret_friend.HideStorageCache
import dev.sun.wechat.features.items.secret_friend.HideStorageRecords
import dev.sun.wechat.features.items.secret_friend.HideTagMembers
import dev.sun.wechat.features.items.secret_friend.MuteSecretFriend
import dev.sun.wechat.features.items.secret_friend.SecretFriendManager
import dev.sun.wechat.features.items.secret_friend.SecretFriendState
import dev.sun.wechat.features.items.secret_friend.SecretFriendToastToggle

/**
 * 密友功能诊断：把「哪一层没隐藏掉」变成可见信息。
 *
 * 密友由 30+ 个独立开关组成（会话 / 通讯录 / 搜索 / 聊天 / 资料 / 朋友圈 / 外围界面…），
 * 任一开关关闭，或某个依赖的类/方法在当前微信版本上失配，该面就会静默失效——用户只看到
 * 「还能搜到 / 还能点进去」，却无从判断是哪一层。本页集中展示总开关、名单、临时解除、
 * 每个隐藏面的开关状态，以及**关键锚点是否解析成功**（逐类逐方法），
 * 据此可直接判断是「开关没开」还是「当前微信版本锚点漂移」。
 *
 * 与模块自检页（[dev.sun.wechat.features.items.self_check.ModuleSelfCheck]）的关系：
 * 模块自检页只做一行「密友状态」概览并链到这里，本页才是密友的完整诊断。
 */
object SecretFriendDiagnostics {

    private const val TAG = "SecretFriendDiagnostics"

    /** 密友总开关开启动作（供诊断行直接执行）。 */
    private val openMasterAction: () -> String = {
        runCatching {
            SecretFriendManager.applyToggle(true)
        }
        "密友功能总开关已开启；若名单为空请到「密友名单管理」添加密友"
    }

    /** 结构化诊断行（供诊断页渲染 + 点开详情）。 */
    data class DiagRow(
        val label: String,
        val section: String,
        val value: String,
        val ok: Boolean,
        val detail: String,
        val actionLabel: String? = null,
        val action: (() -> String)? = null,
    )

    /** 整段文本（供弹窗 / 剪贴板展示）。 */
    fun buildReportText(): String =
        collectDetailRows().joinToString("\n") { it.label + "：" + it.value }

    /** 详细诊断行（含分组、是否正常、点开后的含义/恢复建议）。 */
    fun collectDetailRows(): List<DiagRow> {
        val rows = mutableListOf<DiagRow>()

        val masterOn = SecretFriendState.isSecretFriendMasterOn()
        val count = SecretFriendState.getStoredWxIds().size
        val tempShown = SecretFriendState.isTemporarilyShown()

        rows += DiagRow(
            label = "总开关（密友功能）",
            section = SECTION_MAIN,
            value = if (masterOn) "开启" else "关闭（所有隐藏已失效）",
            ok = masterOn,
            detail = "总开关 = 「密友功能」分类页顶部的开关，缺省开启。\n" +
                "关闭后本分类下所有隐藏/拦截都不生效，且名单保留、重开即恢复。\n" +
                "当前：${if (masterOn) "开启" else "关闭"}。",
            actionLabel = if (masterOn) null else "开启总开关",
            action = if (masterOn) null else openMasterAction,
        )
        val listOk = count > 0
        rows += DiagRow(
            label = "名单人数",
            section = SECTION_MAIN,
            value = if (count == 0) "0（名单为空，全部隐藏不生效）" else "$count",
            ok = listOk,
            detail = "当前密友名单共 $count 人。\n" +
                "名单为空时，即使开关都开着也不会有任何隐藏效果——请到「密友名单管理」里添加密友。",
        )
        rows += DiagRow(
            label = "临时解除",
            section = SECTION_MAIN,
            value = if (tempShown) "生效中（名单内所有人当前可见）" else "未激活",
            ok = !tempShown,
            detail = "临时解除期间，名单内所有人对你可见（用于临时查看）。\n" +
                "当前：${if (tempShown) "生效中" else "未激活"}。到期后会自动恢复隐藏。",
        )

        // 13 项隐藏面
        rows += faceRow(masterOn, "主页会话隐藏", HideConversations.isEnabled)
        rows += faceRow(masterOn, "通讯录隐藏", HideSecretContacts.isEnabled)
        rows += faceRow(masterOn, "标签内隐藏", HideTagMembers.isEnabled)
        rows += faceRow(masterOn, "主页搜索隐藏", HideSearch.isEnabled)
        rows += faceRow(masterOn, "禁止进入聊天", BlockChat.isEnabled)
        rows += faceRow(masterOn, "禁止查看资料", BlockProfile.isEnabled)
        rows += faceRow(masterOn, "朋友圈隐藏", HideMoments.isEnabled)
        rows += faceRow(masterOn, "我的朋友圈隐藏", HideMyMoments.isEnabled)
        rows += faceRow(masterOn, "最近转发隐藏", HideRecentForward.isEnabled)
        rows += faceRow(masterOn, "状态页隐藏", HideStatusPage.isEnabled)
        rows += faceRow(masterOn, "存储空间记录隐藏", HideStorageRecords.isEnabled)
        rows += faceRow(masterOn, "存储空间缓存隐藏", HideStorageCache.isEnabled)
        rows += faceRow(masterOn, "免打扰（消息静音）", MuteSecretFriend.isEnabled)
        rows += faceRow(masterOn, "隐藏密友提示", !SecretFriendToastToggle.isEnabled)

        // 锚点自检：逐类 + 逐方法
        rows += anchorRows(masterOn)

        return rows
    }

    /** 隐藏面诊断行。 */
    private fun faceRow(masterOn: Boolean, name: String, enabled: Boolean): DiagRow {
        val value = when {
            !masterOn -> "总开关关闭，不生效"
            enabled -> "开启"
            else -> "关闭（该层未隐藏）"
        }
        val detail = when {
            !masterOn -> "因为总开关关闭，本面当前不会隐藏任何内容。开启总开关后按本面自身开关生效。"
            enabled -> "本面已开启。若仍能看到该面的密友，多半是它依赖的类/方法在当前微信版本漂移（见「锚点自检」）。"
            else -> "本面自身开关关闭——到「密友功能」分类页打开它即可生效。"
        }
        return DiagRow("── $name ──", SECTION_FACES, value, masterOn && enabled, detail)
    }

    /**
     * 锚点自检：每个锚点先探测类是否存在，再逐个探测方法是否存在，
     * 输出「类 + 每个方法」独立的一行，方便定位是哪一环漂移。
     */
    private fun anchorRows(masterOn: Boolean): List<DiagRow> {
        val out = mutableListOf<DiagRow>()
        ANCHORS.forEach { anchor ->
            val clazz = loadClass(anchor.className)
            val classFound = clazz != null
            out += DiagRow(
                label = "── 锚点·${anchor.display} ──",
                section = SECTION_ANCHOR,
                value = if (classFound) "类已找到" else "类未找到（该面锚点失配）",
                ok = classFound,
                detail = "类 ${anchor.className}\n" +
                    if (classFound) "在当前微信版本中存在。"
                    else "未找到——依赖它的功能可能失效，需要适配当前微信版本。",
            )
            if (!classFound) return@forEach
            anchor.methods.forEach { methodName ->
                val found = runCatching {
                    val c = clazz!!
                    c.declaredMethods.any { it.name == methodName } ||
                        c.methods.any { it.name == methodName }
                }.getOrDefault(false)
                out += DiagRow(
                    label = "　· ${anchor.display}.${methodName}",
                    section = SECTION_ANCHOR,
                    value = if (found) "方法已找到" else "方法未找到（该面无法拦截）",
                    ok = found,
                    detail = "方法 ${anchor.className}.$methodName\n" +
                        if (found) "在当前微信版本中存在。"
                        else "未找到——类在但方法已漂移，依赖该方法的拦截不会生效。",
                )
            }
        }
        return out
    }

    /** 用模块 ClassLoader 解析宿主类；解析不到（类不存在/CL 不可见）返回 null，不抛异常。 */
    private fun loadClass(className: String): Class<*>? = runCatching {
        Class.forName(className, false, SecretFriendDiagnostics::class.java.classLoader)
    }.getOrNull()

    /** 一个锚点：宿主类 + 需要逐个探测的方法。 */
    private data class Anchor(val display: String, val className: String, val methods: List<String>)

    const val SECTION_MAIN = "总开关与名单"
    const val SECTION_FACES = "各隐藏面"
    const val SECTION_ANCHOR = "锚点自检"

    private val ANCHORS = listOf(
        Anchor("聊天页 ChattingUI", "com.tencent.mm.ui.chatting.ChattingUI", listOf("onCreate")),
        Anchor(
            "资料页 ContactInfoUI",
            "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
            listOf("onCreate"),
        ),
        Anchor(
            "通讯录 AddressLiveList",
            "com.tencent.mm.ui.contact.address.AddressLiveList",
            listOf("onInit", "initData"),
        ),
        Anchor("朋友圈评论 SnsCommentFooter", "com.tencent.mm.plugin.sns.ui.SnsCommentFooter", listOf("onCreate")),
        Anchor("主界面 LauncherUI", "com.tencent.mm.ui.LauncherUI", listOf("onCreate")),
        Anchor("wcdb SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase", listOf("query")),
        Anchor("wcdb compat SQLiteDatabase", "com.tencent.wcdb.compat.SQLiteDatabase", listOf("query")),
        Anchor("聊天消息列表 ChattingUI\$1", "com.tencent.mm.ui.chatting.ChattingUI\$1", emptyList()),
        Anchor("联系人标签 LabelUI", "com.tencent.mm.plugin.label.ui.LabelUI", listOf("onCreate")),
        Anchor("发现页 DiscoverUI", "com.tencent.mm.ui.LauncherUI", emptyList()),
    )
}