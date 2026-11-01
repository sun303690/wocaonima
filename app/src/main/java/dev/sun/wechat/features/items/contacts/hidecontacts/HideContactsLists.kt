package dev.sun.wechat.features.items.contacts.hidecontacts

import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.dexkit.dsl.DexClassDelegate
import dev.sun.wechat.dexkit.dsl.DexMethodDelegate
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.items.contacts.HideContacts
import dev.sun.wechat.utils.WeLogger
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "HideContacts.Lists"

/**
 * List/adapter-level hiding for the surfaces whose rows never pass through a rewritable SQL query.
 *
 * The SQL rewriter in HideContactsSql.kt covers everything WeChat reads out of `rcontact` /
 * `rconversation` with a statement we can see. The four surfaces installed here are invisible to it:
 *
 * - the 通讯录 and @成员 lists are MVVM "live lists" whose rows are already-materialised
 *   `Contact` objects by the time any adapter sees them;
 * - group-member lists come from `chatroom.memberlist`, a `;`-separated string column, so there is
 *   no per-member row to filter in SQL at all;
 * - 收藏 has largely moved to the WCDB ORM builder (`r82.e` / `br5.f`), which emits no raw SQL;
 * - 视频号 like lists are network-driven protobuf, with no local table behind them.
 *
 * Every hook here filters the *input* the adapter is built from rather than remapping adapter
 * indices. That is deliberate: an earlier iteration of this feature shifted `getView` positions
 * through a `hiddenPositions` set without touching `getItem`, so tapping a row opened the wrong
 * chat. Cutting the entry before the adapter ever counts it keeps `getCount()`, `getItem()` and
 * every click listener consistent by construction, and a resolve failure degrades to "the contact
 * stays visible" instead of "the wrong contact opens".
 *
 * Filtering is done by **substituting a filtered copy**, never by mutating the collection WeChat
 * handed us, unless that collection is provably a per-call/per-response object with no other
 * consumer. 隐藏 here is a display-time decision that `#show`, the triple-tap gesture and the
 * 定时显示 scheduler must be able to undo instantly; a destructive `removeAll` on a list the host
 * retains would make "temporarily show" unable to bring the rows back until WeChat repopulates it.
 */
internal fun HideContacts.installListHooks() {
    // 按面安装：每个面独立开关，关掉的面连 Hook 都不装。
    if (surfaceOn(SURFACE_CONTACTS)) {
        installMvvmListHooks()
        installContactCountHook()
        installLabelMemberHooks()
    }
    if (surfaceOn(SURFACE_GROUP_MEMBERS)) installGroupMemberHooks()
    if (surfaceOn(SURFACE_FAVORITES)) installFavoriteHooks()
    if (surfaceOn(SURFACE_FINDER_LIKE)) installFinderLikeHooks()
    if (surfaceOn(SURFACE_SEARCH)) installSearchResultRowHooks()
}

// ---------------------------------------------------------------------------------------------
// MvvmList — 通讯录 (AddressLiveList) and the @成员选择器 (AtSomeoneLiveList)
// ---------------------------------------------------------------------------------------------

/**
 * `MvvmList.e(List snapshotList)` is WeChat's per-list "preprocess the snapshot before it reaches
 * the adapter" hook, and both list classes implement it identically: sort the snapshot in place,
 * walk it once to recompute the section headers *from whatever it was handed*, then `map` it into a
 * list of *clones* which becomes the adapter's data. Everything downstream — the adapter rows, the
 * section flags and (for `AtSomeoneLiveList`) the A-Z index list it rebuilds into its `C` field — is
 * derived from the argument alone, so replacing the argument with a filtered copy in a `hookBefore`
 * yields a fully self-consistent result with no index arithmetic anywhere. Verified identical on
 * 8.0.69, 8.0.74 and 8.0.76 (`ui/contact/address/AddressLiveList.e`,
 * `ui/chatting/atsomeone/AtSomeoneLiveList.e`).
 *
 * The argument is **substituted, not mutated**. `MvvmList.l()` calls `e(this.f182257p)`
 * (`plugin/mvvmlist/MvvmList.java:373` on 8.0.76, `:373` on 8.0.74, `:361` on 8.0.69), i.e. `args[0]`
 * *is* the list's persistent snapshot field, not a per-call copy. An earlier version of this hook
 * called `removeAll` on it, which deleted the rows out of WeChat's own backing store: `#show`, the
 * triple-tap gesture and a scheduled SHOW could not bring the 通讯录 rows back, and neither could
 * un-hiding a contact, until WeChat repopulated the list from its data source.
 *
 * A `hookAfter` on the return value would not do either: it would drop the adapter rows but leave
 * the section headers that `e()` already computed over the unfiltered snapshot, so a section whose
 * only member was hidden would keep its header. Filtering the input is what keeps them in sync.
 *
 * `AddressLiveList` (通讯录) and `AtSomeoneLiveList` (@成员) differ only in their entry class
 * (`ah5.g` vs `com.tencent.mm.ui.chatting.atsomeone.b` on 8.0.76), and both entry classes carry the
 * contact as their single `com.tencent.mm.storage.*` field, so one reflective shape works for both —
 * hence the shared [hookMvvmListPreprocess] helper, which is the only thing either surface installs.
 */
