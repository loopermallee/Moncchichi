package com.loopermallee.moncchichi.hub.ui.hud

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class HudNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        publishActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        publishActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        publishActiveNotifications()
    }

    private fun publishActiveNotifications() {
        val active = activeNotifications?.mapNotNull { sbn ->
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) {
                return@mapNotNull null
            }
            val appName = try {
                val applicationInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(applicationInfo).toString()
            } catch (ignored: Exception) {
                sbn.packageName
            }
            HudNotification(
                appName = appName,
                title = title,
                body = text,
                timestamp = sbn.postTime,
            )
        }?.distinctBy { it.appName + it.title + it.body }?.take(6) ?: emptyList()
        HudNotificationCenter.publish(active)
    }
}
