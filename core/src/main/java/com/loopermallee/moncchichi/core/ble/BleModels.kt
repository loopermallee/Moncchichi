package com.loopermallee.moncchichi.core.ble

/**
 * Aggregated health data reported by the Even G1.
 */
data class DeviceVitals(
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null,
)
