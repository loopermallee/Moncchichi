package com.loopermallee.moncchichi.hub.ui.hud

import com.loopermallee.moncchichi.client.G1ServiceCommon

enum class HudConnectionStatus {
    DISCONNECTED,
    CONNECTED,
    DISPLAYING,
}

enum class HudTile {
    TIME,
    WEATHER,
    TEMPERATURE,
    NOTIFICATIONS,
}

data class HudConfig(
    val showTime: Boolean = true,
    val showWeather: Boolean = true,
    val showTemperature: Boolean = true,
    val showNotifications: Boolean = true,
    val tileOrder: List<HudTile> = defaultTileOrder(),
) {
    fun toggle(tile: HudTile): HudConfig = when (tile) {
        HudTile.TIME -> copy(showTime = !showTime)
        HudTile.WEATHER -> copy(showWeather = !showWeather)
        HudTile.TEMPERATURE -> copy(showTemperature = !showTemperature)
        HudTile.NOTIFICATIONS -> copy(showNotifications = !showNotifications)
    }

    fun moveUp(tile: HudTile): HudConfig {
        val index = tileOrder.indexOf(tile)
        if (index <= 0) return this
        val mutable = tileOrder.toMutableList()
        mutable.removeAt(index)
        mutable.add(index - 1, tile)
        return copy(tileOrder = mutable)
    }

    fun moveDown(tile: HudTile): HudConfig {
        val index = tileOrder.indexOf(tile)
        if (index == -1 || index >= tileOrder.lastIndex) return this
        val mutable = tileOrder.toMutableList()
        mutable.removeAt(index)
        mutable.add(index + 1, tile)
        return copy(tileOrder = mutable)
    }

    companion object {
        fun defaultTileOrder(): List<HudTile> = listOf(
            HudTile.TIME,
            HudTile.WEATHER,
            HudTile.TEMPERATURE,
            HudTile.NOTIFICATIONS,
        )
    }
}

data class HudNotification(
    val id: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

data class HudTargetOption(
    val id: String,
    val label: String,
    val glasses: G1ServiceCommon.Glasses? = null,
)

data class HudUiState(
    val connectionStatus: HudConnectionStatus = HudConnectionStatus.DISCONNECTED,
    val connectedLensId: String? = null,
    val connectedLensName: String? = null,
    val mirrorText: String = "",
    val isDisplaying: Boolean = false,
    val scrollSpeed: Float = 0f,
    val lastMessageTimestamp: Long? = null,
    val messageDraft: String = "",
    val availableTargets: List<HudTargetOption> = emptyList(),
    val selectedTargetId: String? = null,
    val hudConfig: HudConfig = HudConfig(),
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null,
    val weatherLastUpdated: Long? = null,
    val isWeatherLoading: Boolean = false,
    val notifications: List<HudNotification> = emptyList(),
    val isNotificationAccessGranted: Boolean = false,
    val errorMessage: String? = null,
    val isSending: Boolean = false,
)
