package com.loopermallee.moncchichi.core.ble

/**
 * Consolidated status for the G1 Data Console UI.
 */
enum class DeviceMode {
    IDLE,
    TEXT,
    IMAGE,
    DASHBOARD,
    UNKNOWN,
}

enum class DeviceBadge {
    CHARGING,
    FULL,
    WEARING,
    CRADLE,
}

data class ConsoleDiagnostics(
    val mode: DeviceMode = DeviceMode.UNKNOWN,
    val badges: Set<DeviceBadge> = emptySet(),
    val mtu: Int = 23,
    val highPriority: Boolean = false,
    val leftCccdArmed: Boolean = false,
    val rightCccdArmed: Boolean = false,
)
