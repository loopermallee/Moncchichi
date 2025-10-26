package com.loopermallee.moncchichi.hub.ui.hud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HudNotificationCenter {
    private val _notifications = MutableStateFlow<List<HudNotification>>(emptyList())
    val notifications: StateFlow<List<HudNotification>> = _notifications.asStateFlow()

    fun publish(notifications: List<HudNotification>) {
        _notifications.value = notifications
    }
}
