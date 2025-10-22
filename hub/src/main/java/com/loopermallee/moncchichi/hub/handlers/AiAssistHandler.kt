package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool

object AiAssistHandler {
    suspend fun run(
        text: String,
        context: List<LlmTool.Message>,
        llm: LlmTool,
        display: DisplayTool,
        onAssistant: (LlmTool.Reply) -> Unit,
        log: (String) -> Unit
    ) {
        val answer = llm.answer(text, context)
        val lines = answer.text.chunked(42).take(3)
        display.showLines(lines)
        onAssistant(answer)
        log("[AI] $text → ${answer.text}")
    }
}
