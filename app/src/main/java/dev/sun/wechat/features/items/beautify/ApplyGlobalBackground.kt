package dev.sun.wechat.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Opacity
import androidx.core.view.postDelayed
import com.tencent.mm.ui.mogic.WxViewPager
import coil3.load
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.R
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.activity.TransparentActivity
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.features.api.ui.WeConversationListViewApi
import dev.sun.wechat.features.api.ui.WeMainActivityBeautifyApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.BaseItemContainer
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.IntNumberPickerWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.isDarkMode
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.fs.asAndroidUri
import dev.sun.wechat.utils.reflection.float
import dev.sun.wechat.utils.nul
import java.util.WeakHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.math.max
import kotlin.math.roundToInt

object ApplyGlobalBackground : ClickableFeature() {

    override val technicalId = "应用全局背景"
    override val nameRes = R.string.feature_apply_global_background_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_apply_global_background_description

    private const val TAG = "ApplyGlobalBackground"

    // 背景不再覆盖到「任意页面」上，因此原先用来把背景层让位给原生图片浏览视图的
    // MultiTouchImageView 探针（methodInitImageView + activityAttachedViews）已随
    // 「背景只作用于主界面」一并移除。详见 applyPageBackgroundLayer()。

    /**
     * 每页背景图 URI，按 wechatIndex(0..3) 顺序以换行分隔；空串表示该页未设置。
     *
     * 主界面四个 Tab 各自一张图，随页面滑动平移。不再有「跨 Activity 的统一背景」——
     * 背景只存在于主界面 pager 内部，小程序/视频等独立 Activity 天然不受影响。
     */
    private var pageBackgroundUris by prefOption("global_bg_page_uris", "")

    /** 旧版「单一全局背景」偏好项。仅用于一次性迁移进 [pageBackgroundUris]，迁移后清空。 */
    private var legacyBackgroundUri by prefOption("global_bg_uri", nul<String>())

    private var transparentStatusBar by prefOption("global_bg_transparent_status_bar", false)
    private var opacity by prefOption("global_bg_opacity", 0.10f)
    private var listCards by prefOption("global_bg_list_cards", false)
    private var listRadius by prefOption("global_bg_list_radius", 12)
    private var listHorizontalInset by prefOption("global_bg_list_horizontal_inset", 8)
    private var listVerticalSpacing by prefOption("global_bg_list_vertical_spacing", 4)
    private var listHeight by prefOption("global_bg_list_height", 0)

    private const val LEGACY_BACKGROUND_IMAGE_FILE = "global_background.png"

    // The picked image is copied here instead of keeping a SAF content:// uri whose
    // persistable permission grant some custom ROMs drop after reboot.
    private val legacyBackgroundImageFile by lazy {
        KnownPaths.moduleAssets / LEGACY_BACKGROUND_IMAGE_FILE
    }

    /** 第 [index] 个 Tab(wechatIndex) 的背景图落盘位置。 */
    private fun pageBackgroundImageFile(index: Int) =
        KnownPaths.moduleAssets / "global_background_$index.png"

    /**
     * 每页背景层：每个可见页一个 ImageView，全部带该标签，便于重复安装时识别、更新与移除。
     */
    private const val PAGE_LAYER_TAG = "wekit_page_bg_layer"

    /** 每个背景 ImageView 上记录它对应的 wechatIndex，避免依赖子 View 顺序。 */
    private const val PAGE_LAYER_INDEX_KEY = 0x55020003
    private const val APPLIED_URI_TAG_KEY = 0x55020001
    private const val APPLY_STATUS_BAR_DELAY_MS = 80L

    /** 关闭「状态栏透明」时还原用的安全底色：半透明黑，等价于多数 App 的默认状态栏底色。 */
    private const val RESTORED_STATUS_BAR_COLOR = 0x66000000

    private val rowStates = WeakHashMap<View, RowState>()
    private data class RowState(val background: android.graphics.drawable.Drawable?, val height: Int)

