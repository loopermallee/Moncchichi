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
import com.loopermallee.moncchichi.core.BmpPacketBuilder
import com.loopermallee.moncchichi.core.EvenAiScreenStatus
import com.loopermallee.moncchichi.core.SendTextPacketBuilder
import com.loopermallee.moncchichi.core.text.TextPaginator
import com.loopermallee.moncchichi.core.ble.DeviceVitals
import com.loopermallee.moncchichi.core.ble.G1ReplyParser
import com.loopermallee.moncchichi.telemetry.G1TelemetryEvent
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets
import kotlin.concurrent.withLock

private const val TAG = "DeviceManager"
private const val MTU_COMMAND_MAX_RETRIES = 3
private const val MTU_COMMAND_RETRY_DELAY_MS = 150L
private const val IMAGE_ACK_TIMEOUT_MS = 3_000L

internal class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val logger by lazy { MoncchichiLogger(context) }
    private val _telemetryFlow = MutableStateFlow<List<G1TelemetryEvent>>(emptyList())
    val telemetry: StateFlow<List<G1TelemetryEvent>> = _telemetryFlow.asStateFlow()
    private val _vitals = MutableStateFlow(DeviceVitals())
    val vitals: StateFlow<DeviceVitals> = _vitals.asStateFlow()

    private fun logTelemetry(source: String, tag: String, message: String) {
        val event = G1TelemetryEvent(source = source, tag = tag, message = message)
        _telemetryFlow.value = (_telemetryFlow.value + event).takeLast(200)
        logger.i(source, event.toString())
    }
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
    private var currentMtu = BluetoothConstants.DEFAULT_MTU

    private var periodicVitalsJob: Job? = null
    private val queryStateLock = ReentrantLock()
    private val queryStates = EnumMap<QueryToken, PendingQueryState>(QueryToken::class.java)

    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null
    private var heartbeatSequence = 0
    private val awaitingAck = AtomicBoolean(false)
    private val manualDisconnect = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val hasConnectedSuccessfully = AtomicBoolean(false)
    private var missedHeartbeatCount = 0
    private var lastAckTimestamp: Long = 0L
    private var currentDeviceAddress: String? = null
    private var lastDeviceAddress: String? =
        preferences.getString(KEY_LAST_CONNECTED_MAC, null)
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var reconnectFailureCount: Int =
        preferences.getInt(KEY_RECONNECT_FAILURES, 0)
    private var notifyCallback: ((service: UUID, characteristic: UUID, value: ByteArray) -> Unit)? = null
    private val textPacketBuilder = SendTextPacketBuilder()
    private val bmpPacketBuilder = BmpPacketBuilder()
    private val textPaginator = TextPaginator()

    private enum class QueryToken { BATTERY, FIRMWARE }

    private data class PendingQueryState(
        var attempts: Int = 0,
        var job: Job? = null,
    )

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.debug(TAG, "${tt()} onConnectionStateChange: connected")
                    reconnectJob?.cancel()
                    reconnecting.set(false)
                    missedHeartbeatCount = 0
                    hasConnectedSuccessfully.set(true)
                    setConnectionState(ConnectionState.CONNECTED)
                    updateState(gatt.device, G1ConnectionState.CONNECTED)
                    currentDeviceAddress = gatt.device.address
                    cacheLastConnected(gatt.device.address)
                    clearReconnectFailures()
                    gatt.discoverServices()
                    textPacketBuilder.resetSequence()
                    resetHeartbeatSequence()
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
                    stopPeriodicVitals()
                    resetPendingQuery()
                    clearConnection()
                    setConnectionState(ConnectionState.DISCONNECTED)
                    val wasManual = manualDisconnect.getAndSet(false)
                    val shouldAttemptReconnect = !wasManual && hasConnectedSuccessfully.get()
                    val targetState = when {
                        wasManual -> G1ConnectionState.DISCONNECTED
                        shouldAttemptReconnect -> {
                            reconnecting.set(true)
                            G1ConnectionState.RECONNECTING
                        }
                        else -> G1ConnectionState.DISCONNECTED
                    }
                    updateState(gatt.device, targetState)
                    textPacketBuilder.resetSequence()
                    resetHeartbeatSequence()
                    if (shouldAttemptReconnect && isBluetoothEnabled()) {
                        scope.launch {
                            tryReconnect(context.applicationContext)
                        }
                    } else if (!shouldAttemptReconnect) {
                        reconnecting.set(false)
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
            val desiredMtu = BluetoothConstants.DESIRED_MTU
            val requested = gatt.requestMtu(desiredMtu)
            if (requested) {
                logTelemetry("SERVICE", "[MTU]", "Requested MTU negotiation to $desiredMtu")
            } else {
                logTelemetry("SERVICE", "[MTU]", "Failed to request MTU $desiredMtu")
            }
            initializeCharacteristics(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            val payload = value.copyOf()
            val uuid = characteristic.uuid
            val uuidString = uuid.toString()
            val hex = value.joinToString(" ") { "%02X".format(it) }

            logTelemetry("DEVICE", "[NOTIFY]", "Notification from $uuidString → [$hex]")

            if (uuid == BluetoothConstants.UART_READ_CHARACTERISTIC_UUID) {
                val text = payload.toString(Charsets.UTF_8)
                logger.i("[UART_RX]", "${tt()} Received: $text ($hex)")
                val parsed = G1ReplyParser.parse(payload)
                if (parsed != null) {
                    val current = _vitals.value
                    val merged = DeviceVitals(
                        batteryPercent = parsed.batteryPercent ?: current.batteryPercent,
                        caseBatteryPercent = parsed.caseBatteryPercent ?: current.caseBatteryPercent,
                        firmwareVersion = parsed.firmwareVersion ?: current.firmwareVersion,
                        signalRssi = parsed.signalRssi ?: current.signalRssi,
                        deviceId = parsed.deviceId ?: current.deviceId,
                        connectionState = parsed.connectionState ?: current.connectionState,
                    )
                    if (merged != current) {
                        _vitals.value = merged
                    }
                    parsed.batteryPercent?.let { level ->
                        completePendingQuery(QueryToken.BATTERY)
                        logTelemetry("DEVICE", "[BATTERY]", "Battery = ${level}%")
                        updateBatteryLevel(level)
                    }
                    parsed.caseBatteryPercent?.let { case ->
                        logTelemetry("DEVICE", "[CASE]", "Case battery = ${case}%")
                    }
                    parsed.firmwareVersion?.let { fw ->
                        completePendingQuery(QueryToken.FIRMWARE)
                        logTelemetry("DEVICE", "[FIRMWARE]", "Firmware = $fw")
                    }
                    parsed.signalRssi?.let { rssi ->
                        logTelemetry("DEVICE", "[SIGNAL]", "RSSI = ${rssi} dBm")
                    }
                    parsed.deviceId?.let { id ->
                        logTelemetry("DEVICE", "[HANDSHAKE]", "Device ID = $id")
                    }
                    parsed.connectionState?.let { state ->
                        logTelemetry("DEVICE", "[STATE]", "Connection state = $state")
                    }
                } else {
                    if (value.firstOrNull() == BluetoothConstants.OPCODE_GLASSES_INFO) {
                        when (value.getOrNull(1)?.toUByte()?.toInt()) {
                            0x01, 0x66 -> {
                                val battery = value.getOrNull(2)?.toUByte()?.toInt() ?: -1
                                logTelemetry("DEVICE", "[BATTERY]", "Battery update: ${battery}%")
                                if (battery in 0..100) {
                                    completePendingQuery(QueryToken.BATTERY)
                                    updateBatteryLevel(battery)
                                }
                            }
                            0x02 -> {
                                val fw = value.copyOfRange(2, value.size)
                                    .toString(Charsets.UTF_8)
                                    .trim { it <= ' ' }
                                logTelemetry("DEVICE", "[FIRMWARE]", "Firmware: v$fw")
                                if (fw.isNotEmpty()) {
                                    completePendingQuery(QueryToken.FIRMWARE)
                                    _vitals.value = _vitals.value.copy(firmwareVersion = fw)
                                }
                            }
                        }
                    }
                }

                writableIncoming.tryEmit(payload)
                awaitingAck.set(false)
                lastAckTimestamp = SystemClock.elapsedRealtime()
            }

            notifyCallback?.invoke(
                characteristic.service?.uuid ?: BluetoothConstants.UART_SERVICE_UUID,
                characteristic.uuid,
                payload,
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == batteryCharacteristic?.uuid && status == BluetoothGatt.GATT_SUCCESS) {
                val level = characteristic.value?.firstOrNull()?.toInt() ?: return
                completePendingQuery(QueryToken.BATTERY)
                updateBatteryLevel(level)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logger.debug(TAG, "${tt()} onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                logTelemetry("SERVICE", "[MTU]", "Negotiated MTU=$mtu")
                scope.launch {
                    sendSetMtuCommand(mtu)
                }
            } else {
                logTelemetry("SERVICE", "[MTU]", "MTU negotiation failed with status=$status")
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
        if (lastDeviceAddress != device.address) {
            hasConnectedSuccessfully.set(false)
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
    private fun initializeCharacteristics(gatt: BluetoothGatt) {
        val service: BluetoothGattService? = gatt.getService(BluetoothConstants.UART_SERVICE_UUID)
        if (service == null) {
            logTelemetry("SERVICE", "[SERVICES]", "UART service missing")
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        writeCharacteristic = service.getCharacteristic(BluetoothConstants.UART_WRITE_CHARACTERISTIC_UUID)
        val notifyCharacteristic = service.getCharacteristic(BluetoothConstants.UART_READ_CHARACTERISTIC_UUID)
        readCharacteristic = notifyCharacteristic

        if (writeCharacteristic == null || notifyCharacteristic == null) {
            logTelemetry("SERVICE", "[SERVICES]", "UART characteristics missing")
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        val enabled = gatt.setCharacteristicNotification(notifyCharacteristic, true)
        if (!enabled) {
            logTelemetry("SERVICE", "[NOTIFY]", "Failed to enable notifications")
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        val cccd = notifyCharacteristic.getDescriptor(BluetoothConstants.CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val success = gatt.writeDescriptor(cccd)
            if (!success) {
                logTelemetry("SERVICE", "[NOTIFY]", "Failed to write CCCD descriptor")
                updateState(gatt.device, G1ConnectionState.RECONNECTING)
                setConnectionState(ConnectionState.ERROR)
                return
            } else {
                logTelemetry("SERVICE", "[NOTIFY]", "CCCD descriptor written successfully")
            }
        } else {
            logTelemetry("SERVICE", "[NOTIFY]", "Missing CCCD descriptor on notify characteristic")
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        logTelemetry("SERVICE", "[NOTIFY]", "Notifications enabled for UART_READ_CHARACTERISTIC")
        bluetoothGatt = gatt
        setConnectionState(ConnectionState.CONNECTED)
        updateState(gatt.device, G1ConnectionState.CONNECTED)
        startHeartbeat()
        startPeriodicVitals()
        scope.launch {
            logTelemetry("APP", "[VITALS]", "Auto querying vitals post-connect")
            queryBattery()
            delay(200)
            queryFirmware()
        }
        maybeReadBatteryLevel(gatt)
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
                val success = sendCommand(buildHeartbeatPayload())
                if (success) {
                    logger.heartbeat(
                        TAG,
                        "${tt()} heartbeat sent seq=${formatHeartbeatSequence()}"
                    )
                    awaitingAck.set(true)
                    monitorHeartbeatTimeout()
                }
                delay(BluetoothConstants.HEARTBEAT_INTERVAL_SECONDS * 1000)
            }
        }
    }

    private fun buildHeartbeatPayload(): ByteArray {
        return byteArrayOf(
            BluetoothConstants.OPCODE_HEARTBEAT,
            nextHeartbeatSequence(),
        )
    }

    private fun nextHeartbeatSequence(): Byte {
        heartbeatSequence = (heartbeatSequence + 1) and 0xFF
        return heartbeatSequence.toByte()
    }

    private fun resetHeartbeatSequence() {
        heartbeatSequence = 0
    }

    private fun formatHeartbeatSequence(): String = "0x%02X".format(heartbeatSequence)

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
        stopPeriodicVitals()
        resetPendingQuery()
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
        resetHeartbeatSequence()
        currentMtu = BluetoothConstants.DEFAULT_MTU
        logger.i(TAG, "${tt()} disconnect requested")
    }

    private fun clearConnection() {
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
        batteryCharacteristic = null
        currentDeviceAddress = null
        currentMtu = BluetoothConstants.DEFAULT_MTU
    }

    private fun startPeriodicVitals() {
        periodicVitalsJob?.cancel()
        periodicVitalsJob = scope.launch {
            while (isActive) {
                if (writableConnectionState.value != ConnectionState.CONNECTED) {
                    break
                }
                queryBattery()
                delay(250)
                queryFirmware()
                delay(VITALS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPeriodicVitals() {
        periodicVitalsJob?.cancel()
        periodicVitalsJob = null
    }

    private fun resetPendingQuery(token: QueryToken) {
        val job = queryStateLock.withLock { queryStates.remove(token)?.job }
        job?.cancel()
    }

    private fun resetPendingQuery() {
        val jobs = queryStateLock.withLock {
            val running = queryStates.values.mapNotNull { it.job }
            queryStates.clear()
            running
        }
        jobs.forEach { it.cancel() }
    }

    private fun prepareQuery(token: QueryToken) {
        val previous = queryStateLock.withLock {
            val state = queryStates.getOrPut(token) { PendingQueryState() }
            val job = state.job
            state.job = null
            state.attempts = 0
            job
        }
        previous?.cancel()
    }

    private fun scheduleQueryTimeout(token: QueryToken) {
        val attempts = queryStateLock.withLock {
            val state = queryStates.getOrPut(token) { PendingQueryState() }
            val previous = state.job
            state.job = null
            previous?.cancel()
            state.attempts += 1
            state.attempts
        }
        val job = scope.launch {
            delay(QUERY_TIMEOUT_MS)
            var attemptNumber = 0
            val giveUp = queryStateLock.withLock {
                val state = queryStates[token] ?: return@launch
                if (state.attempts < QUERY_MAX_RETRIES) {
                    attemptNumber = state.attempts
                    state.job = null
                    false
                } else {
                    queryStates.remove(token)
                    true
                }
            }
            if (giveUp) {
                logTelemetry(
                    "SERVICE",
                    "[VITALS]",
                    "Giving up on ${token.name} after $QUERY_MAX_RETRIES attempts",
                )
                return@launch
            }
            logTelemetry(
                "SERVICE",
                "[VITALS]",
                "Timeout waiting for ${token.name} reply; retry #$attemptNumber",
            )
            ensureNotifications()
            when (token) {
                QueryToken.BATTERY -> queryBattery(fromRetry = true)
                QueryToken.FIRMWARE -> queryFirmware(fromRetry = true)
            }
        }
        queryStateLock.withLock {
            val state = queryStates.getOrPut(token) { PendingQueryState() }
            state.job?.cancel()
            state.job = job
            state.attempts = attempts
        }
    }

    private fun completePendingQuery(token: QueryToken) {
        resetPendingQuery(token)
    }

    @SuppressLint("MissingPermission")
    private fun ensureNotifications() {
        val gatt = bluetoothGatt ?: return
        val characteristic = readCharacteristic ?: return
        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!enabled) {
            logTelemetry("SERVICE", "[NOTIFY]", "Failed to re-enable notifications during retry")
            return
        }
        val descriptor = characteristic.getDescriptor(BluetoothConstants.CCCD_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            logTelemetry("SERVICE", "[NOTIFY]", "Failed to rewrite CCCD during retry")
        }
    }

    suspend fun sendText(message: String): Boolean {
        val mtuPayloadCapacity = BluetoothConstants.payloadCapacityFor(currentMtu)
        val chunkCapacity = (mtuPayloadCapacity - SendTextPacketBuilder.HEADER_SIZE)
            .coerceAtLeast(1)
        val pagination = textPaginator.paginate(message)
        val packets = pagination.packets
        val totalPages = packets.size.coerceAtLeast(1)
        data class PageFrame(
            val pageIndex: Int,
            val totalPages: Int,
            val packageIndex: Int,
            val totalPackages: Int,
            val bytes: ByteArray,
        )
        fun chunkPacket(bytes: ByteArray): List<ByteArray> {
            if (bytes.isEmpty()) return listOf(ByteArray(0))
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < bytes.size) {
                val end = min(bytes.size, offset + chunkCapacity)
                chunks += bytes.copyOfRange(offset, end)
                offset = end
            }
            return chunks
        }
        val frames = mutableListOf<PageFrame>()
        if (packets.isEmpty()) {
            frames += PageFrame(0, totalPages, 0, 1, ByteArray(0))
        } else {
            packets.forEachIndexed { pageIndex, packet ->
                val chunks = chunkPacket(packet.toByteArray())
                val totalPackages = chunks.size.coerceAtLeast(1)
                chunks.forEachIndexed { packageIndex, chunk ->
                    frames += PageFrame(pageIndex, totalPages, packageIndex, totalPackages, chunk)
                }
            }
        }
        frames.forEachIndexed { index, frame ->
            val hasMoreFrames = index < frames.lastIndex
            val status = if (hasMoreFrames) {
                EvenAiScreenStatus.AUTOMATIC
            } else {
                EvenAiScreenStatus.AUTOMATIC_COMPLETE
            }
            val payload = textPacketBuilder.buildSendText(
                currentPage = frame.pageIndex,
                totalPages = frame.totalPages,
                totalPackageCount = frame.totalPackages,
                currentPackageIndex = frame.packageIndex,
                screenStatus = status,
                textBytes = frame.bytes,
            )
            if (!sendCommand(payload)) {
                return false
            }
        }
        return true
    }

    suspend fun sendBmpImage(imageBytes: ByteArray): Boolean {
        val frames = bmpPacketBuilder.buildFrames(imageBytes)
        for (frame in frames) {
            if (!sendAndAwaitAck(frame, BluetoothConstants.OPCODE_SEND_BMP)) {
                return false
            }
        }
        val terminator = bmpPacketBuilder.buildTerminator()
        if (!sendAndAwaitAck(terminator, BluetoothConstants.OPCODE_BMP_END)) {
            return false
        }
        val crcFrame = bmpPacketBuilder.buildCrcFrame(imageBytes)
        return sendAndAwaitAck(crcFrame, BluetoothConstants.OPCODE_BMP_CRC)
    }

    private suspend fun sendSetMtuCommand(mtu: Int) {
        val payload = BluetoothConstants.buildSetMtuPayload(mtu)
        repeat(MTU_COMMAND_MAX_RETRIES) { attempt ->
            val attemptNumber = attempt + 1
            val success = sendCommand(payload)
            logTelemetry(
                "SERVICE",
                "[MTU]",
                "Set MTU write attempt #$attemptNumber for mtu=$mtu → ${if (success) "queued" else "failed"}",
            )
            if (success) {
                return
            }
            delay(MTU_COMMAND_RETRY_DELAY_MS)
        }
        logger.w(TAG, "${tt()} Failed to enqueue Set MTU command for mtu=$mtu after $MTU_COMMAND_MAX_RETRIES attempts")
    }

    suspend fun clearScreen(): Boolean = sendCommand(byteArrayOf(BluetoothConstants.OPCODE_CLEAR_SCREEN))

    suspend fun queryBattery(fromRetry: Boolean = false): Boolean {
        if (!fromRetry) {
            prepareQuery(QueryToken.BATTERY)
        }
        val result = sendCommand(BluetoothConstants.BATTERY_QUERY)
        if (result) {
            scheduleQueryTimeout(QueryToken.BATTERY)
        } else {
            resetPendingQuery(QueryToken.BATTERY)
        }
        return result
    }

    suspend fun queryFirmware(fromRetry: Boolean = false): Boolean {
        if (!fromRetry) {
            prepareQuery(QueryToken.FIRMWARE)
        }
        val result = sendCommand(BluetoothConstants.FIRMWARE_QUERY)
        if (result) {
            scheduleQueryTimeout(QueryToken.FIRMWARE)
        } else {
            resetPendingQuery(QueryToken.FIRMWARE)
        }
        return result
    }

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

    private suspend fun sendAndAwaitAck(
        payload: ByteArray,
        opcode: Byte,
        timeoutMs: Long = IMAGE_ACK_TIMEOUT_MS,
    ): Boolean = sendAndAwaitAck(payload, opcode, timeoutMs, ::sendCommand)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun sendAndAwaitAck(
        payload: ByteArray,
        opcode: Byte,
        timeoutMs: Long = IMAGE_ACK_TIMEOUT_MS,
        commandSender: suspend (ByteArray) -> Boolean,
    ): Boolean = coroutineScope {
        if (payload.isEmpty()) {
            return@coroutineScope false
        }
        val ack = async {
            withTimeoutOrNull(timeoutMs) {
                val ackBytes = incoming
                    .filter { it.firstOrNull() == opcode }
                    .first { ackBytes ->
                        when (ackBytes.getOrNull(1)) {
                            BluetoothConstants.ACK_SUCCESS,
                            BluetoothConstants.ACK_FAILURE -> true
                            else -> false
                        }
                    }
                when (ackBytes.getOrNull(1)) {
                    BluetoothConstants.ACK_SUCCESS -> true
                    BluetoothConstants.ACK_FAILURE -> {
                        val opcodeLabel = "0x%02X".format(opcode.toInt() and 0xFF)
                        logger.w(
                            TAG,
                            "${tt()} sendAndAwaitAck: received failure ack for opcode $opcodeLabel",
                        )
                        false
                    }
                    else -> false
                }
            } ?: false
        }
        val sent = commandSender(payload)
        if (!sent) {
            ack.cancel()
            return@coroutineScope false
        }
        ack.await()
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
        if (!hasConnectedSuccessfully.get()) {
            logger.debug(TAG, "${tt()} Skipping reconnect; no successful connection yet")
            reconnecting.set(false)
            return false
        }
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
            _vitals.value = _vitals.value.copy(batteryPercent = level)
            logger.debug(TAG, "${tt()} Battery level $level%")
        }
    }

    data class CachedConnectionState(
        val state: ConnectionState,
        val timestamp: Long,
    )

    fun setOnNotification(cb: (service: UUID, characteristic: UUID, value: ByteArray) -> Unit) {
        notifyCallback = cb
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun write(serviceUuid: UUID, charUuid: UUID, data: ByteArray): Boolean {
        if (serviceUuid != BluetoothConstants.UART_SERVICE_UUID) {
            logger.w(TAG, "${tt()} write(): unexpected service $serviceUuid")
        }
        if (writeCharacteristic?.uuid != charUuid) {
            logger.w(TAG, "${tt()} write(): unexpected char $charUuid (expected ${writeCharacteristic?.uuid})")
        }
        return try {
            runBlocking { sendCommand(data.copyOf()) }
        } catch (t: Throwable) {
            logger.e(TAG, "${tt()} write(): exception", t)
            false
        }
    }

    fun triggerNotification(serviceUuid: UUID, charUuid: UUID, value: ByteArray) {
        notifyCallback?.invoke(serviceUuid, charUuid, value.copyOf())
    }

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
        private const val QUERY_TIMEOUT_MS = 5_000L
        private const val QUERY_MAX_RETRIES = 3
        private const val VITALS_POLL_INTERVAL_MS = 30_000L
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
        hasConnectedSuccessfully.set(false)
    }

    private fun isBluetoothEnabled(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
}