private fun HideContacts.installMvvmListHooks() {
    hookMvvmListPreprocess(methodAddressMvvmListPreprocessList, "AddressLiveList")
    hookMvvmListPreprocess(methodAtSomeoneMvvmListPreprocessList, "AtSomeoneLiveList")
}

private fun HideContacts.hookMvvmListPreprocess(target: DexMethodDelegate, label: String) {
    if (target.isPlaceholder) {
        WeLogger.w(TAG, "$label preprocess method wasn't resolved; that list stays unfiltered")
        return
    }

    target.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        val contacts = args[0] as? List<*> ?: return@hookBefore
        // MvvmList hands us an empty snapshot on the initial load and whenever a filter matches
        // nothing; there is no entry to read the shape off then.
        if (contacts.isEmpty()) return@hookBefore

        // The entry classes are obfuscated, but each holds exactly one field whose type lives in
        // com.tencent.mm.storage (the Contact — y3 on 8.0.76, a3 on 8.0.69); everything else on them
        // is an int/boolean/String or a UI helper from another package. Selecting by declared type
        // rather than by name survives the per-version field renames.
        //
        // Both lookups are the *OrNull* variants behind an early return, matching the 收藏 hook
        // below: this body runs on every single 通讯录 render, so a shape change on some unverified
        // version has to degrade to "the list stays unfiltered", never throw out of a hook.
        val sample = contacts.firstNotNullOfOrNull { it } ?: return@hookBefore
        val contactInfoField = sample.reflekt()
            .firstFieldOrNull { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
            ?.self?.makeAccessible()
        if (contactInfoField == null) {
            WeLogger.w(TAG, "$label entry has no com.tencent.mm.storage field; that list stays unfiltered")
            return@hookBefore
        }
        val usernameField = contactInfoField.type.reflekt()
            .firstFieldOrNull {
                name = "field_username"
                superclass()
            }?.self?.makeAccessible()
        if (usernameField == null) {
            WeLogger.w(TAG, "$label contact has no field_username; that list stays unfiltered")
            return@hookBefore
        }

        val kept = contacts.filterNot { contact ->
            val contactInfo = contact?.let { contactInfoField.get(it) } ?: return@filterNot false
            val username = usernameField.get(contactInfo) as? String ?: return@filterNot false
            isHiddenNow(username)
        }
        if (kept.size == contacts.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${contacts.size - kept.size} hidden contact(s) out of $label")
        // Substitution, never `contacts.removeAll { ... }`: this list is MvvmList's own persistent
        // `f182257p` snapshot field. See the KDoc above.
        args[0] = ArrayList(kept)
    }
}

// ---------------------------------------------------------------------------------------------
// 通讯录 「N 位联系人」
// ---------------------------------------------------------------------------------------------

/**
 * The footer under 通讯录 (`ContactCountView`) has two producers, and only one of them can be fixed
 * in SQL.
 *
 * - 群聊 (`contactType == 2`) counts inline in `ui/contact/f1.run()` and is rewritten by the
 *   `group-count` rule in HideContactsSql.kt.
 * - 联系人 (`contactType == 1`) calls `ContactStorage.getNormalContactCount`, whose statement ends in
 *   a bare `or username = 'weixin'`. `AND` binds tighter than `OR`, so an appended predicate would
 *   attach to that last operand and change nothing at all — the count has to be corrected after the
 *   fact instead.
 *
 * The correction re-counts the hidden set against the same predicates *and the same excluded
 * usernames* WeChat uses, so that hidden groups, 公众号, blocked contacts and helper accounts (none
 * of which the footer counted in the first place) are never subtracted. `includeBlack` and the
 * exclusion varargs are both mirrored from the call's own arguments.
 *
 * 「仅聊天的朋友」 needs nothing: `OnlyChatContactMgrUI` takes its count from an already-filtered
 * `@social.black.android` cursor rather than from this method.
 */
private fun HideContacts.installContactCountHook() {
    if (methodNormalContactCount.isPlaceholder) {
        WeLogger.w(TAG, "getNormalContactCount wasn't resolved; 通讯录联系人计数 stays unadjusted")
        return
    }

    methodNormalContactCount.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        val total = result as? Int ?: return@hookAfter

        val hidden = hiddenContacts
        if (hidden.isEmpty()) return@hookAfter

        // `O(boolean includeBlack, String[] excluded, String... more)` — both vararg groups are
        // turned into `and rcontact.username != '<each>'` terms, so the population it counts is
        // narrower than the bare predicates suggest. Reading them off the live call rather than
        // hard-coding `e01.e2.f269714p` keeps the mirror correct for every caller and every version.
        val excluded = buildSet {
            addAll(stringArrayArg(args.getOrNull(1)))
            addAll(stringArrayArg(args.getOrNull(2)))
            // WeChat skips "weixin" in both loops (`if (!"weixin".equals(str))`) and re-admits it
            // with the trailing `or username = 'weixin'`, so it is not an exclusion at all.
            remove("weixin")
        }

        val hiddenNormal = countHiddenNormalContacts(
            hidden,
            includeBlack = args[0] == true,
            excluded = excluded,
        )
        if (hiddenNormal <= 0) return@hookAfter

        WeLogger.d(TAG, "adjusted 通讯录 contact count by -$hiddenNormal")
        result = (total - hiddenNormal).coerceAtLeast(0)
    }
}

