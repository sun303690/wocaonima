package dev.sun.wechat.features.items.yanwai

import dev.sun.wechat.features.items.yanwai.MessageMetadata

object AnalysisAccountIdentity {
    fun matches(input: AnalysisInput, record: MessageMetadata): Boolean = input.messageId > 0 && input.createdAt > 0 &&
        record.isSend == 0 && input.talker == record.talker && input.messageId == record.messageId &&
        input.createdAt == record.createdAt && input.speaker == record.speaker() &&
        input.text == record.analysisText() && input.voice == record.voiceSource() && input.quoted == record.quotedMessage()
}
