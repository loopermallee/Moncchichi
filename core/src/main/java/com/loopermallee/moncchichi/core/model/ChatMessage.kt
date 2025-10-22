package com.loopermallee.moncchichi.core.model

/**
 * Represents a single line in the assistant conversation log.
 * Only messages with [MessageSource.USER] or [MessageSource.ASSISTANT]
 * should be rendered in the chat UI.
 */
enum class MessageSource {
    USER,
    ASSISTANT,
    BLE,
    SYSTEM,
}

data class ChatMessage(
    val text: String,
    val source: MessageSource,
    val timestamp: Long,
)
