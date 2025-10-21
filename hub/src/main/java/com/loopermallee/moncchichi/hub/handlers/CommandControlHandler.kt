package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

object CommandControlHandler {
    suspend fun run(
        text: String,
        ble: BleTool,
        display: DisplayTool,
        _memory: MemoryRepository,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val cmd = when {
            text.contains("right") && text.contains("off") -> "LENS_RIGHT_OFF"
            text.contains("right") && text.contains("on") -> "LENS_RIGHT_ON"
            text.contains("left") && text.contains("off") -> "LENS_LEFT_OFF"
            text.contains("left") && text.contains("on") -> "LENS_LEFT_ON"
            text.contains("brightness") -> "BRIGHTNESS_AUTO"
            else -> "UNKNOWN"
        }
        val resp = ble.send(cmd)
        val lines = listOf("Command:", cmd, "Result: $resp")
        val summary = lines.joinToString(" | ")
        display.showLines(lines)
        onAssistant(summary)
        log("[CMD] $cmd → $resp")
    }
}