/** Flattens a `String[]` / vararg argument into a list of non-null strings. */
private fun stringArrayArg(arg: Any?): List<String> =
    (arg as? Array<*>)?.mapNotNull { it as? String }.orEmpty()

/**
 * How many of the hidden contacts WeChat's own 「N 位联系人」 query would have counted.
 *
 * Mirrors `getNormalContactCount`'s predicates: a normal, non-hidden-flag contact
 * (`type & 1 != 0`, `type & 32 = 0`), not a 公众号 (`verifyFlag & 8 = 0`), optionally excluding the
 * blacklist (`type & 8 = 0`), and a plain wxid — `username not like '%@%'` is what
 * `e01.e2.b(username, "@micromsg.qq.com", …)` emits, and it is what keeps 群聊 (`@chatroom`) and
 * 企业微信 (`@openim`) out of the total.
 *
 * Accepted limitation: `username not like '%@%'` is hardcoded here, but `e2.b` forks on the
 * `usernameFlag` experiment (`com.tencent.mm.contact.d.f93632g.a()`, `e01/e2.java:434` on 8.0.76) and
 * emits `and ( usernameFlag in ( 0 ) )` instead when it is on. The two are semantically the same
 * classification — `usernameFlag` is the denormalised form of "is this a plain wxid" — so the mirror
 * stays correct in practice. If that column ever lags behind `username` for some row, WeChat's total
 * and this mirror would disagree about that row and the footer could be over-subtracted by one. Not
 * worth a second query path: the failure mode is a slightly wrong count, never a wrong row.
 *
 * [excluded] mirrors the two vararg groups WeChat turns into `and rcontact.username != '<each>'`
 * terms. Skipping them would over-subtract: `ContactCountView` passes `e01.e2.f269714p` plus
 * `z1.r(), "weixin", "helper_entry", "filehelper"`, and entries like `filehelper` or `medianote`
 * satisfy `type & 1` and contain no `@`, so they are pickable as hidden contacts yet were never in
 * WeChat's total to begin with.
 *
 * The `or username = 'weixin'` disjunct is reproduced too, parenthesised: it re-admits 微信团队
 * unconditionally, whatever its flags, so a hidden `weixin` really is in WeChat's total.
 *
 * The statement runs on WeKit's own database handle rather than WeChat's storage wrapper, so it
 * cannot re-enter the wrapper hook. Its formatting deliberately differs from WeChat's
 * (`type & 1 != 0` vs `type & 1 !=0`, and the hidden-set predicate comes first) so it can never be
 * mistaken for the query the `group-count` rule matches either.
 */
