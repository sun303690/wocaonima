package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.PopupWindow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.features.api.ui.WeMainActivityBeautifyApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.content.miuix.MiuixCompatTextField
import dev.sun.wechat.utils.WeLogger
import java.io.File
import top.yukonga.miuix.kmp.basic.Text

/**
 * 主界面右上角「+」号原生菜单卡片化。
 *
 * 微信 8.0.78：点击右上角「+」由 HomeUI.o() 调 wg(PlusSubMenuHelper).a() 弹出
 * MMListPopupWindow 原生菜单。菜单数据在显示前已填充到 wg.s（SparseArray<tg>，
 * tg.b 为 ug：文字/图标资源/类型码）。这里在 SubMenuHelperBase.a()（显示 popup
 * 的公共入口）前拦截，反射读取菜单数据，改为在「+」按钮下方弹出一个灰色圆角
 * 卡片（尺寸与官方面板一致），点击卡片后把位置转发给
 * wg.onItemClick(null,null,pos,0) 复用微信原生跳转逻辑。
 */
object PlusMenuCard : SwitchFeature() {

    override val technicalId = "主界面加号菜单卡片化"
    override val nameRes = R.string.feature_plus_menu_card_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_plus_menu_card_description

    private const val TAG = "PlusMenuCard"
    private const val PANEL_WIDTH_DP = 192

    // ---- 自定义加号菜单（对标 WePeak CustomizePlusMenu）----
    // 文字/背景色分深浅色两套；可选背景图 plus_menu_bg.png（模块 wekit 目录）。
    private const val KEY_TEXT_LIGHT = "plus_menu_text_light"
    private const val KEY_TEXT_DARK = "plus_menu_text_dark"
    private const val KEY_BG_LIGHT = "plus_menu_bg_light"
    private const val KEY_BG_DARK = "plus_menu_bg_dark"
    private const val DEF_TEXT_LIGHT = "#000000"
    private const val DEF_TEXT_DARK = "#ffffff"
    private const val DEF_BG_LIGHT = "#ffffff"
    private const val DEF_BG_DARK = "#2E2E2E"

    /** 背景图缓存：(lastModified:length, drawable)；与 WePeak 同款指纹缓存 */
    @Volatile
    private var cachedBgImage: Pair<String, BitmapDrawable?>? = null

    private data class PlusMenuSkin(
        val textColor: Int,
        val bgColor: Int,
        val bgImage: BitmapDrawable?,
    )

    private fun parseColorOrDef(value: String?, def: Int): Int =
        value?.trim()?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: def

    private fun readSkin(context: Context): PlusMenuSkin {
        val dark = (context.resources.configuration.uiMode and 48) == 32
        val textColor = parseColorOrDef(
            KvStore.getStringOrDef(if (dark) KEY_TEXT_DARK else KEY_TEXT_LIGHT, if (dark) DEF_TEXT_DARK else DEF_TEXT_LIGHT),
            if (dark) Color.WHITE else Color.BLACK
        )
        val bgColor = parseColorOrDef(
            KvStore.getStringOrDef(if (dark) KEY_BG_DARK else KEY_BG_LIGHT, if (dark) DEF_BG_DARK else DEF_BG_LIGHT),
            if (dark) Color.parseColor(DEF_BG_DARK) else Color.parseColor(DEF_BG_LIGHT)
        )
        return PlusMenuSkin(textColor, bgColor, loadBgImage(context))
    }

