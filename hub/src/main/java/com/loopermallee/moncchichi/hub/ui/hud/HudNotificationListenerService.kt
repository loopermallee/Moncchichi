package com.loopermallee.moncchichi.hub.ui.hud

import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HudNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: sbn.packageName
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            ?.toString()
            ?: ""
        val hudNotification = HudNotification(
            id = sbn.key,
            title = title,
            text = text,
            postedAt = System.currentTimeMillis(),
        )
        storeNotification(hudNotification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        removeNotification(sbn.key)
    }

    companion object {
        private val notifications = ConcurrentHashMap<String, HudNotification>()
        private val _notificationFlow = MutableStateFlow<List<HudNotification>>(emptyList())
        val notificationFlow = _notificationFlow.asStateFlow()

        private fun storeNotification(notification: HudNotification) {
            notifications[notification.id] = notification
            trimAndEmit()
        }

        private fun removeNotification(id: String) {
            notifications.remove(id)
            trimAndEmit()
        }

        private fun trimAndEmit(maxItems: Int = 5) {
            val current = notifications.values
                .sortedByDescending { it.postedAt }
                .take(maxItems)
            _notificationFlow.value = current
        }

        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(":").any { component ->
                component.contains(context.packageName, ignoreCase = true)
            }
        }
    }
}
