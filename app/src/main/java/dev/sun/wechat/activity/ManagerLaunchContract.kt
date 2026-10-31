package dev.sun.wechat.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.sun.wechat.constants.PackageNames
import dev.sun.wechat.utils.WeLogger

private const val REQUEST_OPEN_LSPOSED_MANAGER = 0x574B

object ManagerLaunchContract {
    const val ACTION_OPEN_LSPOSED_MANAGER =
        "${PackageNames.MODULE}.action.OPEN_LSPOSED_MANAGER"
    const val EXTRA_ERROR = "manager_launch_error"

    val ROOT_MANAGER_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "com.resukisu.resukisu",
        "me.bmax.apatch",
        "me.yuki.folk",
    )
}

/**
 * 打开模块自己的管理器界面。
 *
 * 模块以 LSPosed 形式注入微信时，模块本体通常**没有作为独立应用安装**，
 * 因此 `dev.sun.wechat/.activity.MainActivity` 并不存在，直接
 * `startActivityForResult` 会抛 `ActivityNotFoundException` 把微信一起带崩
 * （见 2026-10-31 22:31 崩溃报告）。这里改为：先确认目标可解析，再退回
 * 启动模块的 LAUNCHER 入口，都不可用时只提示、不抛异常。
 */
fun openLsposedManager(activity: Activity): Boolean {
    val packageManager = activity.packageManager
    val intent = Intent().apply {
        setClassName(PackageNames.MODULE, "${PackageNames.MODULE}.activity.MainActivity")
        action = ManagerLaunchContract.ACTION_OPEN_LSPOSED_MANAGER
    }
    if (packageManager.resolveActivity(intent, 0) != null) {
        return runCatching {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_OPEN_LSPOSED_MANAGER)
        }.onFailure { WeLogger.w("ManagerLaunch", "failed to open module MainActivity", it) }
            .isSuccess
    }
    val launcher = runCatching { packageManager.getLaunchIntentForPackage(PackageNames.MODULE) }.getOrNull()
    if (launcher != null) {
        return runCatching { activity.startActivity(launcher) }
            .onFailure { WeLogger.w("ManagerLaunch", "failed to open module launcher", it) }
            .isSuccess
    }
    dev.sun.wechat.utils.android.showToast(activity, "模块未作为独立应用安装，无法打开管理器")
    return false
}

@Suppress("DEPRECATION")
fun openRootManager(context: Context): Boolean {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packageManager = context.packageManager
    val launcherPackages = packageManager.queryIntentActivities(
        launcherIntent,
        0,
    ).mapTo(mutableSetOf()) { it.activityInfo.packageName }

    val managerPackage = ManagerLaunchContract.ROOT_MANAGER_PACKAGES
        .firstOrNull(launcherPackages::contains)
        ?: return false
    val intent = packageManager.getLaunchIntentForPackage(managerPackage)?.apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: return false

    return runCatching { context.startActivity(intent) }
        .onFailure { WeLogger.e("ManagerLaunch", "failed to launch $managerPackage", it) }
        .isSuccess
}
