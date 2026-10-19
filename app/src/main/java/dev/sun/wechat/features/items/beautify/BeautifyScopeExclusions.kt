package dev.sun.wechat.features.items.beautify

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import dev.sun.wechat.constants.PackageNames

/**
 * 「界面美化」类功能的统一生效范围排除。
 *
 * 背景：应用全局背景、美化对话列表（全局列表卡片）这类美化能力，都是通过 `Activity.onResume`
 * 之类的全局 hook 对「当前页面的整个 decor」动手术。如果不加限制，它们会连小程序宿主页、
 * 视频/直播等沉浸式页面一起改：
 *   - 小程序页面有自己的设计，盖上半透明背景/圆角卡片显得很脏；
 *   - 视频/直播页里是 Surface（硬件合成），全局背景的 decor 叠加还可能把它卷进原生视图的
 *     布局变动（历史上 hwui 的 SurfaceControl 空指针崩溃就发生在这类页面上）。
 *
 * 所以这里集中两条判定：是不是小程序容器页、是不是视频/沉浸式播放页。所有美化功能统一复用，
 * 避免各写一份、行为漂移。
 */
internal object BeautifyScopeExclusions {

    /** 小程序 / 小游戏在宿主进程里的容器 Activity 包前缀。 */
    private val miniProgramPrefixes = listOf(
        "${PackageNames.WECHAT}.plugin.appbrand",
        "${PackageNames.WECHAT}.plugin.lite",
        "${PackageNames.WECHAT}.plugin.subapp",
    )

    /** 视频 / 直播 / 沉浸式播放页的 Activity 类名关键字。 */
    private val videoActivityMarkers = listOf(
        "VideoActivity",
        "VideoUI",
        "VideoPlayer",
        "OnlineVideo",
        "MMRecordUI",
        "RecordVideo",
        "LiveUI",
        "FinderLive",
        "ThumbPlayer",
        "FinderHomeAffinityUI",
    )

    fun isMiniProgramActivity(activity: Activity?): Boolean {
        val name = activity?.javaClass?.name ?: return false
        return miniProgramPrefixes.any { name.startsWith(it) }
    }

    fun isVideoActivity(activity: Activity?): Boolean {
        val name = activity?.javaClass?.name ?: return false
        return videoActivityMarkers.any { name.contains(it) }
    }

    /** 沉浸式（小程序 / 视频 / 直播）页面：全局美化跳过。 */
    fun isExcludedForGlobalBeautify(activity: Activity?): Boolean =
        isMiniProgramActivity(activity) || isVideoActivity(activity)

    /** 从任意 View 反查它所属的 Activity（沿 ContextWrapper 链上溯）。 */
    fun activityOf(view: View?): Activity? {
        var context: android.content.Context? = view?.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}