private fun countHiddenNormalContacts(
    hidden: Set<String>,
    includeBlack: Boolean,
    excluded: Set<String>,
): Int {
    // `IN ()` is a SQLite syntax error; the caller already bails, this is belt-and-braces.
    if (hidden.isEmpty()) return 0
    if (!WeDatabaseApi.isReady) return 0

    val blacklistClause = if (includeBlack) "" else "and type & 8 = 0 "
    val excludedClause =
        if (excluded.isEmpty()) "" else "and username not in (${excluded.toSqlList()}) "
    val sql = "select count(username) from rcontact " +
            "where username in (${hidden.toSqlList()}) " +
            "and ( ( type & 1 != 0 and type & 32 = 0 $blacklistClause" +
            "and verifyFlag & 8 = 0 and username not like '%@%' " +
            "$excludedClause) or username = 'weixin' )"

    val value = WeDatabaseApi.executeQuery(sql).firstOrNull()?.values?.firstOrNull()
    return (value as? Long)?.toInt() ?: 0
}

// ---------------------------------------------------------------------------------------------
// 群成员列表
// ---------------------------------------------------------------------------------------------

/**
 * Group-member lists cannot be filtered in SQL: the members live in `chatroom.memberlist`, a single
 * `;`-separated text column, so a row that contains a hidden contact also contains everyone else.
 * Both member UIs do, however, start from a plain `List<String>` of usernames, which is the ideal
 * place to cut.
 *
 * - 查看全部群成员 (`SeeRoomMemberUI`): its adapter is fed by `cc.d(List usernames)`, which clears
 *   its item list and rebuilds it from the argument. Filtering the argument in a `hookBefore` means
 *   the adapter's list, `getCount()` and `getItem()` are all built from the same filtered input.
 * - `SelectMemberUI` and the subclasses that inherit its `V6()` (`j7()` on 8.0.69): the adapter's
 *   loader Runnable reads it once and builds its `bd` items from it, so a `hookAfter` returning a
 *   filtered copy is equivalent. Identical on 8.0.69/8.0.74/8.0.76, the inheriting UIs are
 *   `SelectDelMemberUI` (删除成员), `SelectAddRoomManagerUI` (添加管理员),
 *   `TransferRoomOwnerUI` (转让群主) and `SeeMemberRecordUI` (群成员记录) — there is **no** invite UI
 *   among them, despite what an earlier version of this comment and the `@Feature` blurb claimed.
 *
 * `V6()` returns `ChatroomInfo.z0()`, which **caches** the parsed member list in a field and hands
 * back the same instance every time — so this must never filter in place. A fresh `ArrayList` is
 * returned instead, and only when something was actually removed, leaving WeChat's cache (used by
 * member counts, @全体 delivery and message routing) untouched.
 *
 * NB: `SelectDelRoomManagerUI` / `SelectRoomFollowMemberManagerUI` override `V6()` without calling
 * super, so the 管理员 lists are deliberately not covered — they are a different surface.
 */
private fun HideContacts.installGroupMemberHooks() {
    if (methodSeeRoomMemberSetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SeeRoomMemberUI adapter setter wasn't resolved; 查看全部群成员 stays unfiltered")
    } else {
        methodSeeRoomMemberSetMemberList.hookBefore {
            if (isTemporarilyShown) return@hookBefore
            val members = args[0] as? List<*> ?: return@hookBefore
            val filtered = filterHiddenUsernames(members) ?: return@hookBefore
            WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SeeRoomMemberUI")
            args[0] = filtered
        }
    }

    if (methodSelectMemberUiGetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SelectMemberUI member-list getter wasn't resolved; 群成员选择器 stays unfiltered")
        return
    }

    methodSelectMemberUiGetMemberList.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        val members = result as? List<*> ?: return@hookAfter
        val filtered = filterHiddenUsernames(members) ?: return@hookAfter
        WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SelectMemberUI")
        result = filtered
    }
}

/**
 * Returns a copy of [members] without the hidden usernames, or `null` when nothing was removed —
 * so callers can leave the host's own (possibly cached) list object completely alone.
 */
private fun filterHiddenUsernames(members: List<*>): ArrayList<Any?>? {
    if (members.isEmpty()) return null
    val filtered = members.filterNot { it is String && HideContacts.isHiddenNow(it) }
    if (filtered.size == members.size) return null
    return ArrayList(filtered)
}

// ---------------------------------------------------------------------------------------------
// 标签成员
// ---------------------------------------------------------------------------------------------

