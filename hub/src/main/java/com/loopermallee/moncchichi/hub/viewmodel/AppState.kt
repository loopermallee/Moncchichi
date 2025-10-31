package com.loopermallee.moncchichi.hub.viewmodel

import com.loopermallee.moncchichi.core.model.ChatMessage
import com.loopermallee.moncchichi.hub.ui.scanner.PairingProgress
import com.loopermallee.moncchichi.hub.ui.scanner.ScanBannerState

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

data class AppState(
    val device: DeviceInfo = DeviceInfo(),
    val consoleLines: List<String> = emptyList(),
    val permissionsOk: Boolean = false,
    val assistant: AssistantPane = AssistantPane(),
    val scanBanner: ScanBannerState = ScanBannerState(),
    val pairingProgress: Map<String, PairingProgress> = emptyMap(),
    val showTroubleshootDialog: Boolean = false,
)
