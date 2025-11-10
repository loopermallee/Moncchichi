package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.bluetooth.BondAwaitResult
import com.loopermallee.moncchichi.bluetooth.BondResult
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_PING
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_OK
import com.loopermallee.moncchichi.bluetooth.G1Protocols.isAckComplete
import com.loopermallee.moncchichi.bluetooth.G1Protocols.isAckContinuation
import com.loopermallee.moncchichi.core.MicControlPacket
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.collections.ArrayDeque
import kotlin.text.Charsets

/**
 * High-level BLE orchestration layer that manages the dual-lens Even Realities G1 glasses.
 */
class MoncchichiBleService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: MoncchichiLogger = MoncchichiLogger(context),
) {

    enum class Lens { LEFT, RIGHT }

    enum class ConnectionStage {
        Idle,
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

    data class AckEvent(
        val lens: Lens,
        val opcode: Int?,
        val status: Int?,
        val success: Boolean,
        val timestampMs: Long,
        val warmup: Boolean,
    )

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
    private val connectionOrder = mutableListOf<Lens>()
    private val hostHeartbeatSequence = IntArray(Lens.values().size)
    private val knownDevices = mutableMapOf<Lens, BluetoothDevice>()
    private val bondFailureStreak = mutableMapOf<Lens, Int>()
    private val reconnectBackoffMs = longArrayOf(2_000L, 5_000L, 10_000L)
    private val reconnectCoordinator = ReconnectCoordinator(
        scope = scope,
        backoffMs = reconnectBackoffMs,
        shouldContinue = { lens -> shouldAutoReconnect(lens) },
        onAttempt = { lens, attempt, reason ->
            recordReconnectAttempt(lens)
            val lensLabel = lens.name.lowercase(Locale.US)
            log("[LINK][AUTO] $lensLabel reconnect attempt $attempt ($reason)")
        },
        attempt = { lens, attempt, reason ->
            val device = knownDevices[lens] ?: return@ReconnectCoordinator false
            connect(device, lens)
        },
        onSuccess = { lens ->
            val lensLabel = lens.name.lowercase(Locale.US)
            log("[LINK][AUTO] $lensLabel auto-reconnect success")
            recordReconnectSuccess(lens)
        },
        onStop = { lens ->
            updateLens(lens) { it.copy(reconnecting = false) }
        },
    )
    private var pendingRightBondSequence = false
    private var rightBondRetryAttempt = 0
    private var rightBondRetryJob: Job? = null
    private var totalBondResetEvents = 0

    private var heartbeatJob: Job? = null
    private var rebondJob: Job? = null
    private var consecutiveConnectionFailures = 0

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
    private val lastDisconnectTimestamp = EnumMap<Lens, Long>(Lens::class.java)
    private val stabilityTimeFormatter = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

    suspend fun connect(device: BluetoothDevice, lensOverride: Lens? = null): Boolean =
        withContext(Dispatchers.IO) {
            val lens = lensOverride ?: inferLens(device)
            log("Connecting ${device.address} as $lens")

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

            maybeClearStaleBond(device, lens)

            val record = buildClientRecord(lens, device)
            clientRecords[lens]?.dispose()
            clientRecords[lens] = record
            hostHeartbeatSequence[lens.ordinal] = 0
            updateLens(lens) { it.copy(state = G1BleClient.ConnectionState.CONNECTING, rssi = null) }

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

    private suspend fun maybeClearStaleBond(device: BluetoothDevice, lens: Lens) {
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
        if (!staleBond && !removalDetected) {
            return
        }
        val shouldClear = staleBond || (removalDetected && currentBondState != BluetoothDevice.BOND_NONE)
        val lensLabel = lens.name.lowercase(Locale.US)
        val reasonLabel = formatBondReason(status.lastBondReason)
        if (shouldClear) {
            logWarn("[PAIRING] $lensLabel clearing stale bond before connect (bondState=$currentBondState reason=$reasonLabel)")
            val cleared = clearBond(device)
            log("[PAIRING] removeBond ${device.address} -> $cleared (pre-connect)")
            if (cleared) {
                delay(STALE_BOND_CLEAR_DELAY_MS)
            }
        }
        if (removalDetected) {
            val elapsed = status.lastBondEventAt?.let { System.currentTimeMillis() - it } ?: 0L
            val remaining = (BOND_RECOVERY_GUARD_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                logWarn("[PAIRING] $lensLabel delaying reconnect by ${remaining}ms after bond removal (reason=$reasonLabel)")
                delay(remaining)
            }
        }
    }

    fun disconnect(lens: Lens) {
        clientRecords.remove(lens)?.let { record ->
            log("Disconnecting $lens")
            record.client.refreshDeviceCache()
            record.dispose()
        }
        keepAliveWriteTimestamps.remove(lens)
        hostHeartbeatSequence[lens.ordinal] = 0
        knownDevices.remove(lens)
        bondFailureStreak.remove(lens)
        resetContinuationBuffer(lens)
        clearPendingCommands(lens)
        lastAckSuccessMs.remove(lens)
        lastAckSignature.remove(lens)
        cancelReconnect(lens)
        lastDisconnectTimestamp[lens] = System.currentTimeMillis()
        resetStabilityMetrics(lens)
        updateLens(lens) { LensStatus() }
        if (connectionOrder.remove(lens)) {
            refreshConnectionOrderState()
        }
        ensureHeartbeatLoop()
        val current = state.value
        if (!current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.Idle)
            if (connectionOrder.isNotEmpty()) {
                connectionOrder.clear()
                refreshConnectionOrderState()
            }
            scope.launch { refreshAllGattCaches() }
        } else if (current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.LeftReady)
        }
    }

    fun disconnectAll() {
        ALL_LENSES.forEach { disconnect(it) }
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
        val payload = byteArrayOf(0xF5.toByte(), 0x24)
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
            log("[HUD][${lens.shortLabel()}] Text sent (${bytes.size} bytes)")
        } else {
            logWarn("[HUD][${lens.shortLabel()}] Text send failed")
        }
        return ok
    }

    fun clearDisplay(lens: Lens): Boolean {
        val payload = byteArrayOf(G1Protocols.CMD_CLEAR.toByte(), 0x00)
        val ok = runBlocking { send(payload, lens.toTarget()) }
        if (ok) {
            log("[HUD][${lens.shortLabel()}] Cleared display")
        } else {
            logWarn("[HUD][${lens.shortLabel()}] Clear failed")
        }
        return ok
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        rebondJob?.cancel()
        rebondJob = null
        clientRecords.values.forEach { it.dispose() }
        clientRecords.clear()
        keepAliveWriteTimestamps.clear()
        hostHeartbeatSequence.fill(0)
        ackContinuationBuffers.values.forEach { it.reset() }
        pendingCommandAcks.values.forEach { it.clear() }
        lastAckSuccessMs.clear()
        lastAckSignature.clear()
        ALL_LENSES.forEach { updateLens(it) { LensStatus() } }
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
            logger = logger,
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
                handleBondTransitions(lens, previousStatus, state)
                handleReconnectStateChange(lens, previousStatus.state, state)
                updateRssiAverage(lens, state.rssi)
            }
        }
        jobs += scope.launch {
            client.incoming.collect { payload ->
                val now = System.currentTimeMillis()
                notifyHeartbeatSignal(lens, now)
                val opcode = payload.firstOrNull()?.toInt()?.and(0xFF)
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
                            timestampMs = now,
                            warmup = false,
                        )
                        handleAckEvent(lens, ackEvent)
                        return@collect
                    }
                }
                _incoming.tryEmit(IncomingFrame(lens, payload))
                when (val parsed = G1ReplyParser.parseNotify(payload)) {
                    is G1ReplyParser.Parsed.Vitals -> {
                        val vitals = parsed.vitals
                        val parts = buildList {
                            vitals.batteryPercent?.let { add("battery=${it}%") }
                            vitals.charging?.let { add(if (it) "charging" else "not charging") }
                            vitals.firmwareVersion?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        if (parts.isNotEmpty()) {
                            log("[BLE][VITALS][$lens] ${parts.joinToString(separator = ", ")}")
                        }
                    }
                    is G1ReplyParser.Parsed.EvenAi -> {
                        val event = EvenAiEvent(lens, parsed.event)
                        _evenAiEvents.tryEmit(event)
                        log("Even AI event from $lens -> ${describeEvenAi(parsed.event)}")
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
                    timestampMs = event.timestampMs,
                    warmup = event.warmup,
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
                val now = System.currentTimeMillis()
                val lastWrite = keepAliveWriteTimestamps[lens]
                val elapsed = lastWrite?.let { now - it } ?: Long.MAX_VALUE
                if (elapsed < KEEP_ALIVE_MIN_INTERVAL_MS) {
                    delay(KEEP_ALIVE_MIN_INTERVAL_MS - elapsed)
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
        updateLens(lens) { status ->
            val previousFailures = status.consecutiveKeepAliveFailures
            val nextFailures = if (result.success) 0 else previousFailures + 1
            val threshold = G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS
            val degraded = when {
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
        updateLens(lens) { it.copy(reconnecting = true) }
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
    }

    private fun cancelReconnect(lens: Lens) {
        reconnectCoordinator.cancel(lens)
    }

    private fun shouldAutoReconnect(lens: Lens): Boolean {
        val record = clientRecords[lens]
        val device = knownDevices[lens] ?: return false
        return record?.client?.state?.value?.bonded == true || device.bondState == BluetoothDevice.BOND_BONDED
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
        val anyConnected = state.value.left.isConnected || state.value.right.isConnected
        if (!anyConnected) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = startHeartbeatMonitor(scope)
    }

    private fun nextHostHeartbeatPayload(lens: Lens): ByteArray {
        val index = lens.ordinal
        val sequence = hostHeartbeatSequence[index] and 0xFF
        hostHeartbeatSequence[index] = (sequence + 1) and 0xFF
        return byteArrayOf(
            G1Protocols.CMD_PING.toByte(),
            sequence.toByte(),
        )
    }

    private fun startHeartbeatMonitor(scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            var missCount = 0
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeatToAll()
                val ackLens = waitForAckWithin(HEARTBEAT_ACK_WINDOW_MS)
                val now = System.currentTimeMillis()
                var snapshotEmitted = false
                if (ackLens != null) {
                    missCount = 0
                     recordHeartbeatSuccess(ackLens, now)
                    emitConsole("HB", ackLens, "[OK]", now)
                } else {
                    missCount += 1
                     recordHeartbeatMiss(now)
                    emitConsole("HB", null, "[MISS $missCount]", now)
                    if (missCount >= HEARTBEAT_REBOND_THRESHOLD) {
                        emitConsole("HB", null, "[REBONDED]", now)
                        performRebond()
                        snapshotEmitted = true
                        missCount = 0
                    }
                }
                if (!snapshotEmitted) {
                    emitStabilitySnapshot(now)
                }
            }
        }
    }

    private suspend fun sendHeartbeatToAll() {
        val records = ALL_LENSES.mapNotNull { lens ->
            clientRecords[lens]?.takeIf { it.clientState().isConnected }
        }
        if (records.isEmpty()) {
            return
        }
        records.forEachIndexed { index, record ->
            val payload = nextHostHeartbeatPayload(record.lens)
            val ok = record.client.sendCommand(
                payload = payload,
                ackTimeoutMs = 0L,
                retries = 1,
                retryDelayMs = 0L,
                expectAck = false,
            )
            if (!ok) {
                logWarn("${G1Protocols.opcodeName(CMD_PING)} send failed on ${record.lens}")
            }
            if (index < records.lastIndex) {
                delay(CHANNEL_STAGGER_DELAY_MS)
            }
        }
    }

    private suspend fun waitForAckWithin(timeoutMs: Long): Lens? {
        val start = System.currentTimeMillis()
        val signal = withTimeoutOrNull(timeoutMs) {
            ackSignalFlow.filter { it.timestamp >= start }.first()
        }
        return signal?.lens
    }

    private fun Lens.shortLabel(): String = if (this == Lens.LEFT) "L" else "R"

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
            val formattedTime = stabilityTimeFormatter.get().format(Date(timestamp))
            val message = formatStabilityMessage(metrics, formattedTime)
            emitConsole("STABILITY", lens, message, timestamp)
            _stabilityMetrics.tryEmit(metrics)
        }
    }

    private fun formatStabilityMessage(metrics: BleStabilityMetrics, timeLabel: String): String {
        val heartbeatLabel = metrics.heartbeatCount
        val missLabel = metrics.missedHeartbeats
        val rebondLabel = metrics.rebondEvents
        val rssiLabel = metrics.avgRssi?.toString() ?: "n/a"
        val ackLabel = metrics.lastAckDeltaMs?.let { "${it} ms" } ?: "n/a"
        val reconnectLabel = metrics.reconnectLatencyMs?.let { "${it} ms" } ?: "n/a"
        return "$timeLabel HB=$heartbeatLabel MISS=$missLabel REBOND=$rebondLabel RSSI=$rssiLabel ΔACK=$ackLabel RECON=$reconnectLabel"
    }

    private fun recordHeartbeatSuccess(lens: Lens, timestamp: Long) {
        updateStabilityMetrics(lens, timestamp) { metrics ->
            metrics.copy(heartbeatCount = metrics.heartbeatCount + 1)
        }
    }

    private fun recordHeartbeatMiss(timestamp: Long) {
        ALL_LENSES.forEach { lens ->
            if (currentLensStatus(lens).isConnected) {
                updateStabilityMetrics(lens, timestamp) { metrics ->
                    metrics.copy(missedHeartbeats = metrics.missedHeartbeats + 1)
                }
            }
        }
    }

    private fun incrementRebondCounter(timestamp: Long) {
        ALL_LENSES.forEach { lens ->
            updateStabilityMetrics(lens, timestamp) { metrics ->
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

    private fun emitAckFrame(lens: Lens, payload: ByteArray) {
        val outcome = payload.parseAckOutcome() ?: return
        val timestamp = System.currentTimeMillis()
        val ackEvent = when (outcome) {
            is AckOutcome.Success -> AckEvent(
                lens = lens,
                opcode = outcome.opcode,
                status = outcome.status,
                success = true,
                timestampMs = timestamp,
                warmup = outcome.warmupPrompt,
            )
            is AckOutcome.Failure -> AckEvent(
                lens = lens,
                opcode = outcome.opcode,
                status = outcome.status,
                success = false,
                timestampMs = timestamp,
                warmup = false,
            )
        }
        handleAckEvent(lens, ackEvent)
    }

    private fun handleAckEvent(lens: Lens, event: AckEvent) {
        if (isDuplicateAck(lens, event)) {
            return
        }
        lastAckSignature[lens] = AckSignature(event.opcode, event.status, event.timestampMs)
        if (event.success) {
            markAckSuccess(lens, event.timestampMs)
            emitConsole("ACK", lens, "opcode=${event.opcode.toOpcodeLabel()} status=${event.status.toHex()}", event.timestampMs)
        } else {
            failPendingCommand(lens)
            val suppressed = shouldSuppressAckFailure(lens, event)
            if (!suppressed) {
                updateLens(lens) { it.copy(degraded = true) }
                emitConsole(
                    "ACK",
                    lens,
                    "fail opcode=${event.opcode.toOpcodeLabel()} status=${event.status.toHex()}",
                    event.timestampMs,
                )
                logWarn(
                    "[ACK][${lens.shortLabel()}] failure opcode=${event.opcode.toOpcodeLabel()} status=${event.status.toHex()}"
                )
            } else {
                emitConsole(
                    "ACK",
                    lens,
                    "fail suppressed opcode=${event.opcode.toOpcodeLabel()} status=${event.status.toHex()}",
                    event.timestampMs,
                )
            }
        }
        _ackEvents.tryEmit(event)
    }

    private fun markAckSuccess(lens: Lens, timestamp: Long) {
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
        notifyHeartbeatSignal(lens, timestamp)
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

    private fun notifyHeartbeatSignal(lens: Lens?, timestamp: Long) {
        ackSignalFlow.tryEmit(AckSignal(lens, timestamp))
    }

    private fun emitConsole(
        tag: String,
        lens: Lens?,
        message: String,
        @Suppress("UNUSED_PARAMETER") timestamp: Long,
    ) {
        val lensLabel = lens?.shortLabel() ?: "-"
        log("[$tag][$lensLabel] $message")
    }

    private fun ByteArray.isTextualOk(): Boolean =
        toString(Charsets.UTF_8).trim().equals("OK", ignoreCase = true)

    private fun performRebond() {
        if (rebondJob?.isActive == true) {
            return
        }
        val devices = knownDevices.toMap()
        if (devices.isEmpty()) {
            return
        }
        val timestamp = System.currentTimeMillis()
        incrementRebondCounter(timestamp)
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

    private data class AckSignal(val lens: Lens?, val timestamp: Long)

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
        record.client.refreshDeviceCache()
        record.dispose()
        clientRecords.remove(lens)
        keepAliveWriteTimestamps.remove(lens)
        cancelReconnect(lens)
        lastDisconnectTimestamp[lens] = System.currentTimeMillis()
        resetStabilityMetrics(lens)
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
        if (!current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.Idle)
            if (connectionOrder.isNotEmpty()) {
                connectionOrder.clear()
                refreshConnectionOrderState()
            }
            scope.launch { refreshAllGattCaches() }
        } else if (current.left.isConnected && !current.right.isConnected) {
            setStage(ConnectionStage.LeftReady)
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
        private const val BOND_RECOVERY_GUARD_MS = 3_000L
        private const val UNBOND_REASON_REMOVED = 5
        private const val HEARTBEAT_INTERVAL_MS = 28_000L
        private const val HEARTBEAT_ACK_WINDOW_MS = 1_500L
        private const val HEARTBEAT_REBOND_THRESHOLD = 3
        private const val PING_ACK_SUPPRESS_WINDOW_MS = 150L
        private const val ACK_DUPLICATE_WINDOW_MS = 50L
        private const val REBOND_DELAY_MS = 500L
        private const val RSSI_WINDOW_SIZE = 8
    }
}

internal class ReconnectCoordinator(
    private val scope: CoroutineScope,
    private val backoffMs: LongArray,
    private val shouldContinue: suspend (MoncchichiBleService.Lens) -> Boolean,
    private val onAttempt: (MoncchichiBleService.Lens, Int, String) -> Unit,
    private val attempt: suspend (MoncchichiBleService.Lens, Int, String) -> Boolean,
    private val onSuccess: (MoncchichiBleService.Lens) -> Unit,
    private val onStop: (MoncchichiBleService.Lens) -> Unit,
) {
    private var job: Job? = null
    private val queue = ArrayDeque<Pair<MoncchichiBleService.Lens, String>>()
    private var current: MoncchichiBleService.Lens? = null

    fun schedule(lens: MoncchichiBleService.Lens, reason: String) {
        queue.removeAll { it.first == lens }
        queue.addLast(lens to reason)
        if (job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) { runQueue() }
        }
    }

    fun cancel(lens: MoncchichiBleService.Lens) {
        queue.removeAll { it.first == lens }
        if (current == lens) {
            current = null
            onStop(lens)
        }
        if (queue.isEmpty()) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun runQueue() {
        while (scope.isActive) {
            val next = if (queue.isEmpty()) null else queue.removeFirst()
            if (next == null) {
                job = null
                return
            }
            val (lens, reason) = next
            current = lens
            runSequence(lens, reason)
            current = null
            if (queue.isEmpty()) {
                job = null
                return
            }
        }
    }

    private suspend fun runSequence(lens: MoncchichiBleService.Lens, reason: String) {
        for ((index, delayMs) in backoffMs.withIndex()) {
            if (!shouldContinue(lens)) {
                onStop(lens)
                return
            }
            delay(delayMs)
            if (!shouldContinue(lens)) {
                onStop(lens)
                return
            }
            val attemptNumber = index + 1
            onAttempt(lens, attemptNumber, reason)
            val success = attempt(lens, attemptNumber, reason)
            if (success) {
                onSuccess(lens)
                return
            }
        }
        onStop(lens)
    }
}
