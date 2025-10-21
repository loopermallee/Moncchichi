package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

object SubtitleHandler {
    suspend fun run(
        text: String,
        display: DisplayTool,
        _memory: MemoryRepository,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val msg = "Subtitle mode: $text"
        display.showLines(listOf("🎬 Subtitles", msg))
        onAssistant(msg)
        log("[Subtitles] $msg")
    }
}
