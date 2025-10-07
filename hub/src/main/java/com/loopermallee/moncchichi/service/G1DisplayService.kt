package com.loopermallee.moncchichi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.bluetooth.DeviceManager
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import io.texne.g1.hub.MainActivity
import io.texne.g1.hub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class G1DisplayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceManager by lazy { DeviceManager(applicationContext) }
    private val binder = G1Binder()
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var powerReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        MoncchichiLogger.init(applicationContext)
        MoncchichiLogger.i(SERVICE_TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(G1ConnectionState.DISCONNECTED))
        observeStateChanges()
        startHeartbeatLoop()
        registerPowerAwareReconnect()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean = super.onUnbind(intent)

    override fun onDestroy() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        powerReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        powerReceiver = null
        deviceManager.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeStateChanges() {
        serviceScope.launch {
            deviceManager.state.collectLatest { state ->
                updateNotification(state)
                when (state) {
                    G1ConnectionState.WAITING_FOR_RECONNECT -> scheduleReconnects()
                    G1ConnectionState.DISCONNECTED -> reconnectJob?.cancel()
                    G1ConnectionState.CONNECTED -> reconnectJob?.cancel()
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(8_000L)
                if (deviceManager.state.value == G1ConnectionState.CONNECTED) {
                    val success = deviceManager.sendHeartbeat()
                    if (success) {
                        MoncchichiLogger.d(HEARTBEAT_TAG, "sent")
                    } else {
                        MoncchichiLogger.w(HEARTBEAT_TAG, "failed to send heartbeat")
                    }
                }
            }
        }
    }

    private fun scheduleReconnects() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            while (deviceManager.state.value == G1ConnectionState.WAITING_FOR_RECONNECT) {
                val success = deviceManager.reconnect()
                if (success) {
                    break
                }
                MoncchichiLogger.w(RECONNECT_TAG, "retry scheduled")
                delay(10_000L)
            }
        }
    }

    private fun buildNotification(state: G1ConnectionState): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentText = when (state) {
            G1ConnectionState.CONNECTED -> getString(R.string.notification_connected)
            G1ConnectionState.RECONNECTING -> getString(R.string.notification_reconnecting)
            G1ConnectionState.WAITING_FOR_RECONNECT -> getString(R.string.notification_waiting)
            else -> getString(R.string.notification_disconnected)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(intent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(state: G1ConnectionState) {
        val notification = buildNotification(state)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    inner class G1Binder : Binder() {
        val stateFlow: StateFlow<G1ConnectionState> = deviceManager.state

        fun connect(address: String) {
            serviceScope.launch {
                deviceManager.connect(address)
            }
        }

        fun disconnect() {
            deviceManager.disconnect()
        }

        fun heartbeat() {
            serviceScope.launch {
                deviceManager.sendHeartbeat()
            }
        }

        fun requestReconnect() {
            deviceManager.tryReconnect()
        }
    }

    private fun registerPowerAwareReconnect() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    MoncchichiLogger.debug("[Power]", "Screen on → trigger reconnect")
                    deviceManager.tryReconnect()
                }
            }
        }
        registerReceiver(receiver, filter)
        powerReceiver = receiver
    }

    companion object {
        private const val SERVICE_TAG = "[Service]"
        private const val HEARTBEAT_TAG = "[Heartbeat]"
        private const val RECONNECT_TAG = "[Reconnect]"
        private const val CHANNEL_ID = "moncchichi-ble"
        private const val NOTIFICATION_ID = 52
    }
}