/**
 * 通讯录 -> 标签 -> 某个标签的成员。**打在存储层那一次读上** —— 同类密友模块也挂在同一处。
 *
 * 为什么不是 UI 也不是 SQL：
 * - SQL 侧证否：标签页成员读的是全量映射 `SELECT user, label_id FROM FTS5ContactLabels`
 *   （`plugin/fts/logic/c0` = LoadLabelCacheTask），而 `WHERE label_id=?` 那条出自
 *   MarkLabelDirtyTask（`h0.smali:109`）—— 刷索引用的，筛它等于让宿主把「这个标签没有文档」缓存进
 *   `l.p:HashMap`；`labelIDList` 在真机全天 0 次，`contact-label` 那条规则钉的是宿主不走的路径。
 * - UI 侧也证否了：上一版按 `ContactListExpandPreference` 的 `void (List)` 入口挂（钩子是挂上了，
 *   `label member setters: V, X`），但 `out of a label list` 在多轮真机日志里 **0 次** —— 标签页
 *   根本不走那个控件。这一版把它换掉，不留死钩子。
 *
 * 落点 `ContactLabelStorage.getUserNameListById(String)`（8.0.76 是 `storage/g4.smali:3114` 的
 * `s2`，字面量 `getUserNameListById() called with: id = [`）：一个方法服务所有消费方 ——
 * `ba3/{q5,j3,m1,s1,b3,k0}`、`fa3/{e2,n1}` 都在调它（标签成员页、标签选择器、LabelHelper…），
 * 在源头筛一次就都对了，不用一个页面一个页面地追。返回的是用户名字符串的 ArrayList，元素直接比。
 *
 * 换表而不原地删（同类实现同样是这条：新建 ArrayList 逐元素过滤，**只在 size 变了才返回新表**）：
 * `g4.g:HashMap` 把这个列表缓存着，原地删会让「临时显示」和三击喊不回来。
 *
 * 明确接受的代价：存储层这条不只喂显示 —— 按标签群发/标签消息也读它，所以隐藏联系人会一起收不到。
 * 这是挂在这一层必然带来的语义，不是显示层能两全的位置；要分开得再拆一层。
 */
private fun HideContacts.installLabelMemberHooks() {
    if (methodLabelUserNamesById.isPlaceholder) {
        WeLogger.w(TAG, "getUserNameListById wasn't resolved; 标签成员 stays unfiltered")
        return
    }

    methodLabelUserNamesById.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (!surfaceOn(SURFACE_CONTACTS)) return@hookAfter

        val members = result as? List<*> ?: return@hookAfter
        val filtered = filterHiddenUsernames(members) ?: return@hookAfter
        WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) out of a label")
        result = filtered
    }
}

// ---------------------------------------------------------------------------------------------
// 收藏
// ---------------------------------------------------------------------------------------------

/**
 * 收藏 rows carry their sender in `FavItemInfo.field_fromUser`, but the plugin has largely migrated
 * to the WCDB ORM builder (`r82.e` / `br5.f`, see `fav/ui/adapter/c.java:349-360`), which produces
 * no raw SQL for the query rewriter to intercept. Only the legacy path still emits a statement.
 *
 * `FavoriteAdapter` funnels *both* paths — the ORM branch, the legacy branch, the search branch and
 * the "get null list, new empty one" fallback — through one private setter, `r(List)` (`t(List)` on
 * 8.0.69), which is the sole writer of the adapter's pending data list. Filtering its argument
 * therefore covers every load path at once, and since `getCount()`/`getItem()` both read the list
 * this method installs (after `notifyDataSetChanged` swaps it in), positions can never desync.
 *
 * The filtered result is handed over as a fresh `ArrayList` rather than removed in place: some call
 * sites pass Kotlin's immutable `EmptyList` singleton, whose iterator rejects `remove()`.
 */
private fun HideContacts.installFavoriteHooks() {
    if (methodFavoriteAdapterSetDataList.isPlaceholder) {
        WeLogger.w(TAG, "FavoriteAdapter data setter wasn't resolved; 收藏 stays unfiltered")
        return
    }

    methodFavoriteAdapterSetDataList.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        // `r(null)` is a real call shape (FavApiLogic returns null when the storage is missing),
        // so this must tolerate a null argument rather than assume a list.
        val items = args[0] as? List<*> ?: return@hookBefore
        if (items.isEmpty()) return@hookBefore

        val sample = items.firstNotNullOfOrNull { it } ?: return@hookBefore
        // `field_fromUser` is a real (unobfuscated) storage column name, declared on FavItemInfo's
        // generated base class — hence the superclass walk.
        val fromUserField = sample.reflekt()
            .firstFieldOrNull {
                name = "field_fromUser"
                superclass()
            }?.self?.makeAccessible()
        if (fromUserField == null) {
            WeLogger.w(TAG, "FavItemInfo.field_fromUser not found; 收藏 stays unfiltered")
            return@hookBefore
        }

        val filtered = items.filterNot { item ->
            val fromUser = item?.let { fromUserField.get(it) as? String } ?: return@filterNot false
            isHiddenNow(fromUser)
        }
        if (filtered.size == items.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${items.size - filtered.size} hidden favourite(s)")
        args[0] = ArrayList(filtered)
    }
}

