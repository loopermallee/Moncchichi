package com.loopermallee.moncchichi.bluetooth

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class G1DisplayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val deviceManager by lazy { DeviceManager(this, serviceScope) }
    private val connectionStateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    private val readableStateFlow = connectionStateFlow.asStateFlow()
    private val binder = G1Binder()
    private val heartbeatStarted = AtomicBoolean(false)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            deviceManager.connectionState.collectLatest { connection ->
                val nextState = when (connection) {
                    DeviceManager.ConnectionState.CONNECTED -> G1ConnectionState.CONNECTED
                    DeviceManager.ConnectionState.CONNECTING -> G1ConnectionState.CONNECTING
                    DeviceManager.ConnectionState.DISCONNECTING -> G1ConnectionState.WAITING_FOR_RECONNECT
                    DeviceManager.ConnectionState.DISCONNECTED -> G1ConnectionState.DISCONNECTED
                    DeviceManager.ConnectionState.ERROR -> G1ConnectionState.WAITING_FOR_RECONNECT
                }
                connectionStateFlow.value = nextState
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind(${intent?.action})")
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

    private fun startHeartbeatMonitoring() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(8_000)
                try {
                    if (binder.checkBinderHeartbeat()) {
                        Log.d(TAG, "Heartbeat OK")
                    } else if (deviceManager.anyWaitingForReconnect()) {
                        Log.w(TAG, "Lost heartbeat, marking WAITING_FOR_RECONNECT")
                        deviceManager.resetDisconnected()
                        connectionStateFlow.value = G1ConnectionState.WAITING_FOR_RECONNECT
                    } else {
                        Log.d(TAG, "Heartbeat idle; no devices pending reconnect")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}")
                    if (deviceManager.anyWaitingForReconnect()) {
                        connectionStateFlow.value = G1ConnectionState.WAITING_FOR_RECONNECT
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
                    connectionStateFlow.value = G1ConnectionState.WAITING_FOR_RECONNECT
                    isConnected
                }
                isConnected && deviceManager.allConnected() -> {
                    connectionStateFlow.value = G1ConnectionState.CONNECTED
                    true
                }
                isConnected -> {
                    connectionStateFlow.value = G1ConnectionState.CONNECTING
                    true
                }
                else -> {
                    connectionStateFlow.value = G1ConnectionState.DISCONNECTED
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "G1DisplayService"
    }
}
