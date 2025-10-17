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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private const val DEVICE_MANAGER_TAG = "[DeviceManager]"
private const val RECONNECT_TAG = "[Reconnect]"

data class G1TelemetryEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val source: G1TelemetrySource,
    val message: String,
    val payload: ByteArray? = null,
)

enum class G1TelemetrySource {
    APP_SEND,
    DEVICE_SEND,
    SERVICE,
    SYSTEM,
}

class DeviceManager(
    private val context: Context,
) {
    private val logger by lazy { MoncchichiLogger(context) }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    val state: StateFlow<G1ConnectionState> = _stateFlow.asStateFlow()
    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()
    private val _nearbyDevices = MutableStateFlow<List<String>>(emptyList())
    val nearbyDevices: StateFlow<List<String>> = _nearbyDevices.asStateFlow()
    private val notificationEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val notifications: SharedFlow<ByteArray> = notificationEvents.asSharedFlow()
    private val _telemetryFlow = MutableStateFlow<List<G1TelemetryEvent>>(emptyList())
    val telemetryFlow: StateFlow<List<G1TelemetryEvent>> = _telemetryFlow.asStateFlow()

    private val transactionQueueLazy = lazy { G1TransactionQueue(scope, logger) }
    private val transactionQueue: G1TransactionQueue
        get() = transactionQueueLazy.value
    private val connectionMutex = Mutex()
    private val connectionWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var trackedDevice: BluetoothDevice? = null
    @Volatile
    private var isInitializing = false
    private var rssiJob: Job? = null
    private var scanCallback: ScanCallback? = null

    init {
        logger.i(
            DEVICE_MANAGER_TAG,
            "${tt()} Created at ${System.currentTimeMillis()}"
        )
    }

    private fun requireGatt(): BluetoothGatt? {
        if (gatt == null) {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} Gatt not initialized yet – skipping action")
            return null
        }
        return gatt
    }

    // NOTE: All BLE actions must check requireGatt() before use
    // This replaces previous lateinit usage to avoid Kotlin compiler crashes

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger.i(DEVICE_MANAGER_TAG, "${tt()} Gatt state change status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    this@DeviceManager.gatt = gatt
                    trackedDevice = gatt.device
                    updateState(G1ConnectionState.CONNECTED)
                    startRssiMonitor()
                    gatt.discoverServices()
                    completeWaiters(true)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    updateState(G1ConnectionState.CONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    updateState(G1ConnectionState.RECONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.w(DEVICE_MANAGER_TAG, "${tt()} Disconnected from ${gatt.device.address}")
                    cleanup()
                    val targetState = if (_stateFlow.value == G1ConnectionState.DISCONNECTED) {
                        G1ConnectionState.DISCONNECTED
                    } else {
                        G1ConnectionState.RECONNECTING
                    }
                    updateState(targetState)
                    completeWaiters(false)
                }
                else -> {
                    logger.w(DEVICE_MANAGER_TAG, "${tt()} Unknown Bluetooth state $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.w(DEVICE_MANAGER_TAG, "${tt()} Service discovery failed with status $status")
                updateState(G1ConnectionState.RECONNECTING)
                return
            }
            initializeCharacteristics(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val uuid = characteristic.uuid.toString()
            val payload = characteristic.value.copyOf()
            logger.debug(
                "[BLEEvent]",
                "${tt()} Characteristic changed: $uuid, value=${payload.toHexString()}",
            )
            logTelemetry(G1TelemetrySource.DEVICE_SEND, "Notify: $uuid", payload)
            notificationEvents.tryEmit(payload)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            } else {
                logger.w(DEVICE_MANAGER_TAG, "${tt()} RSSI read failed: status=$status")
            }
        }
    }

    suspend fun connect(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return connect(adapter.getRemoteDevice(address))
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean {
        trackedDevice = device
        return connectDevice(device)
    }

    fun currentDevice(): BluetoothDevice? = trackedDevice

    fun currentDeviceName(): String? = gatt?.device?.name

    fun notifyWaiting() {
        updateState(G1ConnectionState.RECONNECTING)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        logger.i(DEVICE_MANAGER_TAG, "${tt()} Manual disconnect requested")
        gatt?.disconnect()
        gatt?.close()
        cleanup()
        updateState(G1ConnectionState.DISCONNECTED)
    }

    suspend fun reconnect(maxRetries: Int = 5): Boolean {
        if (trackedDevice == null) return false
        updateState(G1ConnectionState.RECONNECTING)
        return reconnectWithBackoff(maxRetries)
    }

    suspend fun sendHeartbeat(): Boolean {
        return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_HEARTBEAT), "Heartbeat")
    }

    suspend fun clearScreen(): Boolean {
        return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_CLEAR_SCREEN), "ClearScreen")
    }

    suspend fun sendText(message: String): Boolean {
        val payload = message.encodeToByteArray()
        if (payload.isEmpty()) return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_SEND_TEXT), "SendTextEmpty")
        var offset = 0
        while (offset < payload.size) {
            val chunkLength = min(BluetoothConstants.MAX_CHUNK_SIZE - 1, payload.size - offset)
            val chunk = ByteArray(chunkLength + 1)
            chunk[0] = BluetoothConstants.OPCODE_SEND_TEXT
            System.arraycopy(payload, offset, chunk, 1, chunkLength)
            val success = sendCommand(chunk, "SendTextChunk")
            if (!success) return false
            offset += chunkLength
        }
        return true
    }

    suspend fun sendRawCommand(payload: ByteArray, label: String = "RawCommand"): Boolean {
        return sendCommand(payload, label)
    }

    fun close() {
        if (transactionQueueLazy.isInitialized()) {
            transactionQueue.close()
        }
        disconnect()
        shutdown()
    }

    fun shutdown() = scope.cancel("DeviceManager destroyed")

    @SuppressLint("MissingPermission")
    fun startRssiMonitor() {
        if (rssiJob?.isActive == true) return
        rssiJob = scope.launch {
            while (isActive) {
                try {
                    gatt?.readRemoteRssi()
                } catch (t: Throwable) {
                    logger.w(DEVICE_MANAGER_TAG, "${tt()} Failed to request RSSI", t)
                }
                delay(2_000L)
            }
        }
    }

    fun stopRssiMonitor() {
        rssiJob?.cancel()
        rssiJob = null
        _rssi.value = null
    }

    @SuppressLint("MissingPermission")
    fun startScanNearbyDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        scanCallback?.let { scanner.stopScan(it) }
        val resultList = mutableSetOf<String>()
        _nearbyDevices.value = emptyList()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.name?.takeIf { it.isNotBlank() }?.let { name ->
                    if (resultList.add(name)) {
                        _nearbyDevices.value = resultList.toList()
                    }
                }
            }
        }

        scanCallback = callback
        scanner.startScan(callback)
        scope.launch {
            delay(8_000L)
            runCatching { scanner.stopScan(callback) }
            if (scanCallback === callback) {
                scanCallback = null
            }
            _nearbyDevices.value = resultList.toList()
            logger.i(DEVICE_MANAGER_TAG, "${tt()} Scan finished: ${resultList.size} devices found")
        }
    }

    private suspend fun sendCommand(payload: ByteArray, label: String): Boolean {
        logTelemetry(G1TelemetrySource.APP_SEND, "SendCommand: $label", payload)
        return transactionQueue.run(label) {
            writePayload(payload)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writePayload(payload: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false.also {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} writePayload skipped; characteristic not ready")
        }
        characteristic.value = payload
        return try {
            requireGatt()?.writeCharacteristic(characteristic) ?: run {
                logger.w(DEVICE_MANAGER_TAG, "${tt()} writePayload skipped; GATT is null")
                false
            }
        } catch (t: Throwable) {
            logger.e(DEVICE_MANAGER_TAG, "${tt()} writeCharacteristic failed", t)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeCharacteristics(gatt: BluetoothGatt) {
        val service: BluetoothGattService? = gatt.getService(BluetoothConstants.UART_SERVICE_UUID)
        if (service == null) {
            logger.e(DEVICE_MANAGER_TAG, "${tt()} UART service missing")
            updateState(G1ConnectionState.RECONNECTING)
            return
        }

        writeCharacteristic = service.getCharacteristic(BluetoothConstants.UART_WRITE_CHARACTERISTIC_UUID)
        notifyCharacteristic = service.getCharacteristic(BluetoothConstants.UART_READ_CHARACTERISTIC_UUID)

        if (writeCharacteristic == null || notifyCharacteristic == null) {
            logger.e(DEVICE_MANAGER_TAG, "${tt()} UART characteristics missing")
            updateState(G1ConnectionState.RECONNECTING)
            return
        }

        val enabled = gatt.setCharacteristicNotification(notifyCharacteristic, true)
        if (!enabled) {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} Failed to enable notifications")
            return
        }

        val cccd = notifyCharacteristic!!.getDescriptor(BluetoothConstants.CCCD_UUID)
        if (cccd == null) {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} Missing CCCD descriptor on notify characteristic")
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wrote = try {
            requireGatt()?.writeDescriptor(cccd) ?: false
        } catch (t: Throwable) {
            logger.e(DEVICE_MANAGER_TAG, "${tt()} writeDescriptor(CCCD) failed", t)
            false
        }

        if (!wrote) {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} Failed to write CCCD descriptor")
            return
        }

        logger.i(DEVICE_MANAGER_TAG, "${tt()} Notifications enabled via CCCD")
    }

    private fun cleanup() {
        writeCharacteristic = null
        notifyCharacteristic = null
        gatt = null
        stopRssiMonitor()
        _nearbyDevices.value = emptyList()
    }

    private fun updateState(newState: G1ConnectionState) {
        if (_stateFlow.value == newState) return
        logger.i(DEVICE_MANAGER_TAG, "${tt()} State ${_stateFlow.value} -> $newState")
        _stateFlow.value = newState
    }

    private fun completeWaiters(success: Boolean) {
        if (connectionWaiters.isEmpty()) return
        connectionWaiters.toList().forEach { waiter ->
            if (!waiter.isCompleted) {
                waiter.complete(success)
            }
        }
        connectionWaiters.clear()
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectDevice(device: BluetoothDevice): Boolean {
        val proceed = synchronized(this) {
            if (isInitializing) {
                logger.w(DEVICE_MANAGER_TAG, "${tt()} connectDevice skipped: initialization in progress")
                false
            } else {
                isInitializing = true
                true
            }
        }
        if (!proceed) return false
        return try {
            connectionMutex.withLock {
                updateState(G1ConnectionState.CONNECTING)
                logger.i(DEVICE_MANAGER_TAG, "${tt()} Connecting to ${device.address}")
                logTelemetry(G1TelemetrySource.SERVICE, "Connecting to ${device.address}")
                gatt?.close()
                gatt = device.connectGatt(context, false, gattCallback)
                if (gatt == null) {
                    logger.e(DEVICE_MANAGER_TAG, "${tt()} connectGatt returned null")
                    updateState(G1ConnectionState.DISCONNECTED)
                    false
                } else {
                    val deferred = CompletableDeferred<Boolean>()
                    connectionWaiters += deferred
                    withTimeoutOrNull(20_000L) { deferred.await() } ?: false
                }
            }
        } finally {
            isInitializing = false
        }
    }

    private suspend fun reconnectWithBackoff(maxRetries: Int = 5): Boolean {
        var delayMs = 2_000L
        repeat(maxRetries) { attempt ->
            logger.debug(RECONNECT_TAG, "${tt()} Attempt ${attempt + 1}")
            logTelemetry(G1TelemetrySource.SYSTEM, "Reconnecting attempt #${attempt + 1}")
            if (tryReconnectInternal()) return true
            delay(delayMs)
            delayMs = min(delayMs * 2, 30_000L)
        }
        updateState(G1ConnectionState.DISCONNECTED)
        return false
    }

    private suspend fun tryReconnectInternal(): Boolean {
        val device = trackedDevice ?: return false
        return connectDevice(device)
    }

    @SuppressLint("MissingPermission")
    suspend fun resilientReconnect(maxRetries: Int = 5, delayMs: Long = 8000L) {
        var attempt = 0
        while (attempt < maxRetries && _stateFlow.value != G1ConnectionState.CONNECTED) {
            attempt++
            logger.w(RECONNECT_TAG, "${tt()} reconnect attempt $attempt/$maxRetries")
            val success = reconnect()
            if (success) {
                logger.i(RECONNECT_TAG, "${tt()} reconnect succeeded after $attempt attempts")
                updateState(G1ConnectionState.CONNECTED)
                break
            }
            delay(delayMs)
        }
        if (_stateFlow.value != G1ConnectionState.CONNECTED) {
            logger.e(RECONNECT_TAG, "${tt()} reconnect failed after $maxRetries attempts")
            updateState(G1ConnectionState.DISCONNECTED)
        }
    }

    fun tryReconnect() {
        val device = trackedDevice ?: return
        updateState(G1ConnectionState.RECONNECTING)
        scope.launch {
            reconnectWithBackoff()
        }
    }

    private fun logTelemetry(source: G1TelemetrySource, message: String, payload: ByteArray? = null) {
        val event = G1TelemetryEvent(System.currentTimeMillis(), source, message, payload)
        val updated = (_telemetryFlow.value + event).takeLast(500)
        _telemetryFlow.value = updated
        logger.i("[Telemetry]", "$source → $message (${payload?.size ?: 0} bytes)")
    }

    private fun ByteArray?.toHexString(): String {
        val data = this ?: return "null"
        return data.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
        }
    }

    private fun tt() = "[${Thread.currentThread().name}]"
}
