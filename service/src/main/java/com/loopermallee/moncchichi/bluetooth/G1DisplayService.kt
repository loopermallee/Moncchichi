package com.loopermallee.moncchichi.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.IBinder
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import android.os.ServiceInfo
import androidx.core.app.NotificationCompat
import android.content.Context

class G1DisplayService : Service() {

    private val logger by lazy { MoncchichiLogger(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val deviceManager by lazy { DeviceManager(this, serviceScope) }
    private val connectionStateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    private val readableStateFlow = connectionStateFlow.asStateFlow()
    private val binder = G1Binder()
    private val heartbeatStarted = AtomicBoolean(false)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        try {
            super.onCreate()
            startAsForegroundService()
            logger.i(APP_BOOT_TAG, "${tt()} G1DisplayService created")
            startHeartbeatMonitoring()
            monitorReconnection()
            serviceScope.launch {
                deviceManager.connectionState.collectLatest { connection ->
                    val nextState = when (connection) {
                        DeviceManager.ConnectionState.CONNECTED -> G1ConnectionState.CONNECTED
                        DeviceManager.ConnectionState.CONNECTING -> G1ConnectionState.CONNECTING
                        DeviceManager.ConnectionState.DISCONNECTING -> G1ConnectionState.RECONNECTING
                        DeviceManager.ConnectionState.DISCONNECTED -> G1ConnectionState.DISCONNECTED
                        DeviceManager.ConnectionState.ERROR -> G1ConnectionState.RECONNECTING
                        else -> {
                            logger.w(TAG, "${tt()} Unknown connection state $connection")
                            G1ConnectionState.DISCONNECTED
                        }
                    }
                    connectionStateFlow.value = nextState
                    updateForegroundNotification(nextState)
                }
            }
        } catch (t: Throwable) {
            logger.e(APP_BOOT_TAG, "${tt()} G1DisplayService.onCreate crashed", t)
            stopSelf()
            throw t
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        logger.debug(TAG, "${tt()} onBind(${intent?.action})")
        if (heartbeatStarted.compareAndSet(false, true)) {
            startHeartbeatMonitoring()
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        heartbeatStarted.set(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildForegroundNotification(getString(R.string.notification_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()
    }

    private fun updateForegroundNotification(state: G1ConnectionState) {
        val statusText = when (state) {
            G1ConnectionState.CONNECTED -> getString(R.string.notification_description)
            G1ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            G1ConnectionState.RECONNECTING -> getString(R.string.status_reconnecting)
            else -> getString(R.string.notification_text)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun startHeartbeatMonitoring() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(8_000)
                try {
                    if (binder.checkBinderHeartbeat()) {
                        logger.debug(TAG, "${tt()} Heartbeat OK")
                    } else if (deviceManager.anyWaitingForReconnect()) {
                        logger.w(TAG, "${tt()} Lost heartbeat, marking reconnecting")
                        deviceManager.resetDisconnected()
                        connectionStateFlow.value = G1ConnectionState.RECONNECTING
                    } else {
                        logger.debug(TAG, "${tt()} Heartbeat idle; no devices pending reconnect")
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "${tt()} Heartbeat error: ${e.message}", e)
                    if (deviceManager.anyWaitingForReconnect()) {
                        connectionStateFlow.value = G1ConnectionState.RECONNECTING
                    }
                }
            }
        }
    }

    private fun monitorReconnection() {
        serviceScope.launch {
            connectionStateFlow.collect { state ->
                if (state == G1ConnectionState.RECONNECTING) {
                    logger.w(TAG, "${tt()} Lost connection — starting auto-reconnect sequence")
                    val reconnected = deviceManager.tryReconnect(applicationContext)
                    if (reconnected) {
                        connectionStateFlow.value = G1ConnectionState.CONNECTED
                        logger.i(TAG, "${tt()} Reconnected successfully.")
                    } else {
                        logger.e(TAG, "${tt()} Failed to reconnect automatically.")
                        connectionStateFlow.value = G1ConnectionState.DISCONNECTED
                    }
                }
            }
        }
    }

    inner class G1Binder : Binder() {

        val stateFlow: StateFlow<G1ConnectionState> = readableStateFlow

        fun heartbeat() {
            checkBinderHeartbeat()
        }

        fun checkBinderHeartbeat(): Boolean {
            val isConnected = deviceManager.isConnected()
            return when {
                deviceManager.anyWaitingForReconnect() -> {
                    connectionStateFlow.value = G1ConnectionState.RECONNECTING
                    updateForegroundNotification(G1ConnectionState.RECONNECTING)
                    isConnected
                }
                isConnected && deviceManager.allConnected() -> {
                    connectionStateFlow.value = G1ConnectionState.CONNECTED
                    updateForegroundNotification(G1ConnectionState.CONNECTED)
                    true
                }
                isConnected -> {
                    connectionStateFlow.value = G1ConnectionState.CONNECTING
                    updateForegroundNotification(G1ConnectionState.CONNECTING)
                    true
                }
                else -> {
                    connectionStateFlow.value = G1ConnectionState.DISCONNECTED
                    updateForegroundNotification(G1ConnectionState.DISCONNECTED)
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "G1DisplayService"
        private const val APP_BOOT_TAG = "AppBoot"
        private const val NOTIFICATION_CHANNEL_ID = "moncchichi-g1-display"
        private const val NOTIFICATION_ID = 0xC104
    }

    private fun tt() = "[${Thread.currentThread().name}]"
}
