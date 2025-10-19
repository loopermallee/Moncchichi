package com.loopermallee.moncchichi.telemetry

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured telemetry event for Moncchichi diagnostics.
 * Used by BLE service and UI to timestamp events.
 */
data class G1TelemetryEvent(
    val source: String,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    override fun toString(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = formatter.format(Date(timestamp))
        return "[$formattedTime][$source][$tag] $message"
    }
}
