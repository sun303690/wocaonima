package dev.sun.wechat.features.items.contacts.hidecontacts

import dev.sun.wechat.R
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.ApiFeature
import dev.sun.wechat.features.core.FeatureCategoryIds

import dev.sun.wechat.features.items.contacts.HideContacts
import dev.sun.wechat.features.items.contacts.localizedContactsString
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.TargetProcess
import dev.sun.wechat.utils.WeLogger

/**
 * Notification suppression for 隐藏联系人, split out of [HideContacts] so it can also load in
 * `com.tencent.mm:push`.
 *
 * ## Why this is a separate feature
 *
 * WeChat raises new-message notifications from `com.tencent.mm.booter.CoreService`, which the host
 * manifest pins to `android:process=":push"`. [HideContacts] does not override
 * `shouldLoadInCurrentProcess`, so it only ever loads in the main process — which means the
 * `dealNotify` hook it used to install inline was registered in a process that never calls it. The
 * suppression therefore (almost) never fired. There is a second path on top of that: the LightPush
 * builder raises the notification and plays the ringtone/vibration straight off the push payload and
 * never goes through `dealNotify` at all, so it was not covered even in principle.
 *
 * Widening [HideContacts]'s process set is not an option: its `onEnable` is almost entirely
 * main-process work (SQL hooks, LauncherUI/ChattingUI hooks, the screen-off receiver, the shake
 * detector, the one-shot legacy `parentRef` DB migration) that would be wrong — and in the migration's
 * case racy — if it ran a second time in `:push`. So only the two notification hooks live here, in a
 * feature modelled on
 * [dev.sun.wechat.features.items.notifications.NotificationsEvolved], which already opts into
 * main + push the same way.
 *
 * ## Not user-visible on purpose
 *
 * This is an [ApiFeature] in the `API` category, so it never shows up in the feature list or in the
 * settings search (which only lists `SwitchFeature`s). Notification suppression is not a separate
 * user-facing concept — it is part of "隐藏联系人" — and a second toggle for the same concept would
 * only be confusing. Instead the hooks are installed unconditionally and read [HideContacts]'s own
 * persisted on/off state per invocation, so flipping the 隐藏联系人 switch takes effect immediately
 * without a restart, in both processes.
 *
 * ## Known inconsistency: `temporarilyShown` is main-process only
 *
 * [HideContacts]'s temporary-show escape hatch (`#show` in the input bar, or the triple-tap on the
 * main-screen title) is a plain in-memory flag in the main process; it is deliberately **not**
 * persisted. `:push` cannot see it, and duplicating it into MMKV would mean persisting a state that
 * is supposed to die with the process. The accepted consequence: while temporary-show is active a
 * hidden contact's chats and contact rows reappear, but their **notifications stay suppressed**.
 * Turn 隐藏联系人 off entirely to get notifications back.
 */
object HideContactsNotifications : ApiFeature(), IResolveDex {

    override val technicalId = "隐藏联系人通知抑制"
    override val nameRes = R.string.feature_hide_contacts_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_hide_contacts_notifications_description

    private const val TAG = "HideContactsNotifications"

    /**
     * 「消息通知隐藏」这一面没开时，本类只负责震动（见 [vibrateIfRequested]），通知原样放行。
     *
     * 以前这里是「只要总开关开、对方在名单里就吞通知」，于是想收通知就必须把整个隐藏联系人
     * 关掉——和「密友」的用法冲突。现在与 WCS 的实现对齐：吞通知必须由这一面显式开启
     * （默认关，与 [HideContacts.SURFACE_NOTIFICATIONS] 的默认值一致）。
     */
    private const val CHANNEL_ALERT = "wekit_hidden_alert"
    private const val ALERT_NOTIFICATION_ID = 0x5AC6
    private const val CHATTING_UI = "${PackageNames.WECHAT}.ui.chatting.ChattingUI"
    private const val WEIXIN_TALKER = "weixin"

    /** 震动节流：同一时间窗内连续来消息只震一次，避免长语音被拆成多条时连震。 */
    private const val KEY_LAST_VIBRATE = "hidden_last_vibrate_at"
    private const val VIBRATE_MIN_INTERVAL_MS = 3000L

