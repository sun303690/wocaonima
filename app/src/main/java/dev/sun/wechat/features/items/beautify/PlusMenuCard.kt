package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeMainActivityBeautifyApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.utils.WeLogger
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

    private fun buildPopup(
        context: Context,
        items: List<PlusItem>,
        onItemClick: (Int) -> Unit,
    ): PopupWindow {
        val density = context.resources.displayMetrics.density
        val width = (PANEL_WIDTH_DP * density).toInt()
        val composeView = ComposeView(context).apply {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF2E2E2E),
                    shape = RoundedCornerShape(12.dp)
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
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = item.label,
                        modifier = Modifier.padding(start = 12.dp),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
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