    private val conversationBindListener = WeConversationListViewApi.IBindViewListener { _, view, _, _ ->
        if (isEnabled && listCards) styleListRow(view) else restoreListRow(view)
    }
    /**
     * 「状态栏透明」仍要排除这些页面：全屏媒体查看器、录像、扫码等，套透明状态栏会出错。
     *
     * 注意：背景层已收窄为「只在主界面 pager 内安装」，不再走 Activity 黑白名单；
     * 这份名单现在只服务于 [applyTransparentStatusBarIfEnabled]。
     */
    private val blacklistedActivities = setOf(
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsOnlineVideoActivity",
        "${PackageNames.WECHAT}.plugin.recordvideo.activity.MMRecordUI",
        "${PackageNames.WECHAT}.plugin.fav.ui.detail.FavoriteImgDetailUI",
        "${PackageNames.WECHAT}.plugin.scanner.ui.BaseScanUI",
        "${PackageNames.WECHAT}.plugin.finder.ui.FinderHomeAffinityUI",
        "${PackageNames.WECHAT}.plugin.lite.ui.WxaLiteAppLiteUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.ImageGalleryUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.ImageGalleryGridUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.MediaHistoryGalleryUI",
        "${PackageNames.WECHAT}.plugin.subapp.ui.gallery.GestureGalleryUI",
        "${PackageNames.WECHAT}.plugin.gallery.picker.view.ImageCropUI",
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsBrowseUI",
        "${PackageNames.WECHAT}.plugin.finder.ui.FinderShareFeedRelUI",
        "${PackageNames.WECHAT}.plugin.gallery.ui.ImagePreviewUI",
        "${PackageNames.WECHAT}.plugin.gallery.ui.AlbumPreviewUI",
        "${PackageNames.WECHAT}.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI",
        "${PackageNames.WECHAT}.plugin.location_soso.SoSoProxyUI",
        "${PackageNames.WECHAT}.plugin.finder.feed.ui.FinderProfileTimeLineUI",
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsGalleryUI",
        "${PackageNames.WECHAT}.pluginsdk.ui.ProfileHdHeadImg",
        "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI",
        "${PackageNames.WECHAT}.plugin.voip.ui.VideoActivity"
    )

    /** 沉浸式（小程序 / 视频 / 直播）页面：状态栏透明同样跳过。范围见 [BeautifyScopeExclusions]。 */
    private fun isExcludedActivity(activity: Activity): Boolean =
        BeautifyScopeExclusions.isExcludedForGlobalBeautify(activity)

    override fun onEnable() {
        migrateLegacyBackgroundUri()
        installMainTabBackgroundLayer()
        WeConversationListViewApi.addListener(conversationBindListener)

        Activity::class.reflekt().apply {
            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onStart"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onWindowFocusChanged"
                parameters(Boolean::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }
        }
    }

    private fun activityOf(ctx: Context): Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private const val MIN_OPACITY_PERCENT = 1
    private const val MAX_OPACITY_PERCENT = 80

