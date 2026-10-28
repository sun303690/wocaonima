package dev.sun.wechat.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.sun.wechat.data.entity.AiChatMemoryEntity

@Dao
interface AiChatMemoryDao {
    @Query(
        "SELECT * FROM ai_chat_memories WHERE enabled = 1 AND (talker = :talker OR talker = '*') " +
            "AND (personaId = '' OR personaId = :personaId) AND (:isGroup = 0 OR scope = 'group_ok') " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun forPrompt(talker: String, personaId: String, isGroup: Boolean, limit: Int = 20): List<AiChatMemoryEntity>

    @Query(
        "SELECT * FROM ai_chat_memories WHERE (:query = '' OR content LIKE '%' || :query || '%' " +
            "OR talker LIKE '%' || :query || '%') ORDER BY updatedAt DESC"
    )
    fun search(query: String): List<AiChatMemoryEntity>

    @Query("SELECT * FROM ai_chat_memories ORDER BY updatedAt DESC")
    fun all(): List<AiChatMemoryEntity>

    @Insert
    fun insert(value: AiChatMemoryEntity): Long

    @Update
    fun update(value: AiChatMemoryEntity)

    @Delete
    fun delete(value: AiChatMemoryEntity)

    @Query("DELETE FROM ai_chat_memories WHERE talker = :talker")
    fun clearTalker(talker: String)

    @Query("DELETE FROM ai_chat_memories")
    fun clearAll()
}