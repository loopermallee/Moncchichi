package com.loopermallee.moncchichi.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class G1DisplayService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i("G1DisplayService", "Service created")
        createNotificationChannel()
        startForeground(
            1,
            createNotification("Even G1 Display Service is running", "Connected to Moncchichi")
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "even_g1_channel",
                "Even G1 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, "even_g1_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("G1DisplayService", "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("G1DisplayService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
