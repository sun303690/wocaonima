package dev.sun.wechat.Hchat.hooks.items.fakevoiceduration

import dev.sun.wechat.Hchat.ui.FeatureSettingsProvider
import dev.sun.wechat.Hchat.ui.SimpleFeatureSettingsProvider

class FakeVoiceDurationSettingsProvider : SimpleFeatureSettingsProvider(
    FakeVoiceDurationFeature.ID,
    "伪造语音时长",
    "自定义微信显示的语音时长",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