// ---------------------------------------------------------------------------------------------
// 视频号「朋友❤过」
// ---------------------------------------------------------------------------------------------

/**
 * Index of `wxUsername` in the like-entry protobuf (`a65.je1` on 8.0.76, `mx4.v91` on 8.0.69).
 *
 * The generated field table is identical on both trees — `0=nickName, 1=headImgUrl, 2=likeId,
 * 3=likeFlag, 4=refuseFlag, 5=wxUsername, …, 11=finder_username` — and index 5 is the only entry
 * that is a real wxid. `finder_username` is a `v2_…` Finder identity with no mapping back to a
 * contact, which is why the Finder follow/aggregation lists are explicitly out of scope.
 */
private const val LIKE_ENTRY_WX_USERNAME = 5

/**
 * Hides a hidden contact from the 视频号 like list ("朋友❤过" / the ❤ drawer).
 *
 * Finder feeds have no local table, so there is nothing for the SQL rewriter to touch. The drawer
 * presenter (`FinderLikeDrawerPresenter`, and its "朋友❤过" subclass
 * `FinderFriendLikeListDrawerPresenter`) keeps one `ArrayList` of `FinderFeedLike` items that is
 * handed straight to `WxRecyclerAdapter` as its backing list, and exactly two callbacks append to
 * it: the refresh callback and the load-more callback. Both build their items in a loop over an
 * incoming list of raw like protobufs, so filtering that input list before the loop keeps the
 * adapter's list, its item count and the `notifyItemRangeInserted` offsets all in agreement — which
 * a `FinderFeedFriendLikeConvert.onBindViewHolder` hook could never do, since a bind cannot remove a
 * row.
 *
 * The refresh callback receives a `GetFinderFeedLikedListData` holder whose single `LinkedList`
 * field is the like list; it is a per-response object with no other consumer, so it is filtered in
 * place. The load-more callback receives the list directly, and gets a filtered copy instead —
 * cheaper than proving the network layer does not retain it.
 *
 * Accepted limitation (paging): the presenter decides whether more pages exist partly from how many
 * rows a page yielded, so a page whose entries are *all* hidden can look empty and stop the paging
 * loop early. The consequence is only "fewer rows than the server would have shown" — never a wrong
 * or misattributed row — and it self-corrects the next time the list is refreshed or the next page
 * is requested, since the server-side cursor is untouched. Not worth faking a row count for.
 */
