package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.bluetooth.BondAwaitResult
import com.loopermallee.moncchichi.bluetooth.BondResult
import com.loopermallee.moncchichi.bluetooth.G1Protocols.BATT_SUB_DETAIL
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_BATT_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_CASE_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_PING
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_EVENT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_OK
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_WEAR_DETECT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.isAckComplete
import com.loopermallee.moncchichi.bluetooth.G1Protocols.isAckContinuation
import com.loopermallee.moncchichi.core.MicControlPacket
import com.loopermallee.moncchichi.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.text.SimpleDateFormat
import kotlin.coroutines.coroutineContext
import java.util.Date
import kotlin.collections.ArrayDeque
import kotlin.random.Random
import kotlin.text.Charsets

/**
 * High-level BLE orchestration layer that manages the dual-lens Even Realities G1 glasses.
 */
class MoncchichiBleService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: MoncchichiLogger = MoncchichiLogger(context),
    private val telemetryRepository: BleTelemetryRepository? = null,
    sleepWakeFlow: Flow<Boolean>? = null,
) {

    enum class Lens(val shortLabel: String) {
        LEFT("L"),
        RIGHT("R"),
    }

    enum class ConnectionStage {
        Idle,
        IdleSleep,
        ConnectLeft,
        LeftReady,
        Warmup,
        ConnectRight,
        BothReady,
        Connected,
    }

    data class LensStatus(
        val state: G1BleClient.ConnectionState = G1BleClient.ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
        val lastAckAt: Long? = null,
        val degraded: Boolean = false,
        val attMtu: Int? = null,
        val notificationsArmed: Boolean = false,
        val warmupOk: Boolean = false,
        val lastKeepAliveAt: Long? = null,
        val keepAliveRttMs: Long? = null,
        val consecutiveKeepAliveFailures: Int = 0,
        val keepAliveLockSkips: Int = 0,
        val keepAliveAckTimeouts: Int = 0,
        val bonded: Boolean = false,
        val disconnectStatus: Int? = null,
        val bondTransitions: Int = 0,
        val bondTimeouts: Int = 0,
        val bondAttempts: Int = 0,
        val lastBondResult: BondResult = BondResult.Unknown,
        val pairingDialogsShown: Int = 0,
        val refreshCount: Int = 0,
        val smpFrameCount: Int = 0,
        val lastSmpOpcode: Int? = null,
        val reconnectAttempts: Int = 0,
        val reconnectSuccesses: Int = 0,
        val reconnecting: Boolean = false,
        val bondResetCount: Int = 0,
        val lastBondState: Int? = null,
        val lastBondReason: Int? = null,
        val lastBondEventAt: Long? = null,
        val heartbeatLatencyMs: Long? = null,
        val heartbeatRttAvgMs: Long? = null,
        val heartbeatAckType: AckType? = null,
        val heartbeatLastPingAt: Long? = null,
        val heartbeatMissCount: Int = 0,
        val sleeping: Boolean = false,
        val sleepReason: String? = null,
    ) {
        val isConnected: Boolean get() = state == G1BleClient.ConnectionState.CONNECTED
    }

    data class BleStabilityMetrics(
        val lens: Lens?,
        val timestamp: Long,
        val heartbeatCount: Int,
        val missedHeartbeats: Int,
        val rebondEvents: Int,
        val lastAckDeltaMs: Long?,
        val avgRssi: Int?,
        val reconnectLatencyMs: Long?,
    )

    data class ServiceState(
        val left: LensStatus = LensStatus(),
        val right: LensStatus = LensStatus(),
        val connectionOrder: List<Lens> = emptyList(),
        val autoReconnectAttemptCount: Int = 0,
        val autoReconnectSuccessCount: Int = 0,
        val rightBondRetryCount: Int = 0,
        val bondResetEvents: Int = 0,
    )

    sealed interface Target {
        data object Left : Target
        data object Right : Target
        data object Both : Target
    }

    data class IncomingFrame(val lens: Lens, val payload: ByteArray)

    data class EvenAiEvent(val lens: Lens, val event: G1ReplyParser.EvenAiEvent)
    data class AudioFrame(val lens: Lens, val sequence: Int, val payload: ByteArray)

    enum class AckType {
        BINARY,
        TEXTUAL,
    }

    data class AckEvent(
        val lens: Lens,
        val opcode: Int?,
        val status: Int?,
        val success: Boolean,
        val busy: Boolean,
        val timestampMs: Long,
        val warmup: Boolean,
        val type: AckType,
    )

    data class ReconnectStatus(
        val state: ReconnectPhase = ReconnectPhase.Idle,
        val attempt: Int = 0,
        val nextDelayMs: Long? = null,
    )

    enum class ReconnectPhase { Idle, Waiting, Retrying, Cooldown }

    sealed interface MoncchichiEvent {
        data object ConnectionFailed : MoncchichiEvent
    }

    private data class ClientRecord(
        val lens: Lens,
        val client: G1BleClient,
        val jobs: MutableList<Job>,
    ) {
        fun dispose() {
            jobs.forEach { it.cancel() }
            jobs.clear()
            client.close()
        }
    }

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _connectionStage = MutableStateFlow(ConnectionStage.Idle)
    val connectionStage: StateFlow<ConnectionStage> = _connectionStage.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingFrame> = _incoming.asSharedFlow()
    private val _evenAiEvents = MutableSharedFlow<EvenAiEvent>(extraBufferCapacity = 32)
    val evenAiEvents: SharedFlow<EvenAiEvent> = _evenAiEvents.asSharedFlow()
    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 128)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()
    private val _ackEvents = MutableSharedFlow<AckEvent>(extraBufferCapacity = 32)
    val ackEvents: SharedFlow<AckEvent> = _ackEvents.asSharedFlow()
    private val _events = MutableSharedFlow<MoncchichiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MoncchichiEvent> = _events.asSharedFlow()
    private val _stabilityMetrics = MutableSharedFlow<BleStabilityMetrics>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val stabilityMetrics: SharedFlow<BleStabilityMetrics> = _stabilityMetrics.asSharedFlow()

    private val clientRecords: MutableMap<Lens, ClientRecord> = ConcurrentHashMap()
    private val keepAliveWriteTimestamps = mutableMapOf<Lens, Long>()
    private val caseTelemetryRequested = EnumMap<Lens, Boolean>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, false) }
    }
    private val caseEventCodes = intArrayOf(0x0E, 0x0F)
    private val connectionOrder = mutableListOf<Lens>()
    private val hostHeartbeatSequence = IntArray(Lens.values().size)
    private val knownDevices = mutableMapOf<Lens, BluetoothDevice>()
    private enum class HandshakeStage { Idle, Linked, Acked }
    private val handshakeProgress = EnumMap<Lens, HandshakeStage>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, HandshakeStage.Idle) }
    }
    @Volatile
    private var sequenceLogged = false
    private val caseStateLock = Any()
    @Volatile
    private var lastCaseOpen: Boolean? = null
    private val vitalsLogLock = Any()
    private val lastVitalsLogState = EnumMap<Lens, VitalsLogState?>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, null) }
    }
    private val bondFailureStreak = mutableMapOf<Lens, Int>()
    private val staleBondFailureStreak = EnumMap<Lens, Int>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, 0) }
    }
    private val wakeTelemetryRefreshQueued = AtomicBoolean(false)
    private val reconnectCoordinator = ReconnectCoordinator(
        scope = scope,
        shouldContinue = { lens -> shouldAutoReconnect(lens) },
        onAttempt = { lens, attempt, delayMs, reason ->
            recordReconnectAttempt(lens)
            log("[RECONNECT][${lens.shortLabel}] attempt=$attempt delay=${delayMs}ms reason=$reason")
        },
        attempt = { lens, _, _ ->
            val device = knownDevices[lens] ?: return@ReconnectCoordinator false
            connect(device, lens)
        },
        onSuccess = { lens, attempts ->
            val triesLabel = if (attempts == 1) "1 try" else "$attempts tries"
            log("[RECONNECT][${lens.shortLabel}] success after $triesLabel")
            recordReconnectSuccess(lens)
        },
        onStop = { lens ->
            updateLens(lens) { it.copy(reconnecting = false) }
        },
        updateState = ::setReconnectState,
    )
    private var pendingRightBondSequence = false
    private var rightBondRetryAttempt = 0
    private var rightBondRetryJob: Job? = null
    private var totalBondResetEvents = 0
    private val heartbeatRebondTriggered = EnumMap<Lens, Boolean>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, false) }
    }
    private val heartbeatReconnectTriggered = EnumMap<Lens, Boolean>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, false) }
    }

    private val heartbeatSupervisor = HeartbeatSupervisor(
        parentScope = scope,
        log = ::log,
        emitConsole = ::emitConsole,
        sendHeartbeat = ::performHeartbeat,
        isLensConnected = { lens -> currentLensStatus(lens).isConnected },
        onHeartbeatSuccess = { lens, sequence, timestamp, latency, ackType, elapsedMs, busy ->
            handleHeartbeatSuccess(lens, sequence, timestamp, latency, ackType, elapsedMs, busy)
        },
        onHeartbeatMiss = { lens, timestamp, missCount ->
            handleHeartbeatMiss(lens, timestamp, missCount)
        },
        rebondThreshold = HEARTBEAT_REBOND_THRESHOLD,
        baseIntervalMs = HEARTBEAT_INTERVAL_MS,
        jitterMs = HEARTBEAT_JITTER_MS,
        idlePollMs = HEARTBEAT_IDLE_POLL_MS,
    )
    private var rebondJob: Job? = null
    private var consecutiveConnectionFailures = 0
    private val reconnectFailureTimestamps = EnumMap<Lens, ArrayDeque<Long>>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, ArrayDeque()) }
    }
    private val _reconnectStates = MutableStateFlow(
        Lens.values().associateWith { ReconnectStatus() }
    )
    val reconnectStates: StateFlow<Map<Lens, ReconnectStatus>> = _reconnectStates.asStateFlow()

    private val ackContinuationBuffers: MutableMap<Lens, ByteArrayOutputStream> =
        EnumMap<Lens, ByteArrayOutputStream>(Lens::class.java).apply {
            Lens.values().forEach { lens -> put(lens, ByteArrayOutputStream()) }
        }
    private val ackSignalFlow = MutableSharedFlow<AckSignal>(extraBufferCapacity = 32)
    private val pendingCommandAcks: MutableMap<Lens, ArrayDeque<Long>> =
        EnumMap<Lens, ArrayDeque<Long>>(Lens::class.java).apply {
            Lens.values().forEach { lens -> put(lens, ArrayDeque()) }
        }
    private val lastAckSuccessMs = EnumMap<Lens, Long>(Lens::class.java)
    private val lastAckSignature = EnumMap<Lens, AckSignature>(Lens::class.java)
    private val stabilityState = EnumMap<Lens, AtomicReference<BleStabilityMetrics>>(Lens::class.java).apply {
        Lens.values().forEach { lens ->
            put(lens, AtomicReference(initialStabilityMetrics(lens)))
        }
    }
    private val rssiWindows = EnumMap<Lens, ArrayDeque<Int>>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, ArrayDeque()) }
    }
    private val heartbeatRttWindows = EnumMap<Lens, ArrayDeque<Long>>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, ArrayDeque()) }
    }
    private val lastDisconnectTimestamp = EnumMap<Lens, Long>(Lens::class.java)
    private val stabilityTimeFormatter = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val sleepStates = EnumMap<Lens, SleepState>(Lens::class.java).apply {
        Lens.values().forEach { lens -> put(lens, SleepState()) }
    }
    private val sleepStateLock = Any()
    @Volatile
    private var idleSleepActive: Boolean = false
    private val idleSleepState = MutableStateFlow(false)
    private val sleepMonitorJob = scope.launch {
        while (isActive) {
            checkSleepTimeouts()
            delay(SLEEP_TIMEOUT_POLL_MS)
        }
    }
    private val sleepEventsJob = telemetryRepository?.let { repository ->
        scope.launch {
            repository.sleepEvents.collectLatest { event ->
                event?.let { handleSleepEvent(it) }
            }
        }
    }
    private val sleepWakeJob = sleepWakeFlow?.let { flow ->
        scope.launch {
            flow.collect { sleeping ->
                handleSleepWakeSignal(sleeping)
            }
        }
    }
    private val sleepReconnectJob = scope.launch {
        idleSleepState.collectLatest { sleeping ->
            if (sleeping) {
                reconnectCoordinator.freeze()
            } else {
                reconnectCoordinator.unfreeze()
            }
        }
    }
    private val telemetrySnapshotJob = telemetryRepository?.let { repository ->
        scope.launch {
            repository.snapshot.collect { snapshot ->
                handleTelemetrySnapshot(snapshot)
            }
        }
    }
    private var lastSleepWakeSignal: Boolean? = null

    suspend fun connect(device: BluetoothDevice, lensOverride: Lens? = null): Boolean =
        withContext(Dispatchers.IO) {
            val lens = lensOverride ?: inferLens(device)
            log("Connecting ${device.address} as $lens")

            resetHandshake(lens)

            if (lens == Lens.RIGHT && !awaitLeftReadyForRight()) {
                logWarn("Left lens not ready; postponing right lens connection")
                return@withContext false
            }

            knownDevices[lens] = device
            if (!connectionOrder.contains(lens)) {
                connectionOrder.add(lens)
                refreshConnectionOrderState()
            }
            cancelReconnect(lens)

            val reuseBond = maybeClearStaleBond(device, lens)

            val record = buildClientRecord(lens, device)
            clientRecords[lens]?.dispose()
            clientRecords[lens] = record
            hostHeartbeatSequence[lens.ordinal] = 0
            updateLens(lens) { it.copy(state = G1BleClient.ConnectionState.CONNECTING, rssi = null) }

            if (shouldForceGattRefresh(lens)) {
                val failureCount = reconnectFailureTimestamps.getValue(lens).size
                log("[GATT][${lens.shortLabel}] Refreshing cache before connect (failures=$failureCount)")
                record.client.requestGattRefreshOnConnect()
            }

            if (lens == Lens.LEFT) {
                setStage(ConnectionStage.ConnectLeft)
            } else {
                setStage(ConnectionStage.ConnectRight)
                if (pendingRightBondSequence && !state.value.right.bonded) {
                    delay(RIGHT_BOND_INITIAL_DELAY_MS)
                    pendingRightBondSequence = false
                }
            }

            val readyResult = try {
                if (lens == Lens.RIGHT) {
                    val leftBonded = clientRecords[Lens.LEFT]?.client?.state?.value?.bonded
                        ?: (knownDevices[Lens.LEFT]?.bondState == BluetoothDevice.BOND_BONDED)
                    val rightBonded = record.client.state.value.bonded ||
                        device.bondState == BluetoothDevice.BOND_BONDED
                    log("[PAIRING] Right connect preflight leftBonded=$leftBonded rightBonded=$rightBonded")
                }
                record.client.connect()
                if (reuseBond) {
                    bondFailureStreak[lens] = 0
                    when (lens) {
                        Lens.LEFT -> onLeftBondComplete()
                        Lens.RIGHT -> onRightBondComplete()
                    }
                } else {
                    when (val bondResult = record.client.awaitBonded(BOND_TIMEOUT_MS)) {
                        BondAwaitResult.Success -> {
                            bondFailureStreak[lens] = 0
                            when (lens) {
                                Lens.LEFT -> onLeftBondComplete()
                                Lens.RIGHT -> onRightBondComplete()
                            }
                        }
                        BondAwaitResult.Timeout -> {
                            logWarn("Bond wait timed out for ${device.address} (${lens.name.lowercase(Locale.US)})")
                            val restart = onBondFailure(lens, device, bondResult)
                            if (lens == Lens.RIGHT && state.value.left.bonded) {
                                scheduleRightBondRetry(device)
                            }
                            handleConnectionFailure(lens, record)
                            if (restart) {
                                disconnectAll()
                            }
                            return@withContext false
                        }
                        is BondAwaitResult.Failed -> {
                            logWarn(
                                "Bond failed for ${device.address} (${lens.name.lowercase(Locale.US)}) reason=${bondResult.reason.toBondReasonString()}"
                            )
                            val restart = onBondFailure(lens, device, bondResult)
                            if (lens == Lens.RIGHT && state.value.left.bonded) {
                                scheduleRightBondRetry(device)
                            }
                            handleConnectionFailure(lens, record)
                            if (restart) {
                                disconnectAll()
                            }
                            return@withContext false
                        }
                    }
                }
                val timeout = if (lens == Lens.LEFT) {
                    CONNECT_TIMEOUT_MS
                } else {
                    // Firmware can take up to MTU_WARMUP_GRACE_MS after HELLO; add a small buffer.
                    G1Protocols.MTU_WARMUP_GRACE_MS + 2_000L
                }
                record.client.awaitReady(timeout)
            } catch (error: Throwable) {
                logWarn("Connection failed for ${device.address}: ${error.message ?: "unknown error"}")
                null
            }
            when (readyResult) {
                G1BleClient.AwaitReadyResult.Ready -> Unit
                G1BleClient.AwaitReadyResult.Timeout -> {
                    logWarn("Ready wait timed out for ${device.address} (${lens.name.lowercase(Locale.US)})")
                    handleConnectionFailure(lens, record)
                    return@withContext false
                }
                G1BleClient.AwaitReadyResult.Disconnected -> {
                    logWarn("Link disconnected before ready for ${device.address} (${lens.name.lowercase(Locale.US)})")
                    handleConnectionFailure(lens, record)
                    return@withContext false
                }
                null -> {
                    handleConnectionFailure(lens, record)
                    return@withContext false
                }
            }

            consecutiveConnectionFailures = 0
            reconnectFailureTimestamps[lens]?.clear()
            if (lens == Lens.LEFT) {
                setStage(ConnectionStage.LeftReady)
                delay(G1Protocols.WARMUP_DELAY_MS)
                setStage(ConnectionStage.Warmup)
                maybeStartCompanionSequence()
            }
            if (state.value.left.isConnected && state.value.right.isConnected) {
                setStage(ConnectionStage.BothReady)
                setStage(ConnectionStage.Connected)
            }
            ensureHeartbeatLoop()
            val connectedAt = System.currentTimeMillis()
            lastDisconnectTimestamp.remove(lens)?.let { previous ->
                val latency = (connectedAt - previous).coerceAtLeast(0L)
                updateStabilityMetrics(lens, connectedAt) { metrics ->
                    metrics.copy(reconnectLatencyMs = latency)
                }
            }
            log("Connected ${device.address} on $lens")
            true
        }

    private suspend fun maybeClearStaleBond(device: BluetoothDevice, lens: Lens): Boolean {
        val status = clientState(lens)
        val currentBondState = device.bondState
        val hasBondHistory =
            status.lastBondState != null ||
                status.lastBondEventAt != null ||
                status.bondTransitions > 0 ||
                status.bondAttempts > 0
        val staleBond =
            currentBondState == BluetoothDevice.BOND_BONDED &&
                !status.bonded &&
                hasBondHistory
        val removalDetected = status.lastBondReason == UNBOND_REASON_REMOVED
        val lensLabel = lens.name.lowercase(Locale.US)
        val reasonLabel = formatBondReason(status.lastBondReason)

        var reuseExisting = false
        var clearedBond = false

        if (staleBond) {
            val next = (staleBondFailureStreak[lens] ?: 0) + 1
            val thresholdReached = next >= STALE_BOND_CLEAR_THRESHOLD
            if (thresholdReached) {
                staleBondFailureStreak[lens] = 0
                logWarn("[BOND][${lens.shortLabel}] cleared stale streak=$next reason=$reasonLabel")
                val cleared = clearBond(device)
                log("[PAIRING] removeBond ${device.address} -> $cleared (pre-connect)")
                if (cleared) {
                    clearedBond = true
                    delay(STALE_BOND_CLEAR_DELAY_MS)
                }
            } else {
                staleBondFailureStreak[lens] = next
                reuseExisting = true
                log("[BOND][${lens.shortLabel}] reused stale streak=$next/$STALE_BOND_CLEAR_THRESHOLD")
            }
        } else {
            staleBondFailureStreak[lens] = 0
            if (currentBondState == BluetoothDevice.BOND_BONDED) {
                reuseExisting = true
                log("[BOND][${lens.shortLabel}] reused")
            }
        }

        if (removalDetected && currentBondState != BluetoothDevice.BOND_NONE && !clearedBond) {
            logWarn("[BOND][${lens.shortLabel}] cleared removal reason=$reasonLabel")
            val cleared = clearBond(device)
            log("[PAIRING] removeBond ${device.address} -> $cleared (pre-connect)")
            if (cleared) {
                clearedBond = true
                staleBondFailureStreak[lens] = 0
                delay(STALE_BOND_CLEAR_DELAY_MS)
            }
        }

        if (removalDetected) {
            reuseExisting = false
            val elapsed = status.lastBondEventAt?.let { System.currentTimeMillis() - it } ?: 0L
            val remaining = (BOND_RECOVERY_GUARD_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                logWarn("[PAIRING] $lensLabel delaying reconnect by ${remaining}ms after bond removal (reason=$reasonLabel)")
                delay(remaining)
            }
        }

        return reuseExisting && !clearedBond
    }

    fun disconnect(lens: Lens) {
        clientRecords.remove(lens)?.let { record ->
            log("Disconnecting $lens")
            if (!isSleepModeActive()) {
                record.client.refreshDeviceCache()
            }
            record.dispose()
        }
        keepAliveWriteTimestamps.remove(lens)
        caseTelemetryRequested[lens] = false
        hostHeartbeatSequence[lens.ordinal] = 0
        knownDevices.remove(lens)
        bondFailureStreak.remove(lens)
        staleBondFailureStreak[lens] = 0
        resetContinuationBuffer(lens)
        clearPendingCommands(lens)
        lastAckSuccessMs.remove(lens)
        lastAckSignature.remove(lens)
        cancelReconnect(lens)
        lastDisconnectTimestamp[lens] = System.currentTimeMillis()
        heartbeatRebondTriggered[lens] = false
        resetStabilityMetrics(lens)
        clearHeartbeatRtt(lens)
        updateLens(lens) { LensStatus() }
        if (connectionOrder.remove(lens)) {
            refreshConnectionOrderState()
        }
        ensureHeartbeatLoop()
        val current = state.value
        val sleepActive = isSleepModeActive()
        if (!current.left.isConnected && !current.right.isConnected) {
            if (sleepActive) {
                setStage(ConnectionStage.IdleSleep)
            } else {
                setStage(ConnectionStage.Idle)
            }
            if (connectionOrder.isNotEmpty()) {
                connectionOrder.clear()
                refreshConnectionOrderState()
            }
            if (!sleepActive) {
                scope.launch { refreshAllGattCaches() }
            }
        } else if (current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.LeftReady)
        } else if (sleepActive) {
            setStage(ConnectionStage.IdleSleep)
        }
    }

    fun disconnectAll() {
        ALL_LENSES.forEach { disconnect(it) }
        synchronized(caseStateLock) { lastCaseOpen = null }
    }

    fun updateHeartbeatLidOpen(lidOpen: Boolean?) {
        heartbeatSupervisor.updateCaseState(lidOpen)
    }

    fun updateHeartbeatInCase(lens: Lens, inCase: Boolean?) {
        heartbeatSupervisor.updateInCaseState(lens, inCase)
    }

    suspend fun refreshGattCache(
        lens: Lens,
        logger: (String) -> Unit = { message -> log("${lens.name}: $message") },
    ): Boolean {
        val record = clientRecords[lens] ?: return false
        return record.client.refreshGattCache { message -> logger(message) }
    }

    suspend fun refreshAllGattCaches() {
        val results = clientRecords.values.map { record ->
            record.client.refreshGattCache()
        }
        log("[PAIRING] Global GATT refresh results=${results.joinToString()}")
    }

    suspend fun send(
        payload: ByteArray,
        target: Target = Target.Both,
        ackTimeoutMs: Long = G1Protocols.ACK_TIMEOUT_MS,
        retries: Int = G1Protocols.MAX_RETRIES,
        retryDelayMs: Long = G1Protocols.RETRY_BACKOFF_MS,
    ): Boolean {
        if (isSleepModeActive()) {
            log("[BLE] Send skipped (IdleSleep)")
            return false
        }
        val records = when (target) {
            Target.Left -> listOfNotNull(clientRecords[Lens.LEFT]?.takeIf { it.clientState().isConnected })
            Target.Right -> listOfNotNull(clientRecords[Lens.RIGHT]?.takeIf { it.clientState().isConnected })
            Target.Both -> ALL_LENSES.mapNotNull { lens ->
                clientRecords[lens]?.takeIf { it.clientState().isConnected }
            }
        }
        if (records.isEmpty()) {
            logWarn("No connected lenses for $target")
            return false
        }
        var success = true
        records.forEachIndexed { index, record ->
            val now = System.currentTimeMillis()
            if (ackTimeoutMs > 0) {
                registerPendingCommand(record.lens, now)
            }
            val ok = record.client.sendCommand(payload, ackTimeoutMs, retries, retryDelayMs)
            if (!ok) {
                failPendingCommand(record.lens)
                success = false
                updateLens(record.lens) { it.copy(degraded = true) }
                logWarn("Command failed on ${record.lens}")
            } else {
                updateLens(record.lens) {
                    it.copy(
                        degraded = false,
                        lastAckAt = record.client.lastAckTimestamp(),
                    )
                }
            }
            if (index < records.lastIndex) {
                delay(CHANNEL_STAGGER_DELAY_MS)
            }
        }
        return success
    }

    suspend fun rearmNotifications(target: Target = Target.Both): Boolean {
        if (isSleepModeActive()) {
            log("[BLE] Notify re-arm skipped (IdleSleep)")
            return false
        }
        val records = when (target) {
            Target.Left -> listOfNotNull(clientRecords[Lens.LEFT]?.takeIf { it.clientState().isConnected })
            Target.Right -> listOfNotNull(clientRecords[Lens.RIGHT]?.takeIf { it.clientState().isConnected })
            Target.Both -> ALL_LENSES.mapNotNull { lens ->
                clientRecords[lens]?.takeIf { it.clientState().isConnected }
            }
        }
        if (records.isEmpty()) {
            logWarn("No connected lenses for notify re-arm ($target)")
            return false
        }
        var success = true
        records.forEachIndexed { index, record ->
            val armed = record.client.rearmNotifications()
            if (!armed) {
                success = false
            }
            if (index < records.lastIndex) {
                delay(CHANNEL_STAGGER_DELAY_MS)
            }
        }
        return success
    }

    suspend fun triggerHello(lens: Lens): Boolean {
        val record = clientRecords[lens]?.takeIf { it.clientState().isConnected }
        if (record == null) {
            logWarn("[PAIRING] HELLO quick action skipped – ${lens.name.lowercase(Locale.US)} lens not connected")
            return false
        }
        if (isSleepModeActive()) {
            logWarn("[PAIRING] HELLO quick action skipped – IdleSleep active")
            return false
        }
        if (!record.client.awake.value) {
            record.client.beginWakeHandshake()
            heartbeatSupervisor.updateLensAwake(lens, true)
        }
        val result = record.client.forceHelloHandshake()
        if (!result) {
            updateLens(lens) { status -> status.copy(degraded = true) }
        }
        return result
    }

    suspend fun requestLeftRefresh(): Boolean {
        if (isSleepModeActive()) {
            log("[BLE][${Lens.LEFT.shortLabel}] Left refresh skipped (IdleSleep)")
            return false
        }
        val commands = listOf(
            byteArrayOf(CMD_CASE_GET.toByte()),
            byteArrayOf(CMD_BATT_GET.toByte(), BATT_SUB_DETAIL.toByte()),
            byteArrayOf(CMD_WEAR_DETECT.toByte()),
        )
        var overall = true
        commands.forEachIndexed { index, payload ->
            val ok = send(payload, Target.Left)
            if (!ok) {
                overall = false
            }
            if (index < commands.lastIndex) {
                delay(CHANNEL_STAGGER_DELAY_MS)
            }
        }
        if (overall) {
            log("[BLE][L] Left refresh dispatched (${commands.size} cmds)")
        } else {
            logWarn("[BLE][L] Left refresh encountered failures")
        }
        return overall
    }

    suspend fun setMicEnabled(
        lens: Lens,
        enabled: Boolean,
        ackTimeoutMs: Long = G1Protocols.ACK_TIMEOUT_MS,
        retries: Int = G1Protocols.MAX_RETRIES,
        retryDelayMs: Long = G1Protocols.RETRY_BACKOFF_MS,
    ): Boolean {
        val packet = MicControlPacket(enabled)
        return send(packet.bytes, lens.toTarget(), ackTimeoutMs, retries, retryDelayMs)
    }

    suspend fun sendEvenAiStop(
        lens: Lens,
        ackTimeoutMs: Long = G1Protocols.ACK_TIMEOUT_MS,
        retries: Int = G1Protocols.MAX_RETRIES,
        retryDelayMs: Long = G1Protocols.RETRY_BACKOFF_MS,
    ): Boolean {
        val payload = byteArrayOf(OPC_EVENT.toByte(), 0x24)
        return send(payload, lens.toTarget(), ackTimeoutMs, retries, retryDelayMs)
    }

    fun sendHudText(lens: Lens, text: String): Boolean {
        val bytes = text.encodeToByteArray()
        val payload = ByteArray(3 + bytes.size)
        payload[0] = G1Protocols.CMD_HUD_TEXT.toByte()
        payload[1] = bytes.size.toByte()
        payload[2] = 0x00
        bytes.copyInto(payload, destinationOffset = 3)
        val ok = runBlocking { send(payload, lens.toTarget()) }
        if (ok) {
            log("[HUD][${lens.shortLabel}] Text sent (${bytes.size} bytes)")
        } else {
            logWarn("[HUD][${lens.shortLabel}] Text send failed")
        }
        return ok
    }

    fun clearDisplay(lens: Lens): Boolean {
        val payload = byteArrayOf(G1Protocols.CMD_CLEAR.toByte(), 0x00)
        val ok = runBlocking { send(payload, lens.toTarget()) }
        if (ok) {
            log("[HUD][${lens.shortLabel}] Cleared display")
        } else {
            logWarn("[HUD][${lens.shortLabel}] Clear failed")
        }
        return ok
    }

    fun shutdown() {
        heartbeatSupervisor.shutdown()
        rebondJob?.cancel()
        rebondJob = null
        clientRecords.values.forEach { it.dispose() }
        clientRecords.clear()
        keepAliveWriteTimestamps.clear()
        caseTelemetryRequested.replaceAll { _, _ -> false }
        hostHeartbeatSequence.fill(0)
        ackContinuationBuffers.values.forEach { it.reset() }
        pendingCommandAcks.values.forEach { it.clear() }
        lastAckSuccessMs.clear()
        lastAckSignature.clear()
        ALL_LENSES.forEach {
            clearHeartbeatRtt(it)
            updateLens(it) { LensStatus() }
        }
        if (connectionOrder.isNotEmpty()) {
            connectionOrder.clear()
            refreshConnectionOrderState()
        }
        setStage(ConnectionStage.Idle)
    }

    private fun buildClientRecord(lens: Lens, device: BluetoothDevice): ClientRecord {
        val client = G1BleClient(
            context = context,
            device = device,
            scope = scope,
            label = "$TAG[$lens]",
            lensLabel = lens.shortLabel,
            logger = logger,
            isSleeping = { idleSleepActive },
            idleSleepFlow = idleSleepState,
        )
        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            client.state.collectLatest { state ->
                val previousStatus = clientState(lens)
                updateLens(lens) {
                    it.copy(
                        state = state.status,
                        rssi = state.rssi,
                        attMtu = state.attMtu,
                        notificationsArmed = client.notifyReady.value,
                        warmupOk = state.warmupOk,
                        bonded = state.bonded,
                        disconnectStatus = state.lastDisconnectStatus,
                        bondTransitions = state.bondTransitionCount,
                        bondTimeouts = state.bondTimeoutCount,
                        bondAttempts = state.bondAttemptCount,
                        lastBondResult = state.lastBondResult,
                        pairingDialogsShown = state.pairingDialogsShown,
                        refreshCount = state.refreshCount,
                        smpFrameCount = state.smpFrameCount,
                        lastSmpOpcode = state.lastSmpOpcode,
                        bondResetCount = state.bondResetCount,
                        lastBondState = state.lastBondState,
                        lastBondReason = state.lastBondReason,
                        lastBondEventAt = state.lastBondEventAt,
                    )
                }
                val wasReady = previousStatus.state == G1BleClient.ConnectionState.CONNECTED && previousStatus.warmupOk
                val previouslyConnected = previousStatus.state == G1BleClient.ConnectionState.CONNECTED
                val nowConnected = state.status == G1BleClient.ConnectionState.CONNECTED
                if (nowConnected && !client.awake.value) {
                    client.beginWakeHandshake()
                    heartbeatSupervisor.updateLensAwake(lens, true)
                }
                if (!nowConnected) {
                    caseTelemetryRequested[lens] = false
                }
                if (nowConnected && !previouslyConnected) {
                    markHandshakeLinked(lens)
                } else if (!nowConnected && previouslyConnected) {
                    resetHandshake(lens)
                }
                val nowReady = nowConnected && state.warmupOk
                if (!wasReady && nowReady) {
                    markHandshakeAcked(lens)
                    scheduleCaseTelemetry(lens)
                }
                handleBondTransitions(lens, previousStatus, state)
                handleReconnectStateChange(lens, previousStatus.state, state)
                updateRssiAverage(lens, state.rssi)
            }
        }
        jobs += scope.launch {
            client.notifyReady.collect { armed ->
                updateLens(lens) { current ->
                    if (current.notificationsArmed == armed) current else current.copy(notificationsArmed = armed)
                }
            }
        }
        jobs += scope.launch {
            client.incoming.collect { payload ->
                val now = System.currentTimeMillis()
                val opcode = payload.firstOrNull()?.toInt()?.and(0xFF)
                detectManualExit(lens, payload, now)
                if (opcode == G1Protocols.OPC_EVENT) {
                    handleEventSleepSignals(lens, payload, now)
                }
                when {
                    isAckContinuation(opcode) -> {
                        ackContinuationBuffers[lens]?.let { buffer ->
                            if (payload.size > 1) {
                                buffer.write(payload, 1, payload.size - 1)
                            }
                        }
                        emitConsole("ACK", lens, "continue +${(payload.size - 1).coerceAtLeast(0)}B", now)
                        return@collect
                    }
                    isAckComplete(opcode) -> {
                        val buffer = ackContinuationBuffers[lens]
                        if (payload.size > 1) {
                            buffer?.write(payload, 1, payload.size - 1)
                        }
                        val complete = buffer?.toByteArray()
                        buffer?.reset()
                        complete?.takeIf { it.isNotEmpty() }?.let { data ->
                            emitAckFrame(lens, data)
                        }
                        emitConsole("ACK", lens, "continuation complete", now)
                        return@collect
                    }
                    payload.isTextualOk() -> {
                        emitConsole("ACK", lens, "textual OK", now)
                        val ackEvent = AckEvent(
                            lens = lens,
                            opcode = null,
                            status = STATUS_OK,
                            success = true,
                            busy = false,
                            timestampMs = now,
                            warmup = false,
                            type = AckType.TEXTUAL,
                        )
                        handleAckEvent(lens, ackEvent)
                        return@collect
                    }
                }
                _incoming.tryEmit(IncomingFrame(lens, payload))
                heartbeatSupervisor.onTelemetry(lens, now)
                resetReconnectTimer(lens)
                when (val parsed = G1ReplyParser.parseNotify(payload)) {
                    is G1ReplyParser.Parsed.Vitals -> {
                        val vitals = parsed.vitals
                        handleVitalsSleepSignals(lens, vitals, now)
                        updateCaseOpenState(vitals.caseOpen, now)
                        logVitals(lens, vitals)
                    }
                    is G1ReplyParser.Parsed.EvenAi -> {
                        val event = EvenAiEvent(lens, parsed.event)
                        _evenAiEvents.tryEmit(event)
                        handleEvenAiSleepSignals(lens, parsed.event, now)
                        log("[BLE][EVENAI][${lens.shortLabel}] ${describeEvenAi(parsed.event)}")
                    }
                    else -> Unit
                }
            }
        }
        jobs += scope.launch {
            client.ackEvents.collect { event ->
                val ackEvent = AckEvent(
                    lens = lens,
                    opcode = event.opcode,
                    status = event.status,
                    success = event.success,
                    busy = event.busy,
                    timestampMs = event.timestampMs,
                    warmup = event.warmup,
                    type = AckType.BINARY,
                )
                handleAckEvent(lens, ackEvent)
            }
        }
        jobs += scope.launch {
            client.audioFrames.collect { frame ->
                _audioFrames.tryEmit(AudioFrame(lens, frame.sequence, frame.payload))
            }
        }
        jobs += scope.launch {
            client.keepAlivePrompts.collect { prompt ->
                if (!client.awake.value || isLensSleeping(lens)) {
                    return@collect
                }
                val now = System.currentTimeMillis()
                val lastWrite = keepAliveWriteTimestamps[lens]
                val elapsed = lastWrite?.let { now - it } ?: Long.MAX_VALUE
                if (elapsed < KEEP_ALIVE_MIN_INTERVAL_MS) {
                    delay(KEEP_ALIVE_MIN_INTERVAL_MS - elapsed)
                }
                if (!client.awake.value || isLensSleeping(lens)) {
                    return@collect
                }
                val result = client.respondToKeepAlivePrompt(prompt)
                keepAliveWriteTimestamps[lens] = System.currentTimeMillis()
                handleKeepAliveResult(lens, result)
            }
        }
        jobs += scope.launch {
            client.keepAliveResults.collect { result ->
                logKeepAliveTelemetry(lens, result)
            }
        }
        return ClientRecord(lens, client, jobs)
    }

    private fun clientState(lens: Lens): LensStatus = when (lens) {
        Lens.LEFT -> state.value.left
        Lens.RIGHT -> state.value.right
    }

    private fun ClientRecord.clientState(): LensStatus = clientState(lens)

    private suspend fun handleKeepAliveResult(
        lens: Lens,
        result: G1BleClient.KeepAliveResult,
    ) {
        val sleeping = isLensSleeping(lens)
        updateLens(lens) { status ->
            val previousFailures = status.consecutiveKeepAliveFailures
            val nextFailures = if (result.success) 0 else previousFailures + 1
            val threshold = G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS
            val degraded = when {
                sleeping -> false
                result.success && previousFailures >= threshold -> false
                result.success -> status.degraded
                else -> status.degraded || nextFailures >= threshold
            }
            status.copy(
                lastKeepAliveAt = if (result.success) result.completedTimestampMs else status.lastKeepAliveAt,
                keepAliveRttMs = result.rttMs ?: status.keepAliveRttMs,
                consecutiveKeepAliveFailures = nextFailures,
                degraded = degraded,
                keepAliveLockSkips = status.keepAliveLockSkips + result.lockContentionCount,
                keepAliveAckTimeouts = status.keepAliveAckTimeouts + result.ackTimeoutCount,
            )
        }
    }

    private fun handleBondTransitions(
        lens: Lens,
        previous: LensStatus,
        state: G1BleClient.State,
    ) {
        if (!previous.bonded && state.bonded) {
            when (lens) {
                Lens.LEFT -> onLeftBondComplete()
                Lens.RIGHT -> onRightBondComplete()
            }
        }
        if (state.bondResetCount > previous.bondResetCount) {
            updateBondResetTotal()
        }
        if (lens == Lens.LEFT && state.isReadyForCompanion()) {
            maybeStartCompanionSequence()
        }
    }

    private fun handleReconnectStateChange(
        lens: Lens,
        previousState: G1BleClient.ConnectionState,
        state: G1BleClient.State,
    ) {
        if (state.status == G1BleClient.ConnectionState.CONNECTED) {
            cancelReconnect(lens)
            return
        }
        if (
            previousState == G1BleClient.ConnectionState.CONNECTED &&
            state.status == G1BleClient.ConnectionState.DISCONNECTED &&
            state.bonded
        ) {
            val reasonLabel = state.lastDisconnectStatus?.let { formatGattStatus(it) } ?: "n/a"
            scheduleReconnect(lens, "status=$reasonLabel")
        }
    }

    private fun onLeftBondComplete() {
        if (state.value.right.bonded) {
            pendingRightBondSequence = false
            return
        }
        pendingRightBondSequence = true
        rightBondRetryAttempt = 0
        rightBondRetryJob?.cancel()
        log("[PAIRING][SEQUENCE] left→right initiated")
    }

    private fun onRightBondComplete() {
        pendingRightBondSequence = false
        rightBondRetryAttempt = 0
        rightBondRetryJob?.cancel()
        rightBondRetryJob = null
    }

    private fun maybeStartCompanionSequence() {
        if (!pendingRightBondSequence) {
            return
        }
        val leftRecord = clientRecords[Lens.LEFT] ?: return
        val leftReady = leftRecord.client.state.value.isReadyForCompanion()
        if (!leftReady) {
            return
        }
        val rightDevice = knownDevices[Lens.RIGHT] ?: return
        val existingRightRecord = clientRecords[Lens.RIGHT]
        val rightStatus = existingRightRecord?.client?.state?.value?.status ?: state.value.right.state
        if (rightStatus == G1BleClient.ConnectionState.CONNECTED || rightStatus == G1BleClient.ConnectionState.CONNECTING) {
            return
        }
        pendingRightBondSequence = false
        log("[PAIRING][SEQUENCE] launching companion connect")
        rightBondRetryJob?.cancel()
        rightBondRetryJob = null
        scope.launch(Dispatchers.IO) {
            connect(rightDevice, Lens.RIGHT)
        }
    }

    private fun scheduleRightBondRetry(device: BluetoothDevice) {
        pendingRightBondSequence = true
        val attemptNumber = rightBondRetryAttempt + 1
        val delayMs = RIGHT_BOND_RETRY_BASE_DELAY_MS * (1L shl (attemptNumber - 1))
        log("[PAIRING][SEQUENCE] retry #$attemptNumber")
        rightBondRetryAttempt = attemptNumber
        recordRightBondRetry()
        rightBondRetryJob?.cancel()
        rightBondRetryJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            val leftRecord = clientRecords[Lens.LEFT]
            val leftReady = leftRecord?.client?.state?.value?.isReadyForCompanion() ?: false
            if (!leftReady) {
                logWarn("[PAIRING][SEQUENCE] left not ready; deferring right retry attempt=$attemptNumber")
                return@launch
            }
            pendingRightBondSequence = false
            connect(device, Lens.RIGHT)
        }.also { job ->
            job.invokeOnCompletion { rightBondRetryJob = null }
        }
    }

    private fun recordRightBondRetry() {
        val current = _state.value
        _state.value = current.copy(rightBondRetryCount = current.rightBondRetryCount + 1)
    }

    private fun scheduleCaseTelemetry(lens: Lens) {
        if (caseTelemetryRequested[lens] == true) {
            return
        }
        caseTelemetryRequested[lens] = true
        scope.launch(Dispatchers.IO) {
            requestCaseTelemetry(lens)
            subscribeCaseEvents(lens)
        }
    }

    private suspend fun triggerWakeTelemetryRefresh() {
        if (!wakeTelemetryRefreshQueued.compareAndSet(false, true)) return
        try {
            scheduleCaseTelemetry(Lens.LEFT)
            scheduleCaseTelemetry(Lens.RIGHT)
        } finally {
            wakeTelemetryRefreshQueued.set(false)
        }
    }

    private suspend fun requestCaseTelemetry(lens: Lens) {
        val target = lens.toTarget()
        val commands = listOf(
            byteArrayOf(G1Protocols.OPC_DEVICE_STATUS.toByte()),
            byteArrayOf(G1Protocols.CMD_GLASSES_INFO.toByte(), CASE_BATTERY_SUBCOMMAND),
        )
        commands.forEachIndexed { index, payload ->
            val opcodeLabel = payload.firstOrNull()?.toInt()?.let { String.format(Locale.US, "0x%02X", it and 0xFF) } ?: "n/a"
            val ok = send(payload, target)
            if (!ok) {
                logWarn("[BLE][${lens.shortLabel}] Case telemetry request failed (opcode=$opcodeLabel)")
                caseTelemetryRequested[lens] = false
                return
            }
            if (index < commands.lastIndex) {
                delay(CASE_TELEMETRY_COMMAND_DELAY_MS)
            }
        }
        log("[BLE][${lens.shortLabel}] Case telemetry requested (0x2B/0x2C)")
    }

    private suspend fun subscribeCaseEvents(lens: Lens) {
        val target = lens.toTarget()
        caseEventCodes.forEachIndexed { index, code ->
            val payload = byteArrayOf(G1Protocols.OPC_EVENT.toByte(), code.toByte())
            send(payload, target, ackTimeoutMs = 0L, retries = 0)
            if (index < caseEventCodes.lastIndex) {
                delay(CASE_TELEMETRY_COMMAND_DELAY_MS)
            }
        }
        val codesLabel = caseEventCodes.joinToString(separator = ", ") { String.format(Locale.US, "0x%02X", it) }
        log("[BLE][${lens.shortLabel}] Subscribed to case F5 events $codesLabel")
    }

    private fun logKeepAliveTelemetry(lens: Lens, result: G1BleClient.KeepAliveResult) {
        val lensLabel = lens.name.lowercase(Locale.US)
        val seqLabel = String.format("0x%02X", result.sequence)
        val rttLabel = result.rttMs?.let { "${it}ms" } ?: "n/a"
        val attemptsLabel = result.attemptCount
        val promptLatency = result.completedTimestampMs - result.promptTimestampMs
        val lockSkipsLabel = result.lockContentionCount
        val timeoutLabel = result.ackTimeoutCount
        val message =
            "[KEEPALIVE][$lensLabel] seq=$seqLabel rtt=$rttLabel attempts=$attemptsLabel " +
                "latency=${promptLatency}ms lockSkips=$lockSkipsLabel ackTimeouts=$timeoutLabel"
        if (result.success) {
            log(message)
        } else {
            logWarn(message)
        }
    }

    private fun scheduleReconnect(lens: Lens, reason: String) {
        if (!shouldAutoReconnect(lens)) return
        if (isLensSleeping(lens) || isSleepModeActive()) {
            log("[RECONNECT][${lens.shortLabel}] skipped (sleep)")
            return
        }
        updateLens(lens) { it.copy(reconnecting = true) }
        heartbeatRebondTriggered[lens] = false
        reconnectCoordinator.schedule(lens, reason)
    }

    private fun recordReconnectAttempt(lens: Lens) {
        updateLens(lens) {
            it.copy(
                reconnectAttempts = it.reconnectAttempts + 1,
                reconnecting = true,
            )
        }
        val current = _state.value
        _state.value = current.copy(autoReconnectAttemptCount = current.autoReconnectAttemptCount + 1)
    }

    private fun recordReconnectSuccess(lens: Lens) {
        updateLens(lens) {
            it.copy(
                reconnectSuccesses = it.reconnectSuccesses + 1,
                reconnecting = false,
                degraded = false,
            )
        }
        val current = _state.value
        _state.value = current.copy(autoReconnectSuccessCount = current.autoReconnectSuccessCount + 1)
        bondFailureStreak[lens] = 0
        staleBondFailureStreak[lens] = 0
        heartbeatRebondTriggered[lens] = false
    }

    private fun setReconnectState(lens: Lens, status: ReconnectStatus) {
        val updated = _reconnectStates.value.toMutableMap()
        updated[lens] = status
        _reconnectStates.value = updated
    }

    private fun cancelReconnect(lens: Lens) {
        reconnectCoordinator.cancel(lens)
        heartbeatRebondTriggered[lens] = false
        heartbeatReconnectTriggered[lens] = false
    }

    private fun resetReconnectTimer(lens: Lens) {
        heartbeatRebondTriggered[lens] = false
        heartbeatReconnectTriggered[lens] = false
        reconnectCoordinator.reset(lens)
    }

    private fun shouldAutoReconnect(lens: Lens): Boolean {
        if (isSleepModeActive()) {
            return false
        }
        val record = clientRecords[lens]
        val device = knownDevices[lens] ?: return false
        return record?.client?.state?.value?.bonded == true || device.bondState == BluetoothDevice.BOND_BONDED
    }

    private fun recordConnectionFailure(lens: Lens) {
        val bucket = reconnectFailureTimestamps.getValue(lens)
        val now = System.currentTimeMillis()
        bucket.addLast(now)
        while (bucket.isNotEmpty() && now - bucket.first() > GATT_FAILURE_WINDOW_MS) {
            bucket.removeFirst()
        }
    }

    private fun shouldForceGattRefresh(lens: Lens): Boolean {
        if (isSleepModeActive()) {
            return false
        }
        val bucket = reconnectFailureTimestamps.getValue(lens)
        val now = System.currentTimeMillis()
        while (bucket.isNotEmpty() && now - bucket.first() > GATT_FAILURE_WINDOW_MS) {
            bucket.removeFirst()
        }
        return bucket.size >= GATT_REFRESH_THRESHOLD
    }

    private fun updateBondResetTotal() {
        val total = state.value.left.bondResetCount + state.value.right.bondResetCount
        if (totalBondResetEvents != total) {
            totalBondResetEvents = total
            val current = _state.value
            _state.value = current.copy(bondResetEvents = total)
        }
    }

    private fun currentLensStatus(lens: Lens): LensStatus = when (lens) {
        Lens.LEFT -> state.value.left
        Lens.RIGHT -> state.value.right
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

    private fun inferLens(device: BluetoothDevice): Lens {
        val name = device.name?.lowercase(Locale.US).orEmpty()
        return when {
            name.contains("left") || name.endsWith("_l") || name.endsWith("-l") -> Lens.LEFT
            name.contains("right") || name.endsWith("_r") || name.endsWith("-r") -> Lens.RIGHT
            else -> if (state.value.left.isConnected) Lens.RIGHT else Lens.LEFT
        }
    }

    private fun ensureHeartbeatLoop() {
        heartbeatSupervisor.updateLensConnection(Lens.LEFT, state.value.left.isConnected)
        heartbeatSupervisor.updateLensConnection(Lens.RIGHT, state.value.right.isConnected)
    }

    private fun nextHostHeartbeatPayload(lens: Lens): HeartbeatPacket {
        val index = lens.ordinal
        val sequence = hostHeartbeatSequence[index] and 0xFF
        hostHeartbeatSequence[index] = (sequence + 1) and 0xFF
        val payload = byteArrayOf(
            G1Protocols.CMD_PING.toByte(),
            sequence.toByte(),
        )
        return HeartbeatPacket(sequence, payload)
    }

    private suspend fun waitForHeartbeatAck(lens: Lens, since: Long, timeoutMs: Long): AckSignal? {
        return withTimeoutOrNull(timeoutMs) {
            ackSignalFlow.filter { signal ->
                signal.lens == lens && signal.timestamp >= since
            }.firstOrNull()
        }
    }

    private suspend fun performHeartbeat(lens: Lens): HeartbeatResult? {
        val record = clientRecords[lens] ?: return null
        if (!record.client.awake.value || isLensSleeping(lens)) {
            return null
        }
        val busyDeadline = System.currentTimeMillis() + HEARTBEAT_BUSY_DEFER_MS
        while (coroutineContext.isActive && System.currentTimeMillis() < busyDeadline && isLensBusy(lens)) {
            delay(HEARTBEAT_BUSY_POLL_MS)
        }
        if (!currentLensStatus(lens).isConnected) {
            return null
        }
        val packet = nextHostHeartbeatPayload(lens)
        val attemptStartRealtime = SystemClock.elapsedRealtime()
        val attemptStart = System.currentTimeMillis()
        val queued = record.client.enqueueHeartbeat(packet.sequence, packet.payload)
        val timestamp = System.currentTimeMillis()
        if (queued) {
            emitConsole("PING", lens, "sent", timestamp)
            log("[BLE][PING][${lens.shortLabel}] sent seq=${packet.sequence}")
        }
        if (!queued) {
            return HeartbeatResult(
                sequence = packet.sequence,
                timestamp = timestamp,
                success = false,
                busy = false,
                ackType = null,
                latencyMs = null,
            )
        }
        val ack = waitForHeartbeatAck(lens, attemptStart, HEARTBEAT_ACK_WINDOW_MS)
        val success = ack != null
        val busy = ack?.busy == true
        val latency = ack?.let {
            val delta = it.elapsedRealtime - attemptStartRealtime
            delta.takeIf { value -> value >= 0 }
        }
        return HeartbeatResult(
            sequence = packet.sequence,
            timestamp = timestamp,
            success = success,
            busy = busy,
            ackType = ack?.type,
            latencyMs = latency,
        )
    }

    private fun isLensBusy(lens: Lens): Boolean {
        return synchronized(pendingCommandAcks) {
            pendingCommandAcks[lens]?.isNotEmpty() == true
        }
    }

    private fun handleHeartbeatSuccess(
        lens: Lens,
        sequence: Int,
        timestamp: Long,
        latencyMs: Long?,
        ackType: AckType,
        elapsedSinceLastMs: Long,
        busy: Boolean,
    ) {
        recordHeartbeatSuccess(lens, timestamp)
        val intervalLabel = String.format(Locale.US, "%.1fs", elapsedSinceLastMs / 1000.0)
        val averageRtt = latencyMs?.let { recordHeartbeatRtt(lens, it) }
        val latencyLabel = latencyMs?.let { String.format(Locale.US, "%d ms", it) } ?: "n/a"
        val modeLabel = ackType.name.lowercase(Locale.US)
        val statusLabel = if (busy) "busy" else "ok"
        val averageLabel = averageRtt?.let { String.format(Locale.US, "%d ms", it) } ?: "n/a"
        emitConsole("PING", lens, "RTT=$latencyLabel", timestamp)
        log("[BLE][PING][${lens.shortLabel}] ok seq=$sequence latency=$latencyLabel interval=$intervalLabel status=$statusLabel mode=$modeLabel avg=$averageLabel")
        updateLens(lens) {
            it.copy(
                heartbeatLatencyMs = latencyMs,
                heartbeatRttAvgMs = averageRtt,
                heartbeatAckType = ackType,
                heartbeatLastPingAt = timestamp,
                heartbeatMissCount = 0,
            )
        }
        heartbeatReconnectTriggered[lens] = false
        resetReconnectTimer(lens)
        emitStabilitySnapshot(timestamp)
    }

    private fun handleHeartbeatMiss(lens: Lens, timestamp: Long, missCount: Int) {
        if (isLensSleeping(lens)) {
            updateLens(lens) {
                it.copy(heartbeatMissCount = 0)
            }
            return
        }
        recordHeartbeatMiss(lens, timestamp)
        emitConsole("PING", lens, "[WARN] missed ping ($missCount)", timestamp)
        logWarn("[BLE][PING][${lens.shortLabel}] missed ping ($missCount)")
        updateLens(lens) {
            it.copy(
                heartbeatLastPingAt = timestamp,
                heartbeatMissCount = missCount,
            )
        }
        emitStabilitySnapshot(timestamp)
        maybeTriggerHeartbeatReconnect(lens, missCount)
        if (missCount >= HEARTBEAT_REBOND_THRESHOLD && rebondJob?.isActive != true) {
            if (heartbeatRebondTriggered[lens] != true) {
                heartbeatRebondTriggered[lens] = true
                emitConsole("PING", lens, "rebound", timestamp)
                performRebond(lens)
            }
        }
    }

    private fun maybeTriggerHeartbeatReconnect(lens: Lens, missCount: Int) {
        if (isLensSleeping(lens)) {
            return
        }
        if (missCount < HEARTBEAT_RECONNECT_THRESHOLD) {
            return
        }
        if (heartbeatReconnectTriggered[lens] == true) {
            return
        }
        val reasonLabel = "missed ping streak=$missCount"
        val record = clientRecords[lens]
        val attempted = when {
            record != null -> {
                record.client.reconnect(reasonLabel)
                true
            }
            shouldAutoReconnect(lens) -> {
                scheduleReconnect(lens, reasonLabel)
                true
            }
            else -> false
        }
        if (attempted) {
            log("[RECOVER][${lens.shortLabel}] reconnecting ($reasonLabel)")
            heartbeatReconnectTriggered[lens] = true
        } else {
            logWarn("[BLE][PING][${lens.shortLabel}] reconnect skipped ($reasonLabel)")
        }
    }

private class HeartbeatSupervisor(
    parentScope: CoroutineScope,
    private val log: (String) -> Unit,
    private val emitConsole: (String, MoncchichiBleService.Lens?, String, Long) -> Unit,
    private val sendHeartbeat: suspend (MoncchichiBleService.Lens) -> MoncchichiBleService.HeartbeatResult?,
    private val isLensConnected: (MoncchichiBleService.Lens) -> Boolean,
    private val onHeartbeatSuccess: (
        MoncchichiBleService.Lens,
        Int,
        Long,
        Long?,
        MoncchichiBleService.AckType,
        Long,
        Boolean,
    ) -> Unit,
    private val onHeartbeatMiss: (MoncchichiBleService.Lens, Long, Int) -> Unit,
    private val rebondThreshold: Int,
    private val baseIntervalMs: Long,
    private val jitterMs: Long,
    private val idlePollMs: Long,
) {
    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private var job: Job? = null
    private val lidOpen = AtomicReference<Boolean?>(null)
    private val states = EnumMap<MoncchichiBleService.Lens, LensHeartbeatState>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { lens -> put(lens, LensHeartbeatState()) }
    }

    fun updateLensConnection(lens: MoncchichiBleService.Lens, connected: Boolean) {
        val state = states.getValue(lens)
        if (state.connected == connected) return
        state.connected = connected
        if (!connected) {
            state.nextDueAt = null
            state.missCount = 0
            state.lastSuccessAt = null
        } else {
            state.nextDueAt = 0L
        }
        restartLoop()
    }

    fun updateCaseState(value: Boolean?) {
        val previous = lidOpen.getAndSet(value)
        if (previous == value) return
        if (value == true) {
            states.values.forEach { state ->
                if (state.connected) {
                    state.nextDueAt = 0L
                }
            }
        }
        restartLoop()
    }

    fun updateInCaseState(lens: MoncchichiBleService.Lens, value: Boolean?) {
        val state = states.getValue(lens)
        if (state.inCase == value) return
        state.inCase = value
        if (value != true && state.connected) {
            state.nextDueAt = 0L
        }
        restartLoop()
    }

    fun updateLensAwake(lens: MoncchichiBleService.Lens, awake: Boolean) {
        val state = states.getValue(lens)
        if (state.awake == awake) return
        state.awake = awake
        if (!awake) {
            state.nextDueAt = null
            state.missCount = 0
        } else if (state.connected) {
            state.nextDueAt = 0L
        }
        restartLoop()
    }

    fun onAck(
        lens: MoncchichiBleService.Lens?,
        timestamp: Long,
        type: MoncchichiBleService.AckType,
        success: Boolean,
        @Suppress("UNUSED_PARAMETER") busy: Boolean,
    ) {
        if (lens == null) return
        val state = states[lens] ?: return
        state.lastAckAt = timestamp
        state.lastAckType = type
        if (!state.connected) return
        if (success) {
            state.missCount = 0
        }
        state.nextDueAt = computeNextDue(timestamp)
    }

    fun onTelemetry(lens: MoncchichiBleService.Lens, timestamp: Long) {
        val state = states[lens] ?: return
        if (!state.connected) return
        state.nextDueAt = computeNextDue(timestamp)
    }

    private fun ensureLoop() {
        if (states.values.none { it.connected }) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    private fun restartLoop() {
        job?.cancel()
        job = null
        ensureLoop()
    }

    fun shutdown() {
        job?.cancel()
        job = null
        lidOpen.set(null)
        states.values.forEach { state ->
            state.connected = false
            state.inCase = null
            state.nextDueAt = null
            state.lastSuccessAt = null
            state.lastAckAt = null
            state.lastAckType = null
            state.missCount = 0
            state.awake = true
        }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            var nextDelay = idlePollMs
            var dispatched = false
            states.forEach { (lens, state) ->
                if (!state.connected || !isLensConnected(lens)) {
                    return@forEach
                }
                val gate = gatingReason(lens, state)
                if (gate != null) {
                    val dueAt = state.nextDueAt
                    if (dueAt != null && now >= dueAt) {
                        logSkip(lens, gate, now)
                        state.nextDueAt = null
                        state.missCount = 0
                    }
                    return@forEach
                }
                val dueAt = state.nextDueAt ?: now
                if (now >= dueAt) {
                    dispatched = true
                    val result = sendHeartbeat(lens)
                    val eventTimestamp = result?.timestamp ?: now
                    state.nextDueAt = computeNextDue(eventTimestamp)
                    if (result == null) {
                        return@forEach
                    }
                    if (result.success) {
                        state.missCount = 0
                        val ackType = result.ackType ?: MoncchichiBleService.AckType.BINARY
                        val elapsed = state.lastSuccessAt?.let { eventTimestamp - it }?.takeIf { it >= 0 } ?: baseIntervalMs
                        state.lastSuccessAt = eventTimestamp
                        onHeartbeatSuccess(
                            lens,
                            result.sequence,
                            eventTimestamp,
                            result.latencyMs,
                            ackType,
                            elapsed,
                            result.busy,
                        )
                    } else {
                        state.missCount = (state.missCount + 1).coerceAtMost(rebondThreshold)
                        onHeartbeatMiss(lens, eventTimestamp, state.missCount)
                    }
                } else {
                    val remaining = (dueAt - now).coerceAtLeast(idlePollMs)
                    if (remaining < nextDelay) {
                        nextDelay = remaining
                    }
                }
            }
            if (!dispatched) {
                delay(nextDelay)
            }
        }
    }

    private fun gatingReason(
        lens: MoncchichiBleService.Lens,
        state: LensHeartbeatState,
    ): GateReason? {
        val lid = lidOpen.get()
        if (lid == false) return GateReason.LidClosed
        if (state.inCase == true) return GateReason.InCase
        if (!state.awake) return GateReason.Sleeping
        return null
    }

    private fun logSkip(lens: MoncchichiBleService.Lens, reason: GateReason, timestamp: Long) {
        val message = when (reason) {
            GateReason.LidClosed -> "skipped (lid closed)"
            GateReason.InCase -> "skipped (in case)"
            GateReason.Sleeping -> "skipped (sleeping)"
        }
        emitConsole("PING", lens, message, timestamp)
        log("[BLE][PING][${lens.shortLabel}] $message")
    }

    private fun computeNextDue(base: Long): Long {
        val jitterOffset = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs + 1) else 0L
        val interval = (baseIntervalMs + jitterOffset).coerceAtLeast(idlePollMs)
        return base + interval
    }

    private data class LensHeartbeatState(
        var connected: Boolean = false,
        var inCase: Boolean? = null,
        var nextDueAt: Long? = null,
        var lastSuccessAt: Long? = null,
        var lastAckAt: Long? = null,
        var lastAckType: MoncchichiBleService.AckType? = null,
        var missCount: Int = 0,
        var awake: Boolean = true,
    )

    private enum class GateReason { LidClosed, InCase, Sleeping }
}

    private fun initialStabilityMetrics(lens: Lens): BleStabilityMetrics {
        return BleStabilityMetrics(
            lens = lens,
            timestamp = System.currentTimeMillis(),
            heartbeatCount = 0,
            missedHeartbeats = 0,
            rebondEvents = 0,
            lastAckDeltaMs = null,
            avgRssi = null,
            reconnectLatencyMs = null,
        )
    }

    private fun resetStabilityMetrics(lens: Lens) {
        stabilityState[lens]?.set(initialStabilityMetrics(lens))
        rssiWindows[lens]?.clear()
    }

    private fun updateStabilityMetrics(
        lens: Lens,
        timestamp: Long,
        reducer: (BleStabilityMetrics) -> BleStabilityMetrics,
    ): BleStabilityMetrics {
        val ref = stabilityState[lens] ?: AtomicReference(initialStabilityMetrics(lens)).also { stabilityState[lens] = it }
        val updated = ref.updateAndGet { current ->
            val base = current ?: initialStabilityMetrics(lens)
            reducer(base).copy(lens = lens, timestamp = timestamp)
        }
        return updated
    }

    private fun emitStabilitySnapshot(timestamp: Long) {
        ALL_LENSES.forEach { lens ->
            val status = currentLensStatus(lens)
            val metrics = updateStabilityMetrics(lens, timestamp) { it }
            val hasData =
                status.isConnected ||
                    metrics.heartbeatCount > 0 ||
                    metrics.missedHeartbeats > 0 ||
                    metrics.rebondEvents > 0 ||
                    metrics.lastAckDeltaMs != null ||
                    metrics.avgRssi != null ||
                    metrics.reconnectLatencyMs != null
            if (!hasData) {
                return@forEach
            }
            val formatter = stabilityTimeFormatter.get()
            val formattedTime = if (formatter != null) {
                formatter.format(Date(timestamp))
            } else {
                val fallback = SimpleDateFormat("HH:mm:ss", Locale.US)
                stabilityTimeFormatter.set(fallback)
                fallback.format(Date(timestamp))
            }
            val message = formatStabilityMessage(metrics, formattedTime)
            emitConsole("STABILITY", lens, message, timestamp)
            _stabilityMetrics.tryEmit(metrics)
        }
    }

    private fun formatStabilityMessage(metrics: BleStabilityMetrics, timeLabel: String): String {
        val heartbeatLabel = "${metrics.heartbeatCount}/${metrics.missedHeartbeats}"
        val rebondLabel = metrics.rebondEvents
        val rssiLabel = metrics.avgRssi?.toString() ?: "n/a"
        val ackLabel = metrics.lastAckDeltaMs?.let { "${it} ms" } ?: "n/a"
        val reconnectLabel = metrics.reconnectLatencyMs?.let { "${it} ms" } ?: "n/a"
        return "$timeLabel HB/MISS=$heartbeatLabel REBOND=$rebondLabel RSSI=$rssiLabel ΔACK=$ackLabel RECON=$reconnectLabel"
    }

    private fun recordHeartbeatSuccess(lens: Lens, timestamp: Long) {
        updateStabilityMetrics(lens, timestamp) { metrics ->
            metrics.copy(heartbeatCount = metrics.heartbeatCount + 1)
        }
    }

    private fun recordHeartbeatMiss(lens: Lens, timestamp: Long) {
        updateStabilityMetrics(lens, timestamp) { metrics ->
            metrics.copy(missedHeartbeats = metrics.missedHeartbeats + 1)
        }
    }

    private fun incrementRebondCounter(lens: Lens?, timestamp: Long) {
        val targets: Iterable<Lens> = lens?.let { listOf(it) } ?: ALL_LENSES.asIterable()
        targets.forEach { target: Lens ->
            updateStabilityMetrics(target, timestamp) { metrics ->
                metrics.copy(rebondEvents = metrics.rebondEvents + 1)
            }
        }
        emitStabilitySnapshot(timestamp)
    }

    private fun updateRssiAverage(lens: Lens, rssi: Int?) {
        if (rssi == null) {
            return
        }
        val samples = rssiWindows[lens] ?: return
        samples.addLast(rssi)
        while (samples.size > RSSI_WINDOW_SIZE) {
            samples.removeFirst()
        }
        val average = samples.sum() / samples.size
        updateStabilityMetrics(lens, System.currentTimeMillis()) { metrics ->
            metrics.copy(avgRssi = average)
        }
    }

    private fun recordHeartbeatRtt(lens: Lens, latencyMs: Long): Long? {
        val window = heartbeatRttWindows[lens] ?: return null
        window.addLast(latencyMs)
        while (window.size > HEARTBEAT_RTT_WINDOW_SIZE) {
            window.removeFirst()
        }
        val sum = window.fold(0L) { acc, value -> acc + value }
        return if (window.isEmpty()) {
            null
        } else {
            (sum / window.size)
        }
    }

    private fun clearHeartbeatRtt(lens: Lens) {
        heartbeatRttWindows[lens]?.clear()
    }

    private fun emitAckFrame(lens: Lens, payload: ByteArray) {
        val outcome = payload.parseAckOutcome() ?: return
        val timestamp = System.currentTimeMillis()
        val ackEvent = when (outcome) {
            is AckOutcome.Success -> AckEvent(
                lens = lens,
                opcode = outcome.opcode,
                status = outcome.status,
                success = true,
                busy = false,
                timestampMs = timestamp,
                warmup = outcome.warmupPrompt,
                type = AckType.BINARY,
            )
            is AckOutcome.Busy -> AckEvent(
                lens = lens,
                opcode = outcome.opcode,
                status = outcome.status,
                success = false,
                busy = true,
                timestampMs = timestamp,
                warmup = false,
                type = AckType.BINARY,
            )
            is AckOutcome.Failure -> AckEvent(
                lens = lens,
                opcode = outcome.opcode,
                status = outcome.status,
                success = false,
                busy = false,
                timestampMs = timestamp,
                warmup = false,
                type = AckType.BINARY,
            )
            is AckOutcome.Sleep -> null
            is AckOutcome.Continue -> null
            is AckOutcome.Complete -> null
        } ?: return
        handleAckEvent(lens, ackEvent)
    }

    private fun handleAckEvent(lens: Lens, event: AckEvent) {
        heartbeatSupervisor.onAck(lens, event.timestampMs, event.type, event.success, event.busy)
        if (isDuplicateAck(lens, event)) {
            return
        }
        lastAckSignature[lens] = AckSignature(event.opcode, event.status, event.timestampMs)
        val opcodeLabel = event.opcode.toOpcodeLabel()
        if (event.success) {
            markAckSuccess(lens, event.timestampMs, event.type)
            emitConsole("ACK", lens, "opcode=${opcodeLabel} status=${event.status.toHex()}", event.timestampMs)
        } else if (event.busy) {
            if (event.opcode == CMD_PING) {
                notifyHeartbeatSignal(
                    lens,
                    event.timestampMs,
                    SystemClock.elapsedRealtime(),
                    event.type,
                    success = false,
                    busy = true,
                )
            }
            emitConsole(
                "ACK",
                lens,
                "[BUSY] opcode=${opcodeLabel} retrying",
                event.timestampMs,
            )
            log("[ACK][${lens.shortLabel}][BUSY] opcode=${opcodeLabel} retrying")
        } else {
            failPendingCommand(lens)
            val suppressed = shouldSuppressAckFailure(lens, event) || isLensSleeping(lens)
            if (!suppressed) {
                updateLens(lens) { it.copy(degraded = true) }
                emitConsole(
                    "ACK",
                    lens,
                    "fail opcode=${opcodeLabel} status=${event.status.toHex()}",
                    event.timestampMs,
                )
                logWarn(
                    "[ACK][${lens.shortLabel}] failure opcode=${opcodeLabel} status=${event.status.toHex()}"
                )
            } else {
                emitConsole(
                    "ACK",
                    lens,
                    "fail suppressed opcode=${opcodeLabel} status=${event.status.toHex()}",
                    event.timestampMs,
                )
            }
        }
        _ackEvents.tryEmit(event)
    }

    private fun detectManualExit(lens: Lens, payload: ByteArray, timestamp: Long) {
        val lowercase = payload.decodeAsciiLowercase() ?: return
        if (!lowercase.contains("manual exit")) {
            return
        }
        updateSleepState(lens, timestamp) { current ->
            if (current.manualExit) current else current.copy(manualExit = true)
        }
    }

    private fun handleEventSleepSignals(lens: Lens, payload: ByteArray, timestamp: Long) {
        if (payload.isEmpty()) return
        val subcommand = payload.getOrNull(1)?.toInt()?.and(0xFF)
        when (subcommand) {
            0x08 -> {
                updateHeartbeatLidOpen(true)
                updateHeartbeatInCase(lens, false)
                updateSleepState(lens, timestamp) { state ->
                    state.copy(caseClosed = false, inCase = false, folded = false, manualExit = false)
                }
            }
            0x09 -> {
                updateHeartbeatLidOpen(false)
                updateHeartbeatInCase(lens, true)
                updateSleepState(lens, timestamp) { state -> state.copy(caseClosed = true, inCase = true) }
            }
            0x0A -> updateSleepState(lens, timestamp) { state -> state.copy(charging = true) }
            0x0B -> updateSleepState(lens, timestamp) { state -> state.copy(charging = false) }
            0x0E -> updateSleepState(lens, timestamp) { state -> state.copy(folded = true) }
            0x0F -> updateSleepState(lens, timestamp) { state -> state.copy(folded = false) }
        }
    }

    private fun handleVitalsSleepSignals(
        lens: Lens,
        vitals: G1ReplyParser.DeviceVitals,
        timestamp: Long,
    ) {
        vitals.inCradle?.let { updateHeartbeatInCase(lens, it) }
        updateSleepState(lens, timestamp) { state ->
            var updated = state.copy(lastVitalsAt = timestamp)
            vitals.caseOpen?.let { open ->
                updated = updated.copy(caseClosed = open == false)
                if (open) {
                    updated = updated.copy(manualExit = false, folded = false)
                }
            }
            vitals.inCradle?.let { inCradle ->
                updated = updated.copy(inCase = inCradle)
            }
            vitals.charging?.let { charging ->
                updated = updated.copy(charging = charging)
            }
            updated
        }
    }

    private fun logVitals(lens: Lens, vitals: G1ReplyParser.DeviceVitals) {
        val sleepSnapshot = synchronized(sleepStateLock) { sleepStates[lens] }
        val caseLabel = resolveCaseLabel(vitals, sleepSnapshot)
        val charging = vitals.charging ?: sleepSnapshot?.charging
        val folded = sleepSnapshot?.folded
        val newState = VitalsLogState(
            batteryPercent = vitals.batteryPercent,
            charging = charging,
            caseLabel = caseLabel,
            folded = folded,
        )
        val shouldLog = synchronized(vitalsLogLock) {
            val previous = lastVitalsLogState[lens]
            if (previous == newState) {
                false
            } else {
                lastVitalsLogState[lens] = newState
                true
            }
        }
        if (!shouldLog) {
            return
        }
        val batteryLabel = newState.batteryPercent?.let { "$it%" } ?: "n/a"
        val chargingLabel = when (newState.charging) {
            true -> "charging"
            false -> "not_charging"
            null -> "unknown"
        }
        val caseStateLabel = caseLabel ?: "unknown"
        val foldLabel = when (folded) {
            true -> "folded"
            false -> "unfolded"
            null -> "unknown"
        }
        log("[BLE][VITALS][${lens.shortLabel}] battery=$batteryLabel charging=$chargingLabel case=$caseStateLabel fold=$foldLabel")
    }

    private fun resolveCaseLabel(
        vitals: G1ReplyParser.DeviceVitals,
        state: SleepState?,
    ): String? {
        return when {
            vitals.caseOpen == true -> "CaseOpen"
            vitals.caseOpen == false -> "CaseClosed"
            vitals.inCradle == true -> "InCase"
            vitals.inCradle == false -> "OutOfCase"
            state?.caseClosed == true -> "CaseClosed"
            state?.inCase == true -> "InCase"
            else -> null
        }
    }

    private fun handleEvenAiSleepSignals(
        lens: Lens,
        event: G1ReplyParser.EvenAiEvent,
        timestamp: Long,
    ) {
        if (event is G1ReplyParser.EvenAiEvent.ManualExit) {
            updateSleepState(lens, timestamp) { state ->
                if (state.manualExit) state else state.copy(manualExit = true)
            }
        }
    }

    private fun ByteArray.decodeAsciiLowercase(): String? {
        if (isEmpty()) return null
        if (any { byte ->
                val value = byte.toInt() and 0xFF
                value < 0x20 && value != 0x0A && value != 0x0D
            }
        ) {
            return null
        }
        return runCatching { toString(Charsets.UTF_8).lowercase(Locale.US) }.getOrNull()
    }

    private fun isLensSleeping(lens: Lens): Boolean {
        return synchronized(sleepStateLock) { sleepStates[lens]?.sleepEvent == true }
    }

    private fun isSleepModeActive(): Boolean {
        return idleSleepActive
    }

    private fun updateSleepState(
        lens: Lens,
        timestamp: Long,
        transform: (SleepState) -> SleepState,
    ) {
        var previous: SleepState? = null
        var updated: SleepState? = null
        val previousAuthority = idleSleepActive
        synchronized(sleepStateLock) {
            val current = sleepStates.getValue(lens)
            val mutated = transform(current).withDerived(timestamp)
            if (mutated == current) {
                return
            }
            previous = current
            sleepStates[lens] = mutated
            updated = mutated
            idleSleepActive = sleepStates.values.any { it.sleepEvent } || lastSleepWakeSignal == true
        }
        if (idleSleepState.value != idleSleepActive) {
            idleSleepState.value = idleSleepActive
        }
        handleSleepTransition(lens, previous!!, updated!!, previousAuthority, idleSleepActive, timestamp)
    }

    private fun handleSleepTransition(
        lens: Lens,
        previous: SleepState,
        updated: SleepState,
        previousAuthority: Boolean,
        newAuthority: Boolean,
        timestamp: Long,
    ) {
        val previousTriggers = previous.activeTriggers()
        val currentTriggers = updated.activeTriggers()
        val activeReason = currentTriggers.minByOrNull { it.priority }?.logLabel
        if (!previous.sleeping && updated.sleeping) {
            val reason = selectSleepReason(previousTriggers, currentTriggers)
            log("[SLEEP][${lens.shortLabel}] ${reason}→Sleep")
            clientRecords[lens]?.client?.enterSleepMode()
            heartbeatSupervisor.updateLensAwake(lens, false)
            updateLens(lens) {
                it.copy(
                    sleeping = true,
                    sleepReason = reason,
                    degraded = false,
                    consecutiveKeepAliveFailures = 0,
                    keepAliveAckTimeouts = 0,
                    heartbeatMissCount = 0,
                )
            }
        } else if (previous.sleeping && updated.sleeping && activeReason != null) {
            updateLens(lens) { status ->
                if (status.sleepReason == activeReason && status.sleeping) {
                    status
                } else {
                    status.copy(sleeping = true, sleepReason = activeReason)
                }
            }
        }
        if (previous.sleeping && !updated.sleeping) {
            val reason = selectWakeReason(previousTriggers, currentTriggers)
            log("[WAKE][${lens.shortLabel}] ${reason}→Active")
            updateLens(lens) {
                it.copy(
                    sleeping = false,
                    sleepReason = null,
                    degraded = false,
                    consecutiveKeepAliveFailures = 0,
                    keepAliveAckTimeouts = 0,
                    heartbeatMissCount = 0,
                )
            }
            heartbeatSupervisor.updateLensAwake(lens, true)
            resetTelemetryTimers(lens, timestamp)
        }
        if (!previousAuthority && newAuthority) {
            onSleepModeEntered(timestamp)
        } else if (previousAuthority && !newAuthority) {
            onSleepModeExited(timestamp)
        }
    }

    private fun setIdleSleepAuthority(active: Boolean, timestamp: Long) {
        val previous = idleSleepActive
        idleSleepActive = active
        if (idleSleepState.value != active) {
            idleSleepState.value = active
        }
        if (!previous && active) {
            onSleepModeEntered(timestamp)
        } else if (previous && !active) {
            onSleepModeExited(timestamp)
        }
    }

    private fun handleSleepWakeSignal(sleeping: Boolean) {
        if (lastSleepWakeSignal == sleeping) return
        lastSleepWakeSignal = sleeping
        val timestamp = System.currentTimeMillis()
        setIdleSleepAuthority(sleeping, timestamp)
    }

    private fun handleSleepEvent(event: BleTelemetryRepository.SleepEvent) {
        val timestamp = System.currentTimeMillis()
        when (event) {
            is BleTelemetryRepository.SleepEvent.SleepEntered -> {
                log("[SLEEP][${event.lens?.shortLabel ?: "HEADSET"}] SleepEvent")
                applySleepEventState(event.lens, sleeping = true, timestamp = timestamp)
            }
            is BleTelemetryRepository.SleepEvent.SleepExited -> {
                log("[WAKE][${event.lens?.shortLabel ?: "HEADSET"}] SleepEvent")
                applySleepEventState(event.lens, sleeping = false, timestamp = timestamp)
                if (!isSleepModeActive()) {
                    scope.launch { triggerWakeTelemetryRefresh() }
                }
            }
        }
    }

    private fun applySleepEventState(lens: Lens?, sleeping: Boolean, timestamp: Long) {
        val targets = lens?.let { listOf(it) } ?: Lens.values().toList()
        targets.forEach { target ->
            updateSleepState(target, timestamp) { current ->
                current.copy(sleepEvent = sleeping)
            }
        }
        val authorityActive = synchronized(sleepStateLock) { sleepStates.values.any { it.sleepEvent } }
        setIdleSleepAuthority(authorityActive, timestamp)
    }

    private fun selectSleepReason(
        previous: Set<SleepTrigger>,
        current: Set<SleepTrigger>,
    ): String {
        val activated = current - previous
        val target = if (activated.isNotEmpty()) activated else current
        return target.minByOrNull { it.priority }?.logLabel ?: "CaseClosed"
    }

    private fun selectWakeReason(
        previous: Set<SleepTrigger>,
        current: Set<SleepTrigger>,
    ): String {
        val cleared = previous - current
        return when {
            cleared.any { it == SleepTrigger.FOLDED } -> "Unfolded"
            cleared.any {
                it == SleepTrigger.CASE_CLOSED ||
                    it == SleepTrigger.IN_CASE ||
                    it == SleepTrigger.CASE_CLOSED_NO_CHARGE
            } -> "CaseOpen"
            cleared.any { it == SleepTrigger.VITALS_TIMEOUT || it == SleepTrigger.MANUAL_EXIT } -> "CaseOpen"
            else -> "CaseOpen"
        }
    }

    private fun onSleepModeEntered(timestamp: Long) {
        clientRecords.values.forEach { record ->
            record.client.enterSleepMode()
            heartbeatSupervisor.updateLensAwake(record.lens, false)
        }
        hostHeartbeatSequence.indices.forEach { index -> hostHeartbeatSequence[index] = 0 }
        heartbeatSupervisor.shutdown()
        Lens.values().forEach { lens ->
            cancelReconnect(lens)
            updateLens(lens) {
                it.copy(
                    degraded = false,
                    consecutiveKeepAliveFailures = 0,
                    keepAliveAckTimeouts = 0,
                    heartbeatMissCount = 0,
                )
            }
        }
        reconnectCoordinator.freeze()
        setStage(ConnectionStage.IdleSleep)
    }

    private fun onSleepModeExited(timestamp: Long) {
        Lens.values().forEach { lens -> resetTelemetryTimers(lens, timestamp) }
        Lens.values().forEach { lens -> resetHandshake(lens) }
        clientRecords.values.forEach { record ->
            record.client.beginWakeHandshake()
            heartbeatSupervisor.updateLensAwake(record.lens, true)
        }
        reconnectCoordinator.unfreeze()
        ensureHeartbeatLoop()
        if (_connectionStage.value == ConnectionStage.IdleSleep) {
            setStage(ConnectionStage.Idle)
        }
    }

    private fun resetTelemetryTimers(lens: Lens, timestamp: Long) {
        synchronized(sleepStateLock) {
            val current = sleepStates[lens] ?: return
            sleepStates[lens] = current.copy(lastVitalsAt = timestamp).withDerived(timestamp)
        }
    }

    private fun checkSleepTimeouts() {
        val now = System.currentTimeMillis()
        Lens.values().forEach { lens ->
            updateSleepState(lens, now) { it }
        }
    }

    private fun handleTelemetrySnapshot(snapshot: BleTelemetryRepository.Snapshot) {
        val timestamp = snapshot.recordedAt
        applyTelemetrySnapshot(Lens.LEFT, snapshot.left, snapshot, timestamp)
        applyTelemetrySnapshot(Lens.RIGHT, snapshot.right, snapshot, timestamp)
    }

    private fun applyTelemetrySnapshot(
        lens: Lens,
        lensSnapshot: BleTelemetryRepository.LensSnapshot,
        snapshot: BleTelemetryRepository.Snapshot,
        timestamp: Long,
    ) {
        val resolvedCaseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        val resolvedInCase = lensSnapshot.inCase ?: snapshot.inCase
        val resolvedFoldState = lensSnapshot.foldState ?: snapshot.foldState
        val resolvedVitals = lensSnapshot.lastVitalsTimestamp ?: snapshot.lastVitalsTimestamp
        updateSleepStateFromTelemetry(
            lens,
            resolvedCaseOpen,
            resolvedInCase,
            resolvedFoldState,
            resolvedVitals,
            timestamp,
        )
    }

    private fun updateSleepStateFromTelemetry(
        lens: Lens,
        caseOpen: Boolean?,
        inCase: Boolean?,
        foldState: Boolean?,
        vitalsTimestamp: Long?,
        timestamp: Long,
    ) {
        updateSleepState(lens, timestamp) { state ->
            var updated = state
            caseOpen?.let { open ->
                updated = updated.copy(
                    caseClosed = open == false,
                    manualExit = if (open) false else updated.manualExit,
                )
                if (open) {
                    updated = updated.copy(folded = false)
                }
            }
            inCase?.let { inCradle ->
                updated = updated.copy(inCase = inCradle)
            }
            foldState?.let { folded ->
                updated = updated.copy(folded = folded)
            }
            vitalsTimestamp?.let { lastVitals ->
                updated = updated.copy(lastVitalsAt = lastVitals)
            }
            updated
        }
    }

    private fun markAckSuccess(lens: Lens, timestamp: Long, type: AckType) {
        completePendingCommand(lens)
        val previous = lastAckSuccessMs[lens]
        lastAckSuccessMs[lens] = timestamp
        updateLens(lens) {
            it.copy(lastAckAt = timestamp, degraded = false)
        }
        val delta = previous?.let { timestamp - it }?.takeIf { it >= 0 }
        updateStabilityMetrics(lens, timestamp) { metrics ->
            metrics.copy(lastAckDeltaMs = delta)
        }
        resetReconnectTimer(lens)
        notifyHeartbeatSignal(
            lens,
            timestamp,
            SystemClock.elapsedRealtime(),
            type,
            success = true,
            busy = false,
        )
    }

    private fun isDuplicateAck(lens: Lens, event: AckEvent): Boolean {
        val previous = lastAckSignature[lens] ?: return false
        if (previous.opcode != event.opcode || previous.status != event.status) {
            return false
        }
        val delta = event.timestampMs - previous.timestamp
        return delta in 0..ACK_DUPLICATE_WINDOW_MS
    }

    private fun shouldSuppressAckFailure(lens: Lens, event: AckEvent): Boolean {
        if (event.opcode != CMD_PING) return false
        val lastSuccess = lastAckSuccessMs[lens] ?: return false
        val delta = event.timestampMs - lastSuccess
        return delta in 0..PING_ACK_SUPPRESS_WINDOW_MS
    }

    private fun registerPendingCommand(lens: Lens, timestamp: Long) {
        synchronized(pendingCommandAcks) {
            pendingCommandAcks[lens]?.addLast(timestamp)
        }
    }

    private fun completePendingCommand(lens: Lens) {
        synchronized(pendingCommandAcks) {
            pendingCommandAcks[lens]?.let { deque ->
                if (deque.isNotEmpty()) {
                    deque.removeFirst()
                }
            }
        }
    }

    private fun failPendingCommand(lens: Lens) {
        synchronized(pendingCommandAcks) {
            pendingCommandAcks[lens]?.let { deque ->
                if (deque.isNotEmpty()) {
                    deque.removeFirst()
                }
            }
        }
    }

    private fun clearPendingCommands(lens: Lens) {
        synchronized(pendingCommandAcks) {
            pendingCommandAcks[lens]?.clear()
        }
    }

    private fun notifyHeartbeatSignal(
        lens: Lens?,
        timestamp: Long,
        elapsedRealtime: Long,
        type: AckType,
        success: Boolean,
        busy: Boolean,
    ) {
        ackSignalFlow.tryEmit(AckSignal(lens, timestamp, elapsedRealtime, type, success, busy))
    }

    private fun updateCaseOpenState(caseOpen: Boolean?, timestamp: Long) {
        if (caseOpen == null) {
            return
        }
        var previous: Boolean?
        var changed = false
        synchronized(caseStateLock) {
            if (lastCaseOpen != caseOpen) {
                previous = lastCaseOpen
                lastCaseOpen = caseOpen
                changed = true
            } else {
                previous = lastCaseOpen
            }
        }
        if (!changed) {
            return
        }
        val message = "${previous.toCaseLabel()}→${caseOpen.toCaseLabel()}"
        emitConsole("CASE", null, message, timestamp)
        updateHeartbeatLidOpen(caseOpen)
    }

    private fun Boolean?.toCaseLabel(): String = when (this) {
        true -> "CaseOpen"
        false -> "CaseClosed"
        null -> "Unknown"
    }

    private fun markHandshakeLinked(lens: Lens) {
        val timestamp = System.currentTimeMillis()
        emitConsole("LINK", lens, "Connected", timestamp)
        synchronized(handshakeProgress) {
            handshakeProgress[lens] = HandshakeStage.Linked
            sequenceLogged = false
        }
    }

    private fun markHandshakeAcked(lens: Lens) {
        val timestamp = System.currentTimeMillis()
        emitConsole("ACK", lens, "OK received", timestamp)
        val shouldEmitSequence = synchronized(handshakeProgress) {
            handshakeProgress[lens] = HandshakeStage.Acked
            val complete =
                handshakeProgress[Lens.LEFT] == HandshakeStage.Acked &&
                    handshakeProgress[Lens.RIGHT] == HandshakeStage.Acked &&
                    !sequenceLogged
            if (complete) {
                sequenceLogged = true
            }
            complete
        }
        if (shouldEmitSequence) {
            emitConsole("SEQ", null, "L:HELLO→OK→ACK | R:HELLO→OK→ACK", timestamp)
        }
    }

    private fun resetHandshake(lens: Lens) {
        synchronized(handshakeProgress) {
            handshakeProgress[lens] = HandshakeStage.Idle
            sequenceLogged = false
        }
    }

    private fun emitConsole(
        tag: String,
        lens: Lens?,
        message: String,
        timestamp: Long,
    ) {
        val timeLabel = stabilityTimeFormatter.get().format(Date(timestamp))
        val prefix = if (lens != null) {
            "[$timeLabel][$tag][${lens.shortLabel}]"
        } else {
            "[$timeLabel][$tag]"
        }
        log("$prefix $message")
    }

    private fun ByteArray.isTextualOk(): Boolean =
        toString(Charsets.UTF_8).trim().equals("OK", ignoreCase = true)

    private fun performRebond(triggerLens: Lens? = null) {
        if (isSleepModeActive()) {
            log("[HB][-] rebond skipped (sleep)")
            return
        }
        if (rebondJob?.isActive == true) {
            return
        }
        val devices = knownDevices.toMap()
        if (devices.isEmpty()) {
            return
        }
        val timestamp = System.currentTimeMillis()
        incrementRebondCounter(triggerLens, timestamp)
        rebondJob = scope.launch(Dispatchers.IO) {
            log("[HB][-] rebond sequence start")
            disconnectAll()
            delay(REBOND_DELAY_MS)
            devices.forEach { (lens, device) ->
                connect(device, lens)
                delay(CHANNEL_STAGGER_DELAY_MS)
            }
        }.also { job ->
            job.invokeOnCompletion { rebondJob = null }
        }
    }

    private fun resetContinuationBuffer(lens: Lens) {
        ackContinuationBuffers[lens]?.reset()
    }

    private data class AckSignature(val opcode: Int?, val status: Int?, val timestamp: Long)

    private data class HeartbeatPacket(val sequence: Int, val payload: ByteArray)

    private data class HeartbeatResult(
        val sequence: Int,
        val timestamp: Long,
        val success: Boolean,
        val busy: Boolean,
        val ackType: AckType?,
        val latencyMs: Long?,
    )

    private data class AckSignal(
        val lens: Lens?,
        val timestamp: Long,
        val elapsedRealtime: Long,
        val type: AckType,
        val success: Boolean,
        val busy: Boolean,
    )

    private data class VitalsLogState(
        val batteryPercent: Int?,
        val charging: Boolean?,
        val caseLabel: String?,
        val folded: Boolean?,
    )

    private data class SleepState(
        val caseClosed: Boolean = false,
        val inCase: Boolean = false,
        val folded: Boolean = false,
        val manualExit: Boolean = false,
        val sleepEvent: Boolean = false,
        val charging: Boolean? = null,
        val caseClosedNoCharge: Boolean = false,
        val vitalsTimeout: Boolean = false,
        val lastVitalsAt: Long? = null,
    ) {
        fun withDerived(now: Long): SleepState {
            val derivedCaseClosedNoCharge = if ((caseClosed || inCase) && charging == false) {
                true
            } else {
                false
            }
            val derivedVitalsTimeout = lastVitalsAt?.let { now - it > G1Protocols.SLEEP_VITALS_TIMEOUT_MS } ?: false
            return copy(
                caseClosedNoCharge = derivedCaseClosedNoCharge,
                vitalsTimeout = derivedVitalsTimeout,
            )
        }

        fun activeTriggers(): Set<SleepTrigger> {
            val triggers = mutableSetOf<SleepTrigger>()
            if (caseClosed) triggers += SleepTrigger.CASE_CLOSED
            if (inCase) triggers += SleepTrigger.IN_CASE
            if (folded) triggers += SleepTrigger.FOLDED
            if (manualExit) triggers += SleepTrigger.MANUAL_EXIT
            if (sleepEvent) triggers += SleepTrigger.SLEEP_EVENT
            if (caseClosedNoCharge) triggers += SleepTrigger.CASE_CLOSED_NO_CHARGE
            if (vitalsTimeout) triggers += SleepTrigger.VITALS_TIMEOUT
            return triggers
        }

        val sleeping: Boolean get() = sleepEvent
    }

    private enum class SleepTrigger(val priority: Int, val logLabel: String) {
        CASE_CLOSED(0, "CaseClosed"),
        IN_CASE(1, "CaseClosed"),
        CASE_CLOSED_NO_CHARGE(2, "CaseClosed"),
        FOLDED(3, "Folded"),
        MANUAL_EXIT(4, "ManualExit"),
        VITALS_TIMEOUT(5, "VitalsTimeout"),
        SLEEP_EVENT(6, "SleepEvent"),
    }

    private fun updateLens(lens: Lens, reducer: (LensStatus) -> LensStatus) {
        val current = _state.value
        val updated = when (lens) {
            Lens.LEFT -> current.copy(left = reducer(current.left))
            Lens.RIGHT -> current.copy(right = reducer(current.right))
        }
        _state.value = updated
        ensureHeartbeatLoop()
    }

    private fun refreshConnectionOrderState() {
        _state.value = _state.value.copy(connectionOrder = connectionOrder.toList())
    }

    private fun onBondFailure(
        lens: Lens,
        device: BluetoothDevice,
        result: BondAwaitResult,
    ): Boolean {
        val next = (bondFailureStreak[lens] ?: 0) + 1
        bondFailureStreak[lens] = next
        val label = when (result) {
            BondAwaitResult.Timeout -> "timeout"
            is BondAwaitResult.Failed -> "reason=${result.reason.toBondReasonString()}"
            BondAwaitResult.Success -> "success"
        }
        logWarn("[PAIRING] ${lens.name.lowercase(Locale.US)} bond failure ($label) streak=$next")
        if (next >= MAX_BOND_FAILURE_STREAK) {
            logWarn("[PAIRING] Clearing cached bonds after $next failures for ${device.address}")
            clearAllKnownBonds()
            bondFailureStreak.clear()
            return true
        }
        return false
    }

    private fun handleConnectionFailure(lens: Lens, record: ClientRecord) {
        if (!isSleepModeActive()) {
            record.client.refreshDeviceCache()
        }
        record.dispose()
        clientRecords.remove(lens)
        keepAliveWriteTimestamps.remove(lens)
        cancelReconnect(lens)
        recordConnectionFailure(lens)
        lastDisconnectTimestamp[lens] = System.currentTimeMillis()
        heartbeatRebondTriggered[lens] = false
        staleBondFailureStreak[lens] = 0
        resetStabilityMetrics(lens)
        clearHeartbeatRtt(lens)
        updateLens(lens) { LensStatus() }
        if (connectionOrder.remove(lens)) {
            refreshConnectionOrderState()
        }
        consecutiveConnectionFailures += 1
        if (consecutiveConnectionFailures == CONNECTION_FAILURE_THRESHOLD) {
            logger.i(
                TAG,
                "${tt()} [MONCCHICHI][TROUBLESHOOT] Dialog displayed – connection failed twice"
            )
            _events.tryEmit(MoncchichiEvent.ConnectionFailed)
        }
        val current = state.value
        val sleepActive = isSleepModeActive()
        if (!current.left.isConnected && !current.right.isConnected) {
            if (sleepActive) {
                setStage(ConnectionStage.IdleSleep)
            } else {
                setStage(ConnectionStage.Idle)
            }
            if (connectionOrder.isNotEmpty()) {
                connectionOrder.clear()
                refreshConnectionOrderState()
            }
            if (!sleepActive) {
                scope.launch { refreshAllGattCaches() }
            }
        } else if (current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.LeftReady)
        } else if (sleepActive) {
            setStage(ConnectionStage.IdleSleep)
        }
    }

    private suspend fun awaitLeftReadyForRight(): Boolean {
        val leftRecord = clientRecords[Lens.LEFT] ?: return false
        if (!leftRecord.client.awaitHelloAck(G1Protocols.HELLO_TIMEOUT_MS)) {
            return false
        }
        val result = leftRecord.client.awaitReady(G1Protocols.HELLO_TIMEOUT_MS)
        if (result != G1BleClient.AwaitReadyResult.Ready) {
            return false
        }
        val currentStage = _connectionStage.value
        if (currentStage != ConnectionStage.Warmup && currentStage != ConnectionStage.ConnectRight && currentStage != ConnectionStage.BothReady && currentStage != ConnectionStage.Connected) {
            setStage(ConnectionStage.LeftReady)
            delay(G1Protocols.WARMUP_DELAY_MS)
            setStage(ConnectionStage.Warmup)
        }
        return true
    }

    private fun clearAllKnownBonds() {
        val uniqueDevices = knownDevices.values.distinctBy { it.address }
        uniqueDevices.forEach { device ->
            val cleared = clearBond(device)
            log("[PAIRING] removeBond ${device.address} -> $cleared")
        }
        scope.launch { refreshAllGattCaches() }
    }

    private fun clearBond(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = device.javaClass.getMethod("removeBond")
            (method.invoke(device) as? Boolean) == true
        }.onFailure {
            logWarn("[PAIRING] removeBond failed for ${device.address}: ${it.message}")
        }.getOrElse { false }
    }

    private fun setStage(stage: ConnectionStage) {
        if (_connectionStage.value != stage) {
            _connectionStage.value = stage
            log("Stage -> ${stage.name}")
        }
    }

    private fun log(message: String) {
        logger.i(TAG, "${tt()} $message")
    }

    private fun logWarn(message: String) {
        logger.w(TAG, "${tt()} $message")
    }

    private fun G1BleClient.State.isReadyForCompanion(): Boolean {
        return bonded && (attMtu != null || warmupOk)
    }

    private fun tt(): String = "[${Thread.currentThread().name}]"

    private fun Int?.toOpcodeLabel(): String = this?.let {
        "${G1Protocols.opcodeName(it)}(${String.format("0x%02X", it)})"
    } ?: "unknown"

    private fun Int?.toHex(): String = this?.let { String.format("0x%02X", it) } ?: "n/a"

    private fun Lens.toTarget(): Target = when (this) {
        Lens.LEFT -> Target.Left
        Lens.RIGHT -> Target.Right
    }

    private fun describeEvenAi(event: G1ReplyParser.EvenAiEvent): String = when (event) {
        is G1ReplyParser.EvenAiEvent.ActivationRequested -> "activation"
        is G1ReplyParser.EvenAiEvent.ManualExit -> "manual exit"
        is G1ReplyParser.EvenAiEvent.ManualPaging -> "manual page"
        is G1ReplyParser.EvenAiEvent.RecordingStopped -> "recording stopped"
        is G1ReplyParser.EvenAiEvent.SilentModeToggle -> "silent toggle"
        is G1ReplyParser.EvenAiEvent.Unknown -> "unknown(${"0x%02X".format(event.subcommand)})"
    }

    private fun formatBondReason(reason: Int?): String {
        return when (reason) {
            null -> "n/a"
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

    companion object {
        private const val TAG = "[MoncchichiBle]"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val CHANNEL_STAGGER_DELAY_MS = 5L
        private val ALL_LENSES = Lens.values()
        private const val CONNECTION_FAILURE_THRESHOLD = 2
        private const val MAX_BOND_FAILURE_STREAK = 3
        private const val RIGHT_BOND_INITIAL_DELAY_MS = 500L
        private const val RIGHT_BOND_RETRY_BASE_DELAY_MS = 10_000L
        private const val STALE_BOND_CLEAR_DELAY_MS = 500L
        private const val KEEP_ALIVE_MIN_INTERVAL_MS = 1_000L
        private const val CASE_TELEMETRY_COMMAND_DELAY_MS = 50L
        private const val CASE_BATTERY_SUBCOMMAND: Byte = 0x01
        private const val BOND_RECOVERY_GUARD_MS = 3_000L
        private const val UNBOND_REASON_REMOVED = 5
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_JITTER_MS = 0L
        private const val HEARTBEAT_ACK_WINDOW_MS = 2_000L
        private const val HEARTBEAT_RECONNECT_THRESHOLD = 3
        private const val HEARTBEAT_REBOND_THRESHOLD = 3
        private const val HEARTBEAT_IDLE_POLL_MS = 1_000L
        private const val HEARTBEAT_BUSY_DEFER_MS = 3_000L
        private const val HEARTBEAT_BUSY_POLL_MS = 150L
        private const val PING_ACK_SUPPRESS_WINDOW_MS = 150L
        private const val ACK_DUPLICATE_WINDOW_MS = 50L
        private const val REBOND_DELAY_MS = 500L
        private const val RSSI_WINDOW_SIZE = 8
        private const val HEARTBEAT_RTT_WINDOW_SIZE = 8
        private const val GATT_FAILURE_WINDOW_MS = 60_000L
        private const val GATT_REFRESH_THRESHOLD = 3
        private const val STALE_BOND_CLEAR_THRESHOLD = 3
        private const val SLEEP_TIMEOUT_POLL_MS = 500L
    }
}

internal class ReconnectCoordinator(
    private val scope: CoroutineScope,
    private val shouldContinue: suspend (MoncchichiBleService.Lens) -> Boolean,
    private val onAttempt: (MoncchichiBleService.Lens, Int, Long, String) -> Unit,
    private val attempt: suspend (MoncchichiBleService.Lens, Int, String) -> Boolean,
    private val onSuccess: (MoncchichiBleService.Lens, Int) -> Unit,
    private val onStop: (MoncchichiBleService.Lens) -> Unit,
    private val updateState: (MoncchichiBleService.Lens, MoncchichiBleService.ReconnectStatus) -> Unit,
    private val backoffDelaysMs: LongArray = longArrayOf(1_000L, 3_000L, 5_000L, 10_000L),
    private val maxAttempts: Int = backoffDelaysMs.size,
    private val stabilityResetMs: Long = 30_000L,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val jobs = EnumMap<MoncchichiBleService.Lens, Job?>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { lens -> put(lens, null) }
    }
    private val generations = EnumMap<MoncchichiBleService.Lens, Int>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { lens -> put(lens, 0) }
    }
    private val attempts = EnumMap<MoncchichiBleService.Lens, Int>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { put(it, 0) }
    }
    private val backoffIndexes = EnumMap<MoncchichiBleService.Lens, Int>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { put(it, 0) }
    }
    private val stableSince = EnumMap<MoncchichiBleService.Lens, Long?>(MoncchichiBleService.Lens::class.java)
    @Volatile
    private var frozen: Boolean = false

    fun schedule(lens: MoncchichiBleService.Lens, reason: String) {
        if (frozen) {
            return
        }
        stableSince.remove(lens)
        val existingJob = jobs[lens]
        if (existingJob?.isActive == true) {
            return
        }
        val nextGeneration = (generations[lens] ?: 0) + 1
        generations[lens] = nextGeneration
        existingJob?.cancel()
        attempts[lens] = 0
        val job = scope.launch { runSequence(lens, reason, nextGeneration) }
        jobs[lens] = job
    }

    fun cancel(lens: MoncchichiBleService.Lens) {
        val nextGeneration = (generations[lens] ?: 0) + 1
        generations[lens] = nextGeneration
        jobs[lens]?.cancel()
        jobs[lens] = null
        attempts[lens] = 0
        backoffIndexes[lens] = 0
        stableSince.remove(lens)
        updateState(lens, MoncchichiBleService.ReconnectStatus())
        onStop(lens)
    }

    fun reset(lens: MoncchichiBleService.Lens) {
        if (frozen) {
            return
        }
        val job = jobs[lens]
        val index = (backoffIndexes[lens] ?: 0).coerceAtLeast(0)
        if (index == 0) {
            stableSince.remove(lens)
            updateState(lens, MoncchichiBleService.ReconnectStatus())
        } else {
            val now = elapsedRealtime()
            val since = stableSince[lens]
            if (since == null) {
                stableSince[lens] = now
                updateState(
                    lens,
                    MoncchichiBleService.ReconnectStatus(
                        state = MoncchichiBleService.ReconnectPhase.Cooldown,
                        attempt = 0,
                        nextDelayMs = stabilityResetMs,
                    ),
                )
            } else {
                val elapsed = now - since
                if (elapsed >= stabilityResetMs) {
                    stableSince.remove(lens)
                    backoffIndexes[lens] = 0
                    attempts[lens] = 0
                    updateState(lens, MoncchichiBleService.ReconnectStatus())
                } else {
                    updateState(
                        lens,
                        MoncchichiBleService.ReconnectStatus(
                            state = MoncchichiBleService.ReconnectPhase.Cooldown,
                            attempt = 0,
                            nextDelayMs = stabilityResetMs - elapsed,
                        ),
                    )
                }
            }
        }
        attempts[lens] = 0
        if (job != null) {
            job.cancel()
        }
    }

    private suspend fun runSequence(
        lens: MoncchichiBleService.Lens,
        reason: String,
        generation: Int,
    ) {
        val job = coroutineContext[Job] ?: return
        var completedSuccessfully = false
        try {
            while (scope.isActive && shouldContinue(lens) && !frozen) {
                val nextAttempt = (attempts[lens] ?: 0) + 1
                if (nextAttempt > maxAttempts) {
                    return
                }
                attempts[lens] = nextAttempt
                val delayIndex = (backoffIndexes[lens] ?: 0).coerceIn(0, backoffDelaysMs.lastIndex)
                val delayMs = backoffDelaysMs[delayIndex]
                updateState(
                    lens,
                    MoncchichiBleService.ReconnectStatus(
                        state = MoncchichiBleService.ReconnectPhase.Waiting,
                        attempt = nextAttempt,
                        nextDelayMs = delayMs,
                    ),
                )
                delay(delayMs)
                if (!shouldContinue(lens)) {
                    return
                }
                updateState(
                    lens,
                    MoncchichiBleService.ReconnectStatus(
                        state = MoncchichiBleService.ReconnectPhase.Retrying,
                        attempt = nextAttempt,
                        nextDelayMs = delayMs,
                    ),
                )
                onAttempt(lens, nextAttempt, delayMs, reason)
                val success = attempt(lens, nextAttempt, reason)
                if (success) {
                    completedSuccessfully = true
                    onSuccess(lens, nextAttempt)
                    return
                }
                if (delayIndex < backoffDelaysMs.lastIndex) {
                    backoffIndexes[lens] = delayIndex + 1
                }
            }
        } finally {
            val currentGeneration = generations[lens]
            if (currentGeneration == generation) {
                jobs[lens] = null
                attempts[lens] = 0
                if (!stableSince.containsKey(lens)) {
                    updateState(lens, MoncchichiBleService.ReconnectStatus())
                }
                if (!completedSuccessfully) {
                    onStop(lens)
                }
            }
        }
    }

    fun freeze() {
        if (frozen) return
        frozen = true
        MoncchichiBleService.Lens.values().forEach { lens -> cancel(lens) }
    }

    fun unfreeze() {
        frozen = false
    }
}
