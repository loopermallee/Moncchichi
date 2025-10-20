package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothAdapter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.bluetooth.DeviceManager
import com.loopermallee.moncchichi.core.ble.DeviceVitals
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

class G1DisplayService : Service() {

    private val logger by lazy { MoncchichiLogger(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceManager by lazy { DeviceManager(this, serviceScope) }
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val connectionStateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    private val readableStateFlow = connectionStateFlow.asStateFlow()
    private val binder = G1Binder()
    private val heartbeatStarted = AtomicBoolean(false)
    private var heartbeatJob: Job? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startHeartbeatMonitoring()
        heartbeatStarted.set(true)
        restoreCachedState()
        attemptPersistentRebind()
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
                val previous = connectionStateFlow.value
                connectionStateFlow.value = nextState
                cacheServiceState(nextState)
                if (nextState == G1ConnectionState.CONNECTED && previous != G1ConnectionState.CONNECTED) {
                    serviceScope.launch {
                        deviceManager.queryBattery()
                        deviceManager.queryFirmware()
                    }
                }
                if (nextState == G1ConnectionState.DISCONNECTED &&
                    deviceManager.getLastConnectedAddress() == null
                ) {
                    logger.recovery(TAG, "${tt()} Persistent cache cleared; reverting to scan mode")
                }
            }
        }
        serviceScope.launch {
            deviceManager.batteryLevel.collectLatest { level ->
                level?.let {
                    logger.debug(TAG, "${tt()} Battery telemetry update: $it%")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.debug(TAG, "${tt()} onStartCommand(flags=$flags, startId=$startId)")
        return START_STICKY
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
        super.onDestroy()
        deviceManager.disconnect()
        serviceScope.cancel()
        logger.i(TAG, "${tt()} Service destroyed and BLE disconnected")
    }

    /**
     * Connect directly to a device given its BLE address.
     * This is exposed via the binder for manual pairing from the Hub UI.
     */
    fun connect(address: String) {
        try {
            deviceManager.connectToAddress(address)
        } catch (error: Exception) {
            logger.e(TAG, "${tt()} Failed to connect to $address: ${error.message}", error)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "G1 Connection",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Moncchichi")
            .setContentText("Connecting to Even G1…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
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
                    logger.recovery(TAG, "${tt()} Connection marked for recovery")
                    if (deviceManager.connectionState.value == DeviceManager.ConnectionState.DISCONNECTED) {
                        deviceManager.tryReconnect(applicationContext)
                    }
                }
            }
        }
    }

    inner class G1Binder : Binder() {

        val stateFlow: StateFlow<G1ConnectionState> = readableStateFlow
        val batteryFlow: StateFlow<Int?> = deviceManager.batteryLevel
        val vitalsFlow: StateFlow<DeviceVitals> = deviceManager.vitals

        fun connect(address: String) {
            this@G1DisplayService.connect(address)
        }

        fun heartbeat() {
            checkBinderHeartbeat()
        }

        fun queryBattery() {
            serviceScope.launch { deviceManager.queryBattery() }
        }

        fun queryFirmware() {
            serviceScope.launch { deviceManager.queryFirmware() }
        }

        fun checkBinderHeartbeat(): Boolean {
            val isConnected = deviceManager.isConnected()
            return when {
                deviceManager.anyWaitingForReconnect() -> {
                    logger.heartbeat(TAG, "${tt()} Binder heartbeat flagged reconnecting")
                    connectionStateFlow.value = G1ConnectionState.RECONNECTING
                    isConnected
                }
                isConnected && deviceManager.allConnected() -> {
                    logger.heartbeat(TAG, "${tt()} Binder heartbeat OK")
                    connectionStateFlow.value = G1ConnectionState.CONNECTED
                    true
                }
                isConnected -> {
                    logger.heartbeat(TAG, "${tt()} Binder heartbeat waiting for peers")
                    connectionStateFlow.value = G1ConnectionState.CONNECTING
                    true
                }
                else -> {
                    logger.heartbeat(TAG, "${tt()} Binder heartbeat found disconnect")
                    connectionStateFlow.value = G1ConnectionState.DISCONNECTED
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "G1DisplayService"
        private const val CHANNEL_ID = "g1_status"
        private const val NOTIFICATION_ID = 1337
        private const val PREFS_NAME = "g1_display_service"
        private const val KEY_STATE = "cached_state"
        private const val KEY_STATE_TS = "cached_state_timestamp"
    }

    private fun tt() = "[${Thread.currentThread().name}]"

    private fun restoreCachedState() {
        val cached = prefs.getString(KEY_STATE, null) ?: return
        val restored = runCatching { G1ConnectionState.valueOf(cached) }.getOrNull() ?: return
        connectionStateFlow.value = restored
        val ts = prefs.getLong(KEY_STATE_TS, 0L)
        logger.i(TAG, "${tt()} Restored cached service state $restored (ts=$ts)")
    }

    private fun cacheServiceState(state: G1ConnectionState) {
        prefs.edit()
            .putString(KEY_STATE, state.name)
            .putLong(KEY_STATE_TS, System.currentTimeMillis())
            .apply()
    }

    private fun attemptPersistentRebind() {
        val adapter = bluetoothAdapter
        if (adapter == null || adapter.isEnabled.not()) {
            logger.debug(TAG, "${tt()} Bluetooth disabled — skipping persistent rebind")
            return
        }
        if (deviceManager.connectionState.value != DeviceManager.ConnectionState.DISCONNECTED) {
            return
        }
        val cachedAddress = deviceManager.getLastConnectedAddress() ?: return
        logger.i(TAG, "${tt()} Attempting persistent rebind to $cachedAddress")
        serviceScope.launch {
            deviceManager.connectToAddress(cachedAddress)
        }
    }
}
