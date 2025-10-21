package com.loopermallee.moncchichi.hub.viewmodel

data class DeviceInfo(
    val name: String? = null,
    val id: String? = null,
    val rssi: Int? = null,
    val isConnected: Boolean = false,
    val battery: Int? = null
)

data class AssistantPane(
    val isListening: Boolean = false,
    val lastTranscript: String? = null,
    val lastResponse: String? = null,
    val isBusy: Boolean = false
)

data class AppState(
    val device: DeviceInfo = DeviceInfo(),
    val consoleLines: List<String> = emptyList(),
    val permissionsOk: Boolean = false,
    val assistant: AssistantPane = AssistantPane()
)
