package com.loopermallee.moncchichi.telemetry

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single line of structured telemetry the UI and logs can observe.
 */
data class G1TelemetryEvent(
    /** Wall-clock timestamp in milliseconds so consumers can sort across sources. */
    val ts: Long = System.currentTimeMillis(),
    /** Origin of the event (e.g., APP, SERVICE, DEVICE, SYSTEM). */
    val source: String,
    /** Short category or tag for the event such as "[BLE]" or "[NOTIFY]". */
    val tag: String,
    /** Human friendly description of the event. */
    val message: String,
) {
    override fun toString(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val time = formatter.format(Date(ts))
        return "[$time][$source]$tag $message"
    }
}
