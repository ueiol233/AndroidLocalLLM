package com.ueiol.scanaianswer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ---- Sessions ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(entity: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long)

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Delete
    suspend fun deleteSession(entity: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    // ---- Messages ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun loadMessages(sessionId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)
}