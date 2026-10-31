package dev.sun.wechat.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import coil3.load
import coil3.request.CachePolicy
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.R
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.sun.wechat.activity.TransparentActivity
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.api.ui.WeConversationListViewApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.miuix.MiuixBaseItemContainer
import dev.sun.wechat.ui.content.miuix.MiuixBaseWidget
import dev.sun.wechat.ui.content.miuix.MiuixColorPickerWidget
import dev.sun.wechat.ui.content.miuix.MiuixIntNumberPickerWidget
import dev.sun.wechat.ui.content.miuix.MiuixSegmentedColumn
import dev.sun.wechat.ui.content.miuix.MiuixSwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.getTopMostActivity
import dev.sun.wechat.utils.android.isDarkMode
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.fs.asAndroidUri
import dev.sun.wechat.utils.nul
import java.util.WeakHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.ui.content.miuix.MiuixDropDownMenuWidget
import dev.sun.wechat.ui.content.miuix.DropdownOption
import dev.sun.wechat.ui.utils.dpToPx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    NO_LAYOUT(0, 0, 0, 0, 0),
    PINNED_GROUPED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
}


object ApplyGlobalBackground : ClickableFeature(), IResolveDex {

    override val technicalId = "应用全局背景"
    override val nameRes = R.string.feature_apply_global_background_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_apply_global_background_description

    private const val TAG = "ApplyGlobalBackground"

