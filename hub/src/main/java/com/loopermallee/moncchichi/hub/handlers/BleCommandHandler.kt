package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool

object BleCommandHandler {

    suspend fun run(
        text: String,
        ble: BleTool,
        display: DisplayTool,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val command = mapToCommand(text)
        val response = ble.send(command)
        val summary = "Command: $command → $response"
        display.showLines(listOf("Command", command, "Result", response))
        onAssistant(summary)
        log("[CMD] $command => $response")
    }

    private fun mapToCommand(text: String): String {
        val lower = text.lowercase()
        return when {
            "right" in lower && listOf("off", "disable").any { it in lower } -> "LENS_RIGHT_OFF"
            "right" in lower && listOf("on", "enable").any { it in lower } -> "LENS_RIGHT_ON"
            "left" in lower && listOf("off", "disable").any { it in lower } -> "LENS_LEFT_OFF"
            "left" in lower && listOf("on", "enable").any { it in lower } -> "LENS_LEFT_ON"
            listOf("darker", "dim").any { it in lower } -> "BRIGHTNESS_DOWN"
            listOf("brighter", "increase").any { it in lower } -> "BRIGHTNESS_UP"
            "reset" in lower && "display" in lower -> "DISPLAY_RESET"
            else -> "UNKNOWN"
        }
    }
}
