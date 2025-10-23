package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import kotlin.collections.buildList

object DeviceStatusHandler {
    suspend fun run(
        ble: BleTool,
        display: DisplayTool,
        _memory: MemoryRepository,
        isConnected: Boolean,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit,
        onTelemetry: (Int?, Int?, String?, Int?) -> Unit,
    ) {
        val glasses = ble.battery()
        val case = ble.caseBattery()
        val firmware = ble.firmware()
        val signal = ble.signal()
        val lines = listOf(
            "Device Status",
            "Glasses: ${glasses ?: "unknown"}%",
            "Case: ${case ?: "unknown"}%",
            "Firmware: ${firmware ?: "unknown"}",
            "Signal: ${signal?.let { "${it} dBm" } ?: "unknown"}",
            "Connected: ${if (isConnected) "yes" else "no"}"
        )
        val summaryParts = buildList {
            glasses?.let { add("Battery $it%") }
            case?.let { add("Case $it%") }
            firmware?.let { add("FW $it") }
            signal?.let { add("RSSI ${it} dBm") }
        }
        val assistantLine = if (summaryParts.isEmpty()) {
            "Assistant 🟣 (Device Only): Connection ${if (isConnected) "active" else "offline"}."
        } else {
            "Assistant 🟣 (Device Only): ${summaryParts.joinToString(", ")}"
        }
        display.showLines(lines)
        onAssistant(assistantLine)
        log("[DIAG] ${lines.joinToString(" / ")}")
        onTelemetry(glasses, case, firmware, signal)
    }
}