private fun HideContacts.installFinderLikeHooks() {
    if (methodFinderLikeDrawerRefresh.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer refresh callback wasn't resolved; 视频号点赞列表 stays unfiltered")
    } else {
        methodFinderLikeDrawerRefresh.hookBefore {
            if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

            val data = args[0] ?: return@hookBefore
            val listField = data.reflekt()
                .firstFieldOrNull { type = LinkedList::class }
                ?.self?.makeAccessible() ?: return@hookBefore

            @Suppress("UNCHECKED_CAST")
            val likes = listField.get(data) as? MutableList<Any?> ?: return@hookBefore
            val removed = likes.removeAll { it != null && isHiddenLikeEntry(it) }
            if (removed) WeLogger.d(TAG, "filtered hidden contacts out of the finder like list")
        }
    }

    if (methodFinderLikeDrawerLoadMore.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer load-more callback wasn't resolved; 视频号点赞列表 stays unfiltered")
        return
    }

    methodFinderLikeDrawerLoadMore.hookBefore {
        if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

        val likes = args[0] as? List<*> ?: return@hookBefore
        val filtered = likes.filterNot { it != null && isHiddenLikeEntry(it) }
        if (filtered.size == likes.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${likes.size - filtered.size} hidden like(s) from the finder like list")
        args[0] = ArrayList(filtered)
    }
}

private fun HideContacts.isHiddenLikeEntry(entry: Any): Boolean {
    val wxId = protoGetString(entry, LIKE_ENTRY_WX_USERNAME) ?: return false
    return isHiddenNow(wxId)
}

/**
 * Reads a string field out of a `com.tencent.mm.protobuf.e` subclass by its *position* in the
 * generated field table. The concrete protobuf classes are obfuscated and renamed every version
 * (`a65.je1` -> `mx4.v91`), but `getString(int)` is declared on the unobfuscated base class, so it
 * is resolved by walking up the hierarchy — mirroring `WeMomentsApi.xs4GetString`.
 */
private fun protoGetString(proto: Any, fieldIndex: Int): String? = runCatching {
    var cls: Class<*>? = proto.javaClass
    while (cls != null) {
        val getter = cls.declaredMethods.firstOrNull {
            it.name == "getString" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (getter != null) {
            getter.isAccessible = true
            return@runCatching getter.invoke(proto, fieldIndex) as? String
        }
        cls = cls.superclass
    }
    null
}.getOrNull()

// ---------------------------------------------------------------------------------------------
// 主页搜索的结果行（视图级）
// ---------------------------------------------------------------------------------------------

/**
 * 结果行/条目的字段表，按类缓存一次：这些判据跑在每次敲字的每一条结果上，不能每行都反射。
 * 键是宿主类，一个进程内就那么几个类，不需要回收。
 *
 * 要沿父类链收：联系人行是 `com.tencent.mm.storage.y3`，它的 `field_username` 声明在生成出来的基类上
 * （`dm/f2.smali:223`），只看叶子类自己会一个字段也找不到 —— 这也是本仓 通讯录 那条钩子用
 * `superclass()` 的同一个原因。停在 `java.*` / `android.*` 之前，否则会扫进
 * `android.view.View` 自带的那一堆成员，造出假命中。
 */
private val searchFieldsByClass = ConcurrentHashMap<Class<*>, List<Field>>()

private fun declaredSearchFields(cls: Class<*>): List<Field> =
    searchFieldsByClass.getOrPut(cls) {
        buildList {
            var current: Class<*>? = cls
            while (current != null && !current.name.startsWith("java.") && !current.name.startsWith("android.")) {
                current.reflekt().fields().forEach { add(it.self.makeAccessible()) }
                current = current.superclass
            }
        }
    }

/**
 * 这个对象（或往下 [depth] 层）里有没有哪个 String 字段正好等于一个隐藏 id。
 *
 * 为什么不钉字段名：条目类与字段名每版都改（8.0.76 聊天记录段的条目是 `j23.y`，id 在 `y.e`，
 * 靠 `z23/u1.smali:52-62` 把它交给 `storage.j4.n(String,Z)` 才坐实），而真机 2026-10-31 的第三轮
 * 显示判据太窄也不够用 —— 联系人那一行到底把 id 放在哪一层还没量出来。
 *
 * 只有两个值类型会继续下钻：`List` 和宿主自己的类（`com.tencent.mm.*`）。框架对象（View、
 * Context、Bundle…）不下钻 —— 里面什么都有，钻进去会把无关行判成隐藏。
 *
 * wxid 与群 id 形态都很特别（`wxid_…` / `…@chatroom`），昵称/摘要/时间串都不会等于一个 id，
 * 所以按值比对不会误伤别人。
 */
private fun HideContacts.containsHiddenId(obj: Any, depth: Int): Boolean {
    declaredSearchFields(obj.javaClass).forEach { field ->
        when (val value = field.get(obj)) {
            null -> Unit
            is String -> if (isHiddenNow(value)) return true
            is List<*> -> if (depth > 0 && value.any { it != null && containsHiddenId(it, depth - 1) }) return true
            else -> if (depth > 0 && value.javaClass.name.startsWith("${PackageNames.WECHAT}.")) {
                if (containsHiddenId(value, depth - 1)) return true
            }
        }
    }
    return false
}

/** 被我们改过的行 View，以及它本来的 visibility 与宽高。 */
private class SearchRowBaseline(val visibility: Int, val width: Int, val height: Int)

/**
 * View 是复用的：这次画隐藏联系人的那个 View，下一次可能拿去画别人。所以基线按 View 存，命中就改，
 * 没命中且被我们改过就必须还原 —— 漏了还原会留下「别人那条也变成空行」的空洞，比不隐藏更糟。
 * 用 WeakHashMap：滚出屏幕被回收的 View 自己掉出去，不用我们清。
 */
private val searchRowBaselines = WeakHashMap<View, SearchRowBaseline>()

/**
 * 把主页搜索里属于隐藏联系人的结果行从屏幕上摘掉。
 *
 * 层次是三轮真机读数选出来的：SQL 文本改不到（身份在绑定参数里，而且同级模块早就在给 FTS 查询套
 * `aux_index NOT IN` 了，界面照旧）；FTS 段基类 `a.o0` 只管「结果真从 FTS 查询来」的段（聊天记录段
 * `e33.u` 实测能少一条），联系人那一整段一次都没经过它。剩下能兜住这行的就是渲染那一下 ——
 * `fm5.u` / `fm5.e` 的 `getView`。同类密友模块在 8.0.76 上走的也正是这一层：命中就 `setVisibility(GONE)`
 * 并把 `LayoutParams` 宽高写 0（ListView 里单靠 GONE 不收回高度），原尺寸存 WeakHashMap 以便还原。
 *
 * 「这一行是谁」用宿主自己的映射，不再自己数下标：`getItem(position)` 是 `com.tencent.mm.ui.z9`
 * （两者的共同父类，`super android.widget.BaseAdapter`）在 `z9.smali:177` 的实现 —— 它会先用
 * `o(position)` 判掉 header/分隔行，再从 map 或 cursor 取那一行。`fm5.u.getView` 开头也调同一个
 * `o(I)Z`，可见位置与条目下标本来就不是一对一的。
 *
 * 明确接受的边界：
 * - 只改屏幕上这一行，段计数与「查看全部 N 条」那类文案不动（能改的 SQL 面照旧在改）；点进行为本来
 *   就被 `HideContactsBlock` 静默挡住，这里不会新开出入口。
 * - `fts/ui/f0` 那一族不挂：它没有覆盖 `getItem(int)`，`BaseAdapter` 的默认实现返回 null，拿不到
 *   「这一行是谁」；那条通路由上面 `a.o0` 管。
 */
private fun HideContacts.installSearchResultRowHooks() {
    listOf(classSearchResultAdapter, classSearchConversationAdapter).forEach { anchor ->
        val getView = anchor.resolveGetView()
        if (getView == null) {
            WeLogger.w(TAG, "search result getView wasn't resolved; 主页搜索 stays visible")
            return@forEach
        }
        WeLogger.i(TAG, "hiding 主页搜索 rows at ${getView.declaringClass.name}.${getView.name}")

        getView.hookAfter {
            if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookAfter
            if (!surfaceOn(SURFACE_SEARCH)) return@hookAfter

            val view = result as? View ?: return@hookAfter
            val position = args[0] as Int
            val adapter = thisObject as Adapter
            val row = adapter.getItem(position)
            val baseline = searchRowBaselines[view]

            if (row != null && containsHiddenId(row, 1)) {
                // 已经是隐藏态就别再动：滚动时同一个 View 会被反复 bind，否则这条日志会淹掉文件。
                if (view.visibility == View.GONE && view.layoutParams?.height == 0) return@hookAfter
                if (baseline == null) {
                    searchRowBaselines[view] = SearchRowBaseline(
                        view.visibility,
                        view.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                        view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                view.visibility = View.GONE
                view.layoutParams?.apply {
                    width = 0
                    height = 0
                }?.let { view.layoutParams = it }
                WeLogger.d(TAG, "hid a 主页搜索 result row in ${adapter.javaClass.name} at $position")
            } else if (baseline != null) {
                searchRowBaselines.remove(view)
                view.visibility = baseline.visibility
                view.layoutParams?.apply {
                    width = baseline.width
                    height = baseline.height
                }?.let { view.layoutParams = it }
            }
        }
    }
}

/**
 * 找 `getView(int, View, ViewGroup)`：子类覆盖了就取子类那一份，没覆盖就沿父链上溯到真正声明它的
 * 那一层（8.0.76 上 `fm5.u` 与 `fm5.e` 各自都声明了，见 `fm5/u.smali:202`、`fm5/e.smali:246`）。
 */
private fun DexClassDelegate.resolveGetView(): Method? {
    if (isPlaceholder) return null
    var cls: Class<*>? = clazz
    while (cls != null && cls != Any::class.java) {
        val declared: Class<*> = cls
        val method = declared.declaredMethods.firstOrNull { candidate ->
            candidate.name == "getView" && candidate.parameterCount == 3 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[1] == View::class.java &&
                candidate.parameterTypes[2] == ViewGroup::class.java
        }
        if (method != null) return method.makeAccessible()
        cls = declared.superclass
    }
    return null
}
