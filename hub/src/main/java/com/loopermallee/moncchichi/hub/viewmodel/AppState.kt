package com.loopermallee.moncchichi.hub.viewmodel

import com.loopermallee.moncchichi.hub.data.db.AssistantMessage

data class DeviceInfo(
    val name: String? = null,
    val id: String? = null,
    val isConnected: Boolean = false,
    val glassesBattery: Int? = null,
    val caseBattery: Int? = null
)

data class AssistantPane(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscript: String? = null,
    val lastTranscript: String? = null,
    val lastResponse: String? = null,
    val isBusy: Boolean = false,
    val isOffline: Boolean = false,
    val history: List<AssistantMessage> = emptyList(),
    val voiceEnabled: Boolean = true
)

data class AppState(
    val device: DeviceInfo = DeviceInfo(),
    val consoleLines: List<String> = emptyList(),
    val permissionsOk: Boolean = false,
    val assistant: AssistantPane = AssistantPane()
)