    /**
     * `com.tencent.mm.booter.notification.x.d(x self, String talker, String content, int msgType,
     * int tipsFlag, boolean isRevokeMessage)` — `static void`, so `args[1]` is the talker and
     * cancelling means `result = null`.
     *
     * Verified identical on both trees:
     * - 8.0.76 `com/tencent/mm/booter/notification/x.java:231`
     *   `public static void d(x xVar, String username, String str, int i17, int i18, boolean z17)`,
     *   anchor string at `:249`
     * - 8.0.69 `com/tencent/mm/booter/notification/x.java:319`
     *   `public static void d(x xVar, String talker, String str, int i16, int i17, boolean z16)`,
     *   anchor string at `:394`
     *
     * The anchor occurs exactly once in each tree. Same matcher as
     * [dev.sun.wechat.features.items.notifications.NotificationsEvolved]'s, deliberately — if it
     * ever 0-hits, that feature would already surface the dex-repair dialog, so adding
     * `allowFailure` here would buy nothing.
     */
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    /**
     * `com.tencent.mm.booter.notification.e0.f(long msgId, String userName, String nickName,
     * String content, String avatarPath, Map msgSource, j4 cmd)` — the LightPush notification
     * builder, an **instance** method returning `void`, so cancelling is `result = null`.
     *
     * Verified on both trees (only the obfuscated method name and the cmd-proto type differ, neither
     * of which the matcher depends on):
     * - 8.0.76 `com/tencent/mm/booter/notification/e0.java:444`
     *   `public void f(long j17, String str, String str2, String str3, String str4, Map map, j4 j4Var)`,
     *   anchor string at `:490`
     * - 8.0.69 `com/tencent/mm/booter/notification/e0.java:419`
     *   `public void g(long j16, String str, String str2, String str3, String str4, Map map, i4 i4Var)`,
     *   anchor string at `:476`
     *
     * `args[1]` is the userName: the guard `if (t8.K0(str) || t8.K0(str2))` logs
     * `"LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)"`
     * (8.0.76 `:497`, 8.0.69 `:483`), binding `str`→userName positionally, and `str` is reused as the
     * talker further down (`"... talker: %s, tipsFlag: %s "`).
     *
     * The anchor occurs exactly once per tree, and in both cases the target method is the last
     * declaration before it, so the string is inside the method DexKit will return — which is what
     * makes this matcher safe, not `allowFailure`. `allowFailure` covers **only the 0-hit case**:
     * `DexMethodDelegate.find` (`dexkit/dsl/DexDelegates.kt`) `error()`s on a multi-hit regardless of
     * it. It is set here purely for the 0-hit case: only 8.0.76 and 8.0.69 could be inspected, and on
     * an unverified 8.0.6x build a 0-hit would mark this feature broken and pop the dex-repair dialog
     * on every launch — worse than losing the LightPush bypass while [methodDealNotify] keeps working.
     */
    private val methodNotifyForLightPush by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(7)
            usingEqStrings("notifyForLightPush push:isShake: %B, isSound: %B ctrlFlag:%s")
        }
    }

    /** `CoreService` — and therefore both notification paths — lives in `:push`, not in main. */
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    /**
     * Whether a notification for [wxId] must be swallowed.
     *
     * Everything is read from [KvStore] (MMKV in `MULTI_PROCESS_MODE`) rather than from
     * [HideContacts]'s runtime state, because none of that state exists in `:push`:
     * - `SwitchFeature` persists its on/off state under the feature's `technicalId`, so the 隐藏联系人 switch
     *   is readable here;
     * - `HideContacts.hiddenContacts` is itself nothing but a [KvStore] string-set read.
     *
     * `HideContacts.temporarilyShown` is intentionally *not* consulted — see the class KDoc.
     */
    /**
     * 「密友消息震动」：名单里有人发消息时补一次短震动。
     *
     * 与「消息通知隐藏」解耦——震动只看 [HideContacts.SURFACE_VIBRATE]，不看通知那面，
     * 这样「通知照常弹、另外再震一下」也能用；是否该震由调用方先用 [isHiddenForAlerts] 判定。
     * 震动是选配（默认关），失败一律静默——通知抑制本身不能被它影响。
     * 用 application context 取 Vibrator，只调一次 createOneShot，不持有引用。
     */
    private fun vibrateIfRequested() {
        if (!HideContacts.surfaceOn(HideContacts.SURFACE_VIBRATE)) return
        val now = System.currentTimeMillis()
        val last = KvStore.getLongOrDef(KEY_LAST_VIBRATE, 0L)
        if (now - last < VIBRATE_MIN_INTERVAL_MS) return
        KvStore.putLong(KEY_LAST_VIBRATE, now)
        runCatching {
            val ctx = dev.sun.wechat.utils.HostInfo.application
            val vibrator = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        180L,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(180L)
            }
        }.onFailure { WeLogger.w(TAG, "密友消息震动失败", it) }
    }


    /**
     * 「微信团队提醒」：通知被吞掉后改发一条本模块的通知，点它会进「微信团队」会话。
     *
     * 与 WCS 的实现对齐：
     * - 固定通知 id，后来的消息只刷新这一条，不按条数堆叠，也不再维护自增计数（旧实现会
     *   一直累加 `wcx_team_pending`，清掉通知后计数还在，正文永远显示一个越来越大的数）；
     * - 带 PendingIntent——旧实现漏了，通知点了没任何反应；
     * - 渠道 IMPORTANCE_DEFAULT，文案走模块自己的多语言资源。
     *
     * 不往「微信团队」会话里写消息：本地插入的消息不会触发微信自己的通知，`:push` 进程也没有
     * 消息存储 API，纯通知不需要这些。
     */
    private fun postTeamAlert() {
        if (!HideContacts.surfaceOn(HideContacts.SURFACE_TEAM_SUMMARY)) return
        runCatching {
            val ctx = dev.sun.wechat.utils.HostInfo.application
            val manager = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return
            ensureAlertChannel(manager)

            val openTeamChat = android.content.Intent().apply {
                setClassName(ctx.packageName, CHATTING_UI)
                putExtra("Chat_User", WEIXIN_TALKER)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.Notification.Builder(ctx, CHANNEL_ALERT)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(ctx)
            }

            val notification = builder
                .setContentTitle(localizedContactsString(R.string.contacts_hide_alert_title))
                .setContentText(localizedContactsString(R.string.contacts_hide_alert_body))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        ctx,
                        ALERT_NOTIFICATION_ID,
                        openTeamChat,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }.onFailure { WeLogger.w(TAG, "汇总提醒发送失败", it) }
    }

    private fun ensureAlertChannel(manager: android.app.NotificationManager) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ALERT) != null) return
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ALERT,
                localizedContactsString(R.string.contacts_hide_alert_channel),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    /** 名单内的会话吗——震动与「吞通知」共用这一个判断。 */
    private fun isHiddenForAlerts(wxId: String): Boolean =
        KvStore.getBoolOrDef(HideContacts.technicalId, false) && wxId in HideContacts.hiddenContacts

    private fun isSuppressed(wxId: String): Boolean =
        HideContacts.surfaceOn(HideContacts.SURFACE_NOTIFICATIONS) && isHiddenForAlerts(wxId)

    override fun onEnable() {
        // Both bodies only cancel the call; they mutate no WeChat state and so cannot re-trigger the
        // method they are hooked onto.
        //
        // Accepted design: as an ApiFeature this is always enabled, so both hooks are installed even
        // when 隐藏联系人 itself is off — the on/off decision is made *per invocation*, inside the
        // bodies, by isSuppressed(). That is what buys restart-free toggling of the 隐藏联系人 switch
        // in both processes (and in :push, where none of HideContacts' runtime state exists). The
        // price is two permanently installed hooks that do nothing but an MMKV read and an early
        // return while the feature is off. Deliberate; do not "fix" it by gating installation on the
        // switch, which would make turning 隐藏联系人 on require a WeChat restart to suppress
        // notifications.

        methodDealNotify.hookBefore(100) {
            val talker = args[1] as? String ?: return@hookBefore
            if (!isHiddenForAlerts(talker)) return@hookBefore
            // 震动与「吞通知」解耦：只开震动、没开消息通知隐藏时，通知照常弹，这里额外震一下。
            vibrateIfRequested()
            if (!isSuppressed(talker)) return@hookBefore
            WeLogger.i(TAG, "suppressing message notification from $talker")
            result = null
            postTeamAlert()
        }

        if (methodNotifyForLightPush.isPlaceholder) {
            WeLogger.w(TAG, "LightPush notify method not resolved; its notifications stay visible")
            return
        }

        methodNotifyForLightPush.hookBefore(100) {
            val userName = args[1] as? String ?: return@hookBefore
            if (!isHiddenForAlerts(userName)) return@hookBefore
            vibrateIfRequested()
            if (!isSuppressed(userName)) return@hookBefore
            WeLogger.i(TAG, "suppressing LightPush notification from $userName")
            result = null
            postTeamAlert()
        }
    }
}
