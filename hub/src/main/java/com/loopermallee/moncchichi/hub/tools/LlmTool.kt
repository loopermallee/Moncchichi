package com.loopermallee.moncchichi.hub.tools

interface LlmTool {

    data class Message(val role: Role, val content: String)

    enum class Role { SYSTEM, USER, ASSISTANT }

    data class Reply(val text: String, val isOnline: Boolean, val errorMessage: String? = null)

    suspend fun answer(prompt: String, context: List<Message> = emptyList()): Reply
}
