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
import com.loopermallee.moncchichi.core.ble.ConsoleDiagnostics
import com.loopermallee.moncchichi.core.ble.DeviceBadge
import com.loopermallee.moncchichi.core.ble.DeviceMode
import com.loopermallee.moncchichi.core.ble.DeviceVitals
import com.loopermallee.moncchichi.core.ble.G1ReplyParser
import com.loopermallee.moncchichi.telemetry.G1TelemetryEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets
import kotlin.concurrent.withLock

private const val TAG = "DeviceManager"
private const val MTU_TARGET = 498
private const val MTU_MINIMUM_FOR_NOTIFICATIONS = 256
private const val MAX_CCCD_RETRIES = 3
private const val CCCD_RETRY_DELAY_MS = 500L
private const val RECONNECT_BASE_DELAY_SECONDS = 2L
private const val RECONNECT_MAX_DELAY_SECONDS = 8L

private enum class DeviceArm {
    LEFT,
    RIGHT,
    UNKNOWN,
}

private fun BluetoothDevice.detectArm(): DeviceArm {
    val name = name?.lowercase(Locale.US) ?: return DeviceArm.UNKNOWN
    return when {
        "left" in name -> DeviceArm.LEFT
        "right" in name -> DeviceArm.RIGHT
        else -> DeviceArm.UNKNOWN
    }
}

private fun DeviceArm.prefix(): String = when (this) {
    DeviceArm.LEFT -> "L>"
    DeviceArm.RIGHT -> "R>"
    DeviceArm.UNKNOWN -> "?>"
}

