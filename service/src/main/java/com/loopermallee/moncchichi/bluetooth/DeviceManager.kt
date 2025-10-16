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
import android.os.SystemClock
import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private const val TAG = "DeviceManager"

internal class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val logger by lazy { MoncchichiLogger(context) }
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR,
    }

    private val writableConnectionState = globalConnectionState
    val connectionState = writableConnectionState.asStateFlow()

    private val writableIncoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val incoming = writableIncoming.asSharedFlow()

    private val writeMutex = Mutex()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null
    private val awaitingAck = AtomicBoolean(false)
    private val manualDisconnect = AtomicBoolean(false)
    private var lastAckTimestamp: Long = 0L
    private var currentDeviceAddress: String? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.debug(TAG, "${tt()} onConnectionStateChange: connected")
                    writableConnectionState.value = ConnectionState.CONNECTED
                    updateState(gatt.device, G1ConnectionState.CONNECTED)
                    currentDeviceAddress = gatt.device.address
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    writableConnectionState.value = ConnectionState.CONNECTING
                    updateState(gatt.device, G1ConnectionState.CONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    writableConnectionState.value = ConnectionState.DISCONNECTING
                    updateState(gatt.device, G1ConnectionState.RECONNECTING)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.debug(TAG, "${tt()} onConnectionStateChange: disconnected")
                    stopHeartbeat()
                    clearConnection()
                    writableConnectionState.value = ConnectionState.DISCONNECTED
                    val targetState = if (manualDisconnect.getAndSet(false)) {
                        G1ConnectionState.DISCONNECTED
                    } else {
                        G1ConnectionState.RECONNECTING
                    }
                    updateState(gatt.device, targetState)
                }
                else -> {
                    logger.w(TAG, "${tt()} Unknown Bluetooth state $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.w(TAG, "${tt()} onServicesDiscovered: failure status=$status")
                writableConnectionState.value = ConnectionState.ERROR
                return
            }
            val uartService = gatt.getService(BluetoothConstants.UART_SERVICE_UUID)
            if (uartService == null) {
                logger.w(TAG, "${tt()} UART service not available on device")
                writableConnectionState.value = ConnectionState.ERROR
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
        writableConnectionState.value = ConnectionState.CONNECTING
        currentDeviceAddress = device.address
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
            writableConnectionState.value = ConnectionState.ERROR
            return
        }
        val notified = enableNotifications(gatt, readCharacteristic)
        if (!notified) {
            logger.w(TAG, "${tt()} Unable to enable notifications")
            writableConnectionState.value = ConnectionState.ERROR
            return
        }
        bluetoothGatt = gatt
        startHeartbeat()
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

    private fun startHeartbeat() {
        stopHeartbeat()
        lastAckTimestamp = SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch {
            while (true) {
                val success = sendCommand(byteArrayOf(BluetoothConstants.OPCODE_HEARTBEAT))
                if (success) {
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
                logger.w(TAG, "${tt()} Heartbeat timeout reached, disconnecting")
                disconnect()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatTimeoutJob?.cancel()
        awaitingAck.set(false)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopHeartbeat()
        writableConnectionState.value = ConnectionState.DISCONNECTING
        manualDisconnect.set(true)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        clearConnection()
        writableConnectionState.value = ConnectionState.DISCONNECTED
        currentDeviceAddress?.let { address ->
            deviceStates[address] = G1ConnectionState.DISCONNECTED
        }
        logger.i(TAG, "${tt()} disconnect requested")
    }

    private fun clearConnection() {
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
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

    suspend fun tryReconnect(
        context: Context,
        maxAttempts: Int = 5,
        intervalMs: Long = 10_000L,
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            logger.i(TAG, "${tt()} Reconnect attempt ${attempt + 1} of $maxAttempts")
            var success = false
            for (device in subDevices) {
                try {
                    updateState(device, G1ConnectionState.CONNECTING)
                    val gatt = device.connectGatt(context, false, gattCallback)
                    if (gatt != null) {
                        bluetoothGatt = gatt
                        currentDeviceAddress = device.address
                        logger.i(TAG, "${tt()} Reconnected to ${device.address}")
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    logger.w(TAG, "${tt()} Reconnect failed: ${e.message}")
                }
            }
            if (success) {
                return true
            }
            delay(intervalMs)
        }
        logger.e(TAG, "${tt()} All reconnect attempts failed.")
        return false
    }

    private companion object {
        private val subDevices = mutableListOf<BluetoothDevice>()
        private val deviceStates = mutableMapOf<String, G1ConnectionState>()
        private val globalConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    }

    private fun tt() = "[${Thread.currentThread().name}]"
}
