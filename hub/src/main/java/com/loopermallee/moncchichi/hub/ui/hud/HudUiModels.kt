package com.loopermallee.moncchichi.hub.ui.hud

import androidx.compose.runtime.Immutable

enum class HudConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

@Immutable
data class HudDevice(
    val id: String,
    val name: String,
)

@Immutable
data class HudMirrorState(
    val text: String = "",
    val isDisplaying: Boolean = false,
    val isPaused: Boolean = false,
    val scrollSpeed: Float = 1f,
    val lastUpdatedMillis: Long? = null,
)

@Immutable
data class HudWeatherSnapshot(
    val temperatureCelsius: Double,
    val description: String,
    val updatedAtMillis: Long,
)

@Immutable
data class HudNotification(
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long,
)

enum class HudTile {
    MIRROR,
    TIME,
    WEATHER,
    TEMPERATURE,
    NOTIFICATIONS,
}

@Immutable
data class HudConfig(
    val showTime: Boolean = true,
    val showWeather: Boolean = true,
    val showTemperature: Boolean = true,
    val showNotifications: Boolean = true,
    val tileOrder: List<HudTile> = listOf(
        HudTile.MIRROR,
        HudTile.TIME,
        HudTile.WEATHER,
        HudTile.TEMPERATURE,
        HudTile.NOTIFICATIONS,
    ),
)

@Immutable
data class HudUiState(
    val connectionStatus: HudConnectionStatus = HudConnectionStatus.DISCONNECTED,
    val connectedDevices: List<HudDevice> = emptyList(),
    val selectedTargetId: String? = null,
    val mirrorState: HudMirrorState = HudMirrorState(),
    val lastMessageTimestamp: Long? = null,
    val lastMessage: String = "",
    val hudConfig: HudConfig = HudConfig(),
    val weather: HudWeatherSnapshot? = null,
    val notifications: List<HudNotification> = emptyList(),
    val isRefreshingWeather: Boolean = false,
    val isSendingMessage: Boolean = false,
    val sendError: String? = null,
    val isDisplayServiceConnected: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val postNotificationPermissionGranted: Boolean = false,
)
