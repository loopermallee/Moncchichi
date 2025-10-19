package com.loopermallee.moncchichi.telemetry

/**
 * Represents a telemetry log entry between the app, BLE service, and G1 device.
 * Used for the Live Log feature and diagnostic export.
 */
data class G1TelemetryEvent(
    val source: String,          // "APP", "SERVICE", or "DEVICE"
    val tag: String,             // e.g. "[BLE]", "[NOTIFY]", "[ERROR]"
    val message: String,         // Human-readable message
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "[$time][$source][$tag] $message"
    }
}
