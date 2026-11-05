package dev.sun.wechat.features.items.beautify

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import android.widget.AdapterView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面右上角「+」号原生菜单卡片化。
 *
 * 微信 8.0.78：点击右上角「+」由 HomeUI.o() 调 wg(PlusSubMenuHelper).a() 弹出
 * MMListPopupWindow 原生菜单。菜单数据在显示前已填充到 wg.s（SparseArray<tg>，
 * tg.b 为 ug：文字/图标资源/类型码）。这里在 SubMenuHelperBase.a()（显示 popup
 * 的公共入口）前拦截，反射读取菜单数据，改用 Compose 卡片面板展示；点击卡片后
 * 把位置转发给 wg.onItemClick(null,null,pos,0) 复用微信原生跳转逻辑。
 */
object PlusMenuCard : SwitchFeature(), IResolveDex {

    override val technicalId = "主界面加号菜单卡片化"
    override val nameRes = R.string.feature_plus_menu_card_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_plus_menu_card_description

    private const val TAG = "PlusMenuCard"

    private val methodShowPlusMenu by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.tools.hd"
            name = "a"
            returnType("void")
        }
    }

    @Volatile
    private var dialogShowing = false

    private data class PlusItem(
        val position: Int,
        val label: String,
        val iconRes: Int,
    )

    override fun resolveDex(dexKit: DexKitBridge) {
        methodShowPlusMenu.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.tools.hd"
                name = "a"
                returnType("void")
            }
        }
    }

    override fun onEnable() {
        if (!methodShowPlusMenu.isAvailable) {
            WeLogger.e(TAG, "未解析到 SubMenuHelperBase.a()，加号菜单卡片化不可用")
            return
        }
        methodShowPlusMenu.hookBefore {
            val helper = thisObject ?: return@hookBefore
            // 只处理主界面加号菜单（wg/PlusSubMenuHelper），其它子类不碰
            if (!helper.javaClass.name.startsWith("com.tencent.mm.ui.wg")) return@hookBefore

            // 卡片已显示时，吞掉重复点击
            if (dialogShowing) {
                result = null
                return@hookBefore
            }

            val items = readMenuItems(helper) ?: return@hookBefore
            if (items.isEmpty()) {
                WeLogger.d(TAG, "菜单数据为空，不拦截（${helper.javaClass.name}）")
                return@hookBefore
            }

            val context = readField(helper, "t") as? Context ?: return@hookBefore
            val activity = context as? Activity ?: return@hookBefore
            val clickMethod = runCatching {
                helper.javaClass.getMethod(
                    "onItemClick",
                    AdapterView::class.java,
                    View::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                )
            }.getOrNull() ?: return@hookBefore

            // 阻止原生菜单弹出
            result = null
            dialogShowing = true
            WeLogger.i(TAG, "拦截原生加号菜单，弹出卡片面板（${items.size} 项）")

            showComposeDialog(activity) {
                LaunchedEffect(Unit) {
                    dialog.setOnDismissListener { dialogShowing = false }
                }
                PlusMenuCardContent(
                    context = context,
                    items = items,
                    onItemClick = { position ->
                        onDismiss()
                        dialogShowing = false
                        runCatching { clickMethod.invoke(helper, null, null, position, 0L) }
                            .onFailure { WeLogger.e(TAG, "invoke wg.onItemClick failed", it) }
                    }
                )
            }
        }
        WeLogger.i(TAG, "hook 已注册")
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
        AlertDialogContent(
            title = { Text(stringResource(R.string.plus_menu_card_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(item.position) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = item.label,
                                modifier = Modifier.padding(start = 14.dp),
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        )
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