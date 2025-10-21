package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

object AiAssistHandler {
    suspend fun run(
        text: String,
        llm: LlmTool,
        display: DisplayTool,
        _memory: MemoryRepository,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val answer = llm.answer(text)
        val lines = answer.chunked(42).take(3)
        display.showLines(lines)
        onAssistant(answer)
        log("[AI] $text → $answer")
    }
}