internal class DeviceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val logger by lazy { MoncchichiLogger(context) }
    private val _telemetryFlow = MutableStateFlow<List<G1TelemetryEvent>>(emptyList())
    val telemetry: StateFlow<List<G1TelemetryEvent>> = _telemetryFlow.asStateFlow()
    val telemetryFlow: StateFlow<List<G1TelemetryEvent>>
        get() = telemetry
    private val _consoleDiagnostics = MutableStateFlow(ConsoleDiagnostics())
    val consoleDiagnostics: StateFlow<ConsoleDiagnostics> = _consoleDiagnostics.asStateFlow()
    private val _vitals = MutableStateFlow(DeviceVitals())
    val vitals: StateFlow<DeviceVitals> = _vitals.asStateFlow()

    private fun logTelemetry(
        source: String,
        tag: String,
        message: String,
        arm: DeviceArm? = null,
    ) {
        val prefix = arm?.takeIf { it != DeviceArm.UNKNOWN }?.prefix()
        val decorated = if (prefix != null) {
            "$prefix $message"
        } else {
            message
        }
        val event = G1TelemetryEvent(source = source, tag = tag, message = decorated)
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
    private var pendingNotificationGatt: BluetoothGatt? = null
    private var pendingNotificationDescriptor: BluetoothGattDescriptor? = null
    private var pendingCccdArming = false
    private var cccdRetryCount = 0
    private val cccdArmed = AtomicBoolean(false)
    private var negotiatedMtu = 23
    private var negotiatedHighPriority = false
    private var currentArm: DeviceArm = DeviceArm.UNKNOWN
    private val activeBadges = mutableSetOf<DeviceBadge>()
    private var lastMode: DeviceMode = DeviceMode.UNKNOWN

    private var periodicVitalsJob: Job? = null
    private val queryStateLock = ReentrantLock()
    private val queryStates = EnumMap<QueryToken, PendingQueryState>(QueryToken::class.java)

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
    private var notifyCallback: ((service: UUID, characteristic: UUID, value: ByteArray) -> Unit)? = null

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
                    setConnectionState(ConnectionState.CONNECTED)
                    updateState(gatt.device, G1ConnectionState.CONNECTED)
                    currentDeviceAddress = gatt.device.address
                    cacheLastConnected(gatt.device.address)
                    clearReconnectFailures()
                    negotiatedHighPriority = gatt.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    )
                    logTelemetry(
                        "SERVICE",
                        "[LINK]",
                        "Connection priority request → ${if (negotiatedHighPriority) "HIGH" else "FAILED"}",
                        gatt.device.detectArm()
                    )
                    updateDiagnostics()
                    val mtuRequested = gatt.requestMtu(MTU_TARGET)
                    if (!mtuRequested) {
                        logTelemetry(
                            "SERVICE",
                            "[MTU]",
                            "MTU request to $MTU_TARGET bytes rejected",
                            gatt.device.detectArm()
                        )
                    } else {
                        negotiatedMtu = 23
                        updateDiagnostics()
                    }
                    logTelemetry(
                        "SERVICE",
                        "[RECONNECT]",
                        "Connected",
                        gatt.device.detectArm()
                    )
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
                    stopPeriodicVitals()
                    resetPendingQuery()
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
            initializeCharacteristics(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                val emoji = when {
                    mtu >= 400 -> "🟩"
                    mtu in 200 until 400 -> "🟨"
                    else -> "🟥"
                }
                logTelemetry(
                    "SERVICE",
                    "[MTU]",
                    "$emoji Negotiated MTU=$mtu",
                    gatt.device.detectArm()
                )
                updateDiagnostics()
                if (pendingCccdArming && mtu >= MTU_MINIMUM_FOR_NOTIFICATIONS) {
                    maybeArmNotifications(gatt)
                }
            } else {
                logTelemetry(
                    "SERVICE",
                    "[MTU]",
                    "Failed to negotiate MTU (status=$status)",
                    gatt.device.detectArm()
                )
            }
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
            val arm = gatt.device.detectArm()

            logTelemetry("DEVICE", "[NOTIFY]", "Notification from $uuidString → [$hex]", arm)
            updateModeAndBadges(payload, arm)

            if (uuid == BluetoothConstants.UART_READ_CHARACTERISTIC_UUID) {
                val text = payload.toString(Charsets.UTF_8)
                logger.i("[UART_RX]", "${tt()} Received: $text ($hex)")
                val parsed = G1ReplyParser.parse(payload)
                if (parsed != null) {
                    val current = _vitals.value
                    val merged = DeviceVitals(
                        batteryPercent = parsed.batteryPercent ?: current.batteryPercent,
                        firmwareVersion = parsed.firmwareVersion ?: current.firmwareVersion,
                    )
                    if (merged != current) {
                        _vitals.value = merged
                    }
                    parsed.batteryPercent?.let { level ->
                        completePendingQuery(QueryToken.BATTERY)
                        logTelemetry("DEVICE", "[BATTERY]", "Battery = ${level}%", arm)
                        updateBatteryLevel(level)
                    }
                    parsed.firmwareVersion?.let { fw ->
                        completePendingQuery(QueryToken.FIRMWARE)
                        logTelemetry("DEVICE", "[FIRMWARE]", "Firmware = $fw", arm)
                    }
                } else {
                    when {
                        value.isNotEmpty() && value[0] == BluetoothConstants.OPCODE_BATTERY -> {
                            val battery = value.getOrNull(1)?.toInt() ?: -1
                            logTelemetry("DEVICE", "[BATTERY]", "Battery update: ${battery}%", arm)
                            if (battery in 0..100) {
                                completePendingQuery(QueryToken.BATTERY)
                                updateBatteryLevel(battery)
                            }
                        }
                        value.isNotEmpty() && value[0] == BluetoothConstants.OPCODE_FIRMWARE -> {
                            val fw = value.drop(1).toByteArray().decodeToString().trim()
                            logTelemetry("DEVICE", "[FIRMWARE]", "Firmware: v$fw", arm)
                            if (fw.isNotEmpty()) {
                                completePendingQuery(QueryToken.FIRMWARE)
                                _vitals.value = _vitals.value.copy(firmwareVersion = fw)
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != BluetoothConstants.CCCD_UUID) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cccdArmed.set(true)
                pendingCccdArming = false
                pendingNotificationDescriptor = null
                pendingNotificationGatt = null
                updateDiagnostics()
                val deviceName = gatt.device.name ?: gatt.device.address
                logTelemetry(
                    "SERVICE",
                    "[NOTIFY]",
                    "✅ CCCD armed for $deviceName",
                    gatt.device.detectArm()
                )
                onNotificationsArmed(gatt)
            } else {
                logTelemetry(
                    "SERVICE",
                    "[NOTIFY]",
                    "CCCD write failed with status=$status",
                    gatt.device.detectArm()
                )
                scheduleDescriptorRetry(gatt)
            }
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
        currentArm = device.detectArm()
        activeBadges.clear()
        lastMode = DeviceMode.UNKNOWN
        updateDiagnostics()
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
            logTelemetry("SERVICE", "[NOTIFY]", "Failed to enable notifications", gatt.device.detectArm())
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        val cccd = notifyCharacteristic.getDescriptor(BluetoothConstants.CCCD_UUID)
        if (cccd == null) {
            logTelemetry(
                "SERVICE",
                "[NOTIFY]",
                "Missing CCCD descriptor on notify characteristic",
                gatt.device.detectArm()
            )
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }

        pendingNotificationGatt = gatt
        pendingNotificationDescriptor = cccd
        pendingCccdArming = true
        cccdRetryCount = 0
        cccdArmed.set(false)
        updateDiagnostics()

        if (negotiatedMtu >= MTU_MINIMUM_FOR_NOTIFICATIONS) {
            maybeArmNotifications(gatt)
        } else {
            logTelemetry(
                "SERVICE",
                "[MTU]",
                "Waiting for MTU ≥ $MTU_MINIMUM_FOR_NOTIFICATIONS before arming CCCD",
                gatt.device.detectArm()
            )
        }
    }

    private fun maybeArmNotifications(gatt: BluetoothGatt) {
        if (!pendingCccdArming) return
        if (negotiatedMtu < MTU_MINIMUM_FOR_NOTIFICATIONS) return
        val descriptor = pendingNotificationDescriptor ?: return
        if (cccdRetryCount >= MAX_CCCD_RETRIES) {
            logTelemetry(
                "SERVICE",
                "[NOTIFY]",
                "Giving up on CCCD arming after $MAX_CCCD_RETRIES attempts",
                gatt.device.detectArm()
            )
            pendingCccdArming = false
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }
        val attemptNumber = cccdRetryCount + 1
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val queued = gatt.writeDescriptor(descriptor)
        if (queued) {
            cccdRetryCount = attemptNumber
            logTelemetry(
                "SERVICE",
                "[NOTIFY]",
                "Writing CCCD attempt #$attemptNumber",
                gatt.device.detectArm()
            )
        } else {
            cccdRetryCount = attemptNumber
            logTelemetry(
                "SERVICE",
                "[NOTIFY]",
                "CCCD write attempt #$attemptNumber rejected by stack",
                gatt.device.detectArm()
            )
            scheduleDescriptorRetry(gatt)
        }
    }

    private fun scheduleDescriptorRetry(gatt: BluetoothGatt) {
        if (cccdRetryCount >= MAX_CCCD_RETRIES) {
            logTelemetry(
                "SERVICE",
                "[NOTIFY]",
                "CCCD retry limit reached",
                gatt.device.detectArm()
            )
            pendingCccdArming = false
            updateState(gatt.device, G1ConnectionState.RECONNECTING)
            setConnectionState(ConnectionState.ERROR)
            return
        }
        scope.launch {
            delay(CCCD_RETRY_DELAY_MS)
            val pendingGatt = pendingNotificationGatt ?: gatt
            maybeArmNotifications(pendingGatt)
        }
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

    private fun onNotificationsArmed(gatt: BluetoothGatt) {
        logTelemetry(
            "SERVICE",
            "[NOTIFY]",
            "Notifications enabled for UART_READ_CHARACTERISTIC",
            gatt.device.detectArm()
        )
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
        updateDiagnostics()
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

    private fun updateDiagnostics() {
        val leftArmed = cccdArmed.get() && currentArm == DeviceArm.LEFT
        val rightArmed = cccdArmed.get() && currentArm == DeviceArm.RIGHT
        _consoleDiagnostics.value = ConsoleDiagnostics(
            mode = lastMode,
            badges = activeBadges.toSet(),
            mtu = negotiatedMtu,
            highPriority = negotiatedHighPriority,
            leftCccdArmed = leftArmed,
            rightCccdArmed = rightArmed,
        )
    }

    private fun updateModeAndBadges(payload: ByteArray, arm: DeviceArm) {
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
            logTelemetry("DEVICE", "[MODE]", "Mode updated → ${mode.symbol()}", arm)
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
                applyBadge(badge, arm)
            }
        }
    }

    private fun applyBadge(badge: DeviceBadge, arm: DeviceArm) {
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
            logTelemetry("DEVICE", "[STATUS]", "${badge.symbol()}", arm)
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
        logTelemetry("APP", "[MANUAL]", "🔌 Manual disconnect triggered by user")
        reconnectJob?.cancel()
        reconnecting.set(false)
        val address = currentDeviceAddress
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        clearConnection()
        setConnectionState(ConnectionState.DISCONNECTED)
        address?.let { deviceStates[it] = G1ConnectionState.DISCONNECTED }
        logger.i(TAG, "${tt()} disconnect requested")
        cccdArmed.set(false)
        pendingCccdArming = false
        pendingNotificationDescriptor = null
        pendingNotificationGatt = null
        updateDiagnostics()
    }

    private fun clearConnection() {
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
        batteryCharacteristic = null
        currentDeviceAddress = null
        negotiatedMtu = 23
        negotiatedHighPriority = false
        activeBadges.clear()
        lastMode = DeviceMode.UNKNOWN
        cccdArmed.set(false)
        pendingNotificationGatt = null
        pendingNotificationDescriptor = null
        pendingCccdArming = false
        updateDiagnostics()
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

    suspend fun queryBattery(fromRetry: Boolean = false): Boolean {
        if (!fromRetry) {
            prepareQuery(QueryToken.BATTERY)
        }
        val ascii = "BAT?\n".toByteArray()
        val binary = byteArrayOf(0xF1.toByte(), 0x10, 0x00)
        val success = sendCommand(ascii)
        val fallback = if (!success) sendCommand(binary) else true
        val result = success || fallback
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
        val ascii = "FW?\n".toByteArray()
        val binary = byteArrayOf(0xF1.toByte(), 0x11, 0x00)
        val success = sendCommand(ascii)
        val fallback = if (!success) sendCommand(binary) else true
        val result = success || fallback
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
                val delaySeconds = min(
                    RECONNECT_BASE_DELAY_SECONDS * (1L shl reconnectAttempt),
                    RECONNECT_MAX_DELAY_SECONDS
                )
                reconnectAttempt += 1
                logger.backoff(TAG, "Reconnecting attempt #$reconnectAttempt in ${delaySeconds}s")
                logTelemetry(
                    "SERVICE",
                    "[RECONNECT]",
                    "Reconnecting in ${delaySeconds}s…"
                )
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

    suspend fun exportSessionLog(): File? = withContext(Dispatchers.IO) {
        val diagnostics = _consoleDiagnostics.value
        val directory = File(context.getExternalFilesDir(null), "Moncchichi/Logs")
        if (!directory.exists() && !directory.mkdirs()) {
            logTelemetry("APP", "[EXPORT]", "Unable to create ${directory.absolutePath}")
            return@withContext null
        }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        val file = File(directory, "session_$timestamp.txt")
        val header = buildString {
            appendLine("# Moncchichi G1 Session Log")
            appendLine("timestamp=${System.currentTimeMillis()}")
            appendLine("mtu=${diagnostics.mtu}")
            appendLine("connection_priority=${if (diagnostics.highPriority) "HIGH" else "BALANCED"}")
            appendLine("cccd_left=${diagnostics.leftCccdArmed}")
            appendLine("cccd_right=${diagnostics.rightCccdArmed}")
            appendLine("mode=${diagnostics.mode}")
            appendLine(
                "badges=${diagnostics.badges.joinToString(",") { it.name.lowercase(Locale.US) }}"
            )
            appendLine("heartbeat_interval=${BluetoothConstants.HEARTBEAT_INTERVAL_SECONDS}")
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
            logger.e(TAG, "${tt()} Failed to export session log", error)
            logTelemetry("APP", "[EXPORT]", "Session log export failed: ${error.message}")
            return@withContext null
        }
        logTelemetry("APP", "[EXPORT]", "Session log saved → ${file.absolutePath}")
        file
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
    }

    private fun isBluetoothEnabled(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
}
