package com.loopermallee.moncchichi.core.utils

object ConsoleInterpreter {
    fun summarize(lines: List<String>): List<String> {
        val summary = mutableListOf<String>()
        val recent = lines.takeLast(15)

        if (recent.any { "Connected" in it && "BLE" in it }) summary += "BLE connected successfully."
        if (recent.any { "Keepalive → ACK" in it }) summary += "BLE keepalive responding normally."
        if (recent.any { "Keepalive failed" in it }) summary += "BLE connection lost recently."
        if (recent.any { "Disconnected" in it }) summary += "Device disconnected."
        if (recent.any { "battery=0" in it || "stub" in it }) summary += "Battery telemetry appears stubbed."
        if (recent.any { "Write failed" in it }) summary += "A BLE command failed to send."
        if (recent.none { "BLE" in it }) summary += "No BLE data detected recently."
        if (recent.any { "LLM" in it && "timeout" in it }) summary += "LLM service timeout detected."
        if (recent.any { "Unauthorized" in it || "401" in it }) summary += "Invalid API key."
        if (recent.any { "OFFLINE FALLBACK ENABLED" in it }) summary += "Assistant is running in offline fallback mode."

        return summary
    }
}
