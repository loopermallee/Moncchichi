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
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.TestActivity
import com.loopermallee.moncchichi.bluetooth.DeviceManager
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import io.texne.g1.hub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class G1DisplayService : Service() {

    private val logger by lazy { MoncchichiLogger(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceManager by lazy { DeviceManager(applicationContext) }
    private val ready = MutableStateFlow(false)
    private val _connectionState = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<G1ConnectionState> = _connectionState
    private val binder = LocalBinder()
    private var powerReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val initial = buildForegroundNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        registerPowerAwareReconnect()
        _connectionState.value = deviceManager.state.value

        serviceScope.launch {
            logger.i(SERVICE_TAG, "${tt()} onCreate: launching heartbeat + reconnect loops")
            launch { observeStateChanges() }
            launch { heartbeatLoop() }
            launch { reconnectLoop() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            logger.i(SERVICE_TAG, "${tt()} onStartCommand flags=$flags startId=$startId")
            START_STICKY
        } catch (t: Throwable) {
            Log.e(DEFAULT_LOG_TAG, "onStartCommand crashed", t)
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return try {
            logger.debug(SERVICE_TAG, "${tt()} onBind called ${intent?.action}")
            serviceScope.launch {
                ensureInitialized()
                ready.emit(true)
            }
            serviceScope.launch {
                delay(5_000L)
                if (!ready.value) {
                    logger.debug(SERVICE_TAG, "${tt()} Bind timeout — service not initialized in time")
                }
            }
            binder
        } catch (t: Throwable) {
            Log.e(DEFAULT_LOG_TAG, "onBind crashed", t)
            binder
        }
    }

    override fun onUnbind(intent: Intent?): Boolean = super.onUnbind(intent)

    override fun onDestroy() {
        powerReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        powerReceiver = null
        deviceManager.close()
        ready.value = false
        _connectionState.value = G1ConnectionState.DISCONNECTED
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun ensureInitialized() {
        if (deviceManager.state.value != G1ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) {
                deviceManager.tryReconnect()
            }
        }
    }

    private suspend fun observeStateChanges() {
        deviceManager.state.collectLatest { state ->
            logger.d(SERVICE_TAG, "${tt()} observe: $state")
            updateState(state)
            updateNotification(state)
        }
    }

    private suspend fun heartbeatLoop() {
        while (coroutineContext.isActive) {
            delay(8_000L)
            if (deviceManager.state.value == G1ConnectionState.CONNECTED) {
                val success = deviceManager.sendHeartbeat()
                if (success) {
                    logger.d(HEARTBEAT_TAG, "${tt()} sent")
                } else {
                    logger.w(HEARTBEAT_TAG, "${tt()} failed to send heartbeat")
                }
            }
        }
    }

    private suspend fun reconnectLoop() {
        deviceManager.state.collectLatest { state ->
            if (state == G1ConnectionState.RECONNECTING) {
                while (coroutineContext.isActive && deviceManager.state.value == G1ConnectionState.RECONNECTING) {
                    val success = deviceManager.reconnect()
                    if (success) {
                        logger.i(RECONNECT_TAG, "${tt()} reconnect succeeded")
                        break
                    }
                    logger.w(RECONNECT_TAG, "${tt()} retry scheduled")
                    delay(10_000L)
                }
            }
        }
    }

    private fun updateState(newState: G1ConnectionState) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            logger.debug(SERVICE_TAG, "${tt()} Connection state -> $newState")
        }
    }

    private fun updateNotification(state: G1ConnectionState) {
        val statusText = when (state) {
            G1ConnectionState.CONNECTED -> getString(R.string.notification_connected)
            G1ConnectionState.CONNECTING -> getString(R.string.notification_connecting)
            G1ConnectionState.DISCONNECTED -> getString(R.string.notification_disconnected)
            G1ConnectionState.RECONNECTING -> getString(R.string.notification_reconnecting)
            else -> {
                logger.w(SERVICE_TAG, "${tt()} Unknown notification state $state")
                getString(R.string.notification_unknown)
            }
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Moncchichi G1 Link",
                NotificationManager.IMPORTANCE_LOW,
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .build()
    }

    private fun registerPowerAwareReconnect() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    logger.debug(POWER_TAG, "${tt()} Screen on → trigger reconnect")
                    deviceManager.tryReconnect()
                }
            }
        }
        registerReceiver(receiver, filter)
        powerReceiver = receiver
    }

    inner class LocalBinder : Binder() {
        val readiness: StateFlow<Boolean> = ready
        val connectionStates: StateFlow<G1ConnectionState>
            get() = connectionState

        fun getService(): G1DisplayService = this@G1DisplayService

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

    companion object {
        private const val SERVICE_TAG = "[Service]"
        private const val HEARTBEAT_TAG = "[Heartbeat]"
        private const val RECONNECT_TAG = "[Reconnect]"
        private const val POWER_TAG = "[Power]"
        private const val CHANNEL_ID = "moncchichi_g1"
        private const val NOTIFICATION_ID = 52
        private const val DEFAULT_LOG_TAG = "Moncchichi"
    }

    private fun tt() = "[${Thread.currentThread().name}]"
}
