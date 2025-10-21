package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

object LiveFeedHandler {
    suspend fun run(
        _ble: BleTool,
        display: DisplayTool,
        _memory: MemoryRepository,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val msg = "Live feed not implemented (placeholder)."
        display.showLines(listOf("Live Feed", msg))
        onAssistant(msg)
        log("[Feed] $msg")
    }
}
