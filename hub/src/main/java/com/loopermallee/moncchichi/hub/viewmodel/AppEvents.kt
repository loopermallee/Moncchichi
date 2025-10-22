package com.loopermallee.moncchichi.hub.viewmodel

sealed interface AppEvent {
    data object StartScan : AppEvent
    data object StopScan : AppEvent
    data class Connect(val deviceId: String) : AppEvent
    data object Disconnect : AppEvent
    data class SendBleCommand(val command: String) : AppEvent

    data class UserSaid(val transcript: String) : AppEvent
    data class AssistantAsk(val text: String) : AppEvent
    data object AssistantStartListening : AppEvent
    data object AssistantStopListening : AppEvent
    data class AssistantVoiceToggle(val enabled: Boolean) : AppEvent

    data object RequestRequiredPermissions : AppEvent
}
