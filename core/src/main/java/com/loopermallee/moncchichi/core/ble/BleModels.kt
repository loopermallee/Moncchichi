package com.loopermallee.moncchichi.core.ble

/**
 * Aggregated health data reported by the Even G1.
 */
data class DeviceVitals(
    val batteryPercent: Int? = null,
    val caseBatteryPercent: Int? = null,
    val firmwareVersion: String? = null,
    val signalRssi: Int? = null,
    val deviceId: String? = null,
    val connectionState: String? = null,
)
