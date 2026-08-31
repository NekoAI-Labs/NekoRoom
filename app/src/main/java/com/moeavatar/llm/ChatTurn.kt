package com.moeavatar.llm

/**
 * 统一对话历史条目。
 */
data class ChatTurn(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
