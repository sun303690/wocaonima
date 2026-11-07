package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * 主界面右上角「+」号菜单自定义配色（对标 WePeak「自定义首页右上角菜单」）。
 *
 * WePeak 路线：不拦原生弹窗、不自绘任何 View。微信 8.0.78 的加号菜单由
 * HomeUI.o() → wg(PlusSubMenuHelper).a() 经 com.tencent.mm.ui.tools.hd.a()
 * 弹出原生 MMListPopupWindow；本功能在显示入口 hookAfter 后：
 *   1. 容器（菜单 ListView）setBackground（优先 wekit/plus_menu_bg.png 背景图，
 *      其次 plus_menu_bg_light/dark 底色）；
 *   2. 在菜单 base adapter 的 getView() 返回的 itemView 上递归染色：
 *      TextView → setTextColor；ImageView → drawable.mutate().setColorFilter
 *      （SRC_IN，与 WePeak 同款）；
 *   3. 染色后 view.post 再兜底一次，防列表复用/重绘。
 *
 * 早期版本「拦截弹窗 + Compose 卡」会引发微信 adapter-mismatch ANR，已弃。
 */
object PlusMenuCard : SwitchFeature() {

    override val technicalId = "主界面加号菜单卡片化"
    override val nameRes = R.string.feature_plus_menu_card_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_plus_menu_card_description

    private const val TAG = "PlusMenuCard"

    // ---- 配置键（键名与 WePeak CustomizePlusMenu 完全一致）----
    private const val KEY_TEXT_LIGHT = "plus_menu_text_light"
    private const val KEY_TEXT_DARK = "plus_menu_text_dark"
    private const val KEY_BG_LIGHT = "plus_menu_bg_light"
    private const val KEY_BG_DARK = "plus_menu_bg_dark"
    private const val DEF_TEXT_LIGHT = "#000000"
    private const val DEF_TEXT_DARK = "#ffffff"
    private const val DEF_BG_LIGHT = "#ffffff"
    private const val DEF_BG_DARK = "#2E2E2E"

    /** 容器已挂背景标记（用 tag 防止重复 setBackground） */
    private val appliedContainersTag: java.util.concurrent.ConcurrentHashMap.KeySetView<View, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet(16)

    /** 已挂 getView hook 的 adapter（KeySet 防重复） */
    private val hookedAdapters: java.util.concurrent.ConcurrentHashMap.KeySetView<Any, Boolean> =
        java.util.concurrent.ConcurrentHashMap.newKeySet(16)

