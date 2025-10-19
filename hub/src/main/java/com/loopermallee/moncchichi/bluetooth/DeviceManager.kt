package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import com.loopermallee.moncchichi.ble.G1ReplyParser
import com.loopermallee.moncchichi.core.ble.ConsoleDiagnostics
import com.loopermallee.moncchichi.core.ble.DeviceBadge
import com.loopermallee.moncchichi.core.ble.DeviceMode
import com.loopermallee.moncchichi.telemetry.G1TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEVICE_MANAGER_TAG = "[DeviceManager]"
private const val RECONNECT_TAG = "[Reconnect]"

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
    private val notificationEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifications: SharedFlow<ByteArray> = notificationEvents.asSharedFlow()

    private val telemetryCollector = FlowCollector<ByteArray> { payload ->
        try {
            val hex = payload.toHexString()
            logTelemetry("DEVICE", "[NOTIFY]", "Notify (${payload.size} bytes) → $hex")
            notificationEvents.tryEmit(payload)
            updateModeAndBadges(payload)
            G1ReplyParser.parse(payload, ::bleLog)
        } catch (error: Throwable) {
            logger.e(
                DEVICE_MANAGER_TAG,
                "${tt()} telemetry collector failure: ${error.message}",
                error
            )
        }
    }
    private val _telemetryFlow = MutableStateFlow<List<G1TelemetryEvent>>(emptyList())
    val telemetryFlow: StateFlow<List<G1TelemetryEvent>> = _telemetryFlow.asStateFlow()
    val vitals: StateFlow<G1ReplyParser.DeviceVitals> = G1ReplyParser.vitalsFlow.asStateFlow()
    private val _consoleDiagnostics = MutableStateFlow(ConsoleDiagnostics())
    val consoleDiagnostics: StateFlow<ConsoleDiagnostics> = _consoleDiagnostics.asStateFlow()

    private var lastMode: DeviceMode = DeviceMode.UNKNOWN
    private val activeBadges = mutableSetOf<DeviceBadge>()
    private var negotiatedMtu: Int = 23
    private var highPriority: Boolean = false

    private val transactionQueueLazy = lazy { G1TransactionQueue(scope, logger) }
    private val transactionQueue: G1TransactionQueue
        get() = transactionQueueLazy.value

    private val connectionMutex = Mutex()

    private var bleClient: G1BleUartClient? = null
    private var trackedDevice: BluetoothDevice? = null
    private var incomingJob: Job? = null
    private var clientStateJob: Job? = null
    private var clientRssiJob: Job? = null
    private var rssiJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var reconnectJob: Job? = null

    init {
        logger.i(
            DEVICE_MANAGER_TAG,
            "${tt()} Created at ${System.currentTimeMillis()}"
        )
    }

    private fun bleLog(message: String) {
        logger.i(DEVICE_MANAGER_TAG, "${tt()} $message")
        logTelemetry("BLE", "[LOG]", message)
    }

    fun currentDevice(): BluetoothDevice? = trackedDevice

    fun currentDeviceName(): String? = trackedDevice?.name

    fun notifyWaiting() {
        updateState(G1ConnectionState.RECONNECTING)
    }

    fun shutdown() = scope.cancel("DeviceManager destroyed")

    fun close() {
        if (transactionQueueLazy.isInitialized()) {
            transactionQueue.close()
        }
        disconnect()
        shutdown()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        rssiJob?.cancel()
        rssiJob = null
        clientStateJob?.cancel()
        incomingJob?.cancel()
        clientRssiJob?.cancel()
        bleClient?.close()
        bleClient = null
        _rssi.value = null
        logTelemetry("APP", "[MANUAL]", "🔌 Manual disconnect triggered by user")
        updateState(G1ConnectionState.DISCONNECTED)
        G1ReplyParser.vitalsFlow.value = G1ReplyParser.DeviceVitals()
        lastMode = DeviceMode.UNKNOWN
        activeBadges.clear()
        updateDiagnostics()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return connect(adapter.getRemoteDevice(address))
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean {
        trackedDevice = device
        return connectDevice(device)
    }

    suspend fun reconnect(maxRetries: Int = 5): Boolean {
        val device = trackedDevice ?: return false
        updateState(G1ConnectionState.RECONNECTING)
        return connectDevice(device)
    }

    suspend fun sendHeartbeat(): Boolean {
        return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_HEARTBEAT), "Heartbeat")
    }

    suspend fun clearScreen(): Boolean {
        return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_CLEAR_SCREEN), "ClearScreen")
    }

    suspend fun sendText(message: String): Boolean {
        val payload = message.encodeToByteArray()
        if (payload.isEmpty()) {
            return sendCommand(byteArrayOf(BluetoothConstants.OPCODE_SEND_TEXT), "SendTextEmpty")
        }
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

    fun startRssiMonitor() {
        if (rssiJob?.isActive == true) return
        rssiJob = scope.launch {
            while (isActive) {
                bleClient?.readRemoteRssi()
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

    fun tryReconnect() {
        if (reconnectJob?.isActive == true) return
        val device = trackedDevice ?: return
        updateState(G1ConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            reconnectWithBackoff(device)
        }
    }

    suspend fun resilientReconnect(maxRetries: Int = 5, delayMs: Long = 8_000L) {
        var attempt = 0
        while (attempt < maxRetries && state.value != G1ConnectionState.CONNECTED) {
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
        if (state.value != G1ConnectionState.CONNECTED) {
            logger.e(RECONNECT_TAG, "${tt()} reconnect failed after $maxRetries attempts")
            updateState(G1ConnectionState.DISCONNECTED)
        }
    }

    private suspend fun connectDevice(device: BluetoothDevice): Boolean {
        return connectionMutex.withLock {
            updateState(G1ConnectionState.CONNECTING)
            trackedDevice = device
            logTelemetry("SERVICE", "[CONNECT]", "Connecting to ${device.address}")
            val client = G1BleUartClient(context, device, ::bleLog, scope)
            replaceClient(client)
            client.connect()
            val result = withTimeoutOrNull(20_000L) {
                client.connectionState
                    .filter { it != G1BleUartClient.ConnectionState.CONNECTING }
                    .first()
            }
            val success = result == G1BleUartClient.ConnectionState.CONNECTED
            if (!success) {
                logger.w(DEVICE_MANAGER_TAG, "${tt()} connectDevice timeout or failure")
                updateState(G1ConnectionState.DISCONNECTED)
                G1ReplyParser.vitalsFlow.value = G1ReplyParser.DeviceVitals()
            }
            success
        }
    }

    private suspend fun reconnectWithBackoff(device: BluetoothDevice, maxRetries: Int = 5): Boolean {
        var delayMs = 2_000L
        repeat(maxRetries) { attempt ->
            logger.debug(RECONNECT_TAG, "${tt()} Attempt ${attempt + 1}")
            logTelemetry("SYSTEM", "[RECONNECT]", "Reconnecting attempt #${attempt + 1}")
            val success = connectDevice(device)
            if (success) return true
            delay(delayMs)
            delayMs = min(delayMs * 2, 30_000L)
        }
        updateState(G1ConnectionState.DISCONNECTED)
        return false
    }

    private fun replaceClient(client: G1BleUartClient) {
        incomingJob?.cancel()
        clientStateJob?.cancel()
        clientRssiJob?.cancel()
        bleClient?.close()
        bleClient = client

        incomingJob = scope.launch {
            client.observeNotifications(telemetryCollector)
        }

        clientStateJob = client.connectionState.onEach { state ->
            when (state) {
                G1BleUartClient.ConnectionState.CONNECTED -> {
                    updateState(G1ConnectionState.CONNECTED)
                }
                G1BleUartClient.ConnectionState.CONNECTING -> {
                    updateState(G1ConnectionState.CONNECTING)
                }
                G1BleUartClient.ConnectionState.DISCONNECTED -> {
                    if (this@DeviceManager.state.value == G1ConnectionState.CONNECTED) {
                        updateState(G1ConnectionState.RECONNECTING)
                    } else {
                        updateState(G1ConnectionState.DISCONNECTED)
                    }
                    _rssi.value = null
                    G1ReplyParser.vitalsFlow.value = G1ReplyParser.DeviceVitals()
                }
            }
        }.launchIn(scope)

        clientRssiJob = scope.launch {
            client.rssi.collectLatest { value ->
                _rssi.value = value
            }
        }
    }

    private suspend fun sendCommand(payload: ByteArray, label: String): Boolean {
        logTelemetry("APP", "[WRITE]", "SendCommand: $label (${payload.size} bytes)")
        return transactionQueue.run(label) {
            writePayload(payload)
        }
    }

    private fun writePayload(payload: ByteArray): Boolean {
        val client = bleClient ?: return false.also {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} writePayload skipped; client not ready")
        }
        val ok = client.write(payload)
        if (!ok) {
            logger.w(DEVICE_MANAGER_TAG, "${tt()} writePayload failed to enqueue")
        }
        return ok
    }

    private fun logTelemetry(source: String, tag: String, message: String) {
        val event = G1TelemetryEvent(source = source, tag = tag, message = message)
        _telemetryFlow.value = (_telemetryFlow.value + event).takeLast(500)
        logger.i("[Telemetry]", event.toString())
    }

    private fun updateDiagnostics() {
        _consoleDiagnostics.value = ConsoleDiagnostics(
            mode = lastMode,
            badges = activeBadges.toSet(),
            mtu = negotiatedMtu,
            highPriority = highPriority,
        )
    }

    private fun updateModeAndBadges(payload: ByteArray) {
        if (payload.isEmpty()) return
        val header = payload[0].toInt() and 0xFF
        val mode = when (header) {
            0x25 -> DeviceMode.IDLE
            0x4E -> DeviceMode.TEXT
            0x15, 0x16, 0x20 -> DeviceMode.IMAGE
            0xF5 -> DeviceMode.DASHBOARD
            else -> DeviceMode.UNKNOWN
        }
        if (mode != DeviceMode.UNKNOWN && mode != lastMode) {
            lastMode = mode
            logTelemetry("DEVICE", "[MODE]", "Mode updated → ${mode.symbol()}")
            updateDiagnostics()
        }

        if (header == 0x06 || header == 0xF5) {
            val state = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: return
            val badge = when (state) {
                0x0E -> DeviceBadge.CHARGING
                0x0F -> DeviceBadge.FULL
                0x06 -> DeviceBadge.WEARING
                0x09 -> DeviceBadge.CRADLE
                else -> null
            }
            if (badge != null) {
                applyBadge(badge)
            }
        }
    }

    private fun applyBadge(badge: DeviceBadge) {
        var updated = false
        val category = badge.category()
        val iterator = activeBadges.iterator()
        while (iterator.hasNext()) {
            val existing = iterator.next()
            if (existing.category() == category && existing != badge) {
                iterator.remove()
                updated = true
            }
        }
        if (activeBadges.add(badge)) {
            updated = true
        }
        if (updated) {
            logTelemetry("DEVICE", "[STATUS]", "${badge.symbol()}")
            updateDiagnostics()
        }
    }

    private fun DeviceMode.symbol(): String = when (this) {
        DeviceMode.IDLE -> "⚪ Idle"
        DeviceMode.TEXT -> "🟢 Text"
        DeviceMode.IMAGE -> "🟣 Image"
        DeviceMode.DASHBOARD -> "🟠 Dashboard"
        DeviceMode.UNKNOWN -> "❔ Unknown"
    }

    private fun DeviceBadge.symbol(): String = when (this) {
        DeviceBadge.CHARGING -> "🔋 Charging"
        DeviceBadge.FULL -> "🔋 Full"
        DeviceBadge.WEARING -> "🟢 Wearing"
        DeviceBadge.CRADLE -> "⚫ Cradle"
    }

    private fun DeviceBadge.category(): String = when (this) {
        DeviceBadge.CHARGING, DeviceBadge.FULL -> "battery"
        DeviceBadge.WEARING, DeviceBadge.CRADLE -> "placement"
    }

    private fun updateState(newState: G1ConnectionState) {
        if (_stateFlow.value == newState) return
        logger.i(DEVICE_MANAGER_TAG, "${tt()} State ${_stateFlow.value} -> $newState")
        _stateFlow.value = newState
    }

    private fun ByteArray?.toHexString(): String {
        val data = this ?: return "null"
        return data.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
        }
    }

    private fun tt() = "[${Thread.currentThread().name}]"

    suspend fun exportSessionLog(): File? = withContext(Dispatchers.IO) {
        val directory = File(context.getExternalFilesDir(null), "Moncchichi/Logs")
        if (!directory.exists() && !directory.mkdirs()) {
            logTelemetry("APP", "[EXPORT]", "Unable to create ${directory.absolutePath}")
            return@withContext null
        }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        val file = File(directory, "hub_session_$timestamp.txt")
        val header = buildString {
            appendLine("# Moncchichi Hub Session Log")
            appendLine("timestamp=${System.currentTimeMillis()}")
            appendLine("mtu=$negotiatedMtu")
            appendLine("connection_priority=${if (highPriority) "HIGH" else "BALANCED"}")
            appendLine("mode=${lastMode}")
            appendLine("badges=${activeBadges.joinToString(",") { it.name.lowercase(Locale.US) }}")
            appendLine()
        }
        runCatching {
            FileOutputStream(file).use { stream ->
                stream.write(header.toByteArray())
                _telemetryFlow.value.forEach { event ->
                    stream.write(event.toString().toByteArray())
                    stream.write('\n'.code)
                }
            }
        }.onFailure { error ->
            logger.e(DEVICE_MANAGER_TAG, "${tt()} Failed to export log", error)
            logTelemetry("APP", "[EXPORT]", "Session log export failed: ${error.message}")
            return@withContext null
        }
        logTelemetry("APP", "[EXPORT]", "Session log saved → ${file.absolutePath}")
        file
    }
}