    /**
     * 迁移旧版「单一全局背景」到「每页背景」。
     *
     * - SAF content:// → 复制进 moduleAssets 改写为 file://（旧逻辑，防自定义 ROM 丢授权）；
     * - 单一图迁到第 0 页（微信首页），随后清空旧项，避免每次启动重复迁移。
     */
    private fun migrateLegacyBackgroundUri() {
        val legacy = legacyBackgroundUri ?: return

        val migratedUri = if (legacy.startsWith("content://")) {
            val ok = runCatching {
                HostInfo.application.contentResolver.openInputStream(Uri.parse(legacy))?.use { input ->
                    legacyBackgroundImageFile.toFile().outputStream().use { output ->
                        input.copyTo(output)
                    }
                } != null
            }.onFailure {
                WeLogger.w(TAG, "failed to migrate legacy background image", it)
            }.isSuccess
            if (!ok) return
            legacyBackgroundImageFile.asAndroidUri.toString()
        } else {
            legacy
        }

        if (pageUris().all { it == null }) {
            setPageUri(0, migratedUri)
        }
        legacyBackgroundUri = null
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val originalOpacity = remember { opacity }
            val originalTransparentStatusBar = remember { transparentStatusBar }
            var hasImage by remember { mutableStateOf(pageUris().any { it != null }) }
            var opacityPercent by remember {
                mutableIntStateOf(
                    (opacity * 100f).roundToInt().coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
                )
            }
            var transparentStatusBarInput by remember { mutableStateOf(transparentStatusBar) }
            var cardsInput by remember { mutableStateOf(listCards) }
            var radiusInput by remember { mutableIntStateOf(listRadius) }
            var horizontalInput by remember { mutableIntStateOf(listHorizontalInset) }
            var spacingInput by remember { mutableIntStateOf(listVerticalSpacing) }
            var heightInput by remember { mutableIntStateOf(listHeight) }
            var restartRequired by remember { mutableStateOf(false) }
            val currentRestartRequired by rememberUpdatedState(restartRequired)
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            DisposableEffect(Unit) {
                onDispose {
                    if (currentRestartRequired) {
                        showToast(localizedContext.getString(R.string.saved_restart_wechat))
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_global_background_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.action_select_image),
                                description = stringResource(
                                    if (hasImage) {
                                        R.string.beautify_global_background_set
                                    } else {
                                        R.string.beautify_global_background_not_set
                                    }
                                ),
                                onClick = {
                                    onDismiss()
                                    selectAllBackgroundImages(context)
                                },
                                trailingContent = {
                                    IconButton(
                                        enabled = hasImage,
                                        onClick = {
                                            for (index in MainTabLayout.TAB_ITEMS.indices) {
                                                setPageUri(index, null)
                                                val file = pageBackgroundImageFile(index)
                                                runCatching { file.deleteIfExists() }.onFailure {
                                                    WeLogger.w(TAG, "failed to delete background image file $index", it)
                                                }
                                            }
                                            hasImage = false
                                            showToast(localizedContext.getString(R.string.beautify_global_background_cleared))
                                        },
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = stringResource(R.string.action_clear_image),
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    icon = MaterialSymbols.Outlined.Opacity,
                                    title = stringResource(R.string.opacity_percent),
                                    value = opacityPercent,
                                    startInt = MIN_OPACITY_PERCENT,
                                    endInt = MAX_OPACITY_PERCENT,
                                    stepSize = 1,
                                    valueSuffix = "%",
                                    onValueChange = {
                                        opacityPercent = it
                                        val newOpacity = it / 100f
                                        opacity = newOpacity
                                        restartRequired =
                                            newOpacity != originalOpacity ||
                                                    transparentStatusBarInput != originalTransparentStatusBar
                                    },
                                )
                            }
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_background_status_bar),
                                description = stringResource(R.string.beautify_global_background_status_bar_summary),
                                checked = transparentStatusBarInput,
                                onCheckedChange = {
                                    transparentStatusBarInput = it
                                    transparentStatusBar = it
                                    restartRequired =
                                        opacity != originalOpacity || it != originalTransparentStatusBar
                                },
                            )
                        }
                        item { SwitchWidget(iconPlaceholder = false, title = "列表卡片化", description = "启用圆角、内缩、间距和高度调节", checked = cardsInput, onCheckedChange = { cardsInput = it; listCards = it; restartRequired = true }) }
                        item { BaseItemContainer { IntNumberPickerWidget(iconPlaceholder = false, title = "列表圆角", value = radiusInput, startInt = 0, endInt = 40, stepSize = 1, valueSuffix = "dp", onValueChange = { radiusInput = it; listRadius = it; restartRequired = true }) } }
                        item { BaseItemContainer { IntNumberPickerWidget(iconPlaceholder = false, title = "左右内缩", value = horizontalInput, startInt = 0, endInt = 40, stepSize = 1, valueSuffix = "dp", onValueChange = { horizontalInput = it; listHorizontalInset = it; restartRequired = true }) } }
                        item { BaseItemContainer { IntNumberPickerWidget(iconPlaceholder = false, title = "上下间隔", value = spacingInput, startInt = 0, endInt = 30, stepSize = 1, valueSuffix = "dp", onValueChange = { spacingInput = it; listVerticalSpacing = it; restartRequired = true }) } }
                        item { BaseItemContainer { IntNumberPickerWidget(iconPlaceholder = false, title = "列表高度（0为原始）", value = heightInput, startInt = 0, endInt = 160, stepSize = 4, valueSuffix = "dp", onValueChange = { heightInput = it; listHeight = it; restartRequired = true }) } }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    /** 已被本模块改成透明状态栏的窗口，关闭开关时只还原它们，避免动到视频全屏等系统栏需求。 */
    private val transparentWindows = WeakHashMap<android.view.Window, Boolean>()

    private fun applyTransparentStatusBarIfEnabled(activity: Activity) {
        val window = activity.window
        val shouldBeTransparent = transparentStatusBar &&
                activity.javaClass.name !in blacklistedActivities &&
                !isExcludedActivity(activity)

        if (shouldBeTransparent) {
            applyTransparentStatusBar(activity)
            if (window != null) transparentWindows[window] = true
        } else if (window != null && transparentWindows.remove(window) != null) {
            // 只有本模块之前把它改透明过，才需要（也才可以安全地）还原回系统默认。
            restoreTransparentStatusBar(activity)
        }
    }

