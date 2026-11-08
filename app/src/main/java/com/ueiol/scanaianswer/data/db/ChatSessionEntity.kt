package com.ueiol.scanaianswer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话头（对应抽屉「历史对话列表」的一条记录）。
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val sessionId: String,
    val modelPath: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)