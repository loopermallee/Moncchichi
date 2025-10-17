package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private const val TAG = "DeviceManager"

internal class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val logger by lazy { MoncchichiLogger(context) }
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR,
    }

    private val writableConnectionState = globalConnectionState
    val connectionState = writableConnectionState.asStateFlow()

    private val batteryLevelState = MutableStateFlow<Int?>(
        preferences.getInt(KEY_LAST_BATTERY, -1).takeIf { it in 0..100 }
    )
    val batteryLevel = batteryLevelState.asStateFlow()

    private val writableIncoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val incoming = writableIncoming.asSharedFlow()

    private val writeMutex = Mutex()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null

    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null
    private val awaitingAck = AtomicBoolean(false)
    private val manualDisconnect = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private var missedHeartbeatCount = 0
    private var lastAckTimestamp: Long = 0L
    private var currentDeviceAddress: String? = null
    private var lastDeviceAddress: String? =
        preferences.getString(KEY_LAST_CONNECTED_MAC, null)
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var reconnectFailureCount: Int =
        preferences.getInt(KEY_RECONNECT_FAILURES, 0)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.debug(TAG, "${tt()} onConnectionStateChange: connected")
                    reconnectJob?.cancel()
                    reconnecting.set(false)
                    missedHeartbeatCount = 0
                    setConnectionState(ConnectionState.CONNECTED)
                    updateState(gatt.device, G1ConnectionState.CONNECTED)
                    currentDeviceAddress = gatt.device.address
                    cacheLastConnected(gatt.device.address)
                    clearReconnectFailures()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    setConnectionState(ConnectionState.CONNECTING)
                    updateState(gatt.device, G1ConnectionState.CONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    setConnectionState(ConnectionState.DISCONNECTING)
                    updateState(gatt.device, G1ConnectionState.RECONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.debug(TAG, "${tt()} onConnectionStateChange: disconnected")
                    stopHeartbeat()
                    clearConnection()
                    setConnectionState(ConnectionState.DISCONNECTED)
                    val wasManual = manualDisconnect.getAndSet(false)
                    val targetState = if (wasManual) {
                        G1ConnectionState.DISCONNECTED
                    } else {
                        reconnecting.set(true)
                        G1ConnectionState.RECONNECTING
                    }
                    updateState(gatt.device, targetState)
                    if (!wasManual && isBluetoothEnabled()) {
                        scope.launch {
                            tryReconnect(context.applicationContext)
                        }
                    }
                }
                else -> {
                    logger.w(TAG, "${tt()} Unknown Bluetooth state $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.w(TAG, "${tt()} onServicesDiscovered: failure status=$status")
                setConnectionState(ConnectionState.ERROR)
                return
            }
            val uartService = gatt.getService(BluetoothConstants.UART_SERVICE_UUID)
            if (uartService == null) {
                logger.w(TAG, "${tt()} UART service not available on device")
                setConnectionState(ConnectionState.ERROR)
                return
            }
            initializeCharacteristics(gatt, uartService)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BluetoothConstants.UART_READ_CHARACTERISTIC_UUID) {
                val payload = characteristic.value
                writableIncoming.tryEmit(payload)
                awaitingAck.set(false)
                lastAckTimestamp = SystemClock.elapsedRealtime()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == batteryCharacteristic?.uuid && status == BluetoothGatt.GATT_SUCCESS) {
                val level = characteristic.value?.firstOrNull()?.toInt() ?: return
                updateBatteryLevel(level)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (writableConnectionState.value == ConnectionState.CONNECTING ||
            writableConnectionState.value == ConnectionState.CONNECTED
        ) {
            logger.debug(TAG, "${tt()} connect: already connected or connecting")
            return
        }
        addDevice(device)
        updateState(device, G1ConnectionState.CONNECTING)
        setConnectionState(ConnectionState.CONNECTING)
        currentDeviceAddress = device.address
        manualDisconnect.set(false)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logger.w(TAG, "${tt()} BluetoothAdapter unavailable; cannot connect to $address")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            logger.e(TAG, "${tt()} Invalid BLE address $address", error)
            return
        }
        connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun initializeCharacteristics(gatt: BluetoothGatt, service: BluetoothGattService) {
        writeCharacteristic = service.getCharacteristic(BluetoothConstants.UART_WRITE_CHARACTERISTIC_UUID)
        readCharacteristic = service.getCharacteristic(BluetoothConstants.UART_READ_CHARACTERISTIC_UUID)
        if (writeCharacteristic == null || readCharacteristic == null) {
            logger.w(TAG, "${tt()} Required UART characteristics not found")
            setConnectionState(ConnectionState.ERROR)
            return
        }
        val notified = enableNotifications(gatt, readCharacteristic)
        if (!notified) {
            logger.w(TAG, "${tt()} Unable to enable notifications")
            setConnectionState(ConnectionState.ERROR)
            return
        }
        bluetoothGatt = gatt
        startHeartbeat()
        maybeReadBatteryLevel(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        readChar: BluetoothGattCharacteristic?,
    ): Boolean {
        val characteristic = readChar ?: return false

        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (!ok) return false

        val cccd = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: return false

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gatt.writeDescriptor(cccd)
    }

    @SuppressLint("MissingPermission")
    private fun maybeReadBatteryLevel(gatt: BluetoothGatt) {
        val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
        val characteristic = batteryService?.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
        batteryCharacteristic = characteristic
        if (characteristic != null) {
            logger.debug(TAG, "${tt()} Battery service detected; requesting level")
            gatt.readCharacteristic(characteristic)
        } else {
            logger.debug(TAG, "${tt()} Battery service not exposed by device")
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        lastAckTimestamp = SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch {
            while (true) {
                val success = sendCommand(byteArrayOf(BluetoothConstants.OPCODE_HEARTBEAT))
                if (success) {
                    logger.heartbeat(TAG, "${tt()} heartbeat sent")
                    awaitingAck.set(true)
                    monitorHeartbeatTimeout()
                }
                delay(BluetoothConstants.HEARTBEAT_INTERVAL_SECONDS * 1000)
            }
        }
    }

    private fun monitorHeartbeatTimeout() {
        heartbeatTimeoutJob?.cancel()
        heartbeatTimeoutJob = scope.launch {
            delay(BluetoothConstants.HEARTBEAT_TIMEOUT_SECONDS * 1000)
            val elapsed = SystemClock.elapsedRealtime() - lastAckTimestamp
            if (awaitingAck.get() && elapsed >= BluetoothConstants.HEARTBEAT_TIMEOUT_SECONDS * 1000) {
                handleHeartbeatTimeout()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatTimeoutJob?.cancel()
        awaitingAck.set(false)
    }

    private fun handleHeartbeatTimeout() {
        missedHeartbeatCount += 1
        logger.heartbeat(TAG, "${tt()} Missed heartbeat #$missedHeartbeatCount — scheduling soft reconnect")
        reconnecting.set(true)
        manualDisconnect.set(false)
        bluetoothGatt?.disconnect() ?: scope.launch {
            tryReconnect(context.applicationContext)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopHeartbeat()
        setConnectionState(ConnectionState.DISCONNECTING)
        manualDisconnect.set(true)
        reconnectJob?.cancel()
        reconnecting.set(false)
        val address = currentDeviceAddress
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        clearConnection()
        setConnectionState(ConnectionState.DISCONNECTED)
        address?.let { deviceStates[it] = G1ConnectionState.DISCONNECTED }
        logger.i(TAG, "${tt()} disconnect requested")
    }

    private fun clearConnection() {
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
        batteryCharacteristic = null
        currentDeviceAddress = null
    }

    suspend fun sendText(message: String): Boolean {
        val payload = message.encodeToByteArray()
        if (payload.isEmpty()) {
            return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_SEND_TEXT))
        }
        var index = 0
        var success = true
        while (index < payload.size && success) {
            val remaining = payload.size - index
            val chunkSize = min(BluetoothConstants.MAX_CHUNK_SIZE - 1, remaining)
            val chunk = ByteArray(chunkSize + 1)
            chunk[0] = BluetoothConstants.OPCODE_SEND_TEXT
            System.arraycopy(payload, index, chunk, 1, chunkSize)
            index += chunkSize
            success = sendCommand(chunk)
        }
        return success
    }

    suspend fun clearScreen(): Boolean = sendCommand(byteArrayOf(BluetoothConstants.OPCODE_CLEAR_SCREEN))

    @SuppressLint("MissingPermission")
    private suspend fun sendCommand(payload: ByteArray): Boolean {
        return writeMutex.withLock {
            val gatt = bluetoothGatt ?: return@withLock false
            val characteristic = writeCharacteristic ?: return@withLock false
            characteristic.value = payload
            val success = try {
                gatt.writeCharacteristic(characteristic)
            } catch (t: Throwable) {
                logger.e(TAG, "${tt()} sendCommand: failed", t)
                false
            }
            if (!success) {
                logger.w(TAG, "${tt()} sendCommand: write failed")
            }
            success
        }
    }

    fun isConnected(): Boolean =
        writableConnectionState.value == ConnectionState.CONNECTED

    fun addDevice(device: BluetoothDevice) {
        if (subDevices.none { it.address == device.address }) {
            subDevices.add(device)
        }
        deviceStates[device.address] = deviceStates[device.address] ?: G1ConnectionState.DISCONNECTED
    }

    fun updateState(device: BluetoothDevice, newState: G1ConnectionState) {
        deviceStates[device.address] = newState
    }

    fun allConnected(): Boolean =
        deviceStates.isNotEmpty() && deviceStates.values.all { it == G1ConnectionState.CONNECTED }

    fun anyWaitingForReconnect(): Boolean =
        deviceStates.values.any { it == G1ConnectionState.RECONNECTING }

    fun resetDisconnected() {
        deviceStates.entries
            .filter { it.value == G1ConnectionState.RECONNECTING }
            .forEach { entry -> deviceStates[entry.key] = G1ConnectionState.DISCONNECTED }
    }

    suspend fun tryReconnect(context: Context): Boolean {
        reconnectJob?.cancel()
        reconnectAttempt = 0
        reconnecting.set(true)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logger.w(TAG, "${tt()} Bluetooth adapter unavailable; aborting reconnect")
            reconnecting.set(false)
            return false
        }
        val address = currentDeviceAddress ?: lastDeviceAddress
        if (address == null) {
            logger.w(TAG, "${tt()} No cached address for reconnect")
            reconnecting.set(false)
            return false
        }
        reconnectJob = scope.launch {
            while (isActive) {
                if (!adapter.isEnabled) {
                    logger.backoff(TAG, "Bluetooth disabled; cancelling reconnect loop")
                    break
                }
                if (manualDisconnect.get()) {
                    logger.backoff(TAG, "Manual disconnect detected; stopping reconnect loop")
                    break
                }
                val delaySeconds = min(1L shl (reconnectAttempt + 1), 60L)
                reconnectAttempt += 1
                logger.backoff(TAG, "Reconnecting attempt #$reconnectAttempt in ${delaySeconds}s")
                delay(delaySeconds * 1000)
                if (!adapter.isEnabled || manualDisconnect.get()) {
                    break
                }
                val targetAddress = currentDeviceAddress ?: lastDeviceAddress
                if (targetAddress == null) {
                    logger.backoff(TAG, "Reconnect loop lost address; exiting")
                    break
                }
                val device = runCatching { adapter.getRemoteDevice(targetAddress) }.getOrNull()
                if (device == null) {
                    logger.w(TAG, "${tt()} Unable to resolve device $targetAddress")
                    continue
                }
                addDevice(device)
                updateState(device, G1ConnectionState.CONNECTING)
                setConnectionState(ConnectionState.CONNECTING)
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
                val connected = awaitConnectionForTest(ConnectionState.CONNECTED)
                if (connected) {
                    reconnectFailureCount = 0
                    preferences.edit().putInt(KEY_RECONNECT_FAILURES, reconnectFailureCount).apply()
                    reconnecting.set(false)
                    return@launch
                }
                reconnectFailureCount += 1
                preferences.edit().putInt(KEY_RECONNECT_FAILURES, reconnectFailureCount).apply()
                if (reconnectFailureCount >= 3) {
                    logger.recovery(TAG, "Failed to reconnect 3 times; clearing cache")
                    clearCachedAddress()
                    reconnecting.set(false)
                    setConnectionState(ConnectionState.DISCONNECTED)
                    return@launch
                }
                if (reconnectAttempt >= 5) {
                    logger.recovery(TAG, "Reached maximum reconnect attempts")
                    setConnectionState(ConnectionState.DISCONNECTED)
                    reconnecting.set(false)
                    return@launch
                }
            }
            reconnecting.set(false)
        }
        try {
            reconnectJob?.join()
        } catch (_: CancellationException) {
            reconnecting.set(false)
            return false
        }
        reconnectJob = null
        return reconnectAttempt > 0 && connectionState.value == ConnectionState.CONNECTED
    }

    suspend fun awaitConnectionForTest(
        target: ConnectionState,
        timeoutMs: Long = RECONNECT_TIMEOUT_MS,
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            connectionState.first { it == target }
        } != null
    }

    fun getLastConnectedAddress(): String? = lastDeviceAddress

    fun getCachedConnectionState(): CachedConnectionState? {
        val name = preferences.getString(KEY_CONNECTION_STATE, null) ?: return null
        val timestamp = preferences.getLong(KEY_STATE_TIMESTAMP, 0L)
        val state = runCatching { ConnectionState.valueOf(name) }.getOrElse { return null }
        return CachedConnectionState(state, timestamp)
    }

    fun clearReconnectFailures() {
        reconnectFailureCount = 0
        reconnectAttempt = 0
        preferences.edit().putInt(KEY_RECONNECT_FAILURES, 0).apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun simulateConnectionCycleForTest(address: String, context: Context): Boolean {
        connectToAddress(address)
        val connected = awaitConnectionForTest(ConnectionState.CONNECTED)
        if (!connected) return false
        manualDisconnect.set(false)
        bluetoothGatt?.disconnect()
        return tryReconnect(context)
    }

    private fun updateBatteryLevel(level: Int) {
        if (level in 0..100) {
            batteryLevelState.value = level
            preferences.edit().putInt(KEY_LAST_BATTERY, level).apply()
            logger.debug(TAG, "${tt()} Battery level $level%")
        }
    }

    data class CachedConnectionState(
        val state: ConnectionState,
        val timestamp: Long,
    )

    private companion object {
        private val subDevices = mutableListOf<BluetoothDevice>()
        private val deviceStates = mutableMapOf<String, G1ConnectionState>()
        private val globalConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        private const val PREFS_NAME = "ble_device_manager"
        private const val KEY_LAST_CONNECTED_MAC = "last_connected_mac"
        private const val KEY_CONNECTION_STATE = "last_connection_state"
        private const val KEY_STATE_TIMESTAMP = "last_connection_state_ts"
        private const val KEY_RECONNECT_FAILURES = "reconnect_failures"
        private const val KEY_LAST_BATTERY = "last_battery_level"
        private const val RECONNECT_TIMEOUT_MS = 15_000L
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
    }

    private fun tt() = "[${Thread.currentThread().name}]"

    private fun setConnectionState(state: ConnectionState) {
        writableConnectionState.value = state
        persistConnectionState(state)
    }

    private fun persistConnectionState(state: ConnectionState) {
        preferences.edit()
            .putString(KEY_CONNECTION_STATE, state.name)
            .putLong(KEY_STATE_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun cacheLastConnected(address: String) {
        preferences.edit().putString(KEY_LAST_CONNECTED_MAC, address).apply()
        lastDeviceAddress = address
    }

    private fun clearCachedAddress() {
        preferences.edit().remove(KEY_LAST_CONNECTED_MAC).apply()
        lastDeviceAddress = null
    }

    private fun isBluetoothEnabled(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
}
