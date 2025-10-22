package com.loopermallee.moncchichi.hub.tools.impl

import com.loopermallee.moncchichi.hub.tools.LlmTool
import kotlin.random.Random

internal object LlmFallback {
    private val canned = listOf(
        "I'm offline, but here's what I remember:",
        "No network, sharing cached knowledge:",
        "Working locally. Here's a quick summary:",
        "Offline mode engaged. Suggestion:",
        "Can't reach the cloud, but locally I know that"
    )

    fun respond(prompt: String, context: List<LlmTool.Message>): LlmTool.Reply {
        val prefix = canned.random(Random(prompt.hashCode()))
        val memoryHint = context.lastOrNull { it.role == LlmTool.Role.ASSISTANT }?.content
        val body = buildString {
            append(prefix)
            append(' ')
            if (!memoryHint.isNullOrBlank()) {
                append("Previously we talked about \"")
                append(memoryHint.take(90))
                append("\". ")
            }
            append(
                prompt.takeIf { it.length <= 140 } ?: prompt.take(137) + "…"
            )
        }
        return LlmTool.Reply(body, isOnline = false, errorMessage = null)
    }
}
