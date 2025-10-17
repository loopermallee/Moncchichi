package com.loopermallee.moncchichi.telemetry

/**
 * Represents a unified telemetry event emitted by DeviceManager, Service, and Hub layers.
 * Used for real-time logging, UI diagnostics, and debugging within Compose.
 */
sealed class G1TelemetryEvent(
    val timestamp: Long = java.lang.System.currentTimeMillis(),
    val message: String,
    val category: Category
) {
    enum class Category { APP, SERVICE, DEVICE, SYSTEM }

    class App(message: String) : G1TelemetryEvent(message = message, category = Category.APP)
    class Service(message: String) : G1TelemetryEvent(message = message, category = Category.SERVICE)
    class Device(message: String) : G1TelemetryEvent(message = message, category = Category.DEVICE)
    class System(message: String) : G1TelemetryEvent(message = message, category = Category.SYSTEM)

    override fun toString(): String {
        val shortTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(timestamp)
        return "[$shortTime][$category] $message"
    }
}
