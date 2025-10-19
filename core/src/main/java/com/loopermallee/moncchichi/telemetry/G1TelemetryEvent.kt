package com.loopermallee.moncchichi.telemetry

/**
 * Represents a telemetry log event between the app and the G1 smart glasses.
 * Used for logging, diagnostics, and export in G1DisplayService and the Live Log UI.
 */
data class G1TelemetryEvent(
    val source: String,          // "APP", "SERVICE", or "DEVICE"
    val tag: String,             // "[BLE]", "[NOTIFY]", "[ERROR]" etc.
    val message: String,         // Human-readable content of the event
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "[$time][$source][$tag] $message"
    }
}
