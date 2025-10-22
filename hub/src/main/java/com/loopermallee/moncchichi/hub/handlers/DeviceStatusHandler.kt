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
        val glasses = ble.battery()
        val case = ble.caseBattery()
        val firmware = ble.firmware()
        val lines = listOf(
            "Device Status",
            "Glasses: ${glasses ?: "unknown"}%",
            "Case: ${case ?: "unknown"}%",
            "Firmware: ${firmware ?: "unknown"}",
            "Connected: ${if (isConnected) "yes" else "no"}"
        )
        val summary = lines.joinToString(" | ")
        display.showLines(lines)
        onAssistant(summary)
        log("[Status] ${lines.joinToString(" / ")}")
    }
}
