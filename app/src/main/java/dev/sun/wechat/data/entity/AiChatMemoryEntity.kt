package dev.sun.wechat.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ai_chat_memories", indices = [Index("talker"), Index("personaId"), Index("scope")])
data class AiChatMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val talker: String,
    val personaId: String = "",
    val content: String,
    val scope: String,
    val source: String = "manual",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
