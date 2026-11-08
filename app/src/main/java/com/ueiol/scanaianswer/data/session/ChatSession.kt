package com.ueiol.scanaianswer.data.session

import java.util.UUID

/**
 * 一次拍照解题对应一个 ChatSession。
 * 每次拍照会创建新的 sessionId，同一道题的追问沿用当前会话。
 *
 * 同时维护 [messages]（UI 展示用）和 [history]（LLM 提交用），
 * 避免每次生成时重复转换。
 */
data class ChatSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val modelPath: String,
    val messages: MutableList<Message> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** LLM 历史记录，元素为 (role, content)，直接用于 submitFullHistory */
    val history: MutableList<Pair<String, String>> = mutableListOf()

    fun addUserMessage(content: String) {
        messages.add(Message.User(content))
        history.add(Pair("user", content))
    }

    fun addAssistantMessage(content: String, isThinking: Boolean = false) {
        if (isThinking) {
            messages.add(Message.Assistant(content, isThinking = true))
        } else {
            messages.add(Message.Assistant.parse(content))
        }
        history.add(Pair("assistant", content))
    }

    fun updateLastAssistantMessage(content: String) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg is Message.Assistant) {
            messages.removeLast()
            messages.add(Message.Assistant.parse(content))
        }
        val lastHistory = history.lastOrNull()
        if (lastHistory != null && lastHistory.first == "assistant") {
            history.removeLast()
            history.add(Pair("assistant", content))
        }
    }

    /**
     * 仅挂载到内存（用于从数据库恢复会话），不重复构造历史。
     * 若由 UI 主动新增消息请用 [addUserMessage]/[addAssistantMessage]。
     */
    fun addMessageInMemory(message: Message) {
        messages.add(message)
        history.add(
            Pair(
                if (message is Message.User) "user" else "assistant",
                message.content
            )
        )
    }

    fun clear() {
        messages.clear()
        history.clear()
    }

    fun toHistoryString(): String {
        return messages.joinToString("\n") { msg ->
            when (msg) {
                is Message.User -> "<|im_start|>user\n${msg.content}<|im_end|>"
                is Message.Assistant -> "<|im_start|>assistant\n${msg.content}<|im_end|>"
            }
        }
    }
}