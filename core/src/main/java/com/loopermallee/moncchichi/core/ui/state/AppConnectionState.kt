package com.loopermallee.moncchichi.core.ui.state

enum class AssistantConnState { ONLINE, OFFLINE, ERROR }

enum class DeviceConnState { CONNECTED, DISCONNECTED }

data class AssistantConnInfo(
    val state: AssistantConnState,
    val model: String? = null,
    val reason: String? = null
)

data class DeviceConnInfo(
    val state: DeviceConnState,
    val deviceName: String? = null,
    val rssi: Int? = null,
    val glassesBatteryPct: Int? = null,
    val caseBatteryPct: Int? = null,
    val firmware: String? = null,
    val macAddress: String? = null
)