    private fun bgImageFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "wekit/plus_menu_bg.png")
    }

    private fun loadBgImage(context: Context): BitmapDrawable? {
        val file = bgImageFile(context)
        if (!file.exists()) return null
        val key = "${file.lastModified()}:${file.length()}"
        cachedBgImage?.let { (k, d) -> if (k == key) return d }
        val drawable = runCatching {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp == null) {
                WeLogger.e(TAG, "背景图解码失败：${file.absolutePath}")
                null
            } else {
                BitmapDrawable(context.resources, bmp)
            }
        }.onFailure { WeLogger.e(TAG, "背景图加载失败", it) }.getOrNull()
        cachedBgImage = key to drawable
        return drawable
    }

    @Volatile
    private var popupShowing = false

    private data class PlusItem(
        val position: Int,
        val label: String,
        val iconRes: Int,
    )

    override fun onEnable() {
        if (!WeMainActivityBeautifyApi.methodShowPlusMenu.isAvailable) {
            WeLogger.e(TAG, "未解析到 SubMenuHelperBase.a()，加号菜单卡片化不可用")
            return
        }
        // 注意：这里用 hookAfter 而非 hookBefore + setResult(null)。
        // 微信的菜单 ListView 与 adapter（com.tencent.mm.ui.rg）绑定在 popup 显示流程里，
        // 若直接阻止 popup 显示，微信后续动态更新菜单数据时会因 ListView 未收到
        // notifyDataSetChanged 而抛 adapter-mismatch，甚至拖出 ANR。
        // 让原生 popup 走完「显示」后在同一帧内立即 dismiss，状态机保持完整，
        // popup 尚未绘制，用户看不到原生菜单闪现。
        WeMainActivityBeautifyApi.methodShowPlusMenu.hookAfter {
            val helper = thisObject ?: return@hookAfter
            // 只处理主界面加号菜单（wg/PlusSubMenuHelper），其它子类不碰
            if (!helper.javaClass.name.startsWith("com.tencent.mm.ui.wg")) return@hookAfter

            // 卡片已显示时，吞掉重复点击
            if (popupShowing) return@hookAfter

            val items = readMenuItems(helper) ?: return@hookAfter
            if (items.isEmpty()) {
                WeLogger.d(TAG, "菜单数据为空，不弹卡片（${helper.javaClass.name}）")
                return@hookAfter
            }

            val context = readField(helper, "t") as? Context ?: return@hookAfter
            val clickMethod = runCatching {
                helper.javaClass.getMethod(
                    "onItemClick",
                    AdapterView::class.java,
                    View::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                )
            }.getOrNull() ?: return@hookAfter

            // 让原生 popup 走完显示流程后立即收起，保持微信 ListView/adapter 状态一致
            runCatching {
                (readField(helper, "d") as? android.widget.PopupWindow)?.dismiss()
            }.onFailure { WeLogger.e(TAG, "dismiss 原生加号菜单失败", it) }

            popupShowing = true
            WeLogger.i(TAG, "拦截原生加号菜单，弹出卡片面板（${items.size} 项）")

            // 取右上角「+」按钮作为锚点（HomeUI.x: PlusActionView）
            val homeUI = readField(helper, "u")
            val anchor = homeUI?.let { readField(it, "x") as? View }

            val popup = buildPopup(context, items) { position ->
                popupShowing = false
                runCatching { clickMethod.invoke(helper, null, null, position, 0L) }
                    .onFailure { WeLogger.e(TAG, "invoke wg.onItemClick failed", it) }
            } ?: run {
                popupShowing = false
                return@hookAfter
            }
            popup.setOnDismissListener { popupShowing = false }

            val density = context.resources.displayMetrics.density
            val width = (PANEL_WIDTH_DP * density).toInt()
            if (anchor != null && anchor.isAttachedToWindow) {
                val loc = IntArray(2)
                anchor.getLocationInWindow(loc)
                val x = loc[0] + anchor.width - width
                val y = loc[1] + anchor.height + (4 * density).toInt()
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
            } else {
                // 锚点不可用时退到屏幕右上角（状态栏 + ActionBar 下方）
                val decorView = (context as? android.app.Activity)?.window?.decorView
                if (decorView == null) {
                    popupShowing = false
                    WeLogger.e(TAG, "找不到锚点视图与 Activity decorView，放弃弹出卡片")
                    return@hookAfter
                }
                val dm = decorView.resources.displayMetrics
                val x = dm.widthPixels - width - (12 * density).toInt()
                val y = (60 * density).toInt()
                popup.showAtLocation(decorView, Gravity.TOP or Gravity.START, x, y)
            }
        }
        WeLogger.i(TAG, "hook 已注册")
    }

    /** 自定义加号菜单配置：四个色值输入 + 背景图说明（对标 WePeak CustomizePlusMenu） */
    @Composable
    override fun Ui() {
        val context = LocalContext.current
        var textLight by remember {
            mutableStateOf(KvStore.getStringOrDef(KEY_TEXT_LIGHT, DEF_TEXT_LIGHT))
        }
        var textDark by remember {
            mutableStateOf(KvStore.getStringOrDef(KEY_TEXT_DARK, DEF_TEXT_DARK))
        }
        var bgLight by remember {
            mutableStateOf(KvStore.getStringOrDef(KEY_BG_LIGHT, DEF_BG_LIGHT))
        }
        var bgDark by remember {
            mutableStateOf(KvStore.getStringOrDef(KEY_BG_DARK, DEF_BG_DARK))
        }
        MiuixCompatTextField(
            value = textLight,
            onValueChange = {
                textLight = it
                KvStore.putString(KEY_TEXT_LIGHT, it)
            },
            label = stringResource(R.string.plus_menu_text_light),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        MiuixCompatTextField(
            value = textDark,
            onValueChange = {
                textDark = it
                KvStore.putString(KEY_TEXT_DARK, it)
            },
            label = stringResource(R.string.plus_menu_text_dark),
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp)
        )
        MiuixCompatTextField(
            value = bgLight,
            onValueChange = {
                bgLight = it
                KvStore.putString(KEY_BG_LIGHT, it)
            },
            label = stringResource(R.string.plus_menu_bg_light),
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp)
        )
        MiuixCompatTextField(
            value = bgDark,
            onValueChange = {
                bgDark = it
                KvStore.putString(KEY_BG_DARK, it)
            },
            label = stringResource(R.string.plus_menu_bg_dark),
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = stringResource(R.string.plus_menu_bg_image_hint) +
                "\n" + bgImageFile(context).absolutePath,
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 12.sp
        )
    }

    private fun buildPopup(
        context: Context,
        items: List<PlusItem>,
        onItemClick: (Int) -> Unit,
    ): PopupWindow? {
        val density = context.resources.displayMetrics.density
        val width = (PANEL_WIDTH_DP * density).toInt()
        // PopupWindow 的 PopupDecorView 不在 Activity 的 View 树里，Compose 拿不到
        // ViewTreeLifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner，会抛
        // "ViewTreeLifecycleOwner not found from PopupWindow$PopupDecorView" 直接崩微信。
        // 这里从 context 沿 ContextWrapper 找宿主 Activity，手动挂上三个 owner。
        val owner = findComposeOwner(context)
        if (owner == null) {
            WeLogger.e(TAG, "PopupWindow 找不到宿主 ComponentActivity，放弃 Compose 卡片")
            return null
        }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                PlusMenuCardContent(context = context, items = items, onItemClick = onItemClick)
            }
        }
        return PopupWindow(
            composeView,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = (8 * density).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }


    private fun findComposeOwner(context: Context): ComponentActivity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is ComponentActivity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return (context as? android.app.Activity) as? ComponentActivity
    }

    private fun readField(obj: Any, name: String): Any? = runCatching {
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
    }.onFailure { WeLogger.e(TAG, "read field $name failed", it) }.getOrNull()

    private fun readMenuItems(helper: Any): List<PlusItem>? = runCatching {
        val sparse = readField(helper, "s") as? SparseArray<*> ?: return null
        val result = mutableListOf<PlusItem>()
        for (i in 0 until sparse.size()) {
            val tg = sparse.valueAt(i) ?: continue
            val ug = readField(tg, "b") ?: continue
            val label = readField(ug, "a") as? String ?: continue
            val iconRes = runCatching { readField(ug, "d") as Int }.getOrDefault(0)
            result += PlusItem(sparse.keyAt(i), label, iconRes)
        }
        result
    }.onFailure { WeLogger.e(TAG, "read menu items failed", it) }.getOrNull()

    @Composable
    private fun PlusMenuCardContent(
        context: Context,
        items: List<PlusItem>,
        onItemClick: (Int) -> Unit,
    ) {
        val skin = remember { readSkin(context) }
        Box(modifier = Modifier.fillMaxWidth()) {
            skin.bgImage?.let { bg ->
                Image(
                    bitmap = bg.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (skin.bgImage == null) {
                            Modifier.background(
                                color = ComposeColor(skin.bgColor),
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 6.dp)
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item.position) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = remember(item.iconRes) {
                            runCatching {
                                val drawable = context.resources.getDrawable(item.iconRes, context.theme)
                                drawableToImageBitmap(drawable)
                            }.getOrNull()
                        }
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = item.label,
                                colorFilter = ColorFilter.tint(ComposeColor(skin.textColor)),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = item.label,
                            modifier = Modifier.padding(start = 12.dp),
                            color = ComposeColor(skin.textColor),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
        val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}