    private val methodInitImageView by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.base.MultiTouchImageView"
            modifiers(Modifiers.FINAL)
            returnType = "void"
            addInvoke {
                declaredClass = "android.widget.ImageView"
                name = "setScaleType"
            }
        }
    }

    private var backgroundUri by prefOption("global_bg_uri", nul<String>())
    private var transparentStatusBar by prefOption("global_bg_transparent_status_bar", false)
    private var opacity by prefOption("global_bg_opacity", 0.10f)
    private var listCards by prefOption("global_bg_list_cards", false)
    private var listRadius by prefOption("global_bg_list_radius", 12)
    private var listHorizontalInset by prefOption("global_bg_list_horizontal_inset", 8)
    private var listVerticalSpacing by prefOption("global_bg_list_vertical_spacing", 4)
    private var listHeight by prefOption("global_bg_list_height", 0)
    private var globalTextColorEnabled by prefOption("global_bg_text_color_enabled", false)
    private var globalTextColor by prefOption("global_bg_text_color", "#FF000000")

    private const val BACKGROUND_IMAGE_FILE = "global_background.png"

    // The picked image is copied here instead of keeping a SAF content:// uri whose
    // persistable permission grant some custom ROMs drop after reboot.
    private val backgroundImageFile by lazy { KnownPaths.moduleAssets / BACKGROUND_IMAGE_FILE }

    private const val OVERLAY_TAG = "wekit_global_bg_overlay"
    private const val APPLIED_URI_TAG_KEY = 0x55020001
    private var backgroundRevision = 0L
    private const val APPLY_STATUS_BAR_DELAY_MS = 80L
    private const val AGGREGATION_CONTAINER_UI =
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
    private const val EDGE_TO_EDGE_WRAPPER_CLASS = "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout"
    private const val DRAW_STATUS_BAR_FRAME_CLASS = "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout"

    private val rowStates = WeakHashMap<View, RowState>()
    private val collapsedStatusBarWrappers = WeakHashMap<View, Boolean>()
    private val loggedStatusBarBands = WeakHashMap<View, Boolean>()
    private val globalTextColors = WeakHashMap<TextView, Int>()
    private val applyingGlobalTextColor = ThreadLocal.withInitial { false }
    private var cachedTextColorValue: String? = null
    private var cachedTextColorInt: Int = Color.BLACK
    private data class RowState(val background: android.graphics.drawable.Drawable?, val height: Int)

    private val conversationBindListener = WeConversationListViewApi.IBindViewListener { _, view, _, _ ->
        if (isEnabled && listCards) styleListRow(view) else restoreListRow(view)
    }

    /**
     * Activities that must never receive the background overlay — full-screen media viewers,
     * video recorders, scanners, and other UIs where a tinted overlay would be wrong.
     *
     * Note: ThumbPlayerViewContainer / ThumbPlayerVideoView are Views (FrameLayout / TextureView),
     * not Activities, so they were removed from this list — they would never match here.
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

    override fun onEnable() {
        migrateLegacyBackgroundUri()
        if (listCards) WeConversationListViewApi.addListener(conversationBindListener)

        Activity::class.reflekt().apply {
            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                val activity = thisObject as Activity
                if (!isBackgroundExcluded(activity)) {
                    applyTransparentStatusBarIfEnabled(activity)
                    // Install and start decoding the overlay before the first resume frame.
                    // Folder/container pages do substantial synchronous list setup in onResume;
                    // starting the image there made navigation visibly hitch.
                    applyBackground(activity)
                }
            }

            firstMethod {
                name = "onStart"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                if (!isBackgroundExcluded(activity)) {
                    applyTransparentStatusBarIfEnabled(activity)
                    applyBackground(activity)
                }
            }

            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                // The status bar is already configured in onCreate/onStart. Re-applying it
                // in every onResume overlaps the aggregation container's list setup.
                if (activity.javaClass.name != AGGREGATION_CONTAINER_UI) {
                    applyTransparentStatusBarIfEnabled(activity)
                }
                // Normally already loaded from onCreate/onStart. This only restores an
                // existing overlay after returning from a paused Activity.
                applyBackground(activity)
            }

            firstMethod {
                name = "onWindowFocusChanged"
                parameters(Boolean::class)
            }.hookAfter {
                val activity = thisObject as Activity
                if (!isBackgroundExcluded(activity) &&
                    activity.javaClass.name != AGGREGATION_CONTAINER_UI
                ) {
                    applyTransparentStatusBarIfEnabled(activity)
                }
            }
        }

        // Applying the color when TextViews receive their own color is O(changed views).
        // Do not traverse the complete Activity tree on every resume/focus event.
        TextView::class.reflekt().apply {
            firstMethod {
                name = "setTextColor"
                parameters(Int::class)
            }.hookAfter {
                reapplyGlobalTextColor(thisObject as? TextView ?: return@hookAfter)
            }
            firstMethod {
                name = "setTextColor"
                parameters(android.content.res.ColorStateList::class)
            }.hookAfter {
                reapplyGlobalTextColor(thisObject as? TextView ?: return@hookAfter)
            }
        }

        methodInitImageView.hookBefore {
            val view = thisObject as ImageView
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val activity = activityOf(v.context) ?: return
                    synchronized(activityAttachedViews) {
                        activityAttachedViews.getOrPut(activity) { mutableSetOf() }.add(v)
                    }
                    WeLogger.d(TAG, "view attached to ${activity.javaClass.simpleName}")
                    overlayFromActivity(activity)?.isVisible = false
                }

                override fun onViewDetachedFromWindow(v: View) {
                    val activity = activityOf(v.context) ?: return
                    val empty = synchronized(activityAttachedViews) {
                        val set = activityAttachedViews[activity] ?: return
                        set.remove(v)
                        set.isEmpty()
                    }
                    if (empty) {
                        WeLogger.d(TAG, "all views detached from ${activity.javaClass.simpleName}")
                        overlayFromActivity(activity)?.isVisible = true
                    }
                }
            })
        }
    }

    // Per-activity set of currently-attached MultiTouchImageViews.
    // Using a Set means duplicate OnAttachStateChangeListener registrations (caused by
    // t() being re-triggered via onMeasure after each setImageBitmap call on a recycled
    // ViewPager page) are harmless: add/remove are idempotent on a Set, so the counter
    // never goes negative regardless of how many listeners fire per attach/detach cycle.
    private val activityAttachedViews = WeakHashMap<Activity, MutableSet<View>>()

    private fun activityOf(ctx: Context): Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun overlayFromActivity(activity: Activity): ImageView? =
        findOverlay(activity.window?.decorView as? ViewGroup ?: return null)

    private fun shouldApplyGlobalTextColor(view: TextView): Boolean {
        val activity = activityOf(view.context) ?: return false
        if (!isEnabled || backgroundUri == null || !globalTextColorEnabled) return false
        if (isBackgroundExcluded(activity)) return false
        val className = activity.javaClass.name
        if (className.contains("chatting", ignoreCase = true) ||
            className.contains("webview", ignoreCase = true)
        ) return false
        if (view is android.widget.EditText || view is android.widget.Button || !view.isEnabled) return false
        return view.autoLinkMask == 0 && view.urls.isEmpty()
    }

    private fun reapplyGlobalTextColor(view: TextView) {
        if (applyingGlobalTextColor.get()) return
        if (!shouldApplyGlobalTextColor(view)) return
        val color = resolvedGlobalTextColor()
        globalTextColors.putIfAbsent(view, view.currentTextColor)
        if (view.currentTextColor == color) return
        applyingGlobalTextColor.set(true)
        try {
            view.setTextColor(color)
        } finally {
            applyingGlobalTextColor.set(false)
        }
    }

    private fun resolvedGlobalTextColor(): Int {
        val value = globalTextColor
        if (cachedTextColorValue != value) {
            cachedTextColorValue = value
            cachedTextColorInt = runCatching { value.toColorInt() }.getOrDefault(Color.BLACK)
        }
        return cachedTextColorInt
    }

    private fun applyGlobalTextColors(root: View) {
        if (root is TextView) {
            if (shouldApplyGlobalTextColor(root)) {
                reapplyGlobalTextColor(root)
            } else {
                globalTextColors.remove(root)?.let { original ->
                    applyingGlobalTextColor.set(true)
                    try {
                        root.setTextColor(original)
                    } finally {
                        applyingGlobalTextColor.set(false)
                    }
                }
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let(::applyGlobalTextColors)
            }
        }
    }

    private fun refreshGlobalTextColors() {
        if (globalTextColorEnabled) {
            val roots = LinkedHashSet<View>()
            getTopMostActivity(allowPaused = true)?.window?.decorView?.let(roots::add)
            globalTextColors.keys.toList().forEach { view ->
                activityOf(view.context)?.window?.decorView?.let(roots::add)
            }
            roots.forEach(::applyGlobalTextColors)
        } else {
            applyingGlobalTextColor.set(true)
            try {
                globalTextColors.entries.toList().forEach { (view, color) ->
                    runCatching { view.setTextColor(color) }
                }
            } finally {
                applyingGlobalTextColor.set(false)
            }
            globalTextColors.clear()
        }
    }

    private const val MIN_OPACITY_PERCENT = 1
    private const val MAX_OPACITY_PERCENT = 80

    /**
     * Old versions stored the SAF content:// uri of the picked image. Copy that image into
     * moduleAssets once and rewrite the pref to a file uri. On failure the legacy value is
     * kept so a transient failure (e.g. storage not ready yet) retries on the next startup.
     */
    private fun migrateLegacyBackgroundUri() {
        val legacy = backgroundUri ?: return
        if (!legacy.startsWith("content://")) return

        val migrated = runCatching {
            HostInfo.application.contentResolver.openInputStream(Uri.parse(legacy))?.use { input ->
                backgroundImageFile.toFile().outputStream().use { output ->
                    input.copyTo(output)
                }
            } != null
        }.onFailure {
            WeLogger.w(TAG, "failed to migrate legacy background image", it)
        }.isSuccess

        if (migrated) {
            backgroundUri = backgroundImageFile.asAndroidUri.toString()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val originalOpacity = remember { opacity }
            val originalTransparentStatusBar = remember { transparentStatusBar }
            var hasImage by remember { mutableStateOf(backgroundUri != null) }
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

            val dialogSurface = MiuixTheme.colorScheme.surface
            AlertDialogContent(
                containerColor = dialogSurface.copy(alpha = 0.66f),
                title = { Text(stringResource(R.string.beautify_global_background_title)) },
                text = {
                    MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            MiuixBaseWidget(
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
                                    selectBackgroundImage(context)
                                },
                                trailingContent = {
                                    IconButton(
                                        enabled = hasImage,
                                        onClick = {
                                            backgroundUri = null
                                            hasImage = false
                                            runCatching { backgroundImageFile.deleteIfExists() }
                                                .onFailure {
                                                    WeLogger.w(TAG, "failed to delete background image file", it)
                                                }
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
                            MiuixBaseItemContainer {
                                MiuixIntNumberPickerWidget(
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
                            MiuixSwitchWidget(
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
                        item {
                            MiuixSwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_background_text_color),
                                description = stringResource(R.string.beautify_global_background_text_color_summary),
                                checked = globalTextColorEnabled,
                                onCheckedChange = {
                                    globalTextColorEnabled = it
                                    refreshGlobalTextColors()
                                },
                            )
                        }
                        if (globalTextColorEnabled) {
                            item {
                                MiuixColorPickerWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_global_background_text_color_picker),
                                    value = globalTextColor,
                                    onValueChange = {
                                        globalTextColor = it
                                        refreshGlobalTextColors()
                                    },
                                )
                            }
                        }
                        item {
                            MiuixSwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_global_background_list_cards),
                                description = stringResource(R.string.beautify_global_background_list_cards_summary),
                                checked = cardsInput,
                                onCheckedChange = { enabled ->
                                    cardsInput = enabled
                                    listCards = enabled
                                    if (enabled) {
                                        WeConversationListViewApi.removeListener(conversationBindListener)
                                        WeConversationListViewApi.addListener(conversationBindListener)
                                    } else {
                                        WeConversationListViewApi.removeListener(conversationBindListener)
                                        rowStates.keys.toList().forEach(::restoreListRow)
                                    }
                                    restartRequired = false
                                },
                            )
                        }
                        if (cardsInput) {
                            item {
                                MiuixBaseItemContainer {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_background_list_radius),
                                        value = radiusInput,
                                        startInt = 0,
                                        endInt = 40,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            radiusInput = it
                                            listRadius = it
                                            restartRequired = true
                                        },
                                    )
                                }
                            }
                            item {
                                MiuixBaseItemContainer {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_background_list_horizontal_inset),
                                        value = horizontalInput,
                                        startInt = 0,
                                        endInt = 40,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            horizontalInput = it
                                            listHorizontalInset = it
                                            restartRequired = true
                                        },
                                    )
                                }
                            }
                            item {
                                MiuixBaseItemContainer {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_background_list_spacing),
                                        value = spacingInput,
                                        startInt = 0,
                                        endInt = 30,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            spacingInput = it
                                            listVerticalSpacing = it
                                            restartRequired = true
                                        },
                                    )
                                }
                            }
                            item {
                                MiuixBaseItemContainer {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_background_list_height),
                                        value = heightInput,
                                        startInt = 0,
                                        endInt = 160,
                                        stepSize = 4,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            heightInput = it
                                            listHeight = it
                                            restartRequired = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun applyTransparentStatusBarIfEnabled(activity: Activity) {
        if (!transparentStatusBar) return
        applyTransparentStatusBar(activity)
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

            // Only make the real Android status bar transparent. Do not collapse WeChat's
            // edge-to-edge wrapper or clear its internal DrawStatusBarFrameLayout: those views
            // belong to the same content hierarchy as the wallpaper. Altering both layers makes
            // the wallpaper appear twice (one copy inside, another outside the title area).
            clearStatusBarBackground(activity, decor)
        }.onFailure {
            WeLogger.w(TAG, "failed to apply transparent status bar", it)
        }
    }

    /**
     * MMActivity wraps its content in an EdgeToEdgeWrapperLayout that turns the top window inset
     * into its own padding and paints that strip with the status bar color. The window is already
     * edge-to-edge here, so that padding is a second status bar tall strip right below the real
     * status bar — the dark band seen between the status bar and the title bar. Switch the wrapper
     * to ALWAYS_HIDE so later inset dispatches keep top = 0, then drop the padding it already set.
     */
    private fun collapseStatusBarWrapperInset(decor: ViewGroup) {
        val wrapper = decor.firstViewByClassName(EDGE_TO_EDGE_WRAPPER_CLASS) ?: return
        if (collapsedStatusBarWrappers.put(wrapper, true) == null) {
            runCatching {
                val setter = wrapper.reflekt().firstMethod {
                    name = "setStatusBarStrategy"
                    parameterCount = 1
                }
                val alwaysHide = setter.parameterTypes.single().enumConstants!!.single {
                    (it as Enum<*>).name == "ALWAYS_HIDE"
                }
                setter.invoke(alwaysHide)
                wrapper.requestApplyInsets()
            }.onFailure {
                WeLogger.w(TAG, "failed to hide WeChat edge-to-edge status bar wrapper", it)
            }
        }
        if (wrapper.paddingTop != 0) {
            wrapper.setPadding(wrapper.paddingLeft, 0, wrapper.paddingRight, wrapper.paddingBottom)
        }
    }

    private fun View.firstViewByClassName(className: String): View? {
        if (javaClass.name == className) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).firstViewByClassName(className)?.let { return it }
        }
        return null
    }

    /**
     * WeChat paints its own status bar strip (DrawStatusBarFrameLayout) inside the window content
     * instead of asking the window to color the bar. With the window bar already transparent the
     * strip is a second copy of the bar drawn one status bar height too low, so keep it transparent
     * and let the background show through. WeChat re-applies the color late, so this runs on every
     * pass rather than once.
     */
    private fun clearWeChatStatusBarStrip(decor: ViewGroup) {
        val strip = decor.firstViewByClassName(DRAW_STATUS_BAR_FRAME_CLASS) ?: return
        strip.reflekt().firstMethodOrNull {
            name = "setStatusBarColor"
            parameterCount = 1
        }?.invoke(Color.TRANSPARENT)
    }

    /**
     * The transparent status bar exposes the top of the window, so any opaque view left in that
     * area shows up as a dark band. Log the painted views in the top two status bar heights once
     * per window; the runtime log then names the view that is actually painting the band.
     */
    private fun logStatusBarBandCandidates(activity: Activity, decor: ViewGroup) {
        if (loggedStatusBarBands.put(decor, true) != null) return
        val statusBarHeight = statusBarHeightGuess(decor)
        if (statusBarHeight <= 0) return

        val decorLocation = IntArray(2).also { decor.getLocationInWindow(it) }
        val bandEnd = statusBarHeight * 2
        val viewLocation = IntArray(2)
        val lines = mutableListOf<String>()

        fun visit(view: View, depth: Int) {
            if (depth > 16) return
            view.getLocationInWindow(viewLocation)
            val top = viewLocation[1] - decorLocation[1]
            if (top >= bandEnd) return
            if (view.visibility == View.VISIBLE && view.height > 0) {
                lines += "  ".repeat(depth) + view.javaClass.simpleName +
                        "#" + (view.resourceEntryName() ?: "-") +
                        " " + view.width + "x" + view.height +
                        " top=" + top +
                        " pad=" + view.paddingTop +
                        " bg=" + view.describeBackground()
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
        }

        visit(decor, 0)
        lines.chunked(40).forEachIndexed { index, chunk ->
            WeLogger.d(
                TAG,
                "status bar band #$index (${activity.javaClass.name}, " +
                        "statusBar=${statusBarHeight}):\n" + chunk.joinToString("\n")
            )
        }
    }

    private fun View.describeBackground(): String {
        return when (val background = background) {
            null -> "-"
            is ColorDrawable -> "%08X".format(background.color)
            is LayerDrawable -> (0 until background.numberOfLayers).joinToString(
                prefix = "Layer[", postfix = "]"
            ) { index ->
                val layer = background.getDrawable(index)
                val color = (layer as? ColorDrawable)?.color?.let { "%08X".format(it) }
                "$index:${color ?: layer?.javaClass?.simpleName ?: "-"}@" +
                        (layer?.bounds?.toShortString() ?: "-")
            }
            else -> background.javaClass.simpleName
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

    private fun isBackgroundExcluded(activity: Activity): Boolean {
        val className = activity.javaClass.name
        if (className in blacklistedActivities) return true

        // 微信小程序页面的类名在不同版本会变化，不能只维护单个 Activity 黑名单。
        // appbrand 是传统小程序，wxa/liteapp 覆盖新版及轻应用容器。
        val normalized = className.lowercase()
        return normalized.contains(".plugin.appbrand.") ||
                normalized.contains("wxaapp") ||
                normalized.contains("wxalite") ||
                normalized.contains("liteapp")
    }

    private fun applyBackground(activity: Activity) {
        if (backgroundUri == null) return
        if (isBackgroundExcluded(activity)) {
            overlayFromActivity(activity)?.visibility = View.GONE
            return
        }

        val uri = backgroundUri ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val overlay = findOverlay(decor) ?: createOverlay(activity, decor)

        overlay.visibility = View.VISIBLE
        overlay.alpha = opacity
        overlay.bringToFront()

        val imageKey = "$uri#$backgroundRevision"
        if (overlay.getTag(APPLIED_URI_TAG_KEY) != imageKey) {
            overlay.setTag(APPLIED_URI_TAG_KEY, imageKey)
            overlay.load(backgroundImageFile.toFile()) {
                // The source file is versioned by APPLIED_URI_TAG_KEY when replaced, so
                // Coil's memory cache is safe and prevents decoding the same full-screen
                // bitmap again for every Activity (notably aggregation folders).
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.DISABLED)
                crossfade(false)
            }
        }
    }

    private fun createOverlay(context: Context, decor: ViewGroup): ImageView {
        return ImageView(context).apply {
            tag = OVERLAY_TAG
            background = null
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 100f
            decor.addView(this)
        }
    }

    private fun findOverlay(decor: ViewGroup): ImageView? {
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            if (child is ImageView && child.tag == OVERLAY_TAG) {
                return child
            }
        }
        return null
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
        applyingGlobalTextColor.set(true)
        try {
            globalTextColors.entries.toList().forEach { (view, color) ->
                runCatching { view.setTextColor(color) }
            }
        } finally {
            applyingGlobalTextColor.set(false)
        }
        globalTextColors.clear()
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

    private fun selectBackgroundImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                val ok = runCatching {
                    HostInfo.application.contentResolver.openInputStream(uri)?.use { input ->
                        backgroundImageFile.toFile().outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } != null
                }.onFailure {
                    WeLogger.e(TAG, "failed to import background image", it)
                }.isSuccess

                if (ok) {
                    backgroundRevision++
                    backgroundUri = backgroundImageFile.asAndroidUri.toString()
                    getTopMostActivity(allowPaused = true)?.let(::applyBackground)
                    showToast(context.localizedBeautifyString(R.string.beautify_global_background_selected))
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


object BeautifyConversationList : ClickableFeature() {

    override val technicalId = "美化对话列表"
    override val nameRes = R.string.feature_beautify_conversation_list_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_conversation_list_description

    private const val TAG = "BeautifyConversationList"

    private var presetName by prefOption(
        "beautify_conversation_list_preset",
        ConversationListPreset.NO_LAYOUT.name,
    )
    private var highlightUnreadEnabled by prefOption("beautify_conversation_list_highlight_unread", false)
    private var hideDividersEnabled by prefOption("beautify_conversation_list_hide_dividers", false)

    private val selectedPreset: ConversationListPreset
        get() = ConversationListPreset.entries.firstOrNull { it.name == presetName }
            ?: ConversationListPreset.NO_LAYOUT

    private enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

    private data class RowBackgroundKey(
        val preset: ConversationListPreset,
        val unread: Boolean,
        val isDark: Boolean,
        val density: Float,
        val groupPosition: GroupPosition,
    )

    private data class RowVisualState(
        var baselineBackground: Drawable?,
        var baselinePaddingLeft: Int,
        var baselinePaddingTop: Int,
        var baselinePaddingRight: Int,
        var baselinePaddingBottom: Int,
        var moduleBackground: Drawable? = null,
        var backgroundKey: RowBackgroundKey? = null,
    )

    private sealed interface UnreadAccessor {
        data class Field(val get: (Any) -> Any?) : UnreadAccessor
        data object Missing : UnreadAccessor
    }

    private val rowStates = WeakHashMap<View, RowVisualState>()
    private val unreadAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val unreadFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val usernameAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val usernameFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()

    // ── 全局列表卡片化（lexin 风格）──
    private var globalCardsEnabled by prefOption("beautify_global_list_cards_enabled", false)
    private var globalCardsAlpha by prefOption("beautify_global_list_cards_alpha", 200)
    private var globalCardsRadius by prefOption("beautify_global_list_cards_radius", 16)
    private var globalCardsInset by prefOption("beautify_global_list_cards_inset", 10)
    private var globalCardsGap by prefOption("beautify_global_list_cards_gap", 8)
    private var globalCardsHeight by prefOption("beautify_global_list_cards_height", 0)
    private var globalCardsDark by prefOption("beautify_global_list_cards_dark", false)
    private var globalHooksRegistered = false
    private data class GlobalRowState(
        val background: Drawable?,
        val foreground: Drawable?,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
        val layoutHeight: Int,
        val minimumHeight: Int,
        val elevation: Float,
    )

    private val globalFillsStripped = WeakHashMap<View, Drawable?>()
    private val globalRowStates = WeakHashMap<View, GlobalRowState>()
    private val globalOrigHeights = WeakHashMap<View, Int>()
    private val globalCardDrawables = WeakHashMap<View, GlobalCardDrawable>()
    private val globalChattingCache = WeakHashMap<View, Boolean>()
    private val globalListCache = WeakHashMap<View, Boolean>()
    private val globalPendingLists = WeakHashMap<ViewGroup, Boolean>()

    /** lexin 风格自绘卡片：颜色/圆角在创建时烘焙好，滚动时零测量 */
    private class GlobalCardDrawable(
        private val colour: Int,
        private val corner: Float,
        private val alpha: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        fun matches(cornerPx: Float, alphaValue: Int): Boolean =
            abs(this.corner - cornerPx) < 0.01f && this.alpha == alphaValue

        override fun draw(canvas: Canvas) {
            try {
                rect.set(bounds)
                clip.reset()
                clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                paint.color = colour
                paint.alpha = alpha
                canvas.drawRect(rect, paint)
                canvas.restore()
            } catch (ignored: Throwable) {
                // 仅外观
            }
        }

        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation, context ->
        applyRowVisuals(row, conversation, context)
    }

    override fun onEnable() {
        WeConversationListViewApi.addListener(bindListener)
        registerGlobalHooks()
        updateDividerRequest()
        applyGlobalCardsNow()
        WeConversationListViewApi.refresh()
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(bindListener)
        WeConversationListViewApi.removeDividerOwner(this)
        globalHooksRegistered = false
        rowStates.clear()
        unreadAccessorCache.clear()
        usernameAccessorCache.clear()
        unreadFailuresLogged.clear()
        usernameFailuresLogged.clear()
        restoreGlobalCards()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var preset by remember { mutableStateOf(selectedPreset) }
            var highlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
            var hideDividers by remember { mutableStateOf(hideDividersEnabled) }

            var globalCardsEnabled by remember { mutableStateOf(this@BeautifyConversationList.globalCardsEnabled) }
            var globalCardsDark by remember { mutableStateOf(this@BeautifyConversationList.globalCardsDark) }
            var globalCardsAlpha by remember { mutableStateOf(this@BeautifyConversationList.globalCardsAlpha) }
            var globalCardsRadius by remember { mutableStateOf(this@BeautifyConversationList.globalCardsRadius) }
            var globalCardsInset by remember { mutableStateOf(this@BeautifyConversationList.globalCardsInset) }
            var globalCardsGap by remember { mutableStateOf(this@BeautifyConversationList.globalCardsGap) }
            var globalCardsHeight by remember { mutableStateOf(this@BeautifyConversationList.globalCardsHeight) }

            fun applyGlobalCardsSettings() {
                if (globalCardsEnabled) applyGlobalCardsNow() else restoreGlobalCards()
            }

            fun applyChanges(highlight: Boolean, dividers: Boolean) {
                highlightUnreadEnabled = highlight
                hideDividersEnabled = dividers
                updateDividerRequest()
                WeConversationListViewApi.refresh()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_conversation_list_title)) },
                text = {
                    MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "preset") {
                            MiuixDropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_preset),
                                description = null,
                                value = preset,
                                options = ConversationListPreset.entries.map { entry ->
                                    DropdownOption(
                                        entry,
                                        when (entry) {
                                            ConversationListPreset.NO_LAYOUT -> stringResource(R.string.beautify_conversation_no_layout)
                                            ConversationListPreset.PINNED_GROUPED_CARD -> stringResource(R.string.beautify_conversation_pinned_grouped)
                                        },
                                    )
                                },
                                onValueChange = { entry ->
                                    preset = entry
                                    if (entry == ConversationListPreset.NO_LAYOUT) highlightUnread = false
                                    presetName = entry.name
                                    applyChanges(
                                        highlight = entry != ConversationListPreset.NO_LAYOUT && highlightUnread,
                                        dividers = hideDividers,
                                    )
                                },
                            )
                        }
                        item(
                            key = "highlight_unread",
                            animatedVisibility = preset != ConversationListPreset.NO_LAYOUT,
                        ) {
                            MiuixSwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_highlight_unread),
                                checked = highlightUnread,
                                onCheckedChange = {
                                    highlightUnread = it
                                    applyChanges(highlight = it, dividers = hideDividers)
                                },
                            )
                        }
                        item(key = "hide_dividers") {
                            MiuixSwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_hide_dividers),
                                checked = hideDividers,
                                onCheckedChange = {
                                    hideDividers = it
                                    applyChanges(highlight = highlightUnread, dividers = it)
                                },
                            )
                        }
                        item(key = "global_cards_section") {
                            MiuixSegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                                item {
                                    MiuixSwitchWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_enabled),
                                        description = stringResource(R.string.beautify_global_list_cards_summary),
                                        checked = globalCardsEnabled,
                                        onCheckedChange = {
                                            globalCardsEnabled = it
                                            this@BeautifyConversationList.globalCardsEnabled = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_dark", animatedVisibility = globalCardsEnabled) {
                                    MiuixSwitchWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_dark),
                                        checked = globalCardsDark,
                                        onCheckedChange = {
                                            globalCardsDark = it
                                            this@BeautifyConversationList.globalCardsDark = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_alpha", animatedVisibility = globalCardsEnabled) {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_alpha),
                                        value = globalCardsAlpha,
                                        startInt = 0,
                                        endInt = 255,
                                        stepSize = 5,
                                        valueSuffix = "/255",
                                        onValueChange = {
                                            globalCardsAlpha = it
                                            this@BeautifyConversationList.globalCardsAlpha = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_radius", animatedVisibility = globalCardsEnabled) {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_radius),
                                        value = globalCardsRadius,
                                        startInt = 0,
                                        endInt = 48,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsRadius = it
                                            this@BeautifyConversationList.globalCardsRadius = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_inset", animatedVisibility = globalCardsEnabled) {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_inset),
                                        value = globalCardsInset,
                                        startInt = 0,
                                        endInt = 60,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsInset = it
                                            this@BeautifyConversationList.globalCardsInset = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_gap", animatedVisibility = globalCardsEnabled) {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_gap),
                                        value = globalCardsGap,
                                        startInt = 0,
                                        endInt = 60,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsGap = it
                                            this@BeautifyConversationList.globalCardsGap = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_height", animatedVisibility = globalCardsEnabled) {
                                    MiuixIntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_height),
                                        value = globalCardsHeight,
                                        startInt = -24,
                                        endInt = 48,
                                        stepSize = 2,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsHeight = it
                                            this@BeautifyConversationList.globalCardsHeight = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun applyRowVisuals(
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        val state = rowStates.getOrPut(row) {
            RowVisualState(
                baselineBackground = row.background,
                baselinePaddingLeft = row.paddingLeft,
                baselinePaddingTop = row.paddingTop,
                baselinePaddingRight = row.paddingRight,
                baselinePaddingBottom = row.paddingBottom,
            )
        }
        restoreRowBaseline(row, state)

        val preset = selectedPreset
        if (preset == ConversationListPreset.NO_LAYOUT) {
            WeConversationListViewApi.setRowDividerHidden(this, row, false)
            return
        }

        val grouped = preset == ConversationListPreset.PINNED_GROUPED_CARD
        val groupPosition = if (grouped) groupPosition(conversation, context) else GroupPosition.SINGLE
        val pinned = if (grouped) isPinnedConversation(conversation) else false
        val nextPinned = if (grouped) context.nextConversation?.let(::isPinnedConversation) else null
        WeConversationListViewApi.setRowDividerHidden(
            owner = this,
            row = row,
            hidden = grouped && pinned && nextPinned == false,
        )

        val unread = highlightUnreadEnabled && isUnread(conversation)
        val backgroundKey = RowBackgroundKey(
            preset = preset,
            unread = unread,
            isDark = row.context.isDarkMode,
            density = row.resources.displayMetrics.density,
            groupPosition = groupPosition,
        )
        val background = if (state.backgroundKey == backgroundKey) {
            state.moduleBackground!!
        } else {
            buildRowBackground(row.context, preset, unread, groupPosition).also {
                state.backgroundKey = backgroundKey
                state.moduleBackground = it
            }
        }
        row.background = background
        row.setPadding(
            state.baselinePaddingLeft,
            state.baselinePaddingTop,
            state.baselinePaddingRight,
            state.baselinePaddingBottom,
        )
    }

    private fun restoreRowBaseline(row: View, state: RowVisualState) {
        if (row.background === state.moduleBackground) {
            row.background = state.baselineBackground
            row.setPadding(
                state.baselinePaddingLeft,
                state.baselinePaddingTop,
                state.baselinePaddingRight,
                state.baselinePaddingBottom,
            )
        } else {
            state.baselineBackground = row.background
            state.baselinePaddingLeft = row.paddingLeft
            state.baselinePaddingTop = row.paddingTop
            state.baselinePaddingRight = row.paddingRight
            state.baselinePaddingBottom = row.paddingBottom
            state.moduleBackground = null
            state.backgroundKey = null
        }
    }

    private fun buildRowBackground(
        context: Context,
        preset: ConversationListPreset,
        unread: Boolean,
        groupPosition: GroupPosition,
    ): Drawable {
        val isDark = context.isDarkMode
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
                setCornerRadii(cornerRadii(context, preset.rowRadiusDp, groupPosition))
            } else {
                cornerRadius = preset.rowRadiusDp.dpToPx(context).toFloat()
            }
            setColor(
                when {
                    unread && isDark -> 0xFF253E37.toInt()
                    unread -> 0xFFEAF8F2.toInt()
                    isDark -> preset.darkBackgroundColor
                    else -> preset.lightBackgroundColor
                },
            )
            setStroke(1.dpToPx(context).coerceAtLeast(1), if (isDark) 0x22FFFFFF else 0x16161D1C)
        }
        val horizontalInset = preset.horizontalInsetDp.dpToPx(context)
        val verticalInset = preset.verticalInsetDp.dpToPx(context)
        val topInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.FIRST -> verticalInset
                GroupPosition.MIDDLE, GroupPosition.LAST -> 0
            }
        } else {
            verticalInset
        }
        val bottomInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.LAST -> verticalInset
                GroupPosition.FIRST, GroupPosition.MIDDLE -> 0
            }
        } else {
            verticalInset
        }
        val inset = InsetDrawable(card, horizontalInset, topInset, horizontalInset, bottomInset)
        val rippleColor = if (isDark) 0x2AFFFFFF else 0x18006A62
        return RippleDrawable(ColorStateList.valueOf(rippleColor), inset, null)
    }

    private fun cornerRadii(context: Context, radiusDp: Int, position: GroupPosition): FloatArray {
        val radius = radiusDp.dpToPx(context).toFloat()
        val zero = 0f
        return when (position) {
            GroupPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            GroupPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
            GroupPosition.MIDDLE -> floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
            GroupPosition.LAST -> floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
        }
    }

    private fun groupPosition(
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ): GroupPosition {
        val pinned = isPinnedConversation(conversation)
        val previousPinned = context.previousConversation?.let(::isPinnedConversation)
        val nextPinned = context.nextConversation?.let(::isPinnedConversation)
        return when {
            previousPinned != pinned && nextPinned != pinned -> GroupPosition.SINGLE
            previousPinned != pinned -> GroupPosition.FIRST
            nextPinned != pinned -> GroupPosition.LAST
            else -> GroupPosition.MIDDLE
        }
    }

    private fun isPinnedConversation(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = usernameAccessorCache.computeIfAbsent(modelClass, ::findUsernameAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val talker = (accessor as UnreadAccessor.Field).get(conversation) as? String ?: return false
            WeConversationApi.isPinned(talker)
        } catch (error: Exception) {
            logUsernameFailureOnce(modelClass, "could not read field_username", error)
            false
        }
    }

    private fun findUsernameAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_username"
            superclass()
        } ?: run {
            logUsernameFailureOnce(modelClass, "field_username is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUsernameFailureOnce(modelClass, "could not resolve field_username", error)
        UnreadAccessor.Missing
    }

    private fun logUsernameFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!usernameFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun isUnread(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = unreadAccessorCache.computeIfAbsent(modelClass, ::findUnreadAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val unreadCount = ((accessor as UnreadAccessor.Field).get(conversation) as? Number)
                ?.toInt() ?: return false
            unreadCount > 0
        } catch (error: Exception) {
            logUnreadFailureOnce(modelClass, "could not read field_unReadCount", error)
            false
        }
    }

    private fun findUnreadAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_unReadCount"
            superclass()
        } ?: run {
            logUnreadFailureOnce(modelClass, "field_unReadCount is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUnreadFailureOnce(modelClass, "could not resolve field_unReadCount", error)
        UnreadAccessor.Missing
    }

    private fun logUnreadFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!unreadFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun updateDividerRequest() {
        WeConversationListViewApi.setDividerHidden(owner = this, hidden = isEnabled && hideDividersEnabled)
    }
    private fun isInsideChatting(view: View): Boolean {
        globalChattingCache[view]?.let { return it }
        var result = false
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < 40) {
            val name = cur.javaClass.name
            if (name.contains("Chatting") || name.contains("chatting") ||
                name.contains("ChatUI") || name.contains("ChattingUI")
            ) {
                result = true
                break
            }
            cur = cur.parent as? View
            depth++
        }
        globalChattingCache[view] = result
        return result
    }

    /** 是否通用列表（RecyclerView / ListView），排除聊天页 */
    private fun isGenericList(view: View): Boolean {
        globalListCache[view]?.let { return it }
        val name = view.javaClass.name
        val result = !isInsideChatting(view) && (
            name.contains("RecyclerView") ||
                name.endsWith("ListView") ||
                name.contains("PullDownListView")
            )
        globalListCache[view] = result
        return result
    }

    /** 是否为分隔线：1px 全宽细线，落在卡片缝隙里 */
    private fun isSeparator(view: View): Boolean =
        view.height <= 3 && view.width > 0

    /** 递归扫描视图树，给所有通用列表应用卡片 */
    private fun styleGlobalLists(view: View?, depth: Int) {
        if (view == null || depth > 18 || !globalCardsEnabled) return
        try {
            if (isGenericList(view)) {
                applyGlobalCards(view as ViewGroup)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    styleGlobalLists(view.getChildAt(i), depth + 1)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 应用卡片到列表的所有可见行 */
    private fun applyGlobalCards(list: ViewGroup) {
        val density = list.resources.displayMetrics.density
        val inset = (globalCardsInset.coerceIn(0, 60) * density).roundToInt()
        val gap = (globalCardsGap.coerceIn(0, 60) * density).roundToInt()
        val corner = (globalCardsRadius.coerceIn(1, 48) * density).roundToInt().toFloat()
        val alpha = globalCardsAlpha.coerceIn(0, 255)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (isSeparator(child)) {
                child.background = null
                continue
            }
            styleGlobalRow(child, inset, gap, corner, alpha)
        }
    }

    /** 样式化一行；多行容器则递归处理内部行 */
    private fun styleGlobalRow(row: View, inset: Int, gap: Int, corner: Float, alpha: Int) {
        if (isGlobalRowContainer(row)) {
            row.background = null
            resetGlobalMargins(row)
            val group = row as ViewGroup
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j) ?: continue
                if (!child.isShown) continue
                if (child.height >= dp(child, 34) && child.width * 10 >= group.width * 6) {
                    styleGlobalRow(child, inset, gap, corner, alpha)
                } else {
                    stripGlobalRowFills(child, 0)
                }
            }
            return
        }
        try {
            if (!globalRowStates.containsKey(row)) {
                val margins = row.layoutParams as? ViewGroup.MarginLayoutParams
                globalRowStates[row] = GlobalRowState(
                    background = row.background,
                    foreground = row.foreground,
                    leftMargin = margins?.leftMargin ?: 0,
                    topMargin = margins?.topMargin ?: 0,
                    rightMargin = margins?.rightMargin ?: 0,
                    bottomMargin = margins?.bottomMargin ?: 0,
                    layoutHeight = row.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    minimumHeight = row.minimumHeight,
                    elevation = row.elevation,
                )
            }
            val lp = row.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val mlp = lp
                if (mlp.leftMargin != inset || mlp.rightMargin != inset || mlp.bottomMargin != gap) {
                    mlp.leftMargin = inset
                    mlp.rightMargin = inset
                    mlp.bottomMargin = gap
                    row.layoutParams = mlp
                }
            }
            val current = row.background
            val desired = GlobalCardDrawable(globalCardColour(), corner, alpha)
            if (current !is GlobalCardDrawable || !current.matches(corner, alpha)) {
                row.background = desired
            }
            globalCardDrawables[row] = desired
            if (row.width > 0 && row.height > 0 && !globalFillsStripped.containsKey(row)) {
                globalFillsStripped[row] = row.background
                stripGlobalRowFills(row, 0)
            }
            applyGlobalCardHeight(row)
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** 全局卡片颜色：深色模式用深底，否则白色 */
    private fun globalCardColour(): Int =
        if (globalCardsDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /** 多行容器判断：多个等高子行或单行但容器更高 */
    private fun isGlobalRowContainer(view: View): Boolean {
        if (view !is ViewGroup) return false
        val g = view
        var rows = 0
        var tallest = 0
        var firstH = -1
        var even = true
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            if (!c.isShown) continue
            val ch = c.height
            if (ch < 100) continue
            if (ch > tallest) tallest = ch
            if (firstH < 0) firstH = ch
            else if (abs(ch - firstH) > Math.max(4, firstH / 8)) even = false
            rows++
        }
        return (rows >= 3 && even) || (rows == 1 && view.height > tallest + dp(view, 8))
    }

    /** 还原行 margin */
    private fun resetGlobalMargins(view: View) {
        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            val mlp = lp
            if (mlp.leftMargin != 0 || mlp.rightMargin != 0 || mlp.bottomMargin != 0) {
                mlp.leftMargin = 0
                mlp.rightMargin = 0
                mlp.bottomMargin = 0
                view.layoutParams = mlp
            }
        }
    }

    /** 清掉行内部的底板，让卡片真正露出来 */
    private fun stripGlobalRowFills(view: View, depth: Int) {
        if (view == null || depth > 4 || view !is ViewGroup) return
        try {
            val g = view
            val w = view.width
            val h = view.height
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i) ?: continue
                if (!c.isShown) continue
                val wide = c.width * 10 >= w * 6
                val plate = wide && h > 0 && w > 0 &&
                    (c.height * 10 >= h * 7 || c.height >= dp(c, 20))
                if (plate && isGlobalOpaqueFill(c)) {
                    if (!globalFillsStripped.containsKey(c)) globalFillsStripped[c] = c.background
                    c.background = null
                }
                stripGlobalRowFills(c, depth + 1)
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 判断是否为不透明底板 */
    private fun isGlobalOpaqueFill(view: View): Boolean {
        try {
            val d = view.background ?: return false
            if (d is GlobalCardDrawable) return false
            if (d is ColorDrawable) return ((d.color ushr 24) and 0xFF) > 200
            if (d is GradientDrawable) {
                val csl = d.color ?: return false
                return ((csl.defaultColor ushr 24) and 0xFF) > 200
            }
            val n = d.javaClass.name
            return n.contains("StateListDrawable") || n.contains("NinePatchDrawable")
        } catch (ignored: Throwable) {
            return false
        }
    }

    /** 卡片高度增量：每行只记一次原始高度，重复不累积 */
    private fun applyGlobalCardHeight(row: View) {
        try {
            val dh = (globalCardsHeight.coerceIn(-24, 48) * row.resources.displayMetrics.density).roundToInt()
            if (dh == 0) return
            val lp = row.layoutParams ?: return
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height <= 0) return
            val orig = globalOrigHeights[row]
                ?: (if (row.height > 0) row.height else lp.height).also {
                    if (it <= 0) return
                    globalOrigHeights[row] = it
                }
            val want = Math.max(1, orig + dh)
            if (lp.height != want) {
                lp.height = want
                row.layoutParams = lp
            }
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** dp 转 px */
    private fun dp(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).roundToInt()

    /** 关闭全局卡片：立即完整恢复所有被修改的行和内部底板。 */
    private fun restoreGlobalCards() {
        globalFillsStripped.entries.toList().forEach { (view, background) ->
            runCatching { view.background = background }
        }
        globalRowStates.entries.toList().forEach { (row, state) ->
            runCatching {
                row.background = state.background
                row.foreground = state.foreground
                row.minimumHeight = state.minimumHeight
                row.elevation = state.elevation
                row.layoutParams?.let { lp ->
                    lp.height = state.layoutHeight
                    if (lp is ViewGroup.MarginLayoutParams) {
                        lp.setMargins(state.leftMargin, state.topMargin, state.rightMargin, state.bottomMargin)
                    }
                    row.layoutParams = lp
                }
                row.requestLayout()
                row.invalidate()
            }
        }
        globalFillsStripped.clear()
        globalRowStates.clear()
        globalOrigHeights.clear()
        globalCardDrawables.clear()
        globalChattingCache.clear()
        globalListCache.clear()
        globalPendingLists.clear()
        WeConversationListViewApi.refresh()
    }

    /**
     * 将滚动触发的样式刷新延迟到下一帧，并对同一列表去重。
     * onScrollChanged 发生在 ViewGroup 遍历/绘制过程中，不能同步修改子 View 的
     * layoutParams、margin 或 background，否则可能破坏 dispatchDraw 的子项快照。
     */
    private fun scheduleGlobalCards(list: ViewGroup) {
        if (globalPendingLists.put(list, true) == true) return
        list.post {
            globalPendingLists.remove(list)
            if (!globalCardsEnabled || !list.isAttachedToWindow) return@post
            try {
                applyGlobalCards(list)
            } catch (ignored: Throwable) {
                // 仅外观
            }
        }
    }

    /** 立即对当前可见 Activity 应用全局卡片（开关切换时调用） */
    private fun applyGlobalCardsNow() {
        if (!globalCardsEnabled) return
        try {
            val activity = getTopMostActivity(allowPaused = true) ?: return
            val decor = activity.window?.decorView ?: return
            decor.post {
                if (decor.width > 0 && decor.height > 0) {
                    styleGlobalLists(decor, 0)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 全局 Activity.onResume hook：页面可见时应用卡片 */
    private fun registerGlobalHooks() {
        if (globalHooksRegistered) return
        globalHooksRegistered = true

        Activity::class.reflekt().apply {
            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val activity = thisObject as Activity
                val decor = activity.window?.decorView ?: return@hookAfter
                decor.postDelayed({
                    if (decor.width > 0 && decor.height > 0) {
                        styleGlobalLists(decor, 0)
                    }
                }, 60L)
            }
        }

        View::class.reflekt().apply {
            firstMethod {
                name = "onScrollChanged"
                parameterCount = 4
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val self = thisObject as? View ?: return@hookAfter
                if (self !is ViewGroup) return@hookAfter
                if (!isGenericList(self)) return@hookAfter
                scheduleGlobalCards(self)
            }
        }
    }
}
