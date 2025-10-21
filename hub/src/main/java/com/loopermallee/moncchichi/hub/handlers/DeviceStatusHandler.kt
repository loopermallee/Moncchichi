package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

object DeviceStatusHandler {
    suspend fun run(
        ble: BleTool,
        display: DisplayTool,
        _memory: MemoryRepository,
        isConnected: Boolean,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val batt = ble.battery()
        val lines = listOf(
            "Device Status",
            "Battery: ${batt ?: "unknown"}%",
            "Connected: ${if (isConnected) "yes" else "no"}"
        )
        val summary = lines.joinToString(" | ")
        display.showLines(lines)
        onAssistant(summary)
        log("[Status] ${lines.joinToString(" / ")}")
    }
}
