package dev.sun.wechat.Hchat.hooks.items.quickterminate

import dev.sun.wechat.Hchat.ui.FeatureSettingsProvider
import dev.sun.wechat.Hchat.ui.SimpleFeatureSettingsProvider

class QuickTerminateSettingsProvider : SimpleFeatureSettingsProvider(
    QuickTerminateFeature.ID,
    "快捷终止",
    "从微信右上角加号菜单快速结束微信进程",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
