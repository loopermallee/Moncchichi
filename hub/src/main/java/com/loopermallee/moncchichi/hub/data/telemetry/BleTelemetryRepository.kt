package com.loopermallee.moncchichi.hub.data.telemetry

import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import com.loopermallee.moncchichi.bluetooth.BondResult
import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.G1Protocols
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_COMPLETE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_CONTINUE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_OK
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_BUSY
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.audio.MicStreamManager
import com.loopermallee.moncchichi.hub.ble.DashboardDataEncoder
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.diagnostics.TelemetryConsistencyValidator
import com.loopermallee.moncchichi.hub.telemetry.BleTelemetryParser
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.LinkedHashMap
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.collections.buildList
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.text.Charsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregates BLE telemetry packets (battery %, uptime, firmware, RSSI) emitted by [MoncchichiBleService].
 */
class BleTelemetryRepository(
    private val memory: MemoryRepository,
    private val persistenceScope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {

    private val telemetryParser = BleTelemetryParser()
    private val missingOpcodeLog = mutableSetOf<Int>()

    data class LensTelemetry(
        val batteryPercent: Int? = null,
        val batterySourceOpcode: Int? = null,
        val batteryUpdatedAt: Long? = null,
        val caseBatteryPercent: Int? = null,
        val lastUpdatedAt: Long? = null,
        val lastPowerOpcode: Int? = null,
        val lastPowerUpdatedAt: Long? = null,
        val lastStateUpdatedAt: Long? = null,
        val rssi: Int? = null,
        val firmwareVersion: String? = null,
        val firmwareSourceOpcode: Int? = null,
        val firmwareUpdatedAt: Long? = null,
        val notes: String? = null,
        val charging: Boolean? = null,
        val chargingSourceOpcode: Int? = null,
        val chargingUpdatedAt: Long? = null,
        val wearing: Boolean? = null,
        val inCase: Boolean? = null,
        val silentMode: Boolean? = null,
        val caseOpen: Boolean? = null,
        val bonded: Boolean = false,
        val disconnectReason: Int? = null,
        val bondTransitions: Int = 0,
        val bondTimeouts: Int = 0,
        val bondAttempts: Int = 0,
        val lastBondResult: String? = null,
        val pairingDialogsShown: Int = 0,
        val refreshCount: Int = 0,
        val smpFrames: Int = 0,
        val lastSmpOpcode: Int? = null,
        val reconnectAttempts: Int = 0,
        val reconnectSuccesses: Int = 0,
        val reconnecting: Boolean = false,
        val bondResets: Int = 0,
        val lastBondState: Int? = null,
        val lastBondReason: Int? = null,
        val lastBondEventAt: Long? = null,
        val lastAckAt: Long? = null,
        val lastAckOpcode: Int? = null,
        val lastAckLatencyMs: Long? = null,
        val ackSuccessCount: Int = 0,
        val ackFailureCount: Int = 0,
        val ackWarmupCount: Int = 0,
        val ackDropCount: Int = 0,
        val powerHistory: List<PowerFrame> = emptyList(),
        val reconnectAttemptsSnapshot: Int? = null,
        val heartbeatLatencySnapshotMs: Int? = null,
        val ackMode: MoncchichiBleService.AckType? = null,
    )

    data class CaseStatus(
        val batteryPercent: Int? = null,
        val charging: Boolean? = null,
        val lidOpen: Boolean? = null,
        val silentMode: Boolean? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class PowerFrame(
        val opcode: Int,
        val hex: String,
        val timestampMs: Long,
    )

    data class Snapshot(
        val left: LensTelemetry = LensTelemetry(),
        val right: LensTelemetry = LensTelemetry(),
        val uptimeSeconds: Long? = null,
        val lastLens: Lens? = null,
        val lastFrameHex: String? = null,
        val connectionSequence: String? = null,
        val leftBondAttempts: Int = 0,
        val rightBondAttempts: Int = 0,
        val leftBondResult: String? = null,
        val rightBondResult: String? = null,
        val pairingDialogsShown: Int = 0,
        val gattRefreshCount: Int = 0,
        val autoReconnectAttempts: Int = 0,
        val autoReconnectSuccesses: Int = 0,
        val rightBondRetries: Int = 0,
        val bondResetEvents: Int = 0,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _uartText = MutableSharedFlow<UartLine>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uartText: SharedFlow<UartLine> = _uartText.asSharedFlow()

    private val _dashboardStatus = MutableStateFlow(DashboardDataEncoder.BurstStatus())
    val dashboardStatus: StateFlow<DashboardDataEncoder.BurstStatus> = _dashboardStatus.asStateFlow()

    private val _micPackets = MutableSharedFlow<BleTelemetryParser.TelemetryEvent.AudioPacketEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val micPackets: SharedFlow<BleTelemetryParser.TelemetryEvent.AudioPacketEvent> = _micPackets.asSharedFlow()

    private val _micAvailability = MutableStateFlow(false)
    val micAvailability: StateFlow<Boolean> = _micAvailability.asStateFlow()
    val micAlive: StateFlow<Boolean> = _micAvailability.asStateFlow()

    private val _battery = MutableStateFlow<G1ReplyParser.BatteryInfo?>(null)
    val battery: StateFlow<G1ReplyParser.BatteryInfo?> = _battery.asStateFlow()

    private val _uptime = MutableStateFlow<Long?>(null)
    val uptime: StateFlow<Long?> = _uptime.asStateFlow()

    private val _gesture = MutableSharedFlow<LensGestureEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val gesture: SharedFlow<LensGestureEvent> = _gesture.asSharedFlow()

    data class DeviceStatus<T>(
        val left: T? = null,
        val right: T? = null,
        val leftUpdatedAt: Long? = null,
        val rightUpdatedAt: Long? = null,
    ) {
        fun valueFor(lens: Lens): T? = when (lens) {
            Lens.LEFT -> left
            Lens.RIGHT -> right
        }
    }

    private val _wearingStatus = MutableStateFlow(DeviceStatus<Boolean>())
    val wearingStatus: StateFlow<DeviceStatus<Boolean>> = _wearingStatus.asStateFlow()

    private val _inCaseStatus = MutableStateFlow(DeviceStatus<Boolean>())
    val inCaseStatus: StateFlow<DeviceStatus<Boolean>> = _inCaseStatus.asStateFlow()

    private val _chargingStatus = MutableStateFlow(DeviceStatus<Boolean>())
    val chargingStatus: StateFlow<DeviceStatus<Boolean>> = _chargingStatus.asStateFlow()

    private val _caseStatus = MutableStateFlow(CaseStatus())
    val caseStatus: StateFlow<CaseStatus> = _caseStatus.asStateFlow()

    data class DeviceState(
        val wearing: Boolean?,
        val inCase: Boolean?,
        val silentMode: Boolean?,
        val caseOpen: Boolean?,
    )

    private val _deviceStatus = MutableStateFlow<DeviceState?>(null)
    val deviceStatus: StateFlow<DeviceState?> = _deviceStatus.asStateFlow()

    data class DeviceTelemetrySnapshot(
        val lens: MoncchichiBleService.Lens,
        val timestamp: Long,
        val batteryVoltageMv: Int?,
        val isCharging: Boolean?,
        val caseBatteryPercent: Int?,
        val caseOpen: Boolean?,
        val uptimeSeconds: Long?,
        val firmwareVersion: String?,
        val environment: Map<String, String>?,
        val lastAckStatus: String?,
        val lastAckTimestamp: Long?,
        val lastGesture: LensGestureEvent?,
        val reconnectAttempts: Int? = null,
        val heartbeatLatencyMs: Int? = null,
        val lastAckMode: MoncchichiBleService.AckType? = null,
    )

    private val deviceTelemetryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lensTelemetrySnapshots = mapOf(
        Lens.LEFT to MutableStateFlow(initialDeviceTelemetrySnapshot(Lens.LEFT)),
        Lens.RIGHT to MutableStateFlow(initialDeviceTelemetrySnapshot(Lens.RIGHT)),
    )

    val deviceTelemetryFlow: Flow<List<DeviceTelemetrySnapshot>> =
        combine(
            lensTelemetrySnapshots.getValue(Lens.LEFT),
            lensTelemetrySnapshots.getValue(Lens.RIGHT),
        ) { left, right -> listOf(left, right) }
            .shareIn(
                scope = deviceTelemetryScope,
                started = SharingStarted.Eagerly,
                replay = 1,
            )

    private fun initialDeviceTelemetrySnapshot(lens: Lens): DeviceTelemetrySnapshot {
        return DeviceTelemetrySnapshot(
            lens = lens,
            timestamp = 0L,
            batteryVoltageMv = null,
            isCharging = null,
            caseBatteryPercent = null,
            caseOpen = null,
            uptimeSeconds = null,
            firmwareVersion = null,
            environment = null,
            lastAckStatus = null,
            lastAckTimestamp = null,
            lastGesture = null,
            reconnectAttempts = null,
            heartbeatLatencyMs = null,
            lastAckMode = null,
        )
    }

    private fun updateDeviceTelemetry(
        lens: Lens,
        eventTimestamp: Long,
        logUpdate: Boolean = true,
        persist: Boolean = true,
        transform: (DeviceTelemetrySnapshot) -> DeviceTelemetrySnapshot,
    ) {
        val state = lensTelemetrySnapshots.getValue(lens)
        val previous = state.value
        val candidate = transform(previous)
        val reset = candidate.uptimeSeconds != null && previous.uptimeSeconds != null &&
            candidate.uptimeSeconds < previous.uptimeSeconds
        val merged = candidate.mergeFrom(previous, allowNullReset = reset)
        val changed = merged.withoutTimestamp() != previous.withoutTimestamp()
        if (!changed) {
            return
        }
        val updated = merged.copy(timestamp = eventTimestamp)
        state.value = updated
        if (logUpdate) {
            logDeviceTelemetry(updated)
        }
        if (persist) {
            persistDeviceTelemetrySnapshots(eventTimestamp)
        }
    }

    private fun <T> mergeNonNull(previous: T?, candidate: T?, allowNullReset: Boolean): T? {
        return candidate ?: if (allowNullReset) null else previous
    }

    private fun DeviceTelemetrySnapshot.mergeFrom(
        previous: DeviceTelemetrySnapshot,
        allowNullReset: Boolean,
    ): DeviceTelemetrySnapshot {
        return copy(
            timestamp = previous.timestamp,
            batteryVoltageMv = mergeNonNull(previous.batteryVoltageMv, batteryVoltageMv, allowNullReset),
            isCharging = mergeNonNull(previous.isCharging, isCharging, allowNullReset),
            caseBatteryPercent = mergeNonNull(previous.caseBatteryPercent, caseBatteryPercent, allowNullReset),
            caseOpen = mergeNonNull(previous.caseOpen, caseOpen, allowNullReset),
            uptimeSeconds = mergeNonNull(previous.uptimeSeconds, uptimeSeconds, allowNullReset),
            firmwareVersion = mergeNonNull(previous.firmwareVersion, firmwareVersion, allowNullReset),
            environment = mergeNonNull(previous.environment, environment, allowNullReset),
            lastAckStatus = mergeNonNull(previous.lastAckStatus, lastAckStatus, allowNullReset),
            lastAckTimestamp = mergeNonNull(previous.lastAckTimestamp, lastAckTimestamp, allowNullReset),
            lastGesture = mergeNonNull(previous.lastGesture, lastGesture, allowNullReset),
            reconnectAttempts = mergeNonNull(previous.reconnectAttempts, reconnectAttempts, allowNullReset),
            heartbeatLatencyMs = mergeNonNull(previous.heartbeatLatencyMs, heartbeatLatencyMs, allowNullReset),
            lastAckMode = mergeNonNull(previous.lastAckMode, lastAckMode, allowNullReset),
        )
    }

    private fun DeviceTelemetrySnapshot.withoutTimestamp(): DeviceTelemetrySnapshot {
        return copy(timestamp = 0L)
    }

    private fun logDeviceTelemetry(snapshot: DeviceTelemetrySnapshot) {
        val envSummary = snapshot.environment
            ?.takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString(separator = " ") { (key, value) -> "$key=$value" }
        val message = buildString {
            append("batt=")
            append(snapshot.batteryVoltageMv?.let { "${it} mV" } ?: "?")
            append(' ')
            append("chg=")
            append(snapshot.isCharging?.toString() ?: "?")
            append(' ')
            append("up=")
            append(snapshot.uptimeSeconds?.let { "${it} s" } ?: "?")
            snapshot.firmwareVersion?.let { version ->
                append(' ')
                append("fw=")
                append(version)
            }
            envSummary?.let { summary ->
                append(' ')
                append(summary)
            }
            append(' ')
            append("ack=")
            append(snapshot.lastAckStatus ?: "?")
            snapshot.lastAckMode?.let { mode ->
                append(" mode=")
                append(mode.name)
            }
            snapshot.heartbeatLatencyMs?.let { latency ->
                append(' ')
                append("heartbeat=")
                append("${latency} ms")
            }
            snapshot.reconnectAttempts?.let { attempts ->
                append(' ')
                append("reconnect=")
                append(attempts)
            }
        }
        emitConsole("TELEMETRY", snapshot.lens, message.trim(), snapshot.timestamp)
    }

    private fun persistDeviceTelemetrySnapshots(recordedAt: Long) {
        val left = lensTelemetrySnapshots.getValue(Lens.LEFT).value
        val right = lensTelemetrySnapshots.getValue(Lens.RIGHT).value
        val uptime = listOfNotNull(left.uptimeSeconds, right.uptimeSeconds).maxOrNull()
        val leftRecord = left.toLensRecord()
        val rightRecord = right.toLensRecord()
        if (leftRecord.notes == null && rightRecord.notes == null && uptime == null) {
            return
        }
        val record = MemoryRepository.TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = uptime,
            left = leftRecord,
            right = rightRecord,
        )
        persistenceScope.launch {
            memory.addTelemetrySnapshot(record)
        }
    }

    private fun DeviceTelemetrySnapshot.toLensRecord(): MemoryRepository.LensSnapshot {
        val notes = buildList {
            batteryVoltageMv?.let { add("battMv=$it") }
            isCharging?.let { add("charging=$it") }
            uptimeSeconds?.let { add("uptime=$it") }
            firmwareVersion?.let { add("fw=$it") }
            lastAckStatus?.let { status ->
                val ackPart = buildString {
                    append("ack=")
                    append(status)
                    lastAckTimestamp?.let { ts ->
                        append('@')
                        append(ts)
                    }
                }
                add(ackPart)
            }
            lastGesture?.let { event ->
                add("gesture=${gestureLabel(event.gesture)}")
            }
            caseBatteryPercent?.let { add("casePct=$it") }
            caseOpen?.let { add("caseOpen=$it") }
            reconnectAttempts?.let { add("reconnect=$it") }
            heartbeatLatencyMs?.let { add("heartbeat=${it}ms") }
            lastAckMode?.let { add("ackMode=${it.name}") }
            environment?.takeIf { it.isNotEmpty() }?.let { map ->
                add(map.entries.joinToString(prefix = "env{", postfix = "}") { (key, value) -> "$key=$value" })
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ")

        return MemoryRepository.LensSnapshot(
            batteryPercent = null,
            caseBatteryPercent = caseBatteryPercent,
            caseOpen = caseOpen,
            lastUpdated = timestamp,
            rssi = null,
            firmwareVersion = firmwareVersion,
            notes = notes,
            reconnectAttempts = reconnectAttempts,
            heartbeatLatencyMs = heartbeatLatencyMs,
            lastAckMode = lastAckMode?.name,
        )
    }

    data class UartLine(val lens: Lens, val text: String)

    data class StateChangedEvent(
        val lens: Lens,
        val timestampMs: Long,
        val reason: Reason,
        val value: Boolean?,
    ) {
        enum class Reason { IN_CASE, WEARING, SILENT, CASE_OPEN }
    }

    private val _stateEvents = MutableSharedFlow<StateChangedEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val stateEvents: SharedFlow<StateChangedEvent> = _stateEvents.asSharedFlow()

    private val consoleTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val telemetryValidator: TelemetryConsistencyValidator? =
        if (SettingsRepository.isDiagnosticsEnabled()) {
            TelemetryConsistencyValidator(
                scope = deviceTelemetryScope,
                telemetry = deviceTelemetryFlow,
                caseStatusFlow = caseStatus,
                emitConsole = { tag, lens, message, timestamp ->
                    emitConsole(tag, lens, message, timestamp)
                },
            )
        } else {
            null
        }

    init {
        persistenceScope.launch {
            telemetryParser.events.collect { event ->
                handleTelemetryEvent(event)
            }
        }
    }

    private fun emitConsole(tag: String, lens: Lens?, message: String, timestamp: Long = System.currentTimeMillis()) {
        val sideLabel = lens?.let { if (it == Lens.LEFT) "L" else "R" }
        val timeLabel = synchronized(consoleTimeFormat) { consoleTimeFormat.format(Date(timestamp)) }
        val payload = buildString {
            append("[")
            append(tag)
            append("]")
            sideLabel?.let {
                append("[")
                append(it)
                append("]")
            }
            if (message.startsWith("[")) {
                append(message)
            } else {
                append(' ')
                append(message)
            }
            append(" @ ")
            append(timeLabel)
        }
        logger(payload)
        _events.tryEmit(payload)
    }

    private fun statePriority(reason: StateChangedEvent.Reason): Int = when (reason) {
        StateChangedEvent.Reason.IN_CASE -> 0
        StateChangedEvent.Reason.WEARING -> 1
        StateChangedEvent.Reason.SILENT -> 2
        StateChangedEvent.Reason.CASE_OPEN -> 3
    }

    private fun batteryPriority(opcode: Int): Int = when (opcode) {
        BATTERY_OPCODE -> 3
        STATUS_OPCODE -> 2
        ASCII_NOTIFY_OPCODE -> 1
        else -> 0
    }

    private fun chargingPriority(opcode: Int): Int = when (opcode) {
        STATUS_OPCODE -> 2
        ASCII_NOTIFY_OPCODE -> 1
        else -> 0
    }

    private fun firmwarePriority(opcode: Int): Int = when (opcode) {
        BATTERY_OPCODE -> 3
        ASCII_NOTIFY_OPCODE -> 1
        FIRMWARE_OPCODE -> 2
        else -> 0
    }

    private fun shouldAdopt(
        existingSource: Int?,
        existingTimestamp: Long?,
        newSource: Int,
        newTimestamp: Long,
        priority: (Int) -> Int,
    ): Boolean {
        val currentPriority = existingSource?.let(priority) ?: Int.MIN_VALUE
        val incomingPriority = priority(newSource)
        if (incomingPriority > currentPriority) {
            return true
        }
        if (incomingPriority < currentPriority) {
            return existingTimestamp?.let { newTimestamp - it > SOURCE_DISAGREE_WINDOW_MS } ?: true
        }
        return existingTimestamp?.let { newTimestamp >= it } ?: true
    }

    private val versionRegex = Pattern.compile(
        "ver\\s+([0-9]+\\.[0-9]+\\.[0-9]+).*?(DeviceID|DeviceId|DevId)\\s+(\\d+)",
        Pattern.CASE_INSENSITIVE
    )
    private val buildRegex = Pattern.compile(
        "net\\s+build\\s+time\\s*:\\s*(.+?)(?:,|$)",
        Pattern.CASE_INSENSITIVE
    )

    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private var ackJob: Job? = null
    private var stabilityJob: Job? = null
    private var dashboardStatusJob: Job? = null
    private var dashboardEncoder: DashboardDataEncoder? = null
    private var boundService: MoncchichiBleService? = null
    private var lastConnected = false
    private var serviceScope: CoroutineScope? = null
    private val lastAudioFrameTime = AtomicLong(0L)
    private var micWatchdogJob: Job? = null

    private val leftBuffer = ByteArrayOutputStream()
    private val rightBuffer = ByteArrayOutputStream()
    private data class KeepAliveSnapshot(
        var lastAt: Long? = null,
        var rtt: Long? = null,
        var failures: Int = 0,
        var lockSkips: Int = 0,
        var ackTimeouts: Int = 0,
    )
    private val keepAliveSnapshots = mutableMapOf<Lens, KeepAliveSnapshot>()
    private val stabilityHistory = ArrayDeque<String>()

    fun reset() {
        _snapshot.value = Snapshot()
        emitConsole("DIAG", null, "telemetry reset")
        clearBuffers()
        keepAliveSnapshots.clear()
        synchronized(stabilityHistory) { stabilityHistory.clear() }
        _micAvailability.value = false
        _battery.value = null
        _uptime.value = null
        _deviceStatus.value = null
        _wearingStatus.value = DeviceStatus()
        _inCaseStatus.value = DeviceStatus()
        _chargingStatus.value = DeviceStatus()
        _caseStatus.value = CaseStatus()
        lensTelemetrySnapshots.forEach { (lens, flow) ->
            flow.value = initialDeviceTelemetrySnapshot(lens)
        }
        telemetryValidator?.reset()
        micWatchdogJob?.cancel()
        micWatchdogJob = null
        lastAudioFrameTime.set(0L)
        _dashboardStatus.value = DashboardDataEncoder.BurstStatus()
        serviceScope?.let { scope ->
            if (boundService != null) {
                startMicWatchdog(scope)
            }
        }
    }

    fun bindToService(service: MoncchichiBleService, scope: CoroutineScope) {
        unbind()
        boundService = service
        serviceScope = scope
        lastConnected = service.state.value.left.isConnected || service.state.value.right.isConnected
        dashboardEncoder = DashboardDataEncoder(
            scope = scope,
            writer = { payload, target -> service.send(payload, target) },
        ).also { encoder ->
            dashboardStatusJob = scope.launch {
                encoder.status.collect { status ->
                    _dashboardStatus.value = status
                }
            }
        }
        startMicWatchdog(scope)
        frameJob = scope.launch {
            service.incoming.collect { frame ->
                onFrame(frame.lens, frame.payload)
            }
        }
        stateJob = scope.launch {
            service.state.collectLatest { state ->
                val connected = state.left.isConnected || state.right.isConnected
                if (!connected && lastConnected) {
                    reset()
                }
                if (connected && !lastConnected) {
                    val timestamp = System.currentTimeMillis()
                    val cleared = service.clearDisplay(Lens.RIGHT)
                    val message = if (cleared) {
                        "Cleared display"
                    } else {
                        "Clear display failed"
                    }
                    emitConsole("HUD", Lens.RIGHT, message, timestamp)
                }
                lastConnected = connected
                if (!connected) {
                    _micAvailability.value = false
                    lastAudioFrameTime.set(0L)
                }
                mergeRssi(Lens.LEFT, state.left.rssi)
                mergeRssi(Lens.RIGHT, state.right.rssi)
                mergeKeepAlive(Lens.LEFT, state.left)
                mergeKeepAlive(Lens.RIGHT, state.right)
                mergeBondState(Lens.LEFT, state.left.bonded)
                mergeBondState(Lens.RIGHT, state.right.bonded)
                mergeDisconnectReason(Lens.LEFT, state.left.disconnectStatus)
                mergeDisconnectReason(Lens.RIGHT, state.right.disconnectStatus)
                mergeBondDiagnostics(Lens.LEFT, state.left)
                mergeBondDiagnostics(Lens.RIGHT, state.right)
                mergeReconnectDiagnostics(Lens.LEFT, state.left)
                mergeReconnectDiagnostics(Lens.RIGHT, state.right)
                mergeServiceCounters(state)
                updateConnectionSequence(state.connectionOrder)
            }
        }
        ackJob = scope.launch {
            service.ackEvents.collect { event ->
                onAck(event)
            }
        }
        stabilityJob = scope.launch {
            service.stabilityMetrics.collect { metrics ->
                onStabilityMetrics(metrics)
            }
        }
    }

    fun unbind() {
        frameJob?.cancel(); frameJob = null
        stateJob?.cancel(); stateJob = null
        ackJob?.cancel(); ackJob = null
        stabilityJob?.cancel(); stabilityJob = null
        dashboardStatusJob?.cancel(); dashboardStatusJob = null
        dashboardEncoder?.dispose(); dashboardEncoder = null
        micWatchdogJob?.cancel(); micWatchdogJob = null
        boundService = null
        serviceScope = null
        _dashboardStatus.value = DashboardDataEncoder.BurstStatus()
        _micAvailability.value = false
        lastAudioFrameTime.set(0L)
        lastConnected = false
        clearBuffers()
        keepAliveSnapshots.clear()
        synchronized(stabilityHistory) { stabilityHistory.clear() }
    }

    suspend fun sendMicToggle(enabled: Boolean, lens: Lens = Lens.RIGHT): Boolean {
        val service = boundService ?: return false
        val timestamp = System.currentTimeMillis()
        val ok = service.setMicEnabled(lens, enabled)
        if (ok) {
            SettingsRepository.setMicEnabled(enabled)
            emitConsole("DIAG", lens, "Mic ${if (enabled) "enabled" else "disabled"}", timestamp)
        } else {
            emitConsole("DIAG", lens, "Mic toggle failed (${if (enabled) "enable" else "disable"})", timestamp)
        }
        return ok
    }

    fun enqueueDashboardBurst(
        subcommand: Int,
        payload: ByteArray,
        target: MoncchichiBleService.Target = MoncchichiBleService.Target.Right,
    ) {
        val encoder = dashboardEncoder
        if (encoder == null) {
            emitConsole("DIAG", target.toLensOrNull(), "Dashboard encoder not ready", System.currentTimeMillis())
            return
        }
        val chunkCount = encoder.estimateChunkCount(payload.size)
        val timestamp = System.currentTimeMillis()
        emitConsole(
            "DIAG",
            target.toLensOrNull(),
            "Dashboard burst sub=0x%02X chunks=%d bytes=%d".format(Locale.US, subcommand and 0xFF, chunkCount, payload.size),
            timestamp,
        )
        encoder.enqueue(subcommand, payload, target)
    }

    private fun onStabilityMetrics(metrics: MoncchichiBleService.BleStabilityMetrics) {
        val lens = metrics.lens
        val rssiLabel = metrics.avgRssi?.toString() ?: "n/a"
        val ackLabel = metrics.lastAckDeltaMs?.let { "${it} ms" } ?: "n/a"
        val reconnectLabel = metrics.reconnectLatencyMs?.let { "${it} ms" } ?: "n/a"
        val message =
            "HB=${metrics.heartbeatCount} MISS=${metrics.missedHeartbeats} " +
                "REBOND=${metrics.rebondEvents} RSSI=$rssiLabel ΔACK=$ackLabel RECON=$reconnectLabel"
        emitConsole("STABILITY", lens, message, metrics.timestamp)
        val historyEntry = buildString {
            lens?.let { append(if (it == Lens.LEFT) "[L] " else "[R] ") }
            append(message)
        }
        synchronized(stabilityHistory) {
            if (stabilityHistory.size >= 5) {
                stabilityHistory.removeFirst()
            }
            stabilityHistory.addLast(historyEntry)
        }
        persistenceScope.launch {
            memory.addConsoleLine("[STABILITY] $historyEntry")
        }
    }

    fun onFrame(lens: Lens, frame: ByteArray) {
        if (frame.isEmpty()) return
        val opcode = frame.first().toInt() and 0xFF
        val timestamp = System.currentTimeMillis()
        val parseResult = telemetryParser.parse(lens, frame, timestamp)
        if (parseResult.eventsEmitted) {
            return
        }
        when (val parsed = G1ReplyParser.parseNotify(frame)) {
            is G1ReplyParser.Parsed.Vitals -> {
                handleParsedVitals(lens, parsed.vitals, frame)
                if (opcode != BATTERY_OPCODE && opcode != STATUS_OPCODE) {
                    return
                }
            }
            is G1ReplyParser.Parsed.Ack -> return
            else -> Unit
        }
        if (!parseResult.handlerFound) {
            logParserFallback(opcode, timestamp)
        }
        when (opcode) {
            BATTERY_OPCODE, STATUS_OPCODE -> handleBattery(lens, frame)
            UPTIME_OPCODE -> handleUptime(lens, frame)
            FIRMWARE_OPCODE -> handleFirmware(lens, frame)
            else -> {
                if (decodeBinary(lens, frame)) {
                    return
                }
                val emittedDirect = maybeEmitUtf8(lens, frame)
                if (!emittedDirect) {
                    val emittedBuffered = maybeAssembleUtf8Buffered(lens, frame)
                    if (!emittedBuffered) {
                        logRaw(lens, frame)
                    }
                }
            }
        }
    }

    private fun handleTelemetryEvent(event: BleTelemetryParser.TelemetryEvent) {
        when (event) {
            is BleTelemetryParser.TelemetryEvent.DeviceStatusEvent -> {
                val hex = event.rawFrame.toHex()
                applyStateFlags(event.lens, event.flags, event.timestampMs, hex)
                _deviceStatus.value = DeviceState(
                    wearing = event.flags.wearing,
                    inCase = event.flags.inCradle,
                    silentMode = event.flags.silentMode,
                    caseOpen = event.flags.caseOpen,
                )
                _wearingStatus.update(event.lens, event.flags.wearing, event.timestampMs)
                _inCaseStatus.update(event.lens, event.flags.inCradle, event.timestampMs)
                updateCaseStatus(
                    timestamp = event.timestampMs,
                    lidOpen = event.flags.caseOpen,
                    silentMode = event.flags.silentMode,
                )
                updateDeviceTelemetry(
                    lens = event.lens,
                    eventTimestamp = event.timestampMs,
                    logUpdate = false,
                ) { snapshot ->
                    snapshot.copy(caseOpen = event.flags.caseOpen)
                }
            }
            is BleTelemetryParser.TelemetryEvent.BatteryEvent -> {
                event.info?.let { info ->
                    _battery.value = info
                    updateDeviceTelemetry(event.lens, event.timestampMs) { snapshot ->
                        snapshot.copy(
                            batteryVoltageMv = info.voltage,
                            isCharging = info.isCharging,
                        )
                    }
                    emitConsole(
                        "VITALS",
                        event.lens,
                        "${info.voltage} mV charging=${info.isCharging}",
                        event.timestampMs,
                    )
                }
                event.caseBatteryPercent?.let { percent ->
                    updateCaseStatus(
                        timestamp = event.timestampMs,
                        batteryPercent = percent,
                    )
                }
                if (event.batteryPercent != null || event.caseBatteryPercent != null) {
                    applyBatteryEvent(event)
                } else {
                    handleBattery(event.lens, event.rawFrame)
                }
            }
            is BleTelemetryParser.TelemetryEvent.EnvironmentSnapshotEvent -> {
                handleEnvironmentSnapshot(event)
            }
            is BleTelemetryParser.TelemetryEvent.UptimeEvent -> {
                applyUptimeEvent(event)
            }
            is BleTelemetryParser.TelemetryEvent.AckEvent -> {
                handleAckEvent(event)
            }
            is BleTelemetryParser.TelemetryEvent.GestureEvent -> {
                val inCase = _inCaseStatus.value.valueFor(event.lens)
                val lidOpen = _caseStatus.value.lidOpen
                if (inCase == true || lidOpen == false) {
                    return
                }
                val wearing = _wearingStatus.value.valueFor(event.lens)
                val caseOpen = lidOpen == true
                if (wearing != true && !caseOpen) {
                    return
                }
                val lensGesture = LensGestureEvent(event.lens, event.gesture)
                _gesture.tryEmit(lensGesture)
                val label = gestureLabel(event.gesture)
                emitConsole("GESTURE", event.lens, label, event.timestampMs)
                updateDeviceTelemetry(
                    event.lens,
                    event.timestampMs,
                    logUpdate = false,
                    persist = false,
                ) { snapshot ->
                    snapshot.copy(lastGesture = lensGesture)
                }
            }
            is BleTelemetryParser.TelemetryEvent.SystemEvent -> {
                handleSystemEvent(event)
            }
            is BleTelemetryParser.TelemetryEvent.AudioPacketEvent -> {
                handleAudioPacket(event)
            }
            is BleTelemetryParser.TelemetryEvent.F5Event -> {
                handleF5Event(event)
            }
            is BleTelemetryParser.TelemetryEvent.CaseUpdate -> {
                updateCaseStatus(
                    timestamp = event.timestampMs,
                    batteryPercent = event.caseBatteryPercent,
                    charging = event.charging,
                    lidOpen = event.lidOpen,
                )
            }
        }
    }

    private fun handleEnvironmentSnapshot(event: BleTelemetryParser.TelemetryEvent.EnvironmentSnapshotEvent) {
        val components = buildList {
            event.textValue?.takeIf { it.isNotBlank() }?.let { add(it) }
            event.numericValue?.let { add(it.toString()) }
            if (isEmpty() && event.payload.isNotEmpty()) {
                add(event.payload.toHex())
            }
        }
        val value = components.joinToString(separator = ", ")
        updateDeviceTelemetry(event.lens, event.timestampMs) { snapshot ->
            val existing = snapshot.environment?.let { LinkedHashMap(it) } ?: LinkedHashMap<String, String>()
            existing[event.key] = value
            snapshot.copy(environment = existing)
        }
        val message = buildString {
            append(event.key)
            append('=')
            append(value)
        }
        emitConsole("ENV", event.lens, message, event.timestampMs)
    }

    private fun handleAckEvent(event: BleTelemetryParser.TelemetryEvent.AckEvent) {
        val statusLabel = event.ackCode?.let { describeAckCode(it) } ?: "unknown"
        val outcome = when {
            event.busy -> "busy"
            event.success == true -> "success"
            event.success == false -> "error"
            else -> "pending"
        }
        val payloadHex = event.payload.takeIf { it.isNotEmpty() }?.toHex()
        val message = buildString {
            append(statusLabel)
            append(' ')
            append(outcome)
            event.sequence?.let { seq ->
                append(" seq=")
                append(seq)
            }
            payloadHex?.let { hex ->
                append(" payload=")
                append(hex)
            }
        }
        emitConsole("ACK", event.lens, message.trim(), event.timestampMs)
        val ackStatus = event.ackCode?.let { describeAckCode(it).uppercase(Locale.US) }
            ?: when {
                event.busy -> "BUSY"
                event.success == true -> "SUCCESS"
                event.success == false -> "FAIL"
                else -> "PENDING"
            }
        updateDeviceTelemetry(event.lens, event.timestampMs) { snapshot ->
            snapshot.copy(
                lastAckStatus = ackStatus,
                lastAckTimestamp = event.timestampMs,
                lastAckMode = MoncchichiBleService.AckType.BINARY,
            )
        }
    }

    private fun describeAckCode(code: Int): String {
        return when (code) {
            STATUS_OK -> "ok"
            STATUS_BUSY -> "busy"
            OPC_ACK_CONTINUE -> "continue"
            OPC_ACK_COMPLETE -> "complete"
            else -> "0x%02X".format(Locale.US, code and 0xFF)
        }
    }

    private fun logParserFallback(opcode: Int, timestamp: Long) {
        if (missingOpcodeLog.add(opcode)) {
            emitConsole("DIAG", null, "Legacy fallback for ${opcode.toHexLabel()}", timestamp)
        }
    }

    private fun applyStateFlags(
        lens: Lens,
        flags: G1ReplyParser.StateFlags,
        timestamp: Long,
        hex: String,
    ) {
        var reasonToEmit: StateChangedEvent.Reason? = null
        var reasonValue: Boolean? = null
        updateSnapshot(eventTimestamp = timestamp) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var changed = false

            if (existing.inCase != flags.inCradle) {
                changed = true
                updated = updated.copy(inCase = flags.inCradle)
                if (reasonToEmit == null || statePriority(StateChangedEvent.Reason.IN_CASE) < statePriority(reasonToEmit!!)) {
                    reasonToEmit = StateChangedEvent.Reason.IN_CASE
                    reasonValue = flags.inCradle
                }
            }
            if (existing.wearing != flags.wearing) {
                changed = true
                updated = updated.copy(wearing = flags.wearing)
                if (reasonToEmit == null || statePriority(StateChangedEvent.Reason.WEARING) < statePriority(reasonToEmit!!)) {
                    reasonToEmit = StateChangedEvent.Reason.WEARING
                    reasonValue = flags.wearing
                }
            }
            if (existing.silentMode != flags.silentMode) {
                changed = true
                updated = updated.copy(silentMode = flags.silentMode)
                if (reasonToEmit == null || statePriority(StateChangedEvent.Reason.SILENT) < statePriority(reasonToEmit!!)) {
                    reasonToEmit = StateChangedEvent.Reason.SILENT
                    reasonValue = flags.silentMode
                }
            }
            if (existing.caseOpen != flags.caseOpen) {
                changed = true
                updated = updated.copy(caseOpen = flags.caseOpen)
                if (reasonToEmit == null || statePriority(StateChangedEvent.Reason.CASE_OPEN) < statePriority(reasonToEmit!!)) {
                    reasonToEmit = StateChangedEvent.Reason.CASE_OPEN
                    reasonValue = flags.caseOpen
                }
            }
            if (changed) {
                updated = updated.copy(lastUpdatedAt = timestamp)
            }
            if (existing.lastStateUpdatedAt != timestamp) {
                updated = updated.copy(lastStateUpdatedAt = timestamp)
            }
            val next = if (updated == existing) current else current.updateLens(lens, updated)
            next.withFrame(lens, hex)
        }
        reasonToEmit?.let { reason ->
            val message = when (reason) {
                StateChangedEvent.Reason.IN_CASE -> if (flags.inCradle) "In case" else "Out of case"
                StateChangedEvent.Reason.WEARING -> if (flags.wearing && !flags.inCradle) "Now wearing" else "Not wearing"
                StateChangedEvent.Reason.SILENT -> if (flags.silentMode) "Silent on" else "Silent off"
                StateChangedEvent.Reason.CASE_OPEN -> if (flags.caseOpen) "Case open" else "Case closed"
            }
            emitConsole("STATE", lens, message, timestamp)
            _stateEvents.tryEmit(StateChangedEvent(lens, timestamp, reason, reasonValue))
        }
    }

    private fun applyBatteryEvent(event: BleTelemetryParser.TelemetryEvent.BatteryEvent) {
        val lens = event.lens
        val lensBattery = event.batteryPercent
        val caseBattery = event.caseBatteryPercent
        if (lensBattery == null && caseBattery == null) {
            logRaw(lens, event.rawFrame)
            return
        }
        val opcode = event.opcode
        val timestamp = event.timestampMs
        val hex = event.rawFrame.toHex()
        var batteryChanged = false
        var caseChanged = false
        updateSnapshot(eventTimestamp = timestamp) { current ->
            val existing = current.lens(lens)
            var updated = existing
            val authoritativeFresh = existing.batterySourceOpcode == BATTERY_OPCODE &&
                existing.batteryUpdatedAt?.let { timestamp - it < BATTERY_STICKY_WINDOW_MS } == true
            lensBattery?.let { percent ->
                val shouldIgnoreQuickStatus = opcode != BATTERY_OPCODE && authoritativeFresh
                if (!shouldIgnoreQuickStatus && shouldAdopt(existing.batterySourceOpcode, existing.batteryUpdatedAt, opcode, timestamp, ::batteryPriority)) {
                    if (existing.batteryPercent != percent || existing.batterySourceOpcode != opcode) {
                        batteryChanged = true
                    }
                    updated = updated.copy(
                        batteryPercent = percent,
                        batterySourceOpcode = opcode,
                        batteryUpdatedAt = timestamp,
                        lastUpdatedAt = timestamp,
                    )
                }
            }
            caseBattery?.let { value ->
                if (existing.caseBatteryPercent != value) {
                    caseChanged = true
                    updated = updated.copy(caseBatteryPercent = value, lastUpdatedAt = timestamp)
                }
            }
            if (lensBattery != null || caseBattery != null) {
                val history = (updated.powerHistory + PowerFrame(opcode, hex, timestamp)).takeLast(POWER_HISTORY_SIZE)
                if (history != updated.powerHistory) {
                    updated = updated.copy(powerHistory = history)
                }
                updated = updated.copy(lastPowerOpcode = opcode, lastPowerUpdatedAt = timestamp)
            }
            val next = if (updated == existing) current else current.updateLens(lens, updated)
            next.withFrame(lens, hex)
        }
        val parts = mutableListOf<String>()
        if (batteryChanged) {
            lensBattery?.let { percent -> parts += "battery=${percent}%@${opcode.toHexLabel()}" }
        }
        if (caseChanged) {
            caseBattery?.let { percent -> parts += "case=${percent}%" }
        }
        if (parts.isNotEmpty()) {
            emitConsole("VITALS", lens, parts.joinToString(", "), timestamp)
        }
    }

    private fun applyUptimeEvent(event: BleTelemetryParser.TelemetryEvent.UptimeEvent) {
        val lens = event.lens
        val timestamp = event.timestampMs
        val hex = event.rawFrame.toHex()
        val uptime = event.uptimeSeconds
        _uptime.value = uptime
        updateSnapshot(eventTimestamp = timestamp) { current ->
            if (current.uptimeSeconds == uptime && current.lastLens == lens && current.lastFrameHex == hex) {
                current
            } else {
                current.copy(uptimeSeconds = uptime).withFrame(lens, hex)
            }
        }
        emitConsole("UPTIME", lens, "secs=${uptime}", timestamp)
        updateDeviceTelemetry(lens, timestamp) { snapshot ->
            snapshot.copy(uptimeSeconds = uptime)
        }
    }

    private fun handleAudioPacket(event: BleTelemetryParser.TelemetryEvent.AudioPacketEvent) {
        val sequence = event.sequence
        if (sequence == null) {
            emitConsole("DIAG", event.lens, "Audio packet missing sequence", event.timestampMs)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previous = lastAudioFrameTime.getAndSet(now)
        val wasInactive = !_micAvailability.value
        _micAvailability.value = true
        if (wasInactive) {
            val gap = if (previous != 0L) now - previous else 0L
            val suffix = if (gap > 0L) " after ${gap} ms" else ""
            logger("[AUDIO][WATCHDOG] BLE mic reactivated${suffix}")
        }
        _micPackets.tryEmit(event)
    }

    private fun startMicWatchdog(scope: CoroutineScope) {
        micWatchdogJob?.cancel()
        micWatchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000)
                val last = lastAudioFrameTime.get()
                if (last == 0L) {
                    continue
                }
                val delta = SystemClock.elapsedRealtime() - last
                if (delta > 2000) {
                    val wasAvailable = _micAvailability.value
                    if (wasAvailable) {
                        _micAvailability.value = false
                        MicStreamManager().restart()
                    }
                }
            }
        }
    }

    private fun handleF5Event(event: BleTelemetryParser.TelemetryEvent.F5Event) {
        event.vitals?.let { vitals ->
            handleParsedVitals(event.lens, vitals, event.rawFrame)
        }
        event.evenAiEvent?.let { evenAi ->
            val message = describeEvenAi(evenAi)
            emitConsole("GESTURE", event.lens, message, event.timestampMs)
        }
    }

    private fun handleSystemEvent(event: BleTelemetryParser.TelemetryEvent.SystemEvent) {
        val lens = event.lens
        val timestamp = event.timestampMs
        val eventCode = event.eventCode
        event.wearing?.let { value ->
            _wearingStatus.update(lens, value, timestamp)
            val label = if (value) "wearing" else "not_wearing"
            emitConsole("WEAR", lens, label, timestamp)
        }
        event.charging?.let { value ->
            _chargingStatus.update(lens, value, timestamp)
            if (eventCode == 0x0E) {
                val label = if (value == true) "charging" else "not_charging"
                emitConsole("CASE", lens, label, timestamp)
            } else {
                emitConsole("CHG", lens, if (value) "on" else "off", timestamp)
            }
        }

        if (event.caseOpen != null || event.caseBatteryPercent != null || (eventCode == 0x0E && event.charging != null)) {
            updateCaseStatus(
                timestamp = timestamp,
                lidOpen = event.caseOpen,
                batteryPercent = event.caseBatteryPercent,
                charging = event.charging.takeIf { eventCode == 0x0E },
            )
        }

        updateSnapshot(eventTimestamp = timestamp) { current ->
            var telemetry = current.lens(lens)
            var changed = false
            var stateChanged = false
            event.wearing?.let { value ->
                if (telemetry.wearing != value) {
                    telemetry = telemetry.copy(wearing = value)
                    changed = true
                    stateChanged = true
                }
            }
            if (eventCode != 0x0E) {
                event.charging?.let { value ->
                    if (telemetry.charging != value) {
                        telemetry = telemetry.copy(
                            charging = value,
                            chargingSourceOpcode = event.opcode,
                            chargingUpdatedAt = timestamp,
                        )
                        changed = true
                    }
                }
            }
            if (!changed) {
                current
            } else {
                val updatedTelemetry = telemetry.copy(
                    lastUpdatedAt = timestamp,
                    lastStateUpdatedAt = if (stateChanged) timestamp else telemetry.lastStateUpdatedAt,
                )
                current.updateLens(lens, updatedTelemetry)
            }
        }

        if (eventCode != 0x0E && event.charging != null) {
            updateDeviceTelemetry(
                lens = lens,
                eventTimestamp = timestamp,
                logUpdate = false,
            ) { snapshot ->
                snapshot.copy(isCharging = event.charging)
            }
        }
    }

    private fun updateCaseStatus(
        timestamp: Long,
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        lidOpen: Boolean? = null,
        silentMode: Boolean? = null,
    ) {
        val current = _caseStatus.value
        var changed = false
        var batteryChanged = false
        var lidChanged = false
        var nextBattery = current.batteryPercent
        var nextCharging = current.charging
        var nextLid = current.lidOpen
        var nextSilent = current.silentMode

        batteryPercent?.let { value ->
            if (value != nextBattery) {
                nextBattery = value
                changed = true
                batteryChanged = true
            }
        }
        charging?.let { value ->
            if (value != nextCharging) {
                nextCharging = value
                changed = true
            }
        }
        lidOpen?.let { value ->
            if (value != nextLid) {
                nextLid = value
                changed = true
                lidChanged = true
            }
        }
        silentMode?.let { value ->
            if (value != nextSilent) {
                nextSilent = value
                changed = true
            }
        }
        if (!changed) {
            return
        }
        val updated = CaseStatus(
            batteryPercent = nextBattery,
            charging = nextCharging,
            lidOpen = nextLid,
            silentMode = nextSilent,
            updatedAt = timestamp,
        )
        _caseStatus.value = updated
        emitCaseConsole(updated, timestamp)
        propagateCaseTelemetry(updated, timestamp, batteryChanged, lidChanged)
    }

    private fun emitCaseConsole(status: CaseStatus, timestamp: Long) {
        val parts = buildList {
            status.batteryPercent?.let { add("battery=${it}%") }
            status.charging?.let { add("charging=${it}") }
            status.lidOpen?.let { add("lid=${if (it) "open" else "closed"}") }
            status.silentMode?.let { add("silent=${if (it) "true" else "false"}") }
        }
        if (parts.isEmpty()) {
            return
        }
        emitConsole("CASE", null, parts.joinToString(separator = " "), timestamp)
    }

    private fun propagateCaseTelemetry(
        status: CaseStatus,
        timestamp: Long,
        batteryChanged: Boolean,
        lidChanged: Boolean,
    ) {
        if (!batteryChanged && !lidChanged) {
            return
        }
        MoncchichiBleService.Lens.values().forEach { lens ->
            updateDeviceTelemetry(
                lens = lens,
                eventTimestamp = timestamp,
                logUpdate = false,
                persist = false,
            ) { snapshot ->
                var updated = snapshot
                if (batteryChanged) {
                    updated = updated.copy(caseBatteryPercent = status.batteryPercent)
                }
                if (lidChanged) {
                    updated = updated.copy(caseOpen = status.lidOpen)
                }
                updated
            }
        }
        if (batteryChanged || lidChanged) {
            updateSnapshot(eventTimestamp = timestamp) { current ->
                var left = current.left
                var right = current.right
                var snapshotChanged = false
                if (batteryChanged && left.caseBatteryPercent != status.batteryPercent) {
                    left = left.copy(caseBatteryPercent = status.batteryPercent, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (batteryChanged && right.caseBatteryPercent != status.batteryPercent) {
                    right = right.copy(caseBatteryPercent = status.batteryPercent, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (lidChanged && left.caseOpen != status.lidOpen) {
                    left = left.copy(caseOpen = status.lidOpen, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (lidChanged && right.caseOpen != status.lidOpen) {
                    right = right.copy(caseOpen = status.lidOpen, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (!snapshotChanged) {
                    current
                } else {
                    current.copy(left = left, right = right)
                }
            }
        }
    }

    private fun requestCaseRefresh(lens: Lens) {
        val service = boundService ?: return
        val scope = serviceScope ?: return
        scope.launch {
            val target = when (lens) {
                Lens.LEFT -> MoncchichiBleService.Target.Left
                Lens.RIGHT -> MoncchichiBleService.Target.Right
            }
            val statusPayload = byteArrayOf(G1Protocols.OPC_DEVICE_STATUS.toByte())
            service.send(statusPayload, target)
            service.send(G1Packets.batteryQuery(), target)
        }
    }

    private fun gestureLabel(gesture: G1ReplyParser.GestureEvent): String {
        return when (gesture.code) {
            0x01 -> "single"
            0x02 -> "double"
            0x04 -> "hold"
            else -> gesture.name.lowercase(Locale.US).replace('_', ' ')
        }
    }

    private fun describeEvenAi(event: G1ReplyParser.EvenAiEvent): String {
        return when (event) {
            G1ReplyParser.EvenAiEvent.ActivationRequested -> "Activation requested"
            G1ReplyParser.EvenAiEvent.RecordingStopped -> "Recording stopped"
            is G1ReplyParser.EvenAiEvent.ManualExit -> "Manual exit (${event.gesture.name.lowercase(Locale.US)})"
            is G1ReplyParser.EvenAiEvent.ManualPaging -> "Manual paging (${event.gesture.name.lowercase(Locale.US)})"
            is G1ReplyParser.EvenAiEvent.SilentModeToggle -> "Silent toggle (${event.gesture.name.lowercase(Locale.US)})"
            is G1ReplyParser.EvenAiEvent.Unknown -> "Even AI 0x%02X".format(Locale.US, event.subcommand)
        }
    }

    private fun handleParsedVitals(
        lens: Lens,
        vitals: G1ReplyParser.DeviceVitals,
        frame: ByteArray,
    ) {
        val timestamp = System.currentTimeMillis()
        val hex = frame.toHex()
        val opcode = frame.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val trustedBattery = when (opcode) {
            ASCII_NOTIFY_OPCODE -> vitals.batteryPercent?.takeIf { it in 0..100 }
            STATUS_OPCODE -> null
            else -> vitals.batteryPercent?.takeIf { it in 0..100 }
        }
        val charging = vitals.charging
        val firmwareBanner = vitals.firmwareVersion?.takeIf { it.isNotBlank() }

        var batteryChanged = false
        var chargingChanged = false
        var firmwareChanged = false

        updateSnapshot(eventTimestamp = timestamp) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var lensUpdatedAt = existing.lastUpdatedAt
            var powerHistory = existing.powerHistory

            trustedBattery?.let { percent ->
                if (shouldAdopt(existing.batterySourceOpcode, existing.batteryUpdatedAt, opcode, timestamp, ::batteryPriority)) {
                    if (existing.batteryPercent != percent || existing.batterySourceOpcode != opcode) {
                        batteryChanged = true
                    }
                    updated = updated.copy(
                        batteryPercent = percent,
                        batterySourceOpcode = opcode,
                        batteryUpdatedAt = timestamp,
                        lastUpdatedAt = timestamp,
                    )
                    lensUpdatedAt = timestamp
                }
            }

            charging?.let { value ->
                if (shouldAdopt(existing.chargingSourceOpcode, existing.chargingUpdatedAt, opcode, timestamp, ::chargingPriority)) {
                    if (existing.charging != value || existing.chargingSourceOpcode != opcode) {
                        chargingChanged = true
                    }
                    updated = updated.copy(
                        charging = value,
                        chargingSourceOpcode = opcode,
                        chargingUpdatedAt = timestamp,
                        lastUpdatedAt = timestamp,
                    )
                    lensUpdatedAt = timestamp
                }
            }

            firmwareBanner?.let { banner ->
                if (shouldAdopt(existing.firmwareSourceOpcode, existing.firmwareUpdatedAt, opcode, timestamp, ::firmwarePriority)) {
                    if (existing.firmwareVersion != banner || existing.firmwareSourceOpcode != opcode) {
                        firmwareChanged = true
                    }
                    updated = updated.copy(
                        firmwareVersion = banner,
                        firmwareSourceOpcode = opcode,
                        firmwareUpdatedAt = timestamp,
                        lastUpdatedAt = timestamp,
                    )
                    lensUpdatedAt = timestamp
                }
            }

            if (opcode == ASCII_NOTIFY_OPCODE && (trustedBattery != null || charging != null)) {
                powerHistory = (powerHistory + PowerFrame(opcode, hex, timestamp)).takeLast(POWER_HISTORY_SIZE)
            }

            if (powerHistory !== updated.powerHistory) {
                updated = updated.copy(powerHistory = powerHistory)
            }

            if (batteryChanged || chargingChanged) {
                updated = updated.copy(lastPowerOpcode = opcode, lastPowerUpdatedAt = timestamp)
            }

            if (updated != existing) {
                updated = updated.copy(lastUpdatedAt = lensUpdatedAt)
                current.updateLens(lens, updated).withFrame(lens, hex)
            } else {
                current.withFrame(lens, hex)
            }
        }

        firmwareBanner?.let { banner ->
            updateDeviceTelemetry(lens, timestamp) { snapshot ->
                if (snapshot.firmwareVersion == banner) snapshot else snapshot.copy(firmwareVersion = banner)
            }
        }

        val parts = mutableListOf<String>()
        if (batteryChanged) {
            trustedBattery?.let { percent ->
                parts += "battery=${percent}%@${opcode.toHexLabel()}"
            }
        }
        if (chargingChanged) {
            charging?.let { value ->
                parts += if (value) "charging@${opcode.toHexLabel()}" else "not charging@${opcode.toHexLabel()}"
            }
        }
        if (firmwareChanged) {
            firmwareBanner?.let { banner ->
                parts += "fw=${banner} (${opcode.toHexLabel()})"
            }
        }
        if (parts.isNotEmpty()) {
            emitConsole("VITALS", lens, parts.joinToString(separator = ", " ), timestamp)
        }

        if (firmwareChanged) {
            firmwareBanner?.let { line ->
                parseTextMetadata(lens, line)
                _uartText.tryEmit(UartLine(lens, line))
            }
        }
    }

    private fun handleBattery(lens: Lens, frame: ByteArray) {
        val notifyFrame = G1ReplyParser.decodeFrame(frame)
        val status = notifyFrame?.let { G1ReplyParser.parseBattery(it) }
        val lensBattery = status?.batteryPercent
        val caseBattery = status?.caseBatteryPercent
        if (lensBattery == null && caseBattery == null) {
            logRaw(lens, frame)
            return
        }
        val timestamp = System.currentTimeMillis()
        val opcode = notifyFrame?.opcode ?: BATTERY_OPCODE
        val event = BleTelemetryParser.TelemetryEvent.BatteryEvent(
            lens = lens,
            timestampMs = timestamp,
            opcode = opcode,
            batteryPercent = lensBattery,
            caseBatteryPercent = caseBattery,
            info = notifyFrame?.payload?.let { payload ->
                runCatching { G1ReplyParser.parseBattery(payload) }.getOrNull()
            },
            rawFrame = frame.copyOf(),
        )
        applyBatteryEvent(event)
    }

    private fun handleUptime(lens: Lens, frame: ByteArray) {
        val notifyFrame = G1ReplyParser.decodeFrame(frame)
        val uptime = notifyFrame?.let { G1ReplyParser.parseUptime(it) }
        if (notifyFrame == null || uptime == null) {
            logRaw(lens, frame)
            return
        }
        val timestamp = System.currentTimeMillis()
        val event = BleTelemetryParser.TelemetryEvent.UptimeEvent(
            lens = lens,
            timestampMs = timestamp,
            opcode = notifyFrame.opcode,
            uptimeSeconds = uptime,
            rawFrame = frame.copyOf(),
        )
        applyUptimeEvent(event)
    }

    private fun handleFirmware(lens: Lens, frame: ByteArray) {
        if (frame.size <= 2) {
            logRaw(lens, frame)
            return
        }
        val raw = frame.copyOfRange(2, frame.size)
        val decoded = runCatching { raw.toString(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        val version = if (decoded.isBlank()) raw.toHex() else decoded
        val hex = frame.toHex()
        val eventTimestamp = System.currentTimeMillis()
        var firmwareChanged = false
        updateSnapshot(eventTimestamp = eventTimestamp) { current ->
            val existing = current.lens(lens)
            if (shouldAdopt(existing.firmwareSourceOpcode, existing.firmwareUpdatedAt, FIRMWARE_OPCODE, eventTimestamp, ::firmwarePriority)) {
                if (existing.firmwareVersion != version || existing.firmwareSourceOpcode != FIRMWARE_OPCODE) {
                    firmwareChanged = true
                }
                val updated = existing.copy(
                    firmwareVersion = version,
                    firmwareSourceOpcode = FIRMWARE_OPCODE,
                    firmwareUpdatedAt = eventTimestamp,
                    lastUpdatedAt = eventTimestamp,
                )
                current.updateLens(lens, updated).withFrame(lens, hex)
            } else {
                current.withFrame(lens, hex)
            }
        }
        updateDeviceTelemetry(lens, eventTimestamp) { snapshot ->
            if (snapshot.firmwareVersion == version) snapshot else snapshot.copy(firmwareVersion = version)
        }
        if (firmwareChanged) {
            emitConsole("VITALS", lens, "fw=${version} (${FIRMWARE_OPCODE.toHexLabel()})", eventTimestamp)
        }
    }

    private fun logRaw(lens: Lens, frame: ByteArray) {
        val hex = frame.toHex()
        val lensLabel = if (lens == Lens.LEFT) "L" else "R"
        logger("[UART][$lensLabel] $hex")
        updateSnapshot(persist = false) { current -> current.withFrame(lens, hex) }
    }

    private fun onAck(event: MoncchichiBleService.AckEvent) {
        val opcode = event.opcode?.let { String.format("0x%02X", it) } ?: "n/a"
        val status = event.status?.let { String.format("0x%02X", it) } ?: "n/a"
        val outcome = if (event.success) "OK" else "FAIL"
        val qualifiers = buildList {
            if (event.warmup) add("warmup")
            if (!event.success) add("retrying")
        }
        val qualifierLabel = if (qualifiers.isEmpty()) "" else " (${qualifiers.joinToString(", ")})"
        val message = "ACK $outcome opcode=$opcode status=$status$qualifierLabel"
        emitConsole("DIAG", event.lens, message, event.timestampMs)
        _uartText.tryEmit(UartLine(event.lens, message))
        updateSnapshot(eventTimestamp = event.timestampMs, persist = false) { current ->
            val existing = current.lens(event.lens)
            val latency = existing.lastAckAt?.let { previous -> (event.timestampMs - previous).takeIf { it >= 0 } }
            val failureCount = when {
                event.success && event.opcode != null -> 0
                event.success -> existing.ackFailureCount
                else -> existing.ackFailureCount + 1
            }
            val updated = existing.copy(
                lastAckAt = event.timestampMs,
                lastAckOpcode = event.opcode,
                lastAckLatencyMs = latency,
                ackSuccessCount = existing.ackSuccessCount + if (event.success) 1 else 0,
                ackFailureCount = failureCount,
                ackWarmupCount = existing.ackWarmupCount + if (event.warmup && event.success) 1 else 0,
                ackDropCount = existing.ackDropCount + if (event.success) 0 else 1,
            )
            current.updateLens(event.lens, updated)
        }
    }

    private fun decodeBinary(lens: Lens, frame: ByteArray): Boolean {
        return when (frame.first().toInt() and 0xFF) {
            GLASSES_STATE_OPCODE -> {
                handleGlassesState(lens, frame)
                true
            }
            else -> false
        }
    }

    private fun handleGlassesState(lens: Lens, frame: ByteArray) {
        val notifyFrame = G1ReplyParser.decodeFrame(frame)
        val flags = notifyFrame?.let { G1ReplyParser.parseState(it) }
        if (notifyFrame == null || flags == null) {
            logRaw(lens, frame)
            return
        }
        val timestamp = System.currentTimeMillis()
        applyStateFlags(lens, flags, timestamp, frame.toHex())
    }

    private fun mergeKeepAlive(lens: Lens, status: MoncchichiBleService.LensStatus) {
        val snapshot = keepAliveSnapshots.getOrPut(lens) { KeepAliveSnapshot() }
        if (
            snapshot.lastAt == status.lastKeepAliveAt &&
            snapshot.rtt == status.keepAliveRttMs &&
            snapshot.failures == status.consecutiveKeepAliveFailures &&
            snapshot.lockSkips == status.keepAliveLockSkips &&
            snapshot.ackTimeouts == status.keepAliveAckTimeouts
        ) {
            return
        }
        snapshot.lastAt = status.lastKeepAliveAt
        snapshot.rtt = status.keepAliveRttMs
        snapshot.failures = status.consecutiveKeepAliveFailures
        snapshot.lockSkips = status.keepAliveLockSkips
        snapshot.ackTimeouts = status.keepAliveAckTimeouts
        val now = System.currentTimeMillis()
        val agoLabel = status.lastKeepAliveAt?.let { "${now - it}ms ago" } ?: "n/a"
        val rttLabel = status.keepAliveRttMs?.let { "${it}ms" } ?: "n/a"
        val failures = status.consecutiveKeepAliveFailures
        val lockSkips = status.keepAliveLockSkips
        val ackTimeouts = status.keepAliveAckTimeouts
        val line =
            "keepalive last=$agoLabel rtt=$rttLabel failures=$failures lockSkips=$lockSkips ackTimeouts=$ackTimeouts"
        emitConsole("DIAG", lens, line, now)
        if (status.lastKeepAliveAt != null || failures > 0) {
            _uartText.tryEmit(UartLine(lens, line))
        }
    }

    private fun mergeRssi(lens: Lens, newValue: Int?) {
        updateSnapshot { current ->
            val existing = current.lens(lens)
            if (existing.rssi == newValue) {
                current
            } else {
                current.updateLens(lens, existing.copy(rssi = newValue))
            }
        }
    }

    private fun mergeBondState(lens: Lens, bonded: Boolean) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            if (existing.bonded == bonded) {
                return@updateSnapshot current
            }
            val updated = existing.copy(bonded = bonded)
            if (!existing.bonded && bonded) {
                requestCaseRefresh(lens)
            }
            val next = current.updateLens(lens, updated)
            if (!bonded) {
                emitConsole("PAIRING", lens, "bond missing ⚠️")
            } else if (next.left.bonded && next.right.bonded) {
                emitConsole("PAIRING", null, "bonded ✅ (both lenses)")
            }
            next
        }
    }

    private fun mergeDisconnectReason(lens: Lens, reason: Int?) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            if (existing.disconnectReason == reason) {
                return@updateSnapshot current
            }
            val updated = existing.copy(disconnectReason = reason)
            val next = current.updateLens(lens, updated)
            reason?.let {
                val label = formatGattStatus(it)
                emitConsole("STATE", lens, "disconnect status=$label")
            }
            next
        }
    }

    private fun mergeBondDiagnostics(lens: Lens, status: MoncchichiBleService.LensStatus) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var changed = false
            val lensLabel = lens.name.lowercase(Locale.US)

            if (existing.bondTransitions != status.bondTransitions) {
                updated = updated.copy(bondTransitions = status.bondTransitions)
                emitConsole("PAIRING", lens, "bond transitions=${status.bondTransitions}")
                changed = true
            }
            if (existing.bondTimeouts != status.bondTimeouts) {
                updated = updated.copy(bondTimeouts = status.bondTimeouts)
                emitConsole("PAIRING", lens, "bond timeouts=${status.bondTimeouts}")
                changed = true
            }
            if (existing.bondAttempts != status.bondAttempts) {
                updated = updated.copy(bondAttempts = status.bondAttempts)
                emitConsole("PAIRING", lens, "bond attempts=${status.bondAttempts}")
                changed = true
            }
            val bondResultLabel = status.lastBondResult.name
            if (existing.lastBondResult != bondResultLabel) {
                updated = updated.copy(lastBondResult = bondResultLabel)
                if (status.lastBondResult != BondResult.Unknown) {
                    emitConsole("PAIRING", lens, "last bond=${bondResultLabel}")
                }
                changed = true
            }
            if (existing.pairingDialogsShown != status.pairingDialogsShown) {
                updated = updated.copy(pairingDialogsShown = status.pairingDialogsShown)
                emitConsole("PAIRING", lens, "pairing dialogs=${status.pairingDialogsShown}")
                changed = true
            }
            if (existing.refreshCount != status.refreshCount) {
                updated = updated.copy(refreshCount = status.refreshCount)
                emitConsole("PAIRING", lens, "refresh invoked=${status.refreshCount}")
                changed = true
            }
            if (existing.lastBondState != status.lastBondState) {
                updated = updated.copy(lastBondState = status.lastBondState)
                status.lastBondState?.let { bondState ->
                    emitConsole("PAIRING", lens, "bond state=${formatBondState(bondState)}")
                }
                changed = true
            }
            if (
                existing.lastBondReason != status.lastBondReason ||
                existing.lastBondEventAt != status.lastBondEventAt
            ) {
                updated = updated.copy(
                    lastBondReason = status.lastBondReason,
                    lastBondEventAt = status.lastBondEventAt,
                )
                status.lastBondReason?.let { reasonCode ->
                    val reasonLabel = formatBondReason(reasonCode)
                    val timestampLabel = status.lastBondEventAt?.let { at ->
                        formatBondTimestamp(at)?.let { formatted -> " at $formatted" }
                    } ?: ""
                    emitConsole("PAIRING", lens, "bond reason=$reasonLabel$timestampLabel")
                }
                changed = true
            }
            if (
                existing.smpFrames != status.smpFrameCount ||
                existing.lastSmpOpcode != status.lastSmpOpcode
            ) {
                updated = updated.copy(
                    smpFrames = status.smpFrameCount,
                    lastSmpOpcode = status.lastSmpOpcode,
                )
                val opcodeLabel = formatOpcode(status.lastSmpOpcode)
                emitConsole("DIAG", lens, "SMP frames=${status.smpFrameCount} last=$opcodeLabel")
                changed = true
            }

            if (!changed) {
                current
            } else {
                current.updateLens(lens, updated)
            }
        }
    }

    private fun mergeReconnectDiagnostics(lens: Lens, status: MoncchichiBleService.LensStatus) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var changed = false
            if (existing.reconnectAttempts != status.reconnectAttempts) {
                updated = updated.copy(reconnectAttempts = status.reconnectAttempts)
                emitConsole("STATE", lens, "auto-reconnect attempt ${status.reconnectAttempts}")
                changed = true
            }
            if (existing.reconnectSuccesses != status.reconnectSuccesses) {
                updated = updated.copy(reconnectSuccesses = status.reconnectSuccesses)
                emitConsole("STATE", lens, "auto-reconnect success")
                changed = true
            }
            if (existing.reconnecting != status.reconnecting) {
                updated = updated.copy(reconnecting = status.reconnecting)
                changed = true
            }
            if (existing.bondResets != status.bondResetCount) {
                updated = updated.copy(bondResets = status.bondResetCount)
                emitConsole("PAIRING", lens, "bond reset events=${status.bondResetCount}")
                changed = true
            }

            val heartbeatLatency = status.heartbeatLatencyMs?.toInt()
            val heartbeatChanged = existing.heartbeatLatencySnapshotMs != heartbeatLatency
            val reconnectSnapshot = status.reconnectAttempts
            val reconnectChanged = existing.reconnectAttemptsSnapshot != reconnectSnapshot
            if (heartbeatChanged || reconnectChanged || existing.ackMode != status.heartbeatAckType) {
                updated = updated.copy(
                    heartbeatLatencySnapshotMs = heartbeatLatency,
                    reconnectAttemptsSnapshot = reconnectSnapshot,
                    ackMode = status.heartbeatAckType,
                )
                val hbLabel = heartbeatLatency?.let { "${it} ms" } ?: "n/a"
                val reconnectLabel = reconnectSnapshot ?: 0
                emitConsole("STABILITY", lens, "heartbeat=$hbLabel reconnect=$reconnectLabel", System.currentTimeMillis())
                updateDeviceTelemetry(lens, System.currentTimeMillis(), logUpdate = false, persist = false) { snapshot ->
                    snapshot.copy(
                        heartbeatLatencyMs = heartbeatLatency,
                        reconnectAttempts = reconnectSnapshot,
                        lastAckMode = status.heartbeatAckType ?: snapshot.lastAckMode,
                    )
                }
                changed = true
            }

            if (!changed) {
                current
            } else {
                current.updateLens(lens, updated)
            }
        }
    }

    private fun mergeServiceCounters(state: MoncchichiBleService.ServiceState) {
        updateSnapshot(persist = false) { current ->
            current.copy(
                autoReconnectAttempts = state.autoReconnectAttemptCount,
                autoReconnectSuccesses = state.autoReconnectSuccessCount,
                rightBondRetries = state.rightBondRetryCount,
                bondResetEvents = state.bondResetEvents,
            )
        }
    }

    private fun updateConnectionSequence(order: List<Lens>) {
        val label = if (order.isEmpty()) null else order.joinToString(separator = "→") { it.name.lowercase(Locale.US) }
        val previous = _snapshot.value.connectionSequence
        if (previous == label) {
            return
        }
        updateSnapshot(persist = false) { current -> current.copy(connectionSequence = label) }
        label?.let {
            emitConsole("PAIRING", null, "connect sequence $it")
        }
    }

    private fun Snapshot.lens(lens: Lens): LensTelemetry = when (lens) {
        Lens.LEFT -> left
        Lens.RIGHT -> right
    }

    private fun formatOpcode(value: Int?): String = value?.let { String.format("0x%02X", it) } ?: "n/a"

    private fun Snapshot.updateLens(
        lens: Lens,
        telemetry: LensTelemetry,
    ): Snapshot {
        val updated = when (lens) {
            Lens.LEFT -> copy(left = telemetry)
            Lens.RIGHT -> copy(right = telemetry)
        }
        return updated.recalculateDerived()
    }

    private fun Snapshot.recalculateDerived(): Snapshot {
        return copy(
            leftBondAttempts = left.bondAttempts,
            rightBondAttempts = right.bondAttempts,
            leftBondResult = left.lastBondResult,
            rightBondResult = right.lastBondResult,
            pairingDialogsShown = left.pairingDialogsShown + right.pairingDialogsShown,
            gattRefreshCount = left.refreshCount + right.refreshCount,
            autoReconnectAttempts = left.reconnectAttempts + right.reconnectAttempts,
            autoReconnectSuccesses = left.reconnectSuccesses + right.reconnectSuccesses,
            bondResetEvents = max(bondResetEvents, left.bondResets + right.bondResets),
        )
    }

    private fun Snapshot.withFrame(lens: Lens, hex: String): Snapshot {
        return if (lastLens == lens && lastFrameHex == hex) {
            this
        } else {
            copy(lastLens = lens, lastFrameHex = hex)
        }
    }

    private fun <T> MutableStateFlow<DeviceStatus<T>>.update(
        lens: Lens,
        value: T?,
        timestamp: Long,
    ) {
        if (value == null) return
        val current = this.value
        val updated = when (lens) {
            Lens.LEFT -> current.copy(left = value, leftUpdatedAt = timestamp)
            Lens.RIGHT -> current.copy(right = value, rightUpdatedAt = timestamp)
        }
        if (updated != current) {
            this.value = updated
        }
    }

    private fun formatGattStatus(code: Int): String {
        val normalized = code and 0xFF
        val description = when (normalized) {
            0x13 -> "peer terminated"
            else -> null
        }
        return if (description != null) {
            String.format(Locale.US, "0x%02X (%s)", normalized, description)
        } else {
            String.format(Locale.US, "0x%02X", normalized)
        }
    }

    private fun formatBondState(state: Int): String {
        return when (state) {
            BluetoothDevice.BOND_NONE -> "BOND_NONE"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
            else -> state.toString()
        }
    }

    private fun formatBondReason(reason: Int): String {
        return when (reason) {
            1 -> "UNBOND_REASON_AUTH_FAILED"
            2 -> "UNBOND_REASON_AUTH_REJECTED"
            3 -> "UNBOND_REASON_AUTH_CANCELED"
            4 -> "UNBOND_REASON_REMOTE_DEVICE_DOWN"
            5 -> "UNBOND_REASON_REMOVED"
            6 -> "UNBOND_REASON_OPERATION_CANCELED"
            7 -> "UNBOND_REASON_REPEATED_ATTEMPTS"
            8 -> "UNBOND_REASON_REMOTE_AUTH_CANCELED"
            9 -> "UNBOND_REASON_UNKNOWN"
            10 -> "BOND_FAILURE_UNKNOWN"
            else -> reason.toString()
        }
    }

    private fun formatBondTimestamp(timestampMs: Long): String? {
        return runCatching {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
        }.getOrNull()
    }

    private fun maybeEmitUtf8(lens: Lens, frame: ByteArray): Boolean {
        val first = frame.first().toInt() and 0xFF
        if (first == BATTERY_OPCODE || first == STATUS_OPCODE || first == UPTIME_OPCODE || first == FIRMWARE_OPCODE) return false
        if (frame.size > 64) return false
        val printable = frame.all { byte -> byte.toInt().isAsciiOrCrlf() }
        if (!printable) return false
        val text = runCatching { frame.toString(Charsets.UTF_8).trim() }.getOrNull()
        if (text.isNullOrBlank()) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        lines.forEach { line ->
            parseTextMetadata(lens, line)
            _uartText.tryEmit(UartLine(lens, line))
        }
        return true
    }

    private fun maybeAssembleUtf8Buffered(lens: Lens, frame: ByteArray): Boolean {
        val buffer = if (lens == Lens.LEFT) leftBuffer else rightBuffer
        if (!frame.all { byte -> byte.toInt().isAsciiOrCrlf() }) {
            buffer.reset()
            return false
        }
        if (buffer.size() + frame.size > 64) {
            buffer.reset()
            return false
        }
        buffer.write(frame)
        val accumulated = buffer.toByteArray().toString(Charsets.UTF_8)
        val parts = accumulated.split("\r", "\n")
        if (parts.isEmpty()) return false
        val hasTerminator = accumulated.endsWith("\n") || accumulated.endsWith("\r")
        var emitted = false
        parts.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            val isLast = index == parts.lastIndex
            if (isLast && !hasTerminator) {
                buffer.reset()
                if (raw.isNotEmpty()) {
                    buffer.write(raw.toByteArray(Charsets.UTF_8))
                }
            } else {
                if (trimmed.isNotEmpty()) {
                    parseTextMetadata(lens, trimmed)
                    _uartText.tryEmit(UartLine(lens, trimmed))
                    emitted = true
                }
                if (isLast) {
                    buffer.reset()
                }
            }
        }
        return emitted
    }

    private fun clearBuffers() {
        leftBuffer.reset()
        rightBuffer.reset()
    }

    private fun Int.isAsciiOrCrlf(): Boolean {
        val unsigned = this and 0xFF
        return unsigned == 0x0A || unsigned == 0x0D || unsigned in 0x20..0x7E
    }

    private fun parseTextMetadata(lens: Lens, line: String) {
        var parsedFirmware: String? = null
        var parsedDeviceId: String? = null
        var firmwareChanged = false
        updateSnapshot { current ->
            val existing = current.lens(lens)
            var firmware = existing.firmwareVersion
            var notes = existing.notes
            val versionMatch = versionRegex.matcher(line)
            if (versionMatch.find()) {
                val version = versionMatch.group(1)
                val deviceId = versionMatch.group(3)
                if (firmware != version) {
                    firmwareChanged = true
                }
                firmware = version
                notes = "DeviceID $deviceId"
                parsedFirmware = version
                parsedDeviceId = deviceId
            } else {
                val buildMatch = buildRegex.matcher(line)
                if (buildMatch.find()) {
                    notes = "Build ${buildMatch.group(1)}"
                }
            }
            val updated = existing.copy(firmwareVersion = firmware, notes = notes)
            if (updated != existing) {
                current.updateLens(lens, updated)
            } else {
                current
            }
        }
        val now = System.currentTimeMillis()
        parsedFirmware?.let { version ->
            updateDeviceTelemetry(lens, now, logUpdate = false, persist = false) { snapshot ->
                if (snapshot.firmwareVersion == version) snapshot else snapshot.copy(firmwareVersion = version)
            }
            if (firmwareChanged) {
                val formatted = if (version.startsWith("v", ignoreCase = true) || !version.first().isDigit()) {
                    version
                } else {
                    "v$version"
                }
                val message = buildString {
                    append("Firmware ")
                    append(formatted)
                    parsedDeviceId?.let { id ->
                        append(" DeviceID ")
                        append(id)
                    }
                }
                emitConsole("INFO", lens, message, now)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
    }

    private fun Int.toHexLabel(): String = String.format("0x%02X", this and 0xFF)

    private fun updateSnapshot(
        eventTimestamp: Long? = null,
        persist: Boolean = true,
        transform: (Snapshot) -> Snapshot,
    ) {
        val previous = _snapshot.value
        val updated = _snapshot.updateAndGet(transform)
        if (persist && updated != previous) {
            persistSnapshot(updated, eventTimestamp)
        }
    }

    private fun persistSnapshot(snapshot: Snapshot, eventTimestamp: Long?) {
        val recordedAt = eventTimestamp ?: System.currentTimeMillis()
        val record = MemoryRepository.TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = snapshot.uptimeSeconds,
            left = snapshot.left.toRecord(),
            right = snapshot.right.toRecord(),
        )
        persistenceScope.launch {
            memory.addTelemetrySnapshot(record)
        }
    }

    private fun LensTelemetry.toRecord(): MemoryRepository.LensSnapshot =
        MemoryRepository.LensSnapshot(
            batteryPercent = batteryPercent,
            caseBatteryPercent = caseBatteryPercent,
            caseOpen = caseOpen,
            lastUpdated = lastUpdatedAt,
            rssi = rssi,
            firmwareVersion = firmwareVersion,
            notes = notes,
            reconnectAttempts = reconnectAttemptsSnapshot,
            heartbeatLatencyMs = heartbeatLatencySnapshotMs,
            lastAckMode = ackMode?.name,
        )

    private fun MoncchichiBleService.Target.toLensOrNull(): Lens? = when (this) {
        MoncchichiBleService.Target.Left -> Lens.LEFT
        MoncchichiBleService.Target.Right -> Lens.RIGHT
        MoncchichiBleService.Target.Both -> null
    }

    companion object {
        private const val BATTERY_OPCODE = 0x2C
        private const val STATUS_OPCODE = 0x06
        private const val UPTIME_OPCODE = 0x37
        private const val FIRMWARE_OPCODE = 0x11
        private const val GLASSES_STATE_OPCODE = 0x2B
        private const val ASCII_NOTIFY_OPCODE = 0xF5
        private const val SOURCE_DISAGREE_WINDOW_MS = 3_000L
        private const val POWER_HISTORY_SIZE = 10
        private const val BATTERY_STICKY_WINDOW_MS = 10_000L
    }
}
