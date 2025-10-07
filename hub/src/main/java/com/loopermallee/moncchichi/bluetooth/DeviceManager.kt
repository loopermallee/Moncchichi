package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private const val DEVICE_MANAGER_TAG = "[DeviceManager]"
private const val RECONNECT_TAG = "[Reconnect]"

class DeviceManager(
    private val context: Context,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    val state: StateFlow<G1ConnectionState> = _stateFlow.asStateFlow()

    private val transactionQueue = G1TransactionQueue(scope)
    private val connectionMutex = Mutex()
    private val connectionWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var trackedDevice: BluetoothDevice? = null
    @Volatile
    private var isInitializing = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            MoncchichiLogger.i(DEVICE_MANAGER_TAG, "Gatt state change status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    updateState(G1ConnectionState.CONNECTED)
                    trackedDevice = gatt.device
                    gatt.discoverServices()
                    completeWaiters(true)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    updateState(G1ConnectionState.RECONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    MoncchichiLogger.w(DEVICE_MANAGER_TAG, "Disconnected from ${gatt.device.address}")
                    cleanup()
                    updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
                    completeWaiters(false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                MoncchichiLogger.w(DEVICE_MANAGER_TAG, "Service discovery failed with status $status")
                updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
                return
            }
            initializeCharacteristics(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val uuid = characteristic.uuid.toString()
            MoncchichiLogger.debug(
                "[BLEEvent]",
                "Characteristic changed: $uuid, value=${characteristic.value.toHexString()}",
            )
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

    fun notifyWaiting() {
        updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        MoncchichiLogger.i(DEVICE_MANAGER_TAG, "Manual disconnect requested")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        cleanup()
        updateState(G1ConnectionState.DISCONNECTED)
    }

    suspend fun reconnect(maxRetries: Int = 5): Boolean {
        if (trackedDevice == null) return false
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

    fun close() {
        transactionQueue.close()
        disconnect()
        shutdown()
    }

    fun shutdown() = scope.cancel("DeviceManager destroyed")

    private suspend fun sendCommand(payload: ByteArray, label: String): Boolean {
        return transactionQueue.run(label) {
            writePayload(payload)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writePayload(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false.also {
            MoncchichiLogger.w(DEVICE_MANAGER_TAG, "writePayload called with null gatt")
        }
        val characteristic = writeCharacteristic ?: return false.also {
            MoncchichiLogger.w(DEVICE_MANAGER_TAG, "writePayload called before init")
        }
        characteristic.value = payload
        return try {
            gatt.writeCharacteristic(characteristic)
        } catch (t: Throwable) {
            MoncchichiLogger.e(DEVICE_MANAGER_TAG, "writeCharacteristic failed", t)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeCharacteristics(gatt: BluetoothGatt) {
        val service: BluetoothGattService? = gatt.getService(BluetoothConstants.UART_SERVICE_UUID)
        if (service == null) {
            MoncchichiLogger.e(DEVICE_MANAGER_TAG, "UART service missing")
            updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
            return
        }
        writeCharacteristic = service.getCharacteristic(BluetoothConstants.UART_WRITE_CHARACTERISTIC_UUID)
        notifyCharacteristic = service.getCharacteristic(BluetoothConstants.UART_READ_CHARACTERISTIC_UUID)
        if (writeCharacteristic == null || notifyCharacteristic == null) {
            MoncchichiLogger.e(DEVICE_MANAGER_TAG, "UART characteristics missing")
            updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
            return
        }
        val enabled = gatt.setCharacteristicNotification(notifyCharacteristic, true)
        if (!enabled) {
            MoncchichiLogger.w(DEVICE_MANAGER_TAG, "Failed to enable notifications")
        } else {
            MoncchichiLogger.i(DEVICE_MANAGER_TAG, "Notifications enabled")
        }
    }

    private fun cleanup() {
        writeCharacteristic = null
        notifyCharacteristic = null
        bluetoothGatt = null
    }

    private fun updateState(newState: G1ConnectionState) {
        if (_stateFlow.value == newState) return
        MoncchichiLogger.i(DEVICE_MANAGER_TAG, "State ${_stateFlow.value} -> $newState")
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
                MoncchichiLogger.w(DEVICE_MANAGER_TAG, "connectDevice skipped: initialization in progress")
                false
            } else {
                isInitializing = true
                true
            }
        }
        if (!proceed) return false
        return try {
            connectionMutex.withLock {
                updateState(G1ConnectionState.RECONNECTING)
                MoncchichiLogger.i(DEVICE_MANAGER_TAG, "Connecting to ${device.address}")
                bluetoothGatt?.close()
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
                if (bluetoothGatt == null) {
                    MoncchichiLogger.e(DEVICE_MANAGER_TAG, "connectGatt returned null")
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
            MoncchichiLogger.debug(RECONNECT_TAG, "Attempt ${attempt + 1}")
            if (tryReconnectInternal()) return true
            delay(delayMs)
            delayMs = min(delayMs * 2, 30_000L)
        }
        updateState(G1ConnectionState.WAITING_FOR_RECONNECT)
        return false
    }

    private suspend fun tryReconnectInternal(): Boolean {
        val device = trackedDevice ?: return false
        return connectDevice(device)
    }

    fun tryReconnect() {
        if (trackedDevice == null) return
        scope.launch {
            reconnectWithBackoff()
        }
    }

    private fun ByteArray?.toHexString(): String {
        val data = this ?: return "null"
        return data.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
        }
    }
}
