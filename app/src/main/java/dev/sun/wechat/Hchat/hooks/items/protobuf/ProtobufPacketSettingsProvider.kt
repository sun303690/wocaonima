package dev.sun.wechat.Hchat.hooks.items.protobuf

import dev.sun.wechat.Hchat.ui.FeatureSettingsProvider
import dev.sun.wechat.Hchat.ui.SimpleFeatureSettingsProvider

class ProtobufPacketSettingsProvider : SimpleFeatureSettingsProvider(
    ProtobufPacketFeature.ID,
    "Protobuf",
    "抓包和自定义发包",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