    /** 背景图缓存指纹（lastModified:length），与 WePeak 同款 */
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
            KvStore.getStringOrDef(
                if (dark) KEY_TEXT_DARK else KEY_TEXT_LIGHT,
                if (dark) DEF_TEXT_DARK else DEF_TEXT_LIGHT
            ),
            if (dark) Color.WHITE else Color.BLACK
        )
        val bgColor = parseColorOrDef(
            KvStore.getStringOrDef(
                if (dark) KEY_BG_DARK else KEY_BG_LIGHT,
                if (dark) DEF_BG_DARK else DEF_BG_LIGHT
            ),
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

    override fun onEnable() {
        if (!WeMainActivityBeautifyApi.methodShowPlusMenu.isAvailable) {
            WeLogger.e(TAG, "未解析到 SubMenuHelperBase.a()，加号菜单自定义不可用")
            return
        }
        WeMainActivityBeautifyApi.methodShowPlusMenu.hookAfter(50) {
            try {
                val helper = thisObject as? Any ?: return@hookAfter
                if (!helper.javaClass.name.startsWith("com.tencent.mm.ui.wg")) return@hookAfter
                val context = readField(helper, "t") as? Context ?: return@hookAfter
                val skin = runCatching { readSkin(context) }.getOrNull() ?: return@hookAfter

                var listView: ListView? = null
                var adapter: BaseAdapter? = null
                for (f in helper.javaClass.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(helper) ?: continue
                        if (v is ListView) listView = v
                        if (v is BaseAdapter) adapter = v
                    } catch (_: Exception) {
                    }
                }

                // 1. 容器背景：优先字段里的 ListView，其次按类型扫 PopupWindow（字段名漂移也扫得到）
                val container: android.view.View? = (listView as android.view.View?) ?: run {
                    var popup: android.widget.PopupWindow? = null
                    for (f in helper.javaClass.declaredFields) {
                        if (popup != null) break
                        try {
                            f.isAccessible = true
                            val v = f.get(helper)
                            if (v is android.widget.PopupWindow) popup = v
                        } catch (_: Exception) {
                        }
                    }
                    popup?.contentView as android.view.View?
                }
                container?.let { c ->
                    if (appliedContainersTag.add(c)) {
                        applyContainerBackground(c, skin)
                        // 列表复用时再设一次，防微信内部重设背景
                        c.post { if (appliedContainersTag.contains(c)) applyContainerBackground(c, skin) }
                    }
                }

                // 2. adapter 兜底：字段 → ListView 当前 adapter
                if (adapter == null) {
                    adapter = runCatching { listView?.adapter as? BaseAdapter }.getOrNull()
                }
                if (adapter == null) {
                    WeLogger.d(TAG, "加号菜单容器已换底，adapter 暂不可得（字段 ${helper.javaClass.simpleName}），等下次显示入口再挂")
                    return@hookAfter
                }

                // 3. 给菜单 adapter 挂 getView hook，item 染色（响应由 hook 层保证非空）
                if (hookedAdapters.add(adapter)) {
                    val getView = runCatching {
                        adapter.javaClass.methods.firstOrNull {
                            it.name == "getView" && it.parameterCount == 3
                        }?.apply { isAccessible = true }
                    }.getOrNull() ?: return@hookAfter
                    getView.hookAfter(50) {
                        try {
                            val item = result as? View ?: return@hookAfter
                            colorItemView(item, skin)
                            item.post { colorItemView(item, skin) }
                        } catch (t: Throwable) {
                            WeLogger.e(TAG, "加号菜单 item 染色失败", t)
                        }
                    }
                    WeLogger.i(TAG, "加号菜单自定义已挂：容器 bg=${skin.bgImage != null} adapter=${adapter.javaClass.simpleName}")
                }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "加号菜单自定义失败", t)
            }
        }
    }

    /** WePeak 的 Z(View)：容器 setBackground（背景图优先，否则底色） */
    private fun applyContainerBackground(container: View, skin: PlusMenuSkin) {
        container.setBackground(skin.bgImage ?: ColorDrawable(skin.bgColor))
    }

    /** WePeak 的 wa2 case 14：递归染色 itemView */
    private fun colorItemView(root: View, skin: PlusMenuSkin) {
        for (tv in collectAll(root, TextView::class.java)) {
            tv.setTextColor(skin.textColor)
        }
        for (iv in collectAll(root, ImageView::class.java)) {
            val d = iv.drawable ?: continue
            d.mutate().setColorFilter(skin.textColor, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun <T : View> collectAll(root: View, cls: Class<T>): List<T> {
        val out = mutableListOf<T>()
        if (cls.isInstance(root)) out += cls.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                runCatching { out += collectAll(root.getChildAt(i), cls) }
            }
        }
        return out
    }

    private fun readField(obj: Any, name: String): Any? = runCatching {
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
    }.onFailure { WeLogger.e(TAG, "read field $name failed", it) }.getOrNull()

    /** 自定义加号菜单配色：四个色值输入 + 背景图路径提示（键名与 WePeak 一致） */
    @Composable
    override fun Ui() {
        val context = LocalContext.current
        var textLight by remember { mutableStateOf(KvStore.getStringOrDef(KEY_TEXT_LIGHT, DEF_TEXT_LIGHT)) }
        var textDark by remember { mutableStateOf(KvStore.getStringOrDef(KEY_TEXT_DARK, DEF_TEXT_DARK)) }
        var bgLight by remember { mutableStateOf(KvStore.getStringOrDef(KEY_BG_LIGHT, DEF_BG_LIGHT)) }
        var bgDark by remember { mutableStateOf(KvStore.getStringOrDef(KEY_BG_DARK, DEF_BG_DARK)) }

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
        val bgPath = bgImageFile(context).absolutePath
        Text(
            text = stringResource(R.string.plus_menu_bg_image_hint) + "\n" + bgPath,
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 12.sp
        )
    }
}