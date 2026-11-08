package com.ueiol.scanaianswer.data.session

import android.content.Context
import com.ueiol.scanaianswer.data.db.AppDatabase
import com.ueiol.scanaianswer.data.db.ChatMessageEntity
import com.ueiol.scanaianswer.data.db.ChatSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 会话持久化仓库：桥接内存中的 [ChatSession] 与 Room 数据表。
 * 抽屉「历史对话列表」与主页面聊天共用此仓库。
 */
class ChatRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).chatDao()

    private fun titleOf(firstUserMessage: String): String {
        val trimmed = firstUserMessage.trim()
        return if (trimmed.length <= 20) trimmed else trimmed.take(20) + "…"
    }

    /** 会话头 → 实体 */
    private fun ChatSession.toEntity(): ChatSessionEntity = ChatSessionEntity(
        sessionId = sessionId,
        modelPath = modelPath,
        title = messages.firstOrNull { it is Message.User }?.content?.let(::titleOf) ?: "新对话",
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis()
    )

    /** 消息 → 实体 */
    private fun ChatSessionEntity.messageEntity(msg: Message): ChatMessageEntity = ChatMessageEntity(
        sessionId = sessionId,
        role = if (msg is Message.User) "user" else "assistant",
        content = msg.content,
        isThinking = (msg as? Message.Assistant)?.isThinking ?: false,
        createdAt = msg.timestamp
    )

    /** 新建会话并持久化会话头。 */
    suspend fun createSession(session: ChatSession) {
        dao.insertSession(session.toEntity())
    }

    /** 追加用户/助手消息。 */
    suspend fun appendMessage(sessionId: String, msg: Message) {
        val entity = ChatSessionEntity(
            sessionId = sessionId,
            modelPath = "",
            title = "",
            createdAt = 0,
            updatedAt = 0
        ).messageEntity(msg)
        dao.insertMessage(entity)
        dao.touchSession(sessionId, System.currentTimeMillis())
    }

    /** 更新最后一条助手消息（流式输出的最终结果）。 */
    suspend fun updateLastAssistantMessage(sessionId: String, content: String) {
        val messages = dao.loadMessages(sessionId)
        val last = messages.lastOrNull()?.takeIf { it.role == "assistant" } ?: return
        if (last.content == content) return
        dao.deleteMessagesBySession(sessionId)
        val rebuilt = messages.map {
            if (it.id == last.id) it.copy(content = content, isThinking = false) else it
        }
        rebuilt.forEach { dao.insertMessage(it) }
        dao.touchSession(sessionId, System.currentTimeMillis())
    }

    /** 按 id 加载某个会话（含消息）用于恢复。 */
    suspend fun loadSession(sessionId: String): ChatSession? {
        val header = dao.getSession(sessionId) ?: return null
        val messages = dao.loadMessages(sessionId).map { it.toMessage() }
        val session = ChatSession(
            sessionId = header.sessionId,
            modelPath = header.modelPath
        )
        messages.forEach { session.addMessageInMemory(it) }
        return session
    }

    private fun ChatMessageEntity.toMessage(): Message {
        return if (role == "user") {
            Message.User(content, createdAt)
        } else {
            if (isThinking) {
                Message.Assistant(content, createdAt, isThinking = true)
            } else {
                Message.Assistant.parse(content, createdAt)
            }
        }
    }

    /** 观察所有会话（按时间倒序）。 */
    fun observeSessions(): Flow<List<ChatSessionEntity>> = dao.observeSessions()

    /** 删除会话及其消息。 */
    suspend fun deleteSession(sessionId: String) {
        dao.deleteSessionById(sessionId)
    }
}