    /**
     * 关闭「状态栏背景透明」时把状态栏还原成系统默认。
     *
     * 不还原就是之前那个 bug：applyTransparentStatusBar() 把 layout fullscreen / decorFitsSystemWindows
     * 关掉之后，用户再关掉开关时没有任何代码把它改回去 —— 于是「关了也一直透明」，直到重启微信。
     * 这里逐一撤销 [applyTransparentStatusBar] 做过的三件事。
     *
     * 注意：只在状态栏本来就默认不透明（APP 可自绘）时还原。微信原生界面自己也可能把状态栏设成
     * 浅色，这里用「半透明黑」这个安全的默认值，而不是硬塞一个纯色，避免盖掉微信自身的适配。
     */
    @Suppress("DEPRECATION")
    private fun restoreTransparentStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val decor = window.decorView as? ViewGroup ?: return

            window.statusBarColor = RESTORED_STATUS_BAR_COLOR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
            } else {
                decor.systemUiVisibility = decor.systemUiVisibility and
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN).inv()
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to restore status bar", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val decor = window.decorView as? ViewGroup ?: return

            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }

            clearStatusBarBackground(activity, decor)
            decor.postDelayed(APPLY_STATUS_BAR_DELAY_MS) {
                clearStatusBarBackground(activity, decor)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to apply transparent status bar", it)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun clearStatusBarBackground(activity: Activity, decor: ViewGroup) {
        val statusBarBackgroundId = activity.resources.getIdentifier(
            "statusBarBackground",
            "id",
            "android"
        )

        if (statusBarBackgroundId != 0) {
            decor.findViewById<View>(statusBarBackgroundId)?.makeTransparent()
        }

        setLastViewsTransparent(decor, 3)
    }

    private fun setLastViewsTransparent(viewGroup: ViewGroup, count: Int) {
        val start = max(0, viewGroup.childCount - count)
        for (index in start until viewGroup.childCount) {
            val child = viewGroup.getChildAt(index)
            val name = child.resourceEntryName().orEmpty()
            if (name == "statusBarBackground" || child.height <= statusBarHeightGuess(child)) {
                child.makeTransparent()
            }
        }
    }

    // ------------------------------------------------------------ 每页背景层

    /** 已安装背景层的宿主 pager，作为幂等依据，避免重复安装层与回调。 */
    private val installedPagers =
        java.util.Collections.newSetFromMap(WeakHashMap<WxViewPager, Boolean>())

    /** 每页背景层靠 pager 自身的滚动回调驱动，全进程只挂一次。 */
    private var pageCallbacksHooked = false

    /**
     * 在主界面 pager 内安装「每页背景层」。
     *
     * 背景只挂进主界面自己的 WxViewPager（LauncherUI 内），不碰任何其它 Activity 的 decor：
     * 小程序 / 视频 / 图片浏览都是独立 Activity，结构上就不可能被这层背景波及。
     * 这与 [ReplaceNavigationBar] 的 attachBar 复用同一套「挂到 pager 父容器」的做法。
     */
    private fun installMainTabBackgroundLayer() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val viewPager = thisObject!!.reflekt()
                .firstField { name = "mViewPager" }
                .get() as? WxViewPager ?: return@hookAfter
            attachPageBackgroundLayer(viewPager)
        }
    }

    private fun attachPageBackgroundLayer(pager: WxViewPager) {
        if (!installedPagers.add(pager)) {
            // 已安装过：只刷新一次内容，不重复挂回调。
            applyPageBackgroundLayer(pager, pager.currentItem, 0f)
            return
        }
        hookPagerPageCallbacks()
        applyPageBackgroundLayer(pager, pager.currentItem, 0f)
    }

    /**
     * 直接把「每页背景层」挂到 WxViewPager 自身的滚动回调上。
     *
     * 不再向 pager 注册 OnPageChangeListener：宿主里那份 androidx.viewpager 的监听器类型由
     * 宿主 ClassLoader 提供，与本模块 compileOnly 桩里的 androidx 副本并非同一个 Class，
     * 直接 new 一个传进去在运行时会因类型不一致而失败（桩里也没声明 addOnPageChangeListener）。
     *
     * 因此改为 hook 回调本身：onPageScrolled / onPageSelected 的参数就是 pager 的物理页位，
     * 再经 [MainTabLayout.visibleWechatIndices] 换算成 wechatIndex，语义与原监听器完全一致，
     * 只是去掉了监听器这一层，也与 [ReplaceNavigationBar] / [HomeSidePanel] 同路。
     * 仅作用于主界面自己的那个 pager（由 [installedPagers] 限定），其它 Activity 的 pager 直接跳过。
     */
    private fun hookPagerPageCallbacks() {
        if (pageCallbacksHooked) return
        pageCallbacksHooked = true
        WxViewPager::class.reflekt().apply {
            firstMethod {
                name = "onPageScrolled"
                parameters(int, float, int)
                superclass = true
            }.hookAfter {
                val pager = thisObject as WxViewPager
                if (pager !in installedPagers) return@hookAfter
                applyPageBackgroundLayer(pager, args[0] as Int, args[1] as Float)
            }
            firstMethod {
                name = "onPageSelected"
                parameters(int)
                superclass = true
            }.hookAfter {
                val pager = thisObject as WxViewPager
                if (pager !in installedPagers) return@hookAfter
                applyPageBackgroundLayer(pager, args[0] as Int, 0f)
            }
        }
    }

    /**
     * 把每页背景层同步到 pager 当前状态：层挂在 pager 父容器里、位于 pager 下层，
     * 每层水平平移 `(pagerIndex - position - offset) * width`，于是「每页各一张背景图、
     * 随手指滑动一起平移」。
     *
     * 只保留当前页与左右相邻页（共 ≤3 个 ImageView），且位置恒定、滑动时只改
     * translationX，不新增/移除子 View —— 这正是本仓库里 SurfaceView 位置回调崩溃的规避姿势。
     */
    private fun applyPageBackgroundLayer(
        pager: WxViewPager,
        position: Int = pager.currentItem,
        offset: Float = 0f,
    ) {
        val container = pager.parent as? ViewGroup ?: return
        val uris = pageUris()
        val indices = MainTabLayout.visibleWechatIndices(ReplaceNavigationBar.isEnabled)
        val width = pager.width
        if (width <= 0 || indices.isEmpty()) {
            container.post { applyPageBackgroundLayer(pager, pager.currentItem, 0f) }
            return
        }

        val active = setOf(position, position + 1, position - 1)
        removeStalePageLayers(container, active, uris, indices)

        val pagerIndexInContainer = container.indexOfChild(pager)
        var z = if (pagerIndexInContainer >= 0) pager.z else 0f
        for (pagerIndex in active.sorted()) {
            if (pagerIndex !in indices.indices) continue
            val uri = uris.getOrNull(indices[pagerIndex]) ?: continue

            val layer = findPageLayer(container, pagerIndex) ?: ImageView(pager.context).also {
                it.tag = PAGE_LAYER_TAG
                it.setTag(PAGE_LAYER_INDEX_KEY, pagerIndex)
                it.background = null
                it.isClickable = false
                it.isFocusable = false
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                it.scaleType = ImageView.ScaleType.CENTER_CROP
                container.addView(
                    it,
                    if (pagerIndexInContainer >= 0) pagerIndexInContainer else 0,
                    ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
            // 压在 pager 下层（多个时依次更低），避免抢 pager 子页的触摸与绘制。
            z -= 1f
            layer.z = z
            layer.alpha = opacity
            layer.translationX = (pagerIndex - position - offset) * width
            if (layer.getTag(APPLIED_URI_TAG_KEY) != uri) {
                layer.setTag(APPLIED_URI_TAG_KEY, uri)
                layer.load(uri) { crossfade(true) }
            }
        }
    }

    /** 移除不再需要的背景层：已滑出相邻范围的、或对应页已取消配图的。 */
    private fun removeStalePageLayers(
        container: ViewGroup,
        active: Set<Int>,
        uris: List<String?>,
        indices: List<Int>,
    ) {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.tag != PAGE_LAYER_TAG) continue
            val pagerIndex = child.getTag(PAGE_LAYER_INDEX_KEY) as? Int
            val uri = pagerIndex
                ?.let { p -> indices.getOrNull(p)?.let { wechatIndex -> uris.getOrNull(wechatIndex) } }
            if (pagerIndex == null || pagerIndex !in active || uri == null) {
                container.removeView(child)
            }
        }
    }

    private fun findPageLayer(container: ViewGroup, pagerIndex: Int): ImageView? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ImageView && child.tag == PAGE_LAYER_TAG &&
                (child.getTag(PAGE_LAYER_INDEX_KEY) as? Int) == pagerIndex
            ) {
                return child
            }
        }
        return null
    }

    /** 读出每页的图片 uri；下标 = wechatIndex。 */
    private fun pageUris(): List<String?> =
        List(MainTabLayout.TAB_ITEMS.size) { index ->
            pageBackgroundUris.split("\n").getOrNull(index)?.takeIf { it.isNotBlank() }
        }

    private fun setPageUri(wechatIndex: Int, uri: String?) {
        val current = pageUris().toMutableList()
        if (wechatIndex !in current.indices) return
        current[wechatIndex] = uri
        pageBackgroundUris = current.joinToString("\n") { it ?: "" }
    }

    private fun styleVisibleListRows(container: ViewGroup) {
        for (index in 0 until container.childCount) {
            val row = container.getChildAt(index)
            if (row.visibility == View.VISIBLE && row.height > 0) styleListRow(row)
        }
    }

    private fun styleListRow(row: View) {
        if (!rowStates.containsKey(row)) rowStates[row] = RowState(row.background, row.layoutParams?.height ?: 0)
        val density = row.resources.displayMetrics.density
        val radius = listRadius.coerceIn(0, 40) * density
        val hInset = (listHorizontalInset.coerceIn(0, 40) * density).roundToInt()
        val vInset = (listVerticalSpacing.coerceIn(0, 30) * density / 2f).roundToInt()
        val alpha = 205
        val color = if (row.context.isDarkMode) Color.argb(alpha, 42, 42, 45) else Color.argb(alpha, 255, 255, 255)
        val shape = GradientDrawable().apply { cornerRadius = radius; setColor(color) }
        val inset = InsetDrawable(shape, hInset, vInset, hInset, vInset)
        row.background = RippleDrawable(
            ColorStateList.valueOf(if (row.context.isDarkMode) 0x33FFFFFF else 0x22000000), inset, null
        )
        if (listHeight > 0) row.layoutParams?.let {
            it.height = (listHeight.coerceIn(40, 160) * density).roundToInt()
            row.layoutParams = it
        }
    }

    private fun restoreListRow(row: View) {
        val state = rowStates.remove(row) ?: return
        row.background = state.background
        row.layoutParams?.let { it.height = state.height; row.layoutParams = it }
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(conversationBindListener)
        rowStates.clear()
    }

    private fun View.makeTransparent() {
        setBackgroundColor(Color.TRANSPARENT)
        setBackgroundResource(0)
    }

    private fun View.resourceEntryName(): String? {
        val viewId = id
        if (viewId == View.NO_ID) return null
        return runCatching {
            resources.getResourceEntryName(viewId)
        }.getOrNull()
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightGuess(view: View): Int {
        val resourceId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            view.resources.getDimensionPixelSize(resourceId)
        } else {
            (32f * view.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * 为每个 Tab 依次挑背景图。上一张选好后自动接着选下一张；中途返回则剩余未选的保持原样。
     */
    private fun selectAllBackgroundImages(context: ComponentActivity) {
        selectBackgroundImage(context, 0)
    }

    /** 等上一步的 TransparentActivity 完全收尾后再发起下一次选择，避免连续 launch 互相打断。 */
    private fun postAfterDialog(delayMs: Long = 400L, block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(block, delayMs)
    }
    /** 为第 [wechatIndex] 个 Tab 挑背景图；选好后自动继续下一个。 */
    private fun selectBackgroundImage(context: ComponentActivity, wechatIndex: Int) {
        if (wechatIndex !in MainTabLayout.TAB_ITEMS.indices) return
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                val file = pageBackgroundImageFile(wechatIndex)
                val ok = runCatching {
                    HostInfo.application.contentResolver.openInputStream(uri)?.use { input ->
                        file.toFile().outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } != null
                }.onFailure {
                    WeLogger.e(TAG, "failed to import background image for page $wechatIndex", it)
                }.isSuccess

                if (ok) {
                    setPageUri(wechatIndex, file.asAndroidUri.toString())
                    showToast(context.localizedBeautifyString(R.string.beautify_global_background_selected))
                    context.runOnUiThread {
                        postAfterDialog { selectBackgroundImage(context, wechatIndex + 1) }
                    }
                } else {
                    showToast(context.localizedBeautifyString(R.string.beautify_global_background_import_failed))
                }
            }

            launcher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }
}
