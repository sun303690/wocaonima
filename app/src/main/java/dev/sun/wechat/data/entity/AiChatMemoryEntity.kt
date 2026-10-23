package dev.sun.wechat.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ai_chat_memories", indices = [Index("talker"), Index("scope")])
data class AiChatMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val talker: String,
    val content: String,
    val scope: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
