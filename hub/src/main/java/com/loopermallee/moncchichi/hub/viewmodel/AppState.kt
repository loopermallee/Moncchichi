package com.loopermallee.moncchichi.hub.viewmodel

import com.loopermallee.moncchichi.core.model.ChatMessage

data class DeviceInfo(
    val name: String? = null,
    val id: String? = null,
    val isConnected: Boolean = false,
    val glassesBattery: Int? = null,
    val caseBattery: Int? = null,
    val firmwareVersion: String? = null,
    val signalRssi: Int? = null,
    val connectionState: String? = null,
)

data class AssistantPane(
    val isSpeaking: Boolean = false,
    val lastTranscript: String? = null,
    val lastResponse: String? = null,
    val isBusy: Boolean = false,
    val isOffline: Boolean = false,
    val history: List<ChatMessage> = emptyList(),
    val voiceEnabled: Boolean = true,
    val isThinking: Boolean = false,
)

enum class ScanStage {
    Idle,
    Searching,
    WaitingForCompanion,
    BothDetected,
    Connected,
    Timeout,
}

data class ScanStatus(
    val isVisible: Boolean = false,
    val stage: ScanStage = ScanStage.Idle,
    val title: String = "",
    val message: String = "",
    val hint: String? = null,
    val countdownSeconds: Int = 0,
    val showCountdown: Boolean = false,
    val showSpinner: Boolean = false,
)

data class AppState(
    val device: DeviceInfo = DeviceInfo(),
    val consoleLines: List<String> = emptyList(),
    val permissionsOk: Boolean = false,
    val assistant: AssistantPane = AssistantPane(),
    val scan: ScanStatus = ScanStatus(),
)
