package com.loopermallee.moncchichi.hub.data.telemetry

import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import com.loopermallee.moncchichi.bluetooth.BondResult
import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.G1Protocols
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_PING
import com.loopermallee.moncchichi.bluetooth.G1Protocols.F5EventType
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_COMPLETE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_CONTINUE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_OK
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_BUSY
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_DISPLAY_BRIGHTNESS
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_DISPLAY_DOUBLE_TAP
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_DISPLAY_HEIGHT_DEPTH
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_DISPLAY_LONG_PRESS
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_DISPLAY_MIC_ON_LIFT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_SYSTEM_DEBUG
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_SYSTEM_FIRMWARE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SUBCMD_SYSTEM_REBOOT
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.audio.MicStreamManager
import com.loopermallee.moncchichi.hub.ble.DashboardDataEncoder
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.diagnostics.TelemetryConsistencyValidator
import com.loopermallee.moncchichi.hub.telemetry.BleTelemetryParser
import com.loopermallee.moncchichi.hub.telemetry.BleTelemetryParser.SerialType
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.collections.buildList
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.text.Charsets
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Aggregates BLE telemetry packets (battery %, uptime, firmware, RSSI) emitted by [MoncchichiBleService].
 */
class BleTelemetryRepository(
    private val memory: MemoryRepository,
    private val persistenceScope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val telemetryParser: BleTelemetryParser = BleTelemetryParser(),
) {
    private val missingOpcodeLog = mutableSetOf<Int>()

    data class LensTelemetry(
        val batteryPercent: Int? = null,
        val batterySourceOpcode: Int? = null,
        val batteryUpdatedAt: Long? = null,
        val caseBatteryPercent: Int? = null,
        val caseVoltageMv: Int? = null,
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
        val foldState: Boolean? = null,
        val sleepState: SleepState = SleepState.UNKNOWN,
        val lastSleepAt: Long? = null,
        val lastWakeAt: Long? = null,
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
        val heartbeatLatencyAvgMs: Int? = null,
        val heartbeatLastPingAt: Long? = null,
        val heartbeatMissCount: Int = 0,
        val ackMode: MoncchichiBleService.AckType? = null,
        val lastVitalsTimestamp: Long? = null,
    )

    data class CaseStatus(
        val batteryPercent: Int? = null,
        val charging: Boolean? = null,
        val lidOpen: Boolean? = null,
        val silentMode: Boolean? = null,
        val voltageMv: Int? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    /** Describes the sleep state reported by the lens UART stream. */
    enum class SleepState { UNKNOWN, AWAKE, SLEEPING }

    enum class ValidationState { Idle, Running, Passed, Failed }

    data class ValidationStatus(
        val state: ValidationState = ValidationState.Idle,
        val startedAt: Long? = null,
        val elapsedMs: Long = 0L,
        val missedHeartbeats: Int = 0,
        val reconnects: Int = 0,
        val ackFailures: Int = 0,
        val passCount: Int = 0,
        val lastSummary: String? = null,
        val message: String? = null,
    )

    data class PowerFrame(
        val opcode: Int,
        val hex: String,
        val timestampMs: Long,
    )

    data class Snapshot(
        val left: LensTelemetry = LensTelemetry(),
        val right: LensTelemetry = LensTelemetry(),
        val caseOpen: Boolean? = null,
        val inCase: Boolean? = null,
        val foldState: Boolean? = null,
        val charging: Boolean? = null,
        val lastVitalsTimestamp: Long? = null,
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

    enum class SnapshotSeverity { NORMAL, OK, WARN, ERROR }

    data class SnapshotLog(
        val message: String,
        val timestamp: Long,
        val severity: SnapshotSeverity,
    )

    data class PersistedSnapshot(
        val recordedAt: Long,
        val caseJson: String?,
        val leftJson: String?,
        val rightJson: String?,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _snapshotLine = MutableStateFlow<SnapshotLog?>(null)
    val snapshotLine: StateFlow<SnapshotLog?> = _snapshotLine.asStateFlow()

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

    private val validationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val validationController = ValidationController(
        scope = validationScope,
        logger = logger,
        emitConsole = ::emitConsole,
    )
    val validationStatus: StateFlow<ValidationStatus> = validationController.status

    private val _micPackets = MutableSharedFlow<BleTelemetryParser.TelemetryEvent.AudioPacketEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val micPackets: SharedFlow<BleTelemetryParser.TelemetryEvent.AudioPacketEvent> = _micPackets.asSharedFlow()

    private val snapshotPersistRequests = MutableSharedFlow<MemoryRepository.TelemetrySnapshotRecord>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        persistenceScope.launch {
            snapshotPersistRequests
                .debounce(250)
                .collectLatest { record ->
                    memory.addTelemetrySnapshot(record)
                }
        }
        persistenceScope.launch {
            memory.latestTelemetrySnapshot()?.let { record ->
                restoreFromPersistedSnapshot(record)
            }
        }
        startSnapshotLogger()
    }

    private data class BatteryLogState(
        var voltageMv: Int? = null,
        var batteryPercent: Int? = null,
        var isCharging: Boolean? = null,
        var lastEmitAt: Long = 0L,
    )

    private data class AckLogState(
        var lastOpcode: Int? = null,
        var lastOutcome: AckOutcome = AckOutcome.OK,
        var lastStatus: Int? = null,
        var lastLoggedAt: Long = 0L,
    )

    private enum class AckOutcome { OK, BUSY, FAIL }

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

    private fun handleSystemCommandEvent(event: BleTelemetryParser.TelemetryEvent.SystemCommandEvent) {
        val sub = event.subcommand
        val text = event.text?.trim()?.ifBlank { null }
        val isFirmware = sub == SUBCMD_SYSTEM_FIRMWARE || (sub == null && !text.isNullOrBlank())
        var label = when {
            sub == SUBCMD_SYSTEM_DEBUG -> text?.let { "Debug $it" } ?: "Debug Toggle"
            sub == SUBCMD_SYSTEM_REBOOT -> "Reboot Requested"
            isFirmware -> text?.let { if (it.startsWith("Firmware", ignoreCase = true)) it else "Firmware $it" } ?: "Firmware Info"
            sub != null -> "System 0x%02X".format(Locale.US, sub)
            else -> "System"
        }
        if (isFirmware) {
            val frameId = lensTelemetrySnapshots.values
                .map { it.value.frameSerialId }
                .firstOrNull { !it.isNullOrBlank() }
            if (!frameId.isNullOrBlank()) {
                label = "$label – FrameID: $frameId"
            }
        }
        val prefix = event.lens?.shortLabel?.let { "$it → " } ?: ""
        emitConsole("SYS", null, prefix + label, event.timestampMs)
        if (isFirmware && !text.isNullOrBlank()) {
            MoncchichiBleService.Lens.values().forEach { lens ->
                updateDeviceTelemetry(
                    lens = lens,
                    eventTimestamp = event.timestampMs,
                    logUpdate = false,
                    persist = false,
                ) { snapshot ->
                    snapshot.copy(firmwareVersion = text)
                }
            }
            persistDeviceTelemetrySnapshots(event.timestampMs)
        }
    }

    private fun handleDisplayEvent(event: BleTelemetryParser.TelemetryEvent.DisplayEvent) {
        val sub = event.subcommand
        val label = when (sub) {
            SUBCMD_DISPLAY_HEIGHT_DEPTH -> buildString {
                append("Height=")
                append(event.height?.toString() ?: "?")
                append(" Depth=")
                append(event.depth?.toString() ?: "?")
                event.preview?.let { preview ->
                    append(" ")
                    append(if (preview) "Preview" else "Apply")
                }
            }

            SUBCMD_DISPLAY_BRIGHTNESS -> "Brightness ${event.brightness ?: -1}"
            SUBCMD_DISPLAY_DOUBLE_TAP -> "Double Tap Action 0x%02X".format(Locale.US, event.action ?: 0)
            SUBCMD_DISPLAY_LONG_PRESS -> "Long Press Action 0x%02X".format(Locale.US, event.action ?: 0)
            SUBCMD_DISPLAY_MIC_ON_LIFT -> {
                val enabled = event.enabled ?: false
                "Mic On Lift ${if (enabled) "Enabled" else "Disabled"}"
            }

            else -> if (sub != null) "Display 0x%02X".format(Locale.US, sub) else "Display"
        }
        val prefix = event.lens?.shortLabel?.let { "$it → " } ?: ""
        emitConsole("DISPLAY", null, prefix + label, event.timestampMs)
        updateDeviceTelemetry(
            lens = event.lens,
            eventTimestamp = event.timestampMs,
            logUpdate = false,
            persist = false,
        ) { snapshot ->
            val env = LinkedHashMap(snapshot.environment ?: emptyMap())
            when (sub) {
                SUBCMD_DISPLAY_HEIGHT_DEPTH -> {
                    event.height?.let { env["display_height"] = it.toString() }
                    event.depth?.let { env["display_depth"] = it.toString() }
                    event.preview?.let { env["display_preview"] = it.toString() }
                }

                SUBCMD_DISPLAY_BRIGHTNESS -> event.brightness?.let { env["display_brightness"] = it.toString() }
                SUBCMD_DISPLAY_DOUBLE_TAP -> event.action?.let { env["display_double_tap"] = "0x%02X".format(Locale.US, it) }
                SUBCMD_DISPLAY_LONG_PRESS -> event.action?.let { env["display_long_press"] = "0x%02X".format(Locale.US, it) }
                SUBCMD_DISPLAY_MIC_ON_LIFT -> event.enabled?.let { env["display_mic_on_lift"] = it.toString() }
            }
            snapshot.copy(environment = env)
        }
        persistDeviceTelemetrySnapshots(event.timestampMs)
    }

    private fun handleSerialEvent(event: BleTelemetryParser.TelemetryEvent.SerialNumberEvent) {
        val serial = event.serial?.ifBlank { null }
        val message = when (event.type) {
            SerialType.LENS -> {
                val label = serial ?: "n/a"
                val prefix = event.lens?.shortLabel?.let { "$it → " } ?: ""
                prefix + "LensID: $label"
            }

            SerialType.FRAME -> "FrameID: ${serial ?: "n/a"}"
        }
        emitConsole("SYS", null, message, event.timestampMs)
        when (event.type) {
            SerialType.LENS -> {
                event.lens?.let { lens ->
                    updateDeviceTelemetry(
                        lens = lens,
                        eventTimestamp = event.timestampMs,
                        logUpdate = false,
                        persist = false,
                    ) { snapshot ->
                        snapshot.copy(lensSerialId = serial ?: snapshot.lensSerialId)
                    }
                }
            }

            SerialType.FRAME -> {
                MoncchichiBleService.Lens.values().forEach { lens ->
                    updateDeviceTelemetry(
                        lens = lens,
                        eventTimestamp = event.timestampMs,
                        logUpdate = false,
                        persist = false,
                    ) { snapshot ->
                        snapshot.copy(frameSerialId = serial ?: snapshot.frameSerialId)
                    }
                }
            }
        }
        persistDeviceTelemetrySnapshots(event.timestampMs)
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
        val caseCharging: Boolean?,
        val caseSilentMode: Boolean?,
        val caseVoltageMv: Int?,
        val uptimeSeconds: Long?,
        val firmwareVersion: String?,
        val environment: Map<String, String>?,
        val lensSerialId: String?,
        val frameSerialId: String?,
        val lastAckStatus: String?,
        val lastAckTimestamp: Long?,
        val lastGesture: LensGestureEvent?,
        val reconnectAttempts: Int? = null,
        val heartbeatLatencyMs: Int? = null,
        val ackMode: MoncchichiBleService.AckType? = null,
        val heartbeatLatencyAvgMs: Int? = null,
        val heartbeatMissCount: Int? = null,
    )

    data class LinkPrimingSnapshot(
        val lens: MoncchichiBleService.Lens,
        val notifyArmed: Boolean,
        val warmupOk: Boolean,
        val attMtu: Int?,
    )

    private val deviceTelemetryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lensTelemetrySnapshots = mapOf(
        Lens.LEFT to MutableStateFlow(initialDeviceTelemetrySnapshot(Lens.LEFT)),
        Lens.RIGHT to MutableStateFlow(initialDeviceTelemetrySnapshot(Lens.RIGHT)),
    )

    private val _linkPriming = MutableStateFlow(initialLinkPriming())
    val linkPriming: StateFlow<Map<MoncchichiBleService.Lens, LinkPrimingSnapshot>> =
        _linkPriming.asStateFlow()

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

    fun toTelemetryJson(snapshot: DeviceTelemetrySnapshot, caseStatus: CaseStatus = _caseStatus.value): String? {
        return snapshot.buildTelemetryJson(caseStatus)
    }

    private fun initialDeviceTelemetrySnapshot(lens: Lens): DeviceTelemetrySnapshot {
        return DeviceTelemetrySnapshot(
            lens = lens,
            timestamp = 0L,
            batteryVoltageMv = null,
            isCharging = null,
            caseBatteryPercent = null,
            caseOpen = null,
            caseCharging = null,
            caseSilentMode = null,
            caseVoltageMv = null,
            uptimeSeconds = null,
            firmwareVersion = null,
            environment = null,
            lensSerialId = null,
            frameSerialId = null,
            lastAckStatus = null,
            lastAckTimestamp = null,
            lastGesture = null,
            reconnectAttempts = null,
            heartbeatLatencyMs = null,
            ackMode = null,
            heartbeatLatencyAvgMs = null,
        )
    }

    private fun initialLinkPriming(): Map<MoncchichiBleService.Lens, LinkPrimingSnapshot> {
        return MoncchichiBleService.Lens.values().associateWith { lens ->
            LinkPrimingSnapshot(lens, notifyArmed = false, warmupOk = false, attMtu = null)
        }
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
            caseCharging = mergeNonNull(previous.caseCharging, caseCharging, allowNullReset),
            caseSilentMode = mergeNonNull(previous.caseSilentMode, caseSilentMode, allowNullReset),
            caseVoltageMv = mergeNonNull(previous.caseVoltageMv, caseVoltageMv, allowNullReset),
            uptimeSeconds = mergeNonNull(previous.uptimeSeconds, uptimeSeconds, allowNullReset),
            firmwareVersion = mergeNonNull(previous.firmwareVersion, firmwareVersion, allowNullReset),
            environment = mergeNonNull(previous.environment, environment, allowNullReset),
            lensSerialId = mergeNonNull(previous.lensSerialId, lensSerialId, allowNullReset),
            frameSerialId = mergeNonNull(previous.frameSerialId, frameSerialId, allowNullReset),
            lastAckStatus = mergeNonNull(previous.lastAckStatus, lastAckStatus, allowNullReset),
            lastAckTimestamp = mergeNonNull(previous.lastAckTimestamp, lastAckTimestamp, allowNullReset),
            lastGesture = mergeNonNull(previous.lastGesture, lastGesture, allowNullReset),
            reconnectAttempts = mergeNonNull(previous.reconnectAttempts, reconnectAttempts, allowNullReset),
            heartbeatLatencyMs = mergeNonNull(previous.heartbeatLatencyMs, heartbeatLatencyMs, allowNullReset),
            ackMode = mergeNonNull(previous.ackMode, ackMode, allowNullReset),
            heartbeatLatencyAvgMs = mergeNonNull(previous.heartbeatLatencyAvgMs, heartbeatLatencyAvgMs, allowNullReset),
            heartbeatMissCount = mergeNonNull(previous.heartbeatMissCount, heartbeatMissCount, allowNullReset),
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
            snapshot.caseVoltageMv?.let { mv ->
                append(' ')
                append("caseMv=")
                append(mv)
            }
            snapshot.lensSerialId?.let { serial ->
                append(' ')
                append("lensId=")
                append(serial)
            }
            snapshot.frameSerialId?.let { serial ->
                append(' ')
                append("frameId=")
                append(serial)
            }
            envSummary?.let { summary ->
                append(' ')
                append(summary)
            }
            append(' ')
            append("ack=")
            append(snapshot.lastAckStatus ?: "?")
            snapshot.ackMode?.let { mode ->
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
            snapshot.caseBatteryPercent?.let { percent ->
                append(' ')
                append("casePct=")
                append(percent)
                append('%')
            }
            snapshot.caseOpen?.let { open ->
                append(' ')
                append("caseOpen=")
                append(open)
            }
            snapshot.caseCharging?.let { charging ->
                append(' ')
                append("caseCharging=")
                append(charging)
            }
            snapshot.caseSilentMode?.let { silent ->
                append(' ')
                append("caseSilent=")
                append(silent)
            }
        }
        emitConsole("TELEMETRY", snapshot.lens, message.trim(), snapshot.timestamp)
    }

    private fun persistDeviceTelemetrySnapshots(recordedAt: Long) {
        val leftSnapshot = lensTelemetrySnapshots.getValue(Lens.LEFT).value
        val rightSnapshot = lensTelemetrySnapshots.getValue(Lens.RIGHT).value
        val caseStatus = _caseStatus.value
        val uptime = listOfNotNull(leftSnapshot.uptimeSeconds, rightSnapshot.uptimeSeconds).maxOrNull()
        val caseRecord = caseStatus.toRecord()
        val leftRecord = leftSnapshot.toLensRecord(caseStatus)
        val rightRecord = rightSnapshot.toLensRecord(caseStatus)
        val hasData = leftRecord.hasContent() || rightRecord.hasContent() || caseRecord != null || uptime != null
        if (!hasData) {
            return
        }
        val record = MemoryRepository.TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = uptime,
            case = caseRecord,
            left = leftRecord,
            right = rightRecord,
        )
        val normalized = record.copy(recordedAt = 0L)
        val shouldPersist = synchronized(snapshotPersistLock) {
            if (lastPersistedSnapshotContent == normalized) {
                false
            } else {
                lastPersistedSnapshotContent = normalized
                true
            }
        }
        publishSnapshotLine(recordedAt)
        if (!shouldPersist) {
            return
        }
        if (!snapshotPersistRequests.tryEmit(record)) {
            persistenceScope.launch {
                memory.addTelemetrySnapshot(record)
            }
        }
    }

    private fun DeviceTelemetrySnapshot.toLensRecord(caseStatus: CaseStatus): MemoryRepository.LensSnapshot {
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
                add("gesture=${gestureLabel(lens, event.gesture)}")
            }
            (caseBatteryPercent ?: caseStatus.batteryPercent)?.let { add("casePct=$it") }
            (caseOpen ?: caseStatus.lidOpen)?.let { add("caseOpen=$it") }
            caseCharging?.let { add("caseCharging=$it") }
            (caseSilentMode ?: caseStatus.silentMode)?.let { add("caseSilent=$it") }
            caseVoltageMv?.let { add("caseMv=$it") }
            lensSerialId?.let { add("lensId=$it") }
            frameSerialId?.let { add("frameId=$it") }
            reconnectAttempts?.let { add("reconnect=$it") }
            heartbeatLatencyMs?.let { add("heartbeat=${it}ms") }
            heartbeatLatencyAvgMs?.let { add("heartbeatAvg=${it}ms") }
            ackMode?.let { add("ackMode=${it.name}") }
            environment?.takeIf { it.isNotEmpty() }?.let { map ->
                add(map.entries.joinToString(prefix = "env{", postfix = "}") { (key, value) -> "$key=$value" })
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ")

        val snapshotJson = buildTelemetryJson(caseStatus)
        return MemoryRepository.LensSnapshot(
            batteryPercent = null,
            batteryVoltageMv = batteryVoltageMv,
            caseBatteryPercent = caseBatteryPercent ?: caseStatus.batteryPercent,
            caseOpen = caseOpen ?: caseStatus.lidOpen,
            caseSilentMode = caseSilentMode ?: caseStatus.silentMode,
            lastUpdated = timestamp,
            rssi = null,
            firmwareVersion = firmwareVersion,
            notes = notes,
            reconnectAttempts = reconnectAttempts,
            heartbeatLatencyAvgMs = heartbeatLatencyAvgMs,
            heartbeatLatencyMs = heartbeatLatencyMs,
            lastAckMode = ackMode?.name,
            lastAckStatus = lastAckStatus,
            lastAckTimestamp = lastAckTimestamp,
            uptimeSeconds = uptimeSeconds,
            snapshotJson = snapshotJson,
            heartbeatMissCount = heartbeatMissCount,
        )
    }

    private fun CaseStatus.toRecord(): MemoryRepository.CaseSnapshot? {
        if (batteryPercent == null && charging == null && lidOpen == null && silentMode == null) {
            return null
        }
        return MemoryRepository.CaseSnapshot(
            batteryPercent = batteryPercent,
            charging = charging,
            lidOpen = lidOpen,
            silentMode = silentMode,
        )
    }

    private fun MemoryRepository.LensSnapshot.hasContent(): Boolean {
        return listOf(
            batteryPercent,
            batteryVoltageMv,
            caseBatteryPercent,
            caseOpen,
            caseSilentMode,
            lastUpdated,
            rssi,
            firmwareVersion,
            notes,
            reconnectAttempts,
            heartbeatLatencyAvgMs,
            heartbeatLatencyMs,
            lastAckMode,
            lastAckStatus,
            lastAckTimestamp,
            uptimeSeconds,
            snapshotJson,
            heartbeatMissCount,
        ).any { it != null }
    }

    private fun DeviceTelemetrySnapshot.buildTelemetryJson(caseStatus: CaseStatus): String? {
        val json = JSONObject()
        var hasField = false
        fun put(name: String, value: Any?) {
            if (value != null) {
                json.put(name, value)
                hasField = true
            }
        }
        json.put("lens", lens.name.lowercase(Locale.US))
        put("timestamp", timestamp)
        put("batteryVoltageMv", batteryVoltageMv)
        put("isCharging", isCharging)
        put("caseBatteryPercent", caseBatteryPercent ?: caseStatus.batteryPercent)
        put("caseOpen", caseOpen ?: caseStatus.lidOpen)
        put("caseCharging", caseCharging ?: caseStatus.charging)
        put("caseSilentMode", caseSilentMode ?: caseStatus.silentMode)
        put("caseVoltageMv", caseVoltageMv ?: caseStatus.voltageMv)
        put("uptimeSeconds", uptimeSeconds)
        put("firmwareVersion", firmwareVersion)
        put("lensSerialId", lensSerialId)
        put("frameSerialId", frameSerialId)
        put("lastAckStatus", lastAckStatus)
        put("lastAckTimestamp", lastAckTimestamp)
        val ackModeName = ackMode?.name ?: "UNKNOWN"
        put("ackMode", ackModeName)
        put("lastAckMode", ackModeName)
        put("reconnectAttempts", reconnectAttempts)
        put("heartbeatLatencyMs", heartbeatLatencyMs)
        put("heartbeatLatencyAvgMs", heartbeatLatencyAvgMs)
        put("heartbeatMissCount", heartbeatMissCount)
        environment?.takeIf { it.isNotEmpty() }?.let { map ->
            val envJson = JSONObject()
            map.entries.sortedBy { it.key }.forEach { (key, value) ->
                envJson.put(key, value)
            }
            json.put("environment", envJson)
            hasField = true
        }
        lastGesture?.let { event ->
            json.put("lastGesture", gestureLabel(lens, event.gesture))
            hasField = true
        }
        return if (hasField) json.toString() else null
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

    sealed class SleepEvent {
        data class SleepEntered(val lens: Lens?) : SleepEvent()
        data class SleepExited(val lens: Lens?) : SleepEvent()
    }

    private val _sleepEvents = MutableSharedFlow<SleepEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sleepEvents: SharedFlow<SleepEvent> = _sleepEvents.asSharedFlow()

    val sleepStates: StateFlow<Map<Lens, SleepState>> = snapshot
        .map { current ->
            mapOf(
                Lens.LEFT to current.left.sleepState,
                Lens.RIGHT to current.right.sleepState,
            )
        }
        .stateIn(
            scope = deviceTelemetryScope,
            started = SharingStarted.Eagerly,
            initialValue = Lens.values().associateWith { SleepState.UNKNOWN },
        )

    val isSleepingFlow: StateFlow<Boolean> = snapshot
        .map { current -> Lens.values().all { lens -> current.lens(lens).sleepState == SleepState.SLEEPING } }
        .stateIn(
            scope = deviceTelemetryScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

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

    private val lastRebondCounts = EnumMap<Lens, Int>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, 0) }
    }

    private var lastHeadsetSleeping: Boolean? = null

    init {
        persistenceScope.launch {
            telemetryParser.events.collect { event ->
                handleTelemetryEvent(event)
            }
        }
        startSnapshotLogger()
    }

    private fun startSnapshotLogger() {
        snapshotLogJob?.cancel()
        snapshotLogJob = persistenceScope.launch {
            while (isActive) {
                delay(30_000L)
                val timestamp = System.currentTimeMillis()
                buildTelemetrySnapshotLine(timestamp)?.let { log ->
                    logger(log.message)
                    memory.addConsoleLine(log.message)
                    publishSnapshotLine(log)
                }
            }
        }
    }

    suspend fun exportTelemetrySnapshots(limit: Int = 10): List<PersistedSnapshot> {
        return memory.recentTelemetrySnapshots(limit).map { record ->
            PersistedSnapshot(
                recordedAt = record.recordedAt,
                caseJson = record.case?.toJsonString(),
                leftJson = record.left.snapshotJson,
                rightJson = record.right.snapshotJson,
            )
        }
    }

    private fun buildTelemetrySnapshotLine(recordedAt: Long): SnapshotLog? {
        val case = _caseStatus.value
        val left = lensTelemetrySnapshots.getValue(Lens.LEFT).value
        val right = lensTelemetrySnapshots.getValue(Lens.RIGHT).value
        val uptimeSeconds = listOfNotNull(left.uptimeSeconds, right.uptimeSeconds).maxOrNull()
        val firmware = left.firmwareVersion ?: right.firmwareVersion
        val hasCaseData = listOf(case.batteryPercent, case.charging, case.lidOpen, case.silentMode)
            .any { it != null }
        val hasLensData = listOf(
            left.batteryVoltageMv,
            right.batteryVoltageMv,
            firmware,
            uptimeSeconds,
        ).any { it != null }
        if (!hasCaseData && !hasLensData) {
            return null
        }
        fun MoncchichiBleService.AckType.toSummaryLabel(): String {
            val raw = name.lowercase(Locale.US)
            return raw.replace('_', ' ')
        }
        val caseBattery = case.batteryPercent ?: left.caseBatteryPercent ?: right.caseBatteryPercent
        val lidState = case.lidOpen ?: left.caseOpen ?: right.caseOpen
        val silentMode = case.silentMode ?: left.caseSilentMode ?: right.caseSilentMode
        val caseCharging = case.charging ?: left.caseCharging ?: right.caseCharging
        val primaryAck = selectPrimaryTelemetry(left, right)
        val ackModeLabel = ackModeLabel(primaryAck?.ackMode)
        val ackStatus = primaryAck?.lastAckStatus
        val severity = resolveSnapshotSeverity(ackModeLabel, ackStatus)
        val rtt = primaryAck?.heartbeatLatencyMs
            ?: primaryAck?.heartbeatLatencyAvgMs
            ?: left.heartbeatLatencyMs
            ?: right.heartbeatLatencyMs
        val leftVoltage = left.batteryVoltageMv?.let { "$it mV" } ?: "–"
        val rightVoltage = right.batteryVoltageMv?.let { "$it mV" } ?: "–"
        val caseLabel = caseBattery?.let { "$it %" } ?: "–"
        val lidLabel = when (lidState) {
            true -> "Open"
            false -> "Closed"
            null -> "Unknown"
        }
        val silentLabel = silentMode?.let { if (it) "Silent: On" else "Silent: Off" }
        val chargingLabel = caseCharging?.let { if (it) "Case Charging" else "Case Idle" }
        val ackStatusLabel = ackStatus
            ?.takeUnless { it.equals("OK", ignoreCase = true) }
            ?.let { "Status: $it" }
        val firmwareLabel = firmware?.let { "FW: $it" }
        val uptimeLabel = uptimeSeconds?.let { "Up: $it s" }
        val message = buildString {
            append("[SNAPSHOT] ")
            append("L: ")
            append(leftVoltage)
            append("  R: ")
            append(rightVoltage)
            append("  Case: ")
            append(caseLabel)
            append("  Lid: ")
            append(lidLabel)
            append("  ACK: ")
            append(ackModeLabel)
            ackStatusLabel?.let {
                append("  ")
                append(it)
            }
            append("  RTT: ")
            append(rtt?.let { "$it ms" } ?: "–")
            silentLabel?.let {
                append("  ")
                append(it)
            }
            chargingLabel?.let {
                append("  ")
                append(it)
            }
            firmwareLabel?.let {
                append("  ")
                append(it)
            }
            uptimeLabel?.let {
                append("  ")
                append(it)
            }
        }
        return SnapshotLog(message = message.trimEnd(), timestamp = recordedAt, severity = severity)
    }

    private fun publishSnapshotLine(recordedAt: Long) {
        buildTelemetrySnapshotLine(recordedAt)?.let { publishSnapshotLine(it) }
    }

    private fun publishSnapshotLine(log: SnapshotLog) {
        _snapshotLine.value = log
    }

    private fun selectPrimaryTelemetry(
        left: DeviceTelemetrySnapshot,
        right: DeviceTelemetrySnapshot,
    ): DeviceTelemetrySnapshot? {
        val leftTimestamp = left.lastAckTimestamp
        val rightTimestamp = right.lastAckTimestamp
        return when {
            leftTimestamp == null && rightTimestamp == null -> when {
                left.ackMode != null -> left
                right.ackMode != null -> right
                else -> null
            }
            leftTimestamp == null -> right
            rightTimestamp == null -> left
            leftTimestamp >= rightTimestamp -> left
            else -> right
        }
    }

    private fun ackModeLabel(mode: MoncchichiBleService.AckType?): String {
        return when (mode) {
            MoncchichiBleService.AckType.BINARY -> "Binary"
            MoncchichiBleService.AckType.TEXTUAL -> "Textual"
            null -> "Unknown"
        }
    }

    private fun resolveSnapshotSeverity(ackLabel: String, ackStatus: String?): SnapshotSeverity {
        return when {
            ackStatus != null && ackStatus.equals("OK", ignoreCase = true) -> SnapshotSeverity.OK
            ackStatus != null && ackStatus.equals("BUSY", ignoreCase = true) -> SnapshotSeverity.WARN
            ackStatus != null && ackStatus.isNotBlank() -> SnapshotSeverity.ERROR
            ackLabel.equals("Unknown", ignoreCase = true) -> SnapshotSeverity.WARN
            ackLabel.isNotBlank() -> SnapshotSeverity.NORMAL
            else -> SnapshotSeverity.NORMAL
        }
    }

    private fun restoreFromPersistedSnapshot(record: MemoryRepository.TelemetrySnapshotRecord) {
        val recordedAt = record.recordedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        record.case?.toCaseStatus(recordedAt)?.let { status ->
            _caseStatus.value = status
        }
        val leftFlow = lensTelemetrySnapshots.getValue(Lens.LEFT)
        val rightFlow = lensTelemetrySnapshots.getValue(Lens.RIGHT)
        leftFlow.value = leftFlow.value.mergeWithPersisted(Lens.LEFT, record.left, record.case, recordedAt)
        rightFlow.value = rightFlow.value.mergeWithPersisted(Lens.RIGHT, record.right, record.case, recordedAt)
        updateSnapshot(persist = false) { current ->
            current.copy(
                left = current.left.mergeFromPersisted(record.left),
                right = current.right.mergeFromPersisted(record.right),
                uptimeSeconds = record.uptimeSeconds ?: current.uptimeSeconds,
            )
        }
        synchronized(snapshotPersistLock) {
            lastPersistedSnapshotContent = record.copy(recordedAt = 0L)
        }
        publishSnapshotLine(recordedAt)
    }

    private fun DeviceTelemetrySnapshot.mergeWithPersisted(
        lens: Lens,
        snapshot: MemoryRepository.LensSnapshot,
        case: MemoryRepository.CaseSnapshot?,
        recordedAt: Long,
    ): DeviceTelemetrySnapshot {
        val parsed = snapshot.snapshotJson?.let(::parseLensSnapshotJson)
        val ackType = ackTypeFromString(parsed?.ackMode ?: snapshot.lastAckMode)
        val persistedCaseBattery = snapshot.caseBatteryPercent ?: case?.batteryPercent ?: parsed?.caseBatteryPercent
        val persistedCaseOpen = snapshot.caseOpen ?: case?.lidOpen ?: parsed?.caseOpen
        val persistedCaseSilent = snapshot.caseSilentMode ?: case?.silentMode ?: parsed?.caseSilentMode
        val persistedCaseCharge = case?.charging ?: parsed?.caseCharging
        val uptime = snapshot.uptimeSeconds ?: parsed?.uptimeSeconds
        val firmware = snapshot.firmwareVersion ?: parsed?.firmwareVersion ?: firmwareVersion
        val heartbeatMs = snapshot.heartbeatLatencyMs ?: parsed?.heartbeatLatencyMs
        val heartbeatAvg = snapshot.heartbeatLatencyAvgMs ?: parsed?.heartbeatLatencyAvgMs
        return copy(
            lens = lens,
            timestamp = snapshot.lastUpdated ?: parsed?.timestamp ?: timestamp,
            batteryVoltageMv = snapshot.batteryVoltageMv ?: parsed?.batteryVoltageMv ?: batteryVoltageMv,
            isCharging = parsed?.isCharging ?: isCharging,
            caseBatteryPercent = persistedCaseBattery ?: caseBatteryPercent,
            caseOpen = persistedCaseOpen ?: this.caseOpen,
            caseCharging = persistedCaseCharge ?: caseCharging,
            caseSilentMode = persistedCaseSilent ?: caseSilentMode,
            uptimeSeconds = uptime ?: uptimeSeconds,
            firmwareVersion = firmware,
            environment = parsed?.environment ?: environment,
            lastAckStatus = snapshot.lastAckStatus ?: parsed?.lastAckStatus ?: lastAckStatus,
            lastAckTimestamp = snapshot.lastAckTimestamp ?: parsed?.lastAckTimestamp ?: lastAckTimestamp,
            reconnectAttempts = snapshot.reconnectAttempts ?: reconnectAttempts,
            heartbeatLatencyMs = heartbeatMs ?: heartbeatLatencyMs,
            ackMode = ackType ?: ackMode,
            heartbeatLatencyAvgMs = heartbeatAvg ?: heartbeatLatencyAvgMs,
            heartbeatMissCount = snapshot.heartbeatMissCount ?: parsed?.heartbeatMissCount ?: heartbeatMissCount,
        )
    }

    private fun LensTelemetry.mergeFromPersisted(snapshot: MemoryRepository.LensSnapshot): LensTelemetry {
        val ackType = ackTypeFromString(snapshot.lastAckMode)
        return copy(
            batteryPercent = snapshot.batteryPercent ?: batteryPercent,
            caseBatteryPercent = snapshot.caseBatteryPercent ?: caseBatteryPercent,
            caseOpen = snapshot.caseOpen ?: caseOpen,
            silentMode = snapshot.caseSilentMode ?: silentMode,
            lastUpdatedAt = snapshot.lastUpdated ?: lastUpdatedAt,
            firmwareVersion = snapshot.firmwareVersion ?: firmwareVersion,
            notes = snapshot.notes ?: notes,
            reconnectAttemptsSnapshot = snapshot.reconnectAttempts ?: reconnectAttemptsSnapshot,
            heartbeatLatencySnapshotMs = snapshot.heartbeatLatencyMs ?: heartbeatLatencySnapshotMs,
            heartbeatLatencyAvgMs = snapshot.heartbeatLatencyAvgMs ?: heartbeatLatencyAvgMs,
            heartbeatMissCount = snapshot.heartbeatMissCount ?: heartbeatMissCount,
            ackMode = ackType ?: ackMode,
        )
    }

    private fun MemoryRepository.CaseSnapshot.toCaseStatus(recordedAt: Long): CaseStatus {
        return CaseStatus(
            batteryPercent = batteryPercent,
            charging = charging,
            lidOpen = lidOpen,
            silentMode = silentMode,
            voltageMv = null,
            updatedAt = recordedAt,
        )
    }

    private fun MemoryRepository.CaseSnapshot.toJsonString(): String? {
        val json = JSONObject()
        var hasValue = false
        fun put(name: String, value: Any?) {
            if (value != null) {
                json.put(name, value)
                hasValue = true
            }
        }
        put("batteryPercent", batteryPercent)
        put("charging", charging)
        put("lidOpen", lidOpen)
        put("silentMode", silentMode)
        return if (hasValue) json.toString() else null
    }

    private data class PersistedLensJson(
        val timestamp: Long?,
        val batteryVoltageMv: Int?,
        val isCharging: Boolean?,
        val caseBatteryPercent: Int?,
        val caseOpen: Boolean?,
        val caseCharging: Boolean?,
        val caseSilentMode: Boolean?,
        val uptimeSeconds: Long?,
        val firmwareVersion: String?,
        val environment: Map<String, String>?,
        val lastAckStatus: String?,
        val lastAckTimestamp: Long?,
        val ackMode: String?,
        val heartbeatLatencyMs: Int?,
        val heartbeatLatencyAvgMs: Int?,
        val heartbeatMissCount: Int?,
    )

    private fun parseLensSnapshotJson(json: String): PersistedLensJson? {
        return runCatching {
            val obj = JSONObject(json)
            val env = obj.optJSONObject("environment")?.let { envObj ->
                envObj.keys().asSequence().associateWith { key -> envObj.getString(key) }
            }
            PersistedLensJson(
                timestamp = obj.optNullableLong("timestamp"),
                batteryVoltageMv = obj.optNullableInt("batteryVoltageMv"),
                isCharging = obj.optNullableBoolean("isCharging"),
                caseBatteryPercent = obj.optNullableInt("caseBatteryPercent"),
                caseOpen = obj.optNullableBoolean("caseOpen"),
                caseCharging = obj.optNullableBoolean("caseCharging"),
                caseSilentMode = obj.optNullableBoolean("caseSilentMode"),
                uptimeSeconds = obj.optNullableLong("uptimeSeconds"),
                firmwareVersion = obj.optNullableString("firmwareVersion"),
                environment = env,
                lastAckStatus = obj.optNullableString("lastAckStatus"),
                lastAckTimestamp = obj.optNullableLong("lastAckTimestamp"),
                ackMode = obj.optNullableString("ackMode") ?: obj.optNullableString("lastAckMode"),
                heartbeatLatencyMs = obj.optNullableInt("heartbeatLatencyMs"),
                heartbeatLatencyAvgMs = obj.optNullableInt("heartbeatLatencyAvgMs"),
                heartbeatMissCount = obj.optNullableInt("heartbeatMissCount"),
            )
        }.getOrNull()
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private fun JSONObject.optNullableBoolean(name: String): Boolean? =
        if (has(name) && !isNull(name)) getBoolean(name) else null

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun ackTypeFromString(value: String?): MoncchichiBleService.AckType? {
        if (value.isNullOrBlank()) return null
        return runCatching { MoncchichiBleService.AckType.valueOf(value) }.getOrNull()
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

    private fun recordBatteryVitals(
        lens: Lens,
        timestamp: Long,
        voltageMv: Int?,
        batteryPercent: Int?,
        isCharging: Boolean?,
    ) {
        val state = batteryLogState.getValue(lens)
        var changed = false
        voltageMv?.let { value ->
            if (state.voltageMv != value) {
                state.voltageMv = value
                changed = true
            }
        }
        batteryPercent?.let { percent ->
            if (state.batteryPercent != percent) {
                state.batteryPercent = percent
                changed = true
            }
        }
        isCharging?.let { charging ->
            if (state.isCharging != charging) {
                state.isCharging = charging
                changed = true
            }
        }
        maybeEmitBatteryVitals(lens, timestamp, changed)
    }

    private fun touchBatteryVitals(lens: Lens, timestamp: Long) {
        maybeEmitBatteryVitals(lens, timestamp, changed = false)
    }

    private fun maybeEmitBatteryVitals(lens: Lens, timestamp: Long, changed: Boolean) {
        val state = batteryLogState.getValue(lens)
        val voltage = state.voltageMv
        val charging = state.isCharging
        if (voltage == null || charging == null) {
            return
        }
        if (!changed && timestamp - state.lastEmitAt < BATTERY_LOG_INTERVAL_MS) {
            return
        }
        val message = buildString {
            append(voltage)
            append(" mV")
            state.batteryPercent?.let { percent ->
                append(' ')
                append(percent)
                append('%')
            }
            append(' ')
            append("charging=")
            append(charging)
        }
        emitConsole("VITALS", lens, message, timestamp)
        state.lastEmitAt = timestamp
    }

    private fun processAckSignal(
        lens: Lens,
        opcode: Int?,
        status: Int?,
        success: Boolean?,
        busy: Boolean,
        timestamp: Long,
        ackType: MoncchichiBleService.AckType,
        warmup: Boolean = false,
        logConsole: Boolean = true,
    ) {
        val normalizedStatus = status?.and(0xFF)
        val outcome = when {
            busy || normalizedStatus == STATUS_BUSY -> AckOutcome.BUSY
            success == true || isAckSuccessCode(normalizedStatus) -> AckOutcome.OK
            success == false -> AckOutcome.FAIL
            else -> return
        }
        if (logConsole && shouldLogAck(lens, opcode, outcome, normalizedStatus, timestamp)) {
            val opcodeLabel = opcode?.toHexLabel() ?: "n/a"
            val statusLabel = normalizedStatus?.toHexLabel()
            val message = when (outcome) {
                AckOutcome.OK -> "[OK] opcode=$opcodeLabel"
                AckOutcome.BUSY -> "[BUSY] opcode=$opcodeLabel retrying"
                AckOutcome.FAIL -> buildString {
                    append("[FAIL] opcode=$opcodeLabel")
                    statusLabel?.let {
                        append(" status=")
                        append(it)
                    }
                }
            }
            emitConsole("ACK", lens, message, timestamp)
            ackLogState.getValue(lens).apply {
                lastOpcode = opcode
                lastOutcome = outcome
                lastStatus = normalizedStatus
                lastLoggedAt = timestamp
            }
            if (outcome == AckOutcome.FAIL) {
                val statusLabel = normalizedStatus?.toHexLabel() ?: "n/a"
                _uartText.tryEmit(UartLine(lens, "ACK FAIL opcode=$opcodeLabel status=$statusLabel"))
            }
        }

        val statusText = when (outcome) {
            AckOutcome.OK -> "OK"
            AckOutcome.BUSY -> "BUSY"
            AckOutcome.FAIL -> normalizedStatus?.toHexLabel() ?: "FAIL"
        }

        val telemetryState = lensTelemetrySnapshots.getValue(lens)
        val previousSnapshot = telemetryState.value
        val ackStateChanged = previousSnapshot.lastAckStatus != statusText || previousSnapshot.ackMode != ackType

        updateDeviceTelemetry(
            lens = lens,
            eventTimestamp = timestamp,
            logUpdate = ackStateChanged,
            persist = false,
        ) { snapshot ->
            snapshot.copy(
                lastAckStatus = statusText,
                lastAckTimestamp = timestamp,
                ackMode = ackType,
            )
        }
        persistDeviceTelemetrySnapshots(timestamp)

        updateSnapshot(eventTimestamp = timestamp, persist = false) { current ->
            val existing = current.lens(lens)
            val latency = existing.lastAckAt?.let { previous -> (timestamp - previous).takeIf { it >= 0 } }
            val failureCount = when (outcome) {
                AckOutcome.OK -> if (opcode != null) 0 else existing.ackFailureCount
                AckOutcome.BUSY -> existing.ackFailureCount
                AckOutcome.FAIL -> existing.ackFailureCount + 1
            }
            val dropCount = when (outcome) {
                AckOutcome.FAIL -> existing.ackDropCount + 1
                else -> existing.ackDropCount
            }
            val warmupCount = existing.ackWarmupCount + if (warmup && outcome == AckOutcome.OK) 1 else 0
            val successCount = existing.ackSuccessCount + if (outcome == AckOutcome.OK) 1 else 0
            val updated = existing.copy(
                lastAckAt = timestamp,
                lastAckOpcode = opcode,
                lastAckLatencyMs = latency,
                ackSuccessCount = successCount,
                ackFailureCount = failureCount,
                ackWarmupCount = warmupCount,
                ackDropCount = dropCount,
                ackMode = ackType,
            )
            current.updateLens(lens, updated)
        }

        if (outcome == AckOutcome.OK) {
            touchBatteryVitals(lens, timestamp)
        }
    }

    private fun shouldLogAck(
        lens: Lens,
        opcode: Int?,
        outcome: AckOutcome,
        status: Int?,
        timestamp: Long,
    ): Boolean {
        val state = ackLogState.getValue(lens)
        val duplicate = state.lastOpcode == opcode &&
            state.lastOutcome == outcome &&
            state.lastStatus == status &&
            timestamp - state.lastLoggedAt < ACK_LOG_DEDUP_WINDOW_MS
        return !duplicate
    }

    private fun isAckSuccessCode(status: Int?): Boolean {
        val normalized = status ?: return false
        return when (normalized and 0xFF) {
            STATUS_OK, OPC_ACK_COMPLETE, OPC_ACK_CONTINUE -> true
            else -> false
        }
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
    private var snapshotLogJob: Job? = null
    private var dashboardEncoder: DashboardDataEncoder? = null
    private var boundService: MoncchichiBleService? = null
    private var lastConnected = false
    private val lastLensConnected = EnumMap<Lens, Boolean>(Lens::class.java).apply {
        Lens.values().forEach { put(it, false) }
    }
    private var serviceScope: CoroutineScope? = null
    private val lastAudioFrameTime = AtomicLong(0L)
    private val batteryLogState = EnumMap<Lens, BatteryLogState>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, BatteryLogState()) }
    }
    private val ackLogState = EnumMap<Lens, AckLogState>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, AckLogState()) }
    }
    private var micWatchdogJob: Job? = null
    private val lastCaseRefreshAt = EnumMap<Lens, Long>(Lens::class.java).apply {
        Lens.values().forEach { put(it, 0L) }
    }
    private val snapshotPersistLock = Any()
    private var lastPersistedSnapshotContent: MemoryRepository.TelemetrySnapshotRecord? = null

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
        validationController.stop("telemetry reset")
        synchronized(stabilityHistory) { stabilityHistory.clear() }
        _micAvailability.value = false
        _battery.value = null
        _uptime.value = null
        _deviceStatus.value = null
        _wearingStatus.value = DeviceStatus()
        _inCaseStatus.value = DeviceStatus()
        _chargingStatus.value = DeviceStatus()
        _caseStatus.value = CaseStatus()
        _linkPriming.value = initialLinkPriming()
        lensTelemetrySnapshots.forEach { (lens, flow) ->
            flow.value = initialDeviceTelemetrySnapshot(lens)
        }
        telemetryValidator?.reset()
        micWatchdogJob?.cancel()
        micWatchdogJob = null
        lastAudioFrameTime.set(0L)
        batteryLogState.values.forEach { state ->
            state.voltageMv = null
            state.batteryPercent = null
            state.isCharging = null
            state.lastEmitAt = 0L
        }
        ackLogState.values.forEach { state ->
            state.lastOpcode = null
            state.lastOutcome = AckOutcome.OK
            state.lastStatus = null
            state.lastLoggedAt = 0L
        }
        _dashboardStatus.value = DashboardDataEncoder.BurstStatus()
        lastLensConnected.replaceAll { _, _ -> false }
        lastCaseRefreshAt.replaceAll { _, _ -> 0L }
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
                handleLensConnectionTransition(Lens.LEFT, state.left.isConnected)
                handleLensConnectionTransition(Lens.RIGHT, state.right.isConnected)
                _linkPriming.value = mapOf(
                    Lens.LEFT to LinkPrimingSnapshot(
                        lens = Lens.LEFT,
                        notifyArmed = state.left.notificationsArmed,
                        warmupOk = state.left.warmupOk,
                        attMtu = state.left.attMtu,
                    ),
                    Lens.RIGHT to LinkPrimingSnapshot(
                        lens = Lens.RIGHT,
                        notifyArmed = state.right.notificationsArmed,
                        warmupOk = state.right.warmupOk,
                        attMtu = state.right.attMtu,
                    ),
                )
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
        _linkPriming.value = initialLinkPriming()
        synchronized(stabilityHistory) { stabilityHistory.clear() }
        synchronized(snapshotPersistLock) { lastPersistedSnapshotContent = null }
        validationController.stop("service unbound")
    }

    fun startValidationTest(): Boolean {
        val connections = MoncchichiBleService.Lens.values().associateWith { lens ->
            lastLensConnected[lens] == true
        }
        val snapshot = _snapshot.value
        val lensUpdates = mapOf(
            MoncchichiBleService.Lens.LEFT to snapshot.left.lastUpdatedAt,
            MoncchichiBleService.Lens.RIGHT to snapshot.right.lastUpdatedAt,
        )
        return validationController.start(
            caseStatus = _caseStatus.value,
            connections = connections,
            lensUpdateTimes = lensUpdates,
        )
    }

    fun stopValidationTest(reason: String? = null) {
        validationController.stop(reason)
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
        validationController.onStabilityMetrics(metrics)
        val lens = metrics.lens
        val rssiLabel = metrics.avgRssi?.toString() ?: "n/a"
        val ackLabel = metrics.lastAckDeltaMs?.let { "${it} ms" } ?: "n/a"
        val reconnectLabel = metrics.reconnectLatencyMs?.let { "${it} ms" } ?: "n/a"
        val rebondCount = metrics.rebondEvents
        if (lens != null) {
            val previous = lastRebondCounts[lens] ?: 0
            if (rebondCount > previous) {
                telemetryValidator?.reset()
            }
            lastRebondCounts[lens] = rebondCount
        } else {
            var resetTriggered = false
            Lens.values().forEach { tracked ->
                val previous = lastRebondCounts[tracked] ?: 0
                if (rebondCount > previous) {
                    resetTriggered = true
                }
                lastRebondCounts[tracked] = rebondCount
            }
            if (resetTriggered) {
                telemetryValidator?.reset()
            }
        }
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
        val parseResult = telemetryParser.parse(lens, frame)
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
                    lens = event.lens,
                    timestamp = event.timestampMs,
                    lidOpen = event.flags.caseOpen,
                    silentMode = event.flags.silentMode,
                )
                updateDeviceTelemetry(
                    lens = event.lens,
                    eventTimestamp = event.timestampMs,
                    logUpdate = false,
                ) { snapshot ->
                    snapshot.copy(
                        caseOpen = event.flags.caseOpen,
                        caseSilentMode = event.flags.silentMode,
                    )
                }
                touchBatteryVitals(event.lens, event.timestampMs)
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
                    recordBatteryVitals(
                        event.lens,
                        event.timestampMs,
                        info.voltage,
                        event.batteryPercent,
                        info.isCharging,
                    )
                }
                event.caseBatteryPercent?.let { percent ->
                    updateCaseStatus(
                        lens = event.lens,
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
                val label = gestureLabel(event.lens, event.gesture)
                emitConsole("GESTURE", null, label, event.timestampMs)
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
                    lens = event.lens,
                    timestamp = event.timestampMs,
                    batteryPercent = event.caseBatteryPercent,
                    charging = event.charging,
                    lidOpen = event.lidOpen,
                    silentMode = event.silentMode,
                    voltageMv = event.caseVoltageMv,
                )
            }
            is BleTelemetryParser.TelemetryEvent.SystemCommandEvent -> {
                handleSystemCommandEvent(event)
            }
            is BleTelemetryParser.TelemetryEvent.DisplayEvent -> {
                handleDisplayEvent(event)
            }
            is BleTelemetryParser.TelemetryEvent.SerialNumberEvent -> {
                handleSerialEvent(event)
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
        processAckSignal(
            lens = event.lens,
            opcode = event.opcode,
            status = event.ackCode,
            success = event.success,
            busy = event.busy,
            timestamp = event.timestampMs,
            ackType = MoncchichiBleService.AckType.BINARY,
            warmup = false,
            logConsole = false,
        )
    }

    private fun logParserFallback(opcode: Int, timestamp: Long) {
        if (missingOpcodeLog.add(opcode)) {
            emitConsole("DIAG", null, "Unhandled opcode ${opcode.toHexLabel()}", timestamp)
        }
    }

    private fun applyStateFlags(
        lens: Lens,
        flags: G1ReplyParser.StateFlags,
        timestamp: Long,
        hex: String,
    ) {
        validationController.onStateFlags(lens, timestamp, flags)
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
            updated = updated.withVitalsTimestamp(timestamp)
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
        validationController.onBatteryFrame(lens, timestamp, lensBattery != null, caseBattery != null)
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
            updated = updated.withVitalsTimestamp(timestamp)
            val next = if (updated == existing) current else current.updateLens(lens, updated)
            next.withFrame(lens, hex)
        }
        recordBatteryVitals(
            lens = lens,
            timestamp = timestamp,
            voltageMv = event.info?.voltage,
            batteryPercent = lensBattery,
            isCharging = event.info?.isCharging,
        )
        if (caseChanged) {
            caseBattery?.let { percent ->
                emitConsole("CASE", lens, "battery=${percent}%", timestamp)
            }
        }
    }

    private fun applyUptimeEvent(event: BleTelemetryParser.TelemetryEvent.UptimeEvent) {
        val lens = event.lens
        val timestamp = event.timestampMs
        val hex = event.rawFrame.toHex()
        val uptime = event.uptimeSeconds
        _uptime.value = uptime
        updateSnapshot(eventTimestamp = timestamp) { current ->
            val updatedLens = current.lens(lens).withVitalsTimestamp(timestamp)
            val withLens = if (updatedLens == current.lens(lens)) current else current.updateLens(lens, updatedLens)
            val withUptime = withLens.copy(uptimeSeconds = uptime)
            if (withUptime.uptimeSeconds == current.uptimeSeconds && current.lastLens == lens && current.lastFrameHex == hex && updatedLens == current.lens(lens)) {
                current
            } else {
                withUptime.withFrame(lens, hex)
            }
        }
        emitConsole("UPTIME", lens, "secs=${uptime}", timestamp)
        updateDeviceTelemetry(lens, timestamp) { snapshot ->
            snapshot.copy(uptimeSeconds = uptime)
        }
        touchBatteryVitals(lens, timestamp)
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
        val lens = event.lens
        val typeLabel = when (event.type) {
            F5EventType.GESTURE -> "GESTURE"
            F5EventType.SYSTEM -> "SYSTEM"
            F5EventType.CASE -> "CASE"
            F5EventType.UNKNOWN -> "UNKNOWN"
        }
        val subLabel = event.subcommand?.toHexLabel() ?: "n/a"
        val payloadHex = event.rawFrame.toHex()
        val details = mutableListOf<String>()
        event.vitals?.let { vitals ->
            vitals.batteryPercent?.let { percent ->
                details += "battery=${percent}%"
            }
            vitals.charging?.let { charging ->
                details += "charging=${charging}"
            }
            vitals.firmwareVersion?.takeIf { it.isNotBlank() }?.let { banner ->
                details += "fw=${banner}"
            }
        }
        event.evenAiEvent?.let { evenAi ->
            details += describeEvenAi(evenAi)
        }
        val extra = details.takeIf { it.isNotEmpty() }?.joinToString(separator = " ")
        val logMessage = buildString {
            append("[BLE][F5][")
            append(typeLabel)
            append("][")
            append(lens.shortLabel)
            append("] sub=")
            append(subLabel)
            append(" payload=")
            append(payloadHex)
            if (extra != null) {
                append(' ')
                append(extra)
            }
        }
        logger(logMessage)
        event.vitals?.let { vitals ->
            handleParsedVitals(lens, vitals, event.rawFrame)
        }
        if (event.type != F5EventType.GESTURE) {
            event.evenAiEvent?.let { evenAi ->
                val message = describeEvenAi(evenAi)
                emitConsole("GESTURE", lens, message, event.timestampMs)
            }
        }
        if (event.type == F5EventType.UNKNOWN) {
            validationController.onUnknownF5(lens, event)
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
                recordBatteryVitals(lens, timestamp, null, null, value)
            }
        }

        if (event.caseOpen != null || event.caseBatteryPercent != null || (eventCode == 0x0E && event.charging != null)) {
            updateCaseStatus(
                lens = lens,
                timestamp = timestamp,
                lidOpen = event.caseOpen,
                batteryPercent = event.caseBatteryPercent,
                charging = event.charging.takeIf { eventCode == 0x0E },
            )
        }

        updateSnapshot(eventTimestamp = timestamp) { current ->
            var telemetry = current.lens(lens)
            val existingTelemetry = telemetry
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
            telemetry = telemetry.withVitalsTimestamp(timestamp)
            if (!changed && telemetry == existingTelemetry) {
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
        lens: Lens?,
        timestamp: Long,
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        lidOpen: Boolean? = null,
        silentMode: Boolean? = null,
        voltageMv: Int? = null,
    ) {
        val current = _caseStatus.value
        var changed = false
        var batteryChanged = false
        var lidChanged = false
        var chargingChanged = false
        var silentChanged = false
        var voltageChanged = false
        var nextBattery = current.batteryPercent
        var nextCharging = current.charging
        var nextLid = current.lidOpen
        var nextSilent = current.silentMode
        var nextVoltage = current.voltageMv
        val touchedBattery = batteryPercent != null
        val touchedLid = lidOpen != null
        val touchedSilent = silentMode != null
        val touchedCharging = charging != null
        val touchedVoltage = voltageMv != null

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
                chargingChanged = true
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
                silentChanged = true
            }
        }
        voltageMv?.let { value ->
            if (value != nextVoltage) {
                nextVoltage = value
                changed = true
                voltageChanged = true
            }
        }
        val anyTouched = touchedBattery || touchedLid || touchedSilent || touchedCharging || touchedVoltage
        if (!changed && !anyTouched) {
            return
        }
        val updated = CaseStatus(
            batteryPercent = nextBattery,
            charging = nextCharging,
            lidOpen = nextLid,
            silentMode = nextSilent,
            voltageMv = nextVoltage,
            updatedAt = if (anyTouched) timestamp else current.updatedAt,
        )
        _caseStatus.value = updated
        validationController.onCaseStatus(
            status = updated,
            timestamp = timestamp,
            batteryUpdated = touchedBattery,
            lidUpdated = touchedLid,
            silentUpdated = touchedSilent,
        )
        if (changed) {
            emitCaseConsole(lens, updated, timestamp)
            if (lidChanged) {
                boundService?.updateHeartbeatLidOpen(updated.lidOpen)
            }
            lens?.let { sourceLens ->
                if (chargingChanged) {
                    boundService?.updateHeartbeatInCase(sourceLens, _inCaseStatus.value.valueFor(sourceLens))
                }
            }
            propagateCaseTelemetry(updated, timestamp, batteryChanged, lidChanged, chargingChanged, silentChanged, voltageChanged)
        }
    }

    private fun emitCaseConsole(lens: Lens?, status: CaseStatus, timestamp: Long) {
        val parts = mutableListOf<String>()
        status.lidOpen?.let { open -> parts += if (open) "Lid Open" else "Lid Closed" }
        status.batteryPercent?.let { percent -> parts += String.format(Locale.US, "%d %%", percent) }
        status.voltageMv?.let { mv -> parts += String.format(Locale.US, "%d mV", mv) }
        status.charging?.let { charging -> parts += if (charging) "Charging" else "Not Charging" }
        status.silentMode?.let { silent -> parts += "Silent = ${if (silent) "On" else "Off"}" }
        if (parts.isEmpty()) {
            return
        }
        val prefix = lens?.shortLabel?.let { "$it → " } ?: ""
        val message = buildString {
            append(prefix)
            append(parts.joinToString(separator = " • "))
        }
        emitConsole("CASE", null, message, timestamp)
    }

    private fun propagateCaseTelemetry(
        status: CaseStatus,
        timestamp: Long,
        batteryChanged: Boolean,
        lidChanged: Boolean,
        chargingChanged: Boolean,
        silentChanged: Boolean,
        voltageChanged: Boolean,
    ) {
        if (!batteryChanged && !lidChanged && !chargingChanged && !silentChanged && !voltageChanged) {
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
                if (chargingChanged) {
                    updated = updated.copy(caseCharging = status.charging)
                }
                if (silentChanged) {
                    updated = updated.copy(caseSilentMode = status.silentMode)
                }
                if (voltageChanged) {
                    updated = updated.copy(caseVoltageMv = status.voltageMv)
                }
                updated
            }
        }
        if (batteryChanged || lidChanged || silentChanged || voltageChanged) {
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
                if (silentChanged && status.silentMode != null && left.silentMode != status.silentMode) {
                    left = left.copy(silentMode = status.silentMode, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (silentChanged && status.silentMode != null && right.silentMode != status.silentMode) {
                    right = right.copy(silentMode = status.silentMode, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (voltageChanged && left.caseVoltageMv != status.voltageMv) {
                    left = left.copy(caseVoltageMv = status.voltageMv, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (voltageChanged && right.caseVoltageMv != status.voltageMv) {
                    right = right.copy(caseVoltageMv = status.voltageMv, lastUpdatedAt = timestamp)
                    snapshotChanged = true
                }
                if (!snapshotChanged) {
                    current
                } else {
                    val updatedLeft = left.withVitalsTimestamp(timestamp)
                    val updatedRight = right.withVitalsTimestamp(timestamp)
                    current.copy(
                        left = updatedLeft,
                        right = updatedRight,
                        caseOpen = status.lidOpen ?: current.caseOpen,
                        lastVitalsTimestamp = maxOfNonNull(
                            current.lastVitalsTimestamp,
                            updatedLeft.lastVitalsTimestamp,
                            updatedRight.lastVitalsTimestamp,
                            timestamp,
                        ),
                    )
                }
            }
        }
        persistDeviceTelemetrySnapshots(timestamp)
    }

    private fun handleLensConnectionTransition(lens: Lens, connected: Boolean) {
        val previous = lastLensConnected[lens] ?: false
        if (previous == connected) {
            return
        }
        lastLensConnected[lens] = connected
        validationController.onLensConnection(lens, connected)
        if (connected) {
            requestCaseRefresh(lens)
        } else {
            lastCaseRefreshAt[lens] = 0L
        }
    }

    private fun requestCaseRefresh(lens: Lens, force: Boolean = false) {
        val service = boundService ?: return
        val scope = serviceScope ?: return
        val now = System.currentTimeMillis()
        val last = lastCaseRefreshAt[lens] ?: 0L
        if (!force && now - last < CASE_REFRESH_MIN_INTERVAL_MS) {
            return
        }
        lastCaseRefreshAt[lens] = now
        scope.launch {
            val target = when (lens) {
                Lens.LEFT -> MoncchichiBleService.Target.Left
                Lens.RIGHT -> MoncchichiBleService.Target.Right
            }
            val statusPayload = byteArrayOf(G1Protocols.OPC_DEVICE_STATUS.toByte())
            service.send(statusPayload, target)
            service.send(G1Packets.batteryQuery(), target)
            emitConsole("CASE", lens, "query 0x2B/0x2C", System.currentTimeMillis())
        }
    }

    private fun gestureLabel(lens: Lens, gesture: G1ReplyParser.GestureEvent): String {
        val name = gesture.name.ifBlank { "Gesture 0x%02X".format(Locale.US, gesture.code and 0xFF) }
        return "%s → %s".format(Locale.US, lens.shortLabel, name)
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
        validationController.onVitals(lens, timestamp, vitals)
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

            updated = updated.withVitalsTimestamp(timestamp)
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

        recordBatteryVitals(
            lens = lens,
            timestamp = timestamp,
            voltageMv = null,
            batteryPercent = trustedBattery,
            isCharging = charging,
        )

        if (firmwareChanged) {
            firmwareBanner?.let { banner ->
                emitConsole("VITALS", lens, "fw=${banner} (${opcode.toHexLabel()})", timestamp)
            }
        }

        if (firmwareChanged) {
            firmwareBanner?.let { line ->
                parseTextMetadata(lens, line, timestamp)
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
        validationController.onAck(event)
        processAckSignal(
            lens = event.lens,
            opcode = event.opcode,
            status = event.status,
            success = event.success,
            busy = event.busy,
            timestamp = event.timestampMs,
            ackType = event.type,
            warmup = event.warmup,
            logConsole = true,
        )
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
        var telemetryChanged = false
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var changed = false
            if (existing.reconnectAttempts != status.reconnectAttempts) {
                updated = updated.copy(reconnectAttempts = status.reconnectAttempts)
                emitConsole("STATE", lens, "auto-reconnect attempt ${status.reconnectAttempts}")
                if (status.reconnectAttempts > existing.reconnectAttempts) {
                    validationController.onReconnectAttempt(lens)
                }
                changed = true
            }
            if (existing.reconnectSuccesses != status.reconnectSuccesses) {
                updated = updated.copy(reconnectSuccesses = status.reconnectSuccesses)
                emitConsole("STATE", lens, "auto-reconnect success")
                requestCaseRefresh(lens, force = true)
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
            val heartbeatAverage = status.heartbeatRttAvgMs?.toInt()
            val heartbeatChanged = existing.heartbeatLatencySnapshotMs != heartbeatLatency
            val heartbeatAvgChanged = existing.heartbeatLatencyAvgMs != heartbeatAverage
            val reconnectSnapshot = status.reconnectAttempts
            val reconnectChanged = existing.reconnectAttemptsSnapshot != reconnectSnapshot
            if (heartbeatChanged || reconnectChanged || heartbeatAvgChanged || existing.ackMode != status.heartbeatAckType) {
                updated = updated.copy(
                    heartbeatLatencySnapshotMs = heartbeatLatency,
                    heartbeatLatencyAvgMs = heartbeatAverage,
                    reconnectAttemptsSnapshot = reconnectSnapshot,
                    heartbeatLastPingAt = status.heartbeatLastPingAt,
                    heartbeatMissCount = status.heartbeatMissCount,
                    ackMode = status.heartbeatAckType,
                )
                val hbLabel = heartbeatLatency?.let { "${it} ms" } ?: "n/a"
                val reconnectLabel = reconnectSnapshot ?: 0
                emitConsole("STABILITY", lens, "heartbeat=$hbLabel reconnect=$reconnectLabel", System.currentTimeMillis())
                updateDeviceTelemetry(lens, System.currentTimeMillis(), logUpdate = false, persist = false) { snapshot ->
                    snapshot.copy(
                        heartbeatLatencyMs = heartbeatLatency,
                        heartbeatLatencyAvgMs = heartbeatAverage,
                        reconnectAttempts = reconnectSnapshot,
                        ackMode = status.heartbeatAckType ?: snapshot.ackMode,
                        heartbeatMissCount = status.heartbeatMissCount,
                    )
                }
                telemetryChanged = true
                changed = true
            }

            if (!changed) {
                current
            } else {
                current.updateLens(lens, updated)
            }
        }
        if (telemetryChanged) {
            persistDeviceTelemetrySnapshots(System.currentTimeMillis())
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
        val other = when (lens) {
            Lens.LEFT -> right
            Lens.RIGHT -> left
        }
        val mergedCaseOpen = telemetry.caseOpen ?: other.caseOpen ?: caseOpen
        val mergedInCase = telemetry.inCase ?: other.inCase ?: inCase
        val mergedFoldState = telemetry.foldState ?: other.foldState ?: foldState
        val mergedCharging = telemetry.charging ?: other.charging ?: charging
        val mergedVitals = maxOfNonNull(telemetry.lastVitalsTimestamp, other.lastVitalsTimestamp, lastVitalsTimestamp)
        val normalizedTelemetry = telemetry.withSharedValues(
            caseOpenValue = mergedCaseOpen,
            inCaseValue = mergedInCase,
            foldStateValue = mergedFoldState,
            chargingValue = mergedCharging,
            lastVitalsValue = mergedVitals,
        )
        val normalizedOther = other.withSharedValues(
            caseOpenValue = mergedCaseOpen,
            inCaseValue = mergedInCase,
            foldStateValue = mergedFoldState,
            chargingValue = mergedCharging,
            lastVitalsValue = mergedVitals,
        )
        val updated = when (lens) {
            Lens.LEFT -> copy(
                left = normalizedTelemetry,
                right = normalizedOther,
                caseOpen = mergedCaseOpen,
                inCase = mergedInCase,
                foldState = mergedFoldState,
                charging = mergedCharging,
                lastVitalsTimestamp = mergedVitals,
            )
            Lens.RIGHT -> copy(
                left = normalizedOther,
                right = normalizedTelemetry,
                caseOpen = mergedCaseOpen,
                inCase = mergedInCase,
                foldState = mergedFoldState,
                charging = mergedCharging,
                lastVitalsTimestamp = mergedVitals,
            )
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
        val timestamp = System.currentTimeMillis()
        lines.forEach { line ->
            parseTextMetadata(lens, line, timestamp)
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
        val timestamp = System.currentTimeMillis()
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
                    parseTextMetadata(lens, trimmed, timestamp)
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

    private fun parseTextMetadata(lens: Lens, line: String, timestamp: Long = System.currentTimeMillis()) {
        maybeHandleSleepWakeLine(lens, line, timestamp)
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

    private fun maybeHandleSleepWakeLine(lens: Lens, line: String, timestamp: Long) {
        val normalized = line.trim().lowercase(Locale.US)
        val state = when (normalized) {
            "sleep" -> SleepState.SLEEPING
            "wake" -> SleepState.AWAKE
            else -> return
        }

        updateSnapshot(eventTimestamp = timestamp) { current ->
            val existing = current.lens(lens)
            val updated = when (state) {
                SleepState.SLEEPING -> existing.copy(
                    sleepState = state,
                    lastSleepAt = timestamp,
                    lastStateUpdatedAt = timestamp,
                )

                SleepState.AWAKE -> existing.copy(
                    sleepState = state,
                    lastWakeAt = timestamp,
                    lastStateUpdatedAt = timestamp,
                )

                else -> existing
            }
            if (updated == existing) current else current.updateLens(lens, updated)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
    }

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
        if (updated != previous) {
            maybeEmitSleepTransitions(previous, updated)
        }
    }

    private fun persistSnapshot(snapshot: Snapshot, eventTimestamp: Long?) {
        val recordedAt = eventTimestamp ?: System.currentTimeMillis()
        persistDeviceTelemetrySnapshots(recordedAt)
    }

    fun isLensSleeping(lens: Lens, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return isLensSleeping(_snapshot.value, lens, nowMillis)
    }

    fun isHeadsetSleeping(nowMillis: Long = System.currentTimeMillis()): Boolean =
        isHeadsetSleeping(_snapshot.value, nowMillis)

    fun isSleeping(lens: Lens? = null, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return lens?.let { isLensSleeping(it, nowMillis) } ?: isHeadsetSleeping(nowMillis)
    }

    private fun isLensSleeping(
        snapshot: Snapshot,
        lens: Lens,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val lensSnapshot = snapshot.lens(lens)
        val resolvedCaseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        val resolvedInCase = lensSnapshot.inCase ?: snapshot.inCase
        val resolvedFoldState = lensSnapshot.foldState ?: snapshot.foldState
        val resolvedCharging = lensSnapshot.charging ?: snapshot.charging
        val lastVitals = lensSnapshot.lastVitalsTimestamp ?: snapshot.lastVitalsTimestamp
        if (
            resolvedCaseOpen == null ||
            resolvedInCase == null ||
            resolvedFoldState == null ||
            resolvedCharging == null ||
            lastVitals == null
        ) {
            return false
        }

        val quietFor = nowMillis - lastVitals
        return resolvedFoldState &&
            resolvedInCase &&
            resolvedCaseOpen &&
            !resolvedCharging &&
            quietFor > G1Protocols.CE_IDLE_SLEEP_QUIET_WINDOW_MS
    }

    private fun maybeEmitSleepTransitions(
        previous: Snapshot,
        updated: Snapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val previousHeadset = lastHeadsetSleeping ?: isHeadsetSleeping(previous, nowMillis)
        val updatedHeadset = isHeadsetSleeping(updated, nowMillis)
        if (previousHeadset != updatedHeadset) {
            lastHeadsetSleeping = updatedHeadset
            val event = if (updatedHeadset) SleepEvent.SleepEntered(null) else SleepEvent.SleepExited(null)
            _sleepEvents.tryEmit(event)
        }
    }

    private fun isHeadsetSleeping(snapshot: Snapshot, nowMillis: Long): Boolean {
        return Lens.values().all { lens -> isLensSleeping(snapshot, lens, nowMillis) }
    }

    private fun LensTelemetry.toRecord(): MemoryRepository.LensSnapshot =
        MemoryRepository.LensSnapshot(
            batteryPercent = batteryPercent,
            batteryVoltageMv = null,
            caseBatteryPercent = caseBatteryPercent,
            caseOpen = caseOpen,
            caseSilentMode = silentMode,
            lastUpdated = lastUpdatedAt,
            rssi = rssi,
            firmwareVersion = firmwareVersion,
            notes = notes,
            reconnectAttempts = reconnectAttemptsSnapshot,
            heartbeatLatencyAvgMs = heartbeatLatencyAvgMs,
            heartbeatLatencyMs = heartbeatLatencySnapshotMs,
            lastAckMode = ackMode?.name,
            lastAckStatus = null,
            lastAckTimestamp = null,
            uptimeSeconds = null,
            snapshotJson = null,
            heartbeatMissCount = heartbeatMissCount,
        )

    private fun LensTelemetry.withSharedValues(
        caseOpenValue: Boolean?,
        inCaseValue: Boolean?,
        foldStateValue: Boolean?,
        chargingValue: Boolean?,
        lastVitalsValue: Long?,
    ): LensTelemetry {
        return copy(
            caseOpen = caseOpen ?: caseOpenValue,
            inCase = inCase ?: inCaseValue,
            foldState = foldState ?: foldStateValue,
            charging = charging ?: chargingValue,
            lastVitalsTimestamp = lastVitalsTimestamp ?: lastVitalsValue,
        )
    }

    private fun LensTelemetry.withVitalsTimestamp(timestamp: Long?): LensTelemetry {
        val merged = maxOfNonNull(lastVitalsTimestamp, timestamp)
        return if (merged == lastVitalsTimestamp) this else copy(lastVitalsTimestamp = merged)
    }

    private fun MoncchichiBleService.Target.toLensOrNull(): Lens? = when (this) {
        MoncchichiBleService.Target.Left -> Lens.LEFT
        MoncchichiBleService.Target.Right -> Lens.RIGHT
        MoncchichiBleService.Target.Both -> null
    }

    private class ValidationController(
        private val scope: CoroutineScope,
        private val logger: (String) -> Unit,
        private val emitConsole: (String, MoncchichiBleService.Lens?, String, Long) -> Unit,
    ) {
        companion object {
            private const val WINDOW_MS = 5 * 60 * 1000L
            private const val SUMMARY_INTERVAL_MS = 60_000L
            private const val GRACE_PERIOD_MS = 15_000L
            private const val CASE_TTL_MS = 30_000L
            private const val LENS_TTL_MS = 30_000L
            private const val HEARTBEAT_TTL_MS = 45_000L
            private const val HEARTBEAT_MIN_MS = 25_000L
            private const val HEARTBEAT_MAX_MS = 35_000L
        }

        private val _status = MutableStateFlow(ValidationStatus())
        val status: StateFlow<ValidationStatus> = _status.asStateFlow()

        private var running = false
        private var sessionStart = 0L
        private var job: Job? = null
        private var ackFailures = 0
        private var reconnects = 0
        private var missedHeartbeats = 0
        private var passCount = 0
        private var lastSummaryAt = 0L
        private var lastSummary: String? = null
        private var lastCaseStatus: CaseStatus? = null
        private var lastCaseBatteryAt = 0L
        private var lastCaseLidAt = 0L
        private var lastCaseSilentAt = 0L
        private val lastLensBatteryAt = EnumMap<MoncchichiBleService.Lens, Long>(MoncchichiBleService.Lens::class.java)
        private val lastHeartbeatAckAt = EnumMap<MoncchichiBleService.Lens, Long>(MoncchichiBleService.Lens::class.java)
        private val lastHeartbeatInterval = EnumMap<MoncchichiBleService.Lens, Long>(MoncchichiBleService.Lens::class.java)
        private val lastMissCount = EnumMap<MoncchichiBleService.Lens, Int>(MoncchichiBleService.Lens::class.java)
        private val monitoredLenses = EnumMap<MoncchichiBleService.Lens, Boolean>(MoncchichiBleService.Lens::class.java)

        init {
            MoncchichiBleService.Lens.values().forEach { lens ->
                lastLensBatteryAt[lens] = 0L
                lastHeartbeatAckAt[lens] = 0L
                lastHeartbeatInterval[lens] = 0L
                lastMissCount[lens] = 0
                monitoredLenses[lens] = false
            }
        }

        fun start(
            caseStatus: CaseStatus,
            connections: Map<MoncchichiBleService.Lens, Boolean>,
            lensUpdateTimes: Map<MoncchichiBleService.Lens, Long?>,
        ): Boolean {
            if (running) return false
            val missing = connections.filterValues { it != true }.keys
            if (missing.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val label = missing.joinToString(separator = ",") { it.shortLabel }
                logger("[VALIDATION][WARN] Cannot start validation: lens offline ($label)")
                emitConsole("VALIDATION", null, "start blocked – connect both lenses", now)
                _status.value = ValidationStatus(
                    state = ValidationState.Idle,
                    message = "Connect both lenses before starting",
                )
                return false
            }
            running = true
            passCount = 0
            sessionStart = System.currentTimeMillis()
            lastSummaryAt = sessionStart
            ackFailures = 0
            reconnects = 0
            missedHeartbeats = 0
            lastSummary = null
            lastCaseStatus = caseStatus
            lastCaseBatteryAt = if (caseStatus.batteryPercent != null) caseStatus.updatedAt else 0L
            lastCaseLidAt = if (caseStatus.lidOpen != null) caseStatus.updatedAt else 0L
            lastCaseSilentAt = if (caseStatus.silentMode != null) caseStatus.updatedAt else 0L
            MoncchichiBleService.Lens.values().forEach { lens ->
                val connected = connections[lens] == true
                monitoredLenses[lens] = connected
                lastLensBatteryAt[lens] = lensUpdateTimes[lens] ?: 0L
                lastHeartbeatAckAt[lens] = 0L
                lastHeartbeatInterval[lens] = 0L
                lastMissCount[lens] = 0
            }
            _status.value = ValidationStatus(
                state = ValidationState.Running,
                startedAt = sessionStart,
                elapsedMs = 0L,
                missedHeartbeats = 0,
                reconnects = 0,
                ackFailures = 0,
                passCount = passCount,
                lastSummary = null,
                message = "Started",
            )
            logger("[VALIDATION] Burn-in validation started")
            emitConsole("VALIDATION", null, "started", sessionStart)
            job?.cancel()
            job = scope.launch {
                while (isActive && running) {
                    delay(1_000)
                    tick()
                }
            }
            return true
        }

        fun stop(reason: String? = null) {
            if (!running) {
                if (!reason.isNullOrBlank()) {
                    _status.update { current ->
                        current.copy(message = reason)
                    }
                }
                return
            }
            running = false
            job?.cancel()
            job = null
            val now = System.currentTimeMillis()
            val message = reason ?: "stopped"
            logger("[VALIDATION] stopped: $message")
            emitConsole("VALIDATION", null, "stopped: $message", now)
            _status.update {
                ValidationStatus(
                    state = ValidationState.Idle,
                    message = message,
                )
            }
        }

        fun onAck(event: MoncchichiBleService.AckEvent) {
            if (!running) return
            val lens = event.lens
            if (monitoredLenses[lens] != true) return
            if (event.opcode == CMD_PING) {
                if (event.success) {
                    recordHeartbeat(lens, event.timestampMs)
                } else if (!event.busy) {
                    heartbeatMiss(lens)
                }
            } else if (!event.success && !event.busy) {
                ackFailure(lens, event.opcode, event.status)
            }
        }

        fun onStabilityMetrics(metrics: MoncchichiBleService.BleStabilityMetrics) {
            if (!running) return
            val lens = metrics.lens ?: return
            if (monitoredLenses[lens] != true) return
            val previous = lastMissCount[lens] ?: 0
            val currentMiss = metrics.missedHeartbeats
            lastMissCount[lens] = currentMiss
            if (currentMiss > previous) {
                heartbeatMiss(lens)
            }
        }

        fun onVitals(
            lens: MoncchichiBleService.Lens,
            timestamp: Long,
            vitals: G1ReplyParser.DeviceVitals,
        ) {
            if (!running) return
            if (monitoredLenses[lens] == true && vitals.batteryPercent != null) {
                lastLensBatteryAt[lens] = timestamp
            }
            vitals.caseBatteryPercent?.let { lastCaseBatteryAt = timestamp }
            vitals.caseOpen?.let { lastCaseLidAt = timestamp }
            vitals.silentMode?.let { lastCaseSilentAt = timestamp }
            if (vitals.caseBatteryPercent != null || vitals.caseOpen != null || vitals.silentMode != null) {
                lastCaseStatus = lastCaseStatus?.copy(
                    batteryPercent = vitals.caseBatteryPercent ?: lastCaseStatus?.batteryPercent,
                    lidOpen = vitals.caseOpen ?: lastCaseStatus?.lidOpen,
                    silentMode = vitals.silentMode ?: lastCaseStatus?.silentMode,
                    updatedAt = timestamp,
                )
            }
        }

        fun onBatteryFrame(
            lens: MoncchichiBleService.Lens,
            timestamp: Long,
            hasLensBattery: Boolean,
            hasCaseBattery: Boolean,
        ) {
            if (!running) return
            if (monitoredLenses[lens] == true && hasLensBattery) {
                lastLensBatteryAt[lens] = timestamp
            }
            if (hasCaseBattery) {
                lastCaseBatteryAt = timestamp
            }
        }

        fun onCaseStatus(
            status: CaseStatus,
            timestamp: Long,
            batteryUpdated: Boolean,
            lidUpdated: Boolean,
            silentUpdated: Boolean,
        ) {
            if (!running) return
            lastCaseStatus = status
            if (batteryUpdated && status.batteryPercent != null) {
                lastCaseBatteryAt = timestamp
            }
            if (lidUpdated && status.lidOpen != null) {
                lastCaseLidAt = timestamp
            }
            if (silentUpdated && status.silentMode != null) {
                lastCaseSilentAt = timestamp
            }
        }

        fun onStateFlags(
            lens: MoncchichiBleService.Lens,
            timestamp: Long,
            flags: G1ReplyParser.StateFlags,
        ) {
            if (!running) return
            if (monitoredLenses[lens] == true) {
                lastLensBatteryAt[lens] = lastLensBatteryAt[lens] ?: 0L
            }
            lastCaseLidAt = timestamp
            lastCaseSilentAt = timestamp
        }

        fun onReconnectAttempt(lens: MoncchichiBleService.Lens) {
            if (!running) return
            reconnectFailure(lens)
        }

        fun onLensConnection(lens: MoncchichiBleService.Lens, connected: Boolean) {
            if (!running) return
            val wasMonitored = monitoredLenses[lens] == true
            monitoredLenses[lens] = connected
            if (wasMonitored && !connected) {
                reconnectFailure(lens)
            } else if (connected) {
                lastLensBatteryAt[lens] = 0L
                lastHeartbeatAckAt[lens] = 0L
                lastMissCount[lens] = 0
            }
        }

        fun onUnknownF5(
            lens: MoncchichiBleService.Lens,
            event: BleTelemetryParser.TelemetryEvent.F5Event,
        ) {
            if (!running) return
            logger("[VALIDATION][WARN] unexpected F5 sub=${event.subcommand?.toHexLabel() ?: "n/a"}")
            val now = System.currentTimeMillis()
            emitConsole("VALIDATION", lens, "unexpected F5", now)
            failInternal(now, "Unexpected F5 telemetry", lens)
        }

        private fun tick() {
            if (!running) return
            val now = System.currentTimeMillis()
            val elapsed = now - sessionStart
            if (elapsed >= SUMMARY_INTERVAL_MS && now - lastSummaryAt >= SUMMARY_INTERVAL_MS) {
                issueSummary(now)
            }
            if (elapsed > GRACE_PERIOD_MS) {
                if (checkStaleness(now)) {
                    return
                }
            }
            if (elapsed >= WINDOW_MS && ackFailures == 0 && reconnects == 0 && missedHeartbeats == 0) {
                handlePass(now)
                return
            }
            _status.update { current ->
                current.copy(
                    state = ValidationState.Running,
                    startedAt = sessionStart,
                    elapsedMs = elapsed,
                    missedHeartbeats = missedHeartbeats,
                    reconnects = reconnects,
                    ackFailures = ackFailures,
                    passCount = passCount,
                    lastSummary = lastSummary,
                )
            }
        }

        private fun recordHeartbeat(lens: MoncchichiBleService.Lens, timestamp: Long) {
            val previous = lastHeartbeatAckAt[lens] ?: 0L
            if (previous > 0L) {
                val interval = timestamp - previous
                lastHeartbeatInterval[lens] = interval
                if (interval < HEARTBEAT_MIN_MS || interval > HEARTBEAT_MAX_MS) {
                    heartbeatDeviation(lens, interval)
                    return
                }
            }
            lastHeartbeatAckAt[lens] = timestamp
            lastMissCount[lens] = 0
        }

        private fun heartbeatMiss(lens: MoncchichiBleService.Lens) {
            missedHeartbeats += 1
            val now = System.currentTimeMillis()
            logger("[VALIDATION][WARN] missed heartbeat (${lens.shortLabel})")
            emitConsole("VALIDATION", lens, "missed heartbeat", now)
            failInternal(now, "Missed heartbeat (${lens.shortLabel})", lens)
        }

        private fun heartbeatDeviation(lens: MoncchichiBleService.Lens, interval: Long) {
            val now = System.currentTimeMillis()
            logger("[VALIDATION][WARN] heartbeat interval ${interval}ms (${lens.shortLabel})")
            emitConsole("VALIDATION", lens, "heartbeat interval ${interval}ms", now)
            failInternal(now, "Heartbeat interval out of range", lens)
        }

        private fun ackFailure(lens: MoncchichiBleService.Lens, opcode: Int?, status: Int?) {
            ackFailures += 1
            val now = System.currentTimeMillis()
            logger("[VALIDATION][WARN] ack failure (${lens.shortLabel}) opcode=${opcode?.toHexLabel() ?: "n/a"} status=${status?.toHexLabel() ?: "n/a"}")
            emitConsole("VALIDATION", lens, "ack failure", now)
            failInternal(now, "ACK failure (${lens.shortLabel})", lens)
        }

        private fun reconnectFailure(lens: MoncchichiBleService.Lens) {
            reconnects += 1
            val now = System.currentTimeMillis()
            logger("[ERR] reconnect (${lens.shortLabel})")
            emitConsole("VALIDATION", lens, "reconnect", now)
            failInternal(now, "Reconnect detected (${lens.shortLabel})", lens)
        }

        private fun checkStaleness(now: Long): Boolean {
            monitoredLenses.filterValues { it }.forEach { (lens, _) ->
                val heartbeatAt = lastHeartbeatAckAt[lens] ?: 0L
                if (heartbeatAt == 0L) {
                    heartbeatMiss(lens)
                    return true
                }
                if (now - heartbeatAt > HEARTBEAT_TTL_MS) {
                    heartbeatMiss(lens)
                    return true
                }
                val batteryAt = lastLensBatteryAt[lens] ?: 0L
                if (batteryAt == 0L || now - batteryAt > LENS_TTL_MS) {
                    lensTelemetryFailure(lens)
                    return true
                }
            }
            if (lastCaseBatteryAt == 0L || now - lastCaseBatteryAt > CASE_TTL_MS) {
                caseTelemetryFailure("case battery stale")
                return true
            }
            if (lastCaseLidAt == 0L || now - lastCaseLidAt > CASE_TTL_MS) {
                caseTelemetryFailure("case lid stale")
                return true
            }
            if (lastCaseSilentAt == 0L || now - lastCaseSilentAt > CASE_TTL_MS) {
                caseTelemetryFailure("silent mode stale")
                return true
            }
            return false
        }

        private fun lensTelemetryFailure(lens: MoncchichiBleService.Lens) {
            val now = System.currentTimeMillis()
            logger("[VALIDATION][WARN] telemetry stale (${lens.shortLabel})")
            emitConsole("VALIDATION", lens, "telemetry stale", now)
            failInternal(now, "Lens telemetry stale (${lens.shortLabel})", lens)
        }

        private fun caseTelemetryFailure(reason: String) {
            val now = System.currentTimeMillis()
            logger("[VALIDATION][WARN] $reason")
            emitConsole("VALIDATION", null, reason, now)
            failInternal(now, reason, null)
        }

        private fun issueSummary(now: Long) {
            val intervals = lastHeartbeatInterval.values.filter { it > 0L }
            val hbLabel = if (intervals.isNotEmpty()) {
                val avg = intervals.average().roundToLong().coerceAtLeast(1L)
                "${avg / 1000}s"
            } else {
                "n/a"
            }
            val ackLabel = if (ackFailures == 0) "ACKs OK" else "ACKs $ackFailures"
            val case = lastCaseStatus
            val caseLabel = case?.let {
                val battery = it.batteryPercent?.let { pct -> "$pct%" } ?: "n/a"
                val lid = when (it.lidOpen) {
                    true -> "open"
                    false -> "closed"
                    else -> "unknown"
                }
                val silent = when (it.silentMode) {
                    true -> "silent on"
                    false -> "silent off"
                    null -> null
                }
                buildString {
                    append("Case $battery ($lid)")
                    silent?.let { state ->
                        append(" • ")
                        append(state)
                    }
                }
            } ?: "Case n/a"
            val link = if (ackFailures == 0 && reconnects == 0 && missedHeartbeats == 0) {
                "Link OK"
            } else {
                "Link check"
            }
            val summary = listOf(link, "HB=$hbLabel", ackLabel, caseLabel).joinToString(separator = " • ")
            lastSummary = summary
            lastSummaryAt = now
            logger("[VALIDATION] $summary")
            emitConsole("VALIDATION", null, summary, now)
            _status.update { current -> current.copy(lastSummary = summary) }
        }

        private fun handlePass(now: Long) {
            passCount += 1
            val label = "PASS 5m stable (#$passCount)"
            logger("[VALIDATION] $label")
            emitConsole("VALIDATION", null, label, now)
            val elapsed = now - sessionStart
            _status.update {
                it.copy(
                    state = ValidationState.Passed,
                    startedAt = sessionStart,
                    elapsedMs = elapsed,
                    missedHeartbeats = missedHeartbeats,
                    reconnects = reconnects,
                    ackFailures = ackFailures,
                    passCount = passCount,
                    lastSummary = lastSummary,
                    message = label,
                )
            }
            sessionStart = now
            lastSummaryAt = now
            ackFailures = 0
            reconnects = 0
            missedHeartbeats = 0
            MoncchichiBleService.Lens.values().forEach { lens -> lastMissCount[lens] = 0 }
            scope.launch {
                delay(1_000)
                if (running) {
                    _status.update {
                        it.copy(
                            state = ValidationState.Running,
                            startedAt = sessionStart,
                            elapsedMs = 0L,
                            missedHeartbeats = missedHeartbeats,
                            reconnects = reconnects,
                            ackFailures = ackFailures,
                            passCount = passCount,
                            message = "Cycle reset",
                        )
                    }
                }
            }
        }

        private fun failInternal(
            now: Long,
            message: String,
            lens: MoncchichiBleService.Lens?,
        ) {
            if (!running) return
            running = false
            job?.cancel()
            job = null
            _status.update {
                it.copy(
                    state = ValidationState.Failed,
                    startedAt = sessionStart,
                    elapsedMs = now - sessionStart,
                    missedHeartbeats = missedHeartbeats,
                    reconnects = reconnects,
                    ackFailures = ackFailures,
                    passCount = passCount,
                    lastSummary = lastSummary,
                    message = message,
                )
            }
        }
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
        private const val CASE_REFRESH_MIN_INTERVAL_MS = 3_000L
        private const val BATTERY_LOG_INTERVAL_MS = 30_000L
        private const val ACK_LOG_DEDUP_WINDOW_MS = 3_000L
    }
}

private fun Byte.toHexLabel(): String = toInt().toHexLabel()

private fun Int.toHexLabel(): String = String.format("0x%02X", this and 0xFF)

private fun maxOfNonNull(vararg values: Long?): Long? {
    return values.filterNotNull().maxOrNull()
}
