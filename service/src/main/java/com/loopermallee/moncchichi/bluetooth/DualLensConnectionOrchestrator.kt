package com.loopermallee.moncchichi.bluetooth

import android.os.SystemClock
import com.loopermallee.moncchichi.bluetooth.G1Protocols.BATT_SUB_DETAIL
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CE_IDLE_SLEEP_QUIET_WINDOW_MS
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_BATT_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_CASE_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_WEAR_DETECT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_NOTIFICATION_AUTO_DISPLAY
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_UPTIME
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SLEEP_VITALS_TIMEOUT_MS
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.bluetooth.LinkStatus
import com.loopermallee.moncchichi.telemetry.BleTelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.collections.ArrayDeque
import kotlin.math.min

private const val EVEN_SEQUENCE_DELAY_MS = 200L
private const val BOND_RETRY_WINDOW_MS = 30_000L
private const val BOND_RETRY_MAX_ATTEMPTS = 3

/**
 * Coordinates dual-lens connections while honouring Even parity requirements.
 */
class DualLensConnectionOrchestrator(
    private val pairKey: PairKey,
    private val bleFactory: (LensId) -> BleClient,
    private val scope: CoroutineScope,
    telemetry: BleTelemetryRepository? = null,
    private val logger: (String) -> Unit = {},
) {
    private val telemetryRepository: BleTelemetryRepository = telemetry ?: BleTelemetryRepository()
    private val sleepMonitorJob = scope.launch {
        telemetryRepository.sleepEvents.collect { event ->
            event?.let { handleSleepEvent(it) }
        }
    }
    sealed class State {
        data object Idle : State()
        data object IdleSleep : State()
        data object Scanning : State()
        data object ConnectingRight : State()
        data object ConnectingLeft : State()
        data object RightOnlineUnprimed : State()
        data object LeftOnlineUnprimed : State()
        data object ReadyRight : State()
        data object ReadyBoth : State()
        data object DegradedRightOnly : State()
        data object DegradedLeftOnly : State()
        data object RecoveringLeft : State()
        data object RecoveringRight : State()
        data object Repriming : State()
        data object Stable : State()
    }

    private data class LensSession(
        val id: LensId,
        val client: BleClient,
    )

    private data class PendingCommand(
        val payload: ByteArray,
        val family: CommandRouter.Family,
        val mirror: Boolean,
        val leftDispatched: Boolean = false,
    )

    private fun PendingCommand.requiresLeft(): Boolean {
        return when (family) {
            CommandRouter.Family.BOTH -> true
            CommandRouter.Family.RIGHT_ONLY -> mirror
            else -> false
        }
    }

    private fun PendingCommand.requiresRight(): Boolean {
        return when (family) {
            CommandRouter.Family.BOTH -> true
            CommandRouter.Family.RIGHT_ONLY, CommandRouter.Family.UNKNOWN -> true
            else -> false
        }
    }

    private val stateLock = Mutex()

    private val _connectionState = MutableStateFlow<State>(State.Idle)
    val connectionState: StateFlow<State> = _connectionState.asStateFlow()

    private val _headset = MutableStateFlow(
        HeadsetState(
            pair = pairKey,
            left = null,
            right = null,
        ),
    )
    val headset: StateFlow<HeadsetState> = _headset.asStateFlow()

    private val _clientEvents = MutableSharedFlow<LensClientEvent>(extraBufferCapacity = 32)
    val clientEvents: SharedFlow<LensClientEvent> = _clientEvents.asSharedFlow()

    private val _telemetry = MutableStateFlow<Map<Lens, ClientEvent.Telemetry>>(emptyMap())
    val telemetry: StateFlow<Map<Lens, ClientEvent.Telemetry>> = _telemetry.asStateFlow()

    private var leftSession: LensSession? = null
    private var rightSession: LensSession? = null

    private var latestLeft: LensState? = null
    private var latestRight: LensState? = null

    private val commandRouter = CommandRouter()
    private val pendingMirrors = ArrayDeque<PendingCommand>()
    private val pendingLeftRefresh = ArrayDeque<ByteArray>()
    private val mirrorLock = Mutex()
    private val leftRefreshLock = Mutex()

    @Volatile
    private var rightPrimed: Boolean = false

    @Volatile
    private var leftPrimed: Boolean = false

    @Volatile
    private var leftRefreshRequested: Boolean = false

    private val operationLock = Any()
    private val connectOpsActive: MutableMap<Lens, Boolean> = mutableMapOf(
        Lens.LEFT to false,
        Lens.RIGHT to false,
    )
    private val bondOpsActive: MutableMap<Lens, Boolean> = mutableMapOf(
        Lens.LEFT to false,
        Lens.RIGHT to false,
    )
    private val gattRefreshOpsActive: MutableMap<Lens, Boolean> = mutableMapOf(
        Lens.LEFT to false,
        Lens.RIGHT to false,
    )

    private val stateJobs: MutableMap<Lens, Job> = mutableMapOf()
    private val eventJobs: MutableMap<Lens, Job> = mutableMapOf()
    private val reconnectJobs: MutableMap<Lens, Job> = mutableMapOf()
    private val heartbeatStates: MutableMap<Lens, HeartbeatState> = mutableMapOf(
        Lens.LEFT to HeartbeatState(),
        Lens.RIGHT to HeartbeatState(),
    )
    private val reconnectBackoffStep: MutableMap<Lens, Int> = mutableMapOf(
        Lens.LEFT to 0,
        Lens.RIGHT to 0,
    )
    private val reconnectFailures: MutableMap<Lens, ArrayDeque<Long>> = mutableMapOf(
        Lens.LEFT to ArrayDeque(),
        Lens.RIGHT to ArrayDeque(),
    )
    private val bondLossCounters: MutableMap<Lens, Int> = mutableMapOf(
        Lens.LEFT to 0,
        Lens.RIGHT to 0,
    )
    private val bondRetryHistory: MutableMap<Lens, ArrayDeque<Long>> = mutableMapOf(
        Lens.LEFT to ArrayDeque(),
        Lens.RIGHT to ArrayDeque(),
    )

    private var lastStabilityTransitionAt: Long = 0L

    @Volatile
    private var sessionActive: Boolean = false

    private var heartbeatJob: Job? = null
    private var wakeJob: Job? = null
    private var lastLeftMac: String? = null
    private var lastRightMac: String? = null

    @Volatile
    private var sleeping: Boolean = false
    private var awaitingWakeTelemetry: Boolean = false
    private var wakeRefreshIssued: Boolean = false
    private var wakeQuietUntilElapsed: Long = 0L
    private val wakeTelemetryObserved: MutableMap<Lens, Boolean> = mutableMapOf(
        Lens.LEFT to false,
        Lens.RIGHT to false,
    )
    private var wakeQuietActive: Boolean = false
    private var readyConfigIssued: Boolean = false

    @Volatile
    private var fullResetScheduled: Boolean = false

    suspend fun connectHeadset(pairKey: PairKey, leftMac: String, rightMac: String) = coroutineScope {
        require(pairKey == this@DualLensConnectionOrchestrator.pairKey) {
            "Attempted to connect mismatched pair $pairKey for orchestrator ${this@DualLensConnectionOrchestrator.pairKey}"
        }

        sleeping = false
        disconnectHeadset()

        if (sleeping) {
            return@coroutineScope
        }
        lastLeftMac = leftMac
        lastRightMac = rightMac

        stateLock.withLock {
            latestLeft = null
            latestRight = null
            publishHeadsetState()
        }

        val leftId = LensId(leftMac, Lens.LEFT)
        val rightId = LensId(rightMac, Lens.RIGHT)
        val leftClient = bleFactory(leftId)
        val rightClient = bleFactory(rightId)
        leftSession = LensSession(leftId, leftClient)
        rightSession = LensSession(rightId, rightClient)

        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        rightPrimed = false
        leftPrimed = false
        leftRefreshRequested = false
        resetOperationFlags()

        sessionActive = true
        _connectionState.value = State.ConnectingLeft
        _telemetry.value = emptyMap()
        startHeartbeat()

        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        startTracking(Lens.LEFT, leftClient)
        startTracking(Lens.RIGHT, rightClient)

        setOperationActive(connectOpsActive, Lens.LEFT, true)
        val leftResult = runCatching {
            leftClient.ensureBonded()
            delay(EVEN_SEQUENCE_DELAY_MS)
            leftClient.connectAndSetup()
            _connectionState.value = State.LeftOnlineUnprimed
            delay(EVEN_SEQUENCE_DELAY_MS)
            val primed = leftClient.probeReady(Lens.LEFT)
            if (primed && !isHeartbeatSuppressed()) {
                leftClient.startKeepAlive()
            }
            primed
        }.getOrElse { false }
        setOperationActive(connectOpsActive, Lens.LEFT, false)

        leftPrimed = leftResult
        if (leftPrimed) {
            flushPendingMirrors()
        } else {
            if (!isIdleSleepState()) {
                setConnectionState(State.DegradedRightOnly, debounceStability = true)
            }
            scheduleReconnect(Lens.LEFT, reason = "left_prime_failed")
            return@coroutineScope
        }

        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        awaitLeftPrimeCompletion()
        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        delay(EVEN_SEQUENCE_DELAY_MS)
        _connectionState.value = State.ConnectingRight
        setOperationActive(connectOpsActive, Lens.RIGHT, true)
        val rightResult = runCatching {
            rightClient.ensureBonded()
            delay(EVEN_SEQUENCE_DELAY_MS)
            rightClient.connectAndSetup()
            _connectionState.value = State.RightOnlineUnprimed
            delay(EVEN_SEQUENCE_DELAY_MS)
            val primed = rightClient.probeReady(Lens.RIGHT)
            if (primed && !isHeartbeatSuppressed()) {
                rightClient.startKeepAlive()
                _connectionState.value = State.ReadyRight
            }
            primed
        }.getOrElse { false }
        setOperationActive(connectOpsActive, Lens.RIGHT, false)

        rightPrimed = rightResult
        if (rightPrimed) {
            flushPendingMirrors()
        } else {
            if (!isIdleSleepState()) {
                setConnectionState(State.DegradedLeftOnly, debounceStability = true)
            }
            scheduleReconnect(Lens.RIGHT, reason = "right_prime_failed")
            return@coroutineScope
        }

        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        flushPendingLeftRefresh()

        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        if (leftResult && rightResult) {
            _connectionState.value = State.ReadyBoth
            setConnectionState(State.Stable, debounceStability = true)
            attemptReadyConfiguration()
        }
    }

    suspend fun disconnectHeadset() {
        sessionActive = false
        _connectionState.value = if (sleeping) {
            State.IdleSleep
        } else {
            State.Idle
        }

        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        stopHeartbeat()
        wakeJob?.cancel()
        wakeJob = null
        heartbeatStates.values.forEach { it.reset() }
        reconnectBackoffStep.keys.forEach { reconnectBackoffStep[it] = 0 }
        reconnectFailures.values.forEach { it.clear() }
        bondLossCounters.keys.forEach { bondLossCounters[it] = 0 }

        val leftClient = leftSession?.client
        val rightClient = rightSession?.client

        leftClient?.close()
        rightClient?.close()
        if (leftClient != null || rightClient != null) {
            delay(EVEN_SEQUENCE_DELAY_MS)
        }

        eventJobs.values.forEach { it.cancel() }
        eventJobs.clear()

        stateJobs.values.forEach { it.cancel() }
        stateJobs.clear()

        leftSession = null
        rightSession = null

        stateLock.withLock {
            latestLeft = leftClient?.state?.value?.withSide(Lens.LEFT)
            latestRight = rightClient?.state?.value?.withSide(Lens.RIGHT)
            publishHeadsetState()
        }

        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        rightPrimed = false
        leftPrimed = false
        awaitingWakeTelemetry = false
        wakeRefreshIssued = false
        readyConfigIssued = false
        wakeQuietActive = false
        wakeQuietUntilElapsed = 0L
        resetWakeTelemetryTracking()
        resetOperationFlags()
    }

    fun close() {
        sleepMonitorJob.cancel()
        wakeJob?.cancel()
        runBlocking { disconnectHeadset() }
        sleeping = false
    }

    private fun startTracking(side: Lens, client: BleClient) {
        stateJobs.remove(side)?.cancel()
        eventJobs.remove(side)?.cancel()

        stateJobs[side] = scope.launch {
            stateLock.withLock {
                when (side) {
                    Lens.LEFT -> latestLeft = client.state.value.withSide(side)
                    Lens.RIGHT -> latestRight = client.state.value.withSide(side)
                }
                publishHeadsetState()
            }
            client.state.collect { state ->
                stateLock.withLock {
                    when (side) {
                        Lens.LEFT -> latestLeft = state.withSide(side)
                        Lens.RIGHT -> latestRight = state.withSide(side)
                    }
                    publishHeadsetState()
                }
            }
        }

        eventJobs[side] = scope.launch {
            client.events.collect { handleClientEvent(side, it) }
        }
    }

    suspend fun sendCommand(payload: ByteArray): Boolean {
        if (payload.isEmpty()) {
            return false
        }
        if (isIdleSleepState()) {
            return false
        }
        val opcode = payload.first().toInt() and 0xFF
        val subOpcode = payload.getOrNull(1)?.toInt()?.and(0xFF)
        if (rightPrimed) {
            flushPendingMirrors()
        }
        flushPendingLeftRefresh()

        val decision = commandRouter.classify(opcode, subOpcode)
        return when (decision.family) {
            CommandRouter.Family.RIGHT_ONLY -> sendRightOrQueue(payload, decision.mirror)
            CommandRouter.Family.BOTH -> sendBothOrQueue(payload, decision.mirror)
            CommandRouter.Family.EVENTS -> sendEventsImmediate(payload)
            CommandRouter.Family.UNKNOWN -> sendRightOrQueue(payload, decision.mirror)
        }
    }

    private suspend fun handleClientEvent(side: Lens, event: ClientEvent) {
        val idleSleep = isIdleSleepState()
        when (event) {
            is ClientEvent.Telemetry -> _telemetry.update { current -> current + (side to event) }
            is ClientEvent.ConnectionStateChanged -> {
                if (!event.connected) {
                    when (side) {
                        Lens.RIGHT -> markRightPrimed(false)
                        Lens.LEFT -> markLeftPrimed(false)
                    }
                    if (!idleSleep) {
                        scheduleReconnect(side, reason = "link_state_changed")
                        val target = when (side) {
                            Lens.RIGHT -> when {
                                latestLeft?.connected == true -> State.RecoveringRight
                                latestLeft?.isReady == true -> State.DegradedLeftOnly
                                else -> State.ConnectingRight
                            }
                            Lens.LEFT -> when {
                                latestRight?.connected == true -> State.RecoveringLeft
                                latestRight?.isReady == true -> State.DegradedRightOnly
                                else -> State.ConnectingLeft
                            }
                        }
                        val debounce = isStabilityState(target)
                        if (debounce) {
                            setConnectionState(target, debounceStability = true)
                        } else {
                            _connectionState.value = target
                        }
                    }
                    heartbeatStates[side]?.reset()
                    telemetryRepository.recordTelemetry(
                        side,
                        mapOf("connected" to false),
                    )
                    if (!idleSleep && latestLeft?.connected != true && latestRight?.connected != true) {
                        _connectionState.value = State.Idle
                    }
                } else {
                    reconnectJobs.remove(side)?.cancel()
                    val nextState = when (side) {
                        Lens.RIGHT -> when {
                            leftPrimed && rightPrimed -> {
                                _connectionState.value = State.ReadyBoth
                                State.Stable
                            }
                            leftPrimed -> State.ReadyRight
                            else -> State.RightOnlineUnprimed
                        }
                        Lens.LEFT -> when {
                            leftPrimed && rightPrimed -> {
                                _connectionState.value = State.ReadyBoth
                                State.Stable
                            }
                            else -> State.LeftOnlineUnprimed
                        }
                    }
                    if (!idleSleep) {
                        if (isStabilityState(nextState)) {
                            setConnectionState(nextState, debounceStability = true)
                        } else {
                            _connectionState.value = nextState
                        }
                    }
                    when (side) {
                        Lens.RIGHT -> markRightPrimed(false)
                        Lens.LEFT -> markLeftPrimed(false)
                    }
                    heartbeatStates[side]?.reset()
                }
            }

            is ClientEvent.ReadyProbeResult -> {
                    when (side) {
                        Lens.RIGHT -> markRightPrimed(event.ready)
                        Lens.LEFT -> {
                            markLeftPrimed(event.ready)
                            if (event.ready) {
                                logger("[BLE][${Lens.LEFT.logLabel()}] Vitals refresh queued after LEFT ACK")
                                scheduleLeftRefresh()
                            }
                        }
                    }
                if (!isIdleSleepState()) {
                    if (event.ready && leftPrimed && rightPrimed) {
                        _connectionState.value = State.ReadyBoth
                        setConnectionState(State.Stable, debounceStability = true)
                    } else if (!event.ready) {
                        _connectionState.value = State.Repriming
                    }
                }
                if (event.ready) {
                    requestTelemetryRefresh(side)
                }
            }

            is ClientEvent.Error -> if (!idleSleep) scheduleReconnect(side, reason = "client_error")
            is ClientEvent.BondStateChanged -> handleBondEvent(side, event)
            is ClientEvent.Telemetry -> {
                telemetryRepository.recordTelemetry(
                    side,
                    buildMap {
                        event.batteryPct?.let { put("batteryPercent", it) }
                        event.firmware?.let { put("firmwareVersion", it) }
                        event.rssi?.let { put("rssi", it) }
                        put("timestamp", System.currentTimeMillis())
                    },
                )
                thawFromTelemetry(side, event)
                markHeartbeatAck(side)
                heartbeatStates[side]?.let { heartbeat ->
                    telemetryRepository.updateHeartbeat(
                        side,
                        heartbeat.lastPingAt,
                        heartbeat.lastAckAt,
                        heartbeat.missedPingCount,
                    )
                }
            }
            is ClientEvent.KeepAliveStarted -> if (!isHeartbeatSuppressed()) {
                heartbeatStates[side]?.touchPing()
            }
            else -> Unit
        }
        _clientEvents.emit(LensClientEvent(side, event))
    }

    private suspend fun sendRightOrQueue(payload: ByteArray, mirror: Boolean): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        if (mirror) {
            return sendBothOrQueue(payload, true)
        }
        if (rightPrimed) {
            flushPendingMirrors()
        }
        val shouldQueue = mirrorLock.withLock {
            val available = rightSession != null
            if (!available || !rightPrimed) {
                pendingMirrors.addLast(
                    PendingCommand(
                        payload.copyOf(),
                        CommandRouter.Family.RIGHT_ONLY,
                        mirror,
                        leftDispatched = true,
                    ),
                )
                true
            } else {
                false
            }
        }
        if (shouldQueue) {
            return true
        }
        return sendRightImmediate(payload)
    }

    private suspend fun sendBothOrQueue(payload: ByteArray, mirror: Boolean): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        if (leftPrimed) {
            flushPendingMirrors()
        }
        val leftReady = leftSession != null && leftPrimed
        if (!leftReady) {
            mirrorLock.withLock {
                pendingMirrors.addLast(
                    PendingCommand(
                        payload.copyOf(),
                        CommandRouter.Family.BOTH,
                        mirror,
                        leftDispatched = false,
                    ),
                )
            }
            return true
        }
        val leftOk = sendTo(Lens.LEFT, payload)
        if (!leftOk) {
            return false
        }
        val rightReady = rightSession != null && rightPrimed
        if (!rightReady) {
            mirrorLock.withLock {
                pendingMirrors.addLast(
                    PendingCommand(
                        payload.copyOf(),
                        CommandRouter.Family.BOTH,
                        mirror,
                        leftDispatched = true,
                    ),
                )
            }
            return true
        }
        val rightOk = sendTo(Lens.RIGHT, payload)
        if (!rightOk) {
            mirrorLock.withLock {
                pendingMirrors.addLast(
                    PendingCommand(
                        payload.copyOf(),
                        CommandRouter.Family.BOTH,
                        mirror,
                        leftDispatched = true,
                    ),
                )
            }
            return false
        }
        if (mirror) {
            flushPendingLeftRefresh()
        }
        return true
    }

    private suspend fun sendEventsImmediate(payload: ByteArray): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        var sent = false
        leftSession?.let {
            sent = sendTo(Lens.LEFT, payload) || sent
        }
        rightSession?.let {
            sent = sendTo(Lens.RIGHT, payload) || sent
        }
        return sent
    }

    private suspend fun sendRightImmediate(payload: ByteArray): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        return sendTo(Lens.RIGHT, payload)
    }

    private suspend fun sendTo(side: Lens, payload: ByteArray): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        val session = when (side) {
            Lens.LEFT -> leftSession
            Lens.RIGHT -> rightSession
        } ?: return false
        val success = runCatching { session.client.sendCommand(payload.copyOf()) }.getOrElse { false }
        if (!success) {
            scheduleReconnect(side, reason = "command_failure")
        }
        return success
    }

    private suspend fun markRightPrimed(primed: Boolean) {
        if (primed && !leftPrimed) {
            logger("[BLE][${Lens.RIGHT.logLabel()}] RIGHT primed out of order; waiting for LEFT")
            if (!isIdleSleepState()) {
                scheduleReconnect(Lens.RIGHT, reason = "right_prime_out_of_order")
            }
            rightPrimed = false
            return
        }
        rightPrimed = primed
        if (isIdleSleepState()) {
            if (!primed) {
                leftRefreshRequested = false
            }
            return
        }
        if (primed) {
            flushPendingMirrors()
            attemptReadyConfiguration()
        } else {
            leftRefreshRequested = false
        }
        flushPendingLeftRefresh()
    }

    private suspend fun markLeftPrimed(primed: Boolean) {
        leftPrimed = primed
        if (isIdleSleepState()) {
            if (!primed) {
                leftRefreshRequested = false
            }
            return
        }
        if (primed) {
            logger("[BLE][${Lens.LEFT.logLabel()}] LEFT primed; RIGHT link release enabled")
            flushPendingMirrors()
            flushPendingLeftRefresh()
            if (!rightPrimed && !isIdleSleepState()) {
                scheduleReconnect(Lens.RIGHT, reason = "right_unprimed_after_left_prime")
            }
            attemptReadyConfiguration()
        } else {
            leftRefreshRequested = false
        }
    }

    private suspend fun scheduleLeftRefresh() {
        if (!sessionActive) {
            return
        }
        if (isIdleSleepState()) {
            return
        }
        if (leftRefreshRequested) {
            flushPendingLeftRefresh()
            return
        }
        val commands = listOf(
            byteArrayOf(CMD_CASE_GET.toByte()),
            byteArrayOf(CMD_BATT_GET.toByte(), BATT_SUB_DETAIL.toByte()),
            byteArrayOf(CMD_WEAR_DETECT.toByte()),
        )
        leftRefreshLock.withLock {
            pendingLeftRefresh.clear()
            commands.forEach { pendingLeftRefresh.addLast(it) }
        }
        leftRefreshRequested = true
        flushPendingLeftRefresh()
    }

    private suspend fun flushPendingMirrors() {
        if (isIdleSleepState()) {
            return
        }
        val commands = mutableListOf<PendingCommand>()
        mirrorLock.withLock {
            while (pendingMirrors.isNotEmpty()) {
                commands += pendingMirrors.removeFirst()
            }
        }
        if (commands.isEmpty()) {
            return
        }
        val requeue = mutableListOf<PendingCommand>()
        commands.forEach { command ->
            var current = command
            var pending = false

            if (!current.leftDispatched && current.requiresLeft()) {
                val leftReady = leftSession != null && leftPrimed
                if (!leftReady) {
                    pending = true
                } else {
                    val leftOk = sendTo(Lens.LEFT, current.payload)
                    if (!leftOk) {
                        pending = true
                    } else {
                        current = current.copy(leftDispatched = true)
                    }
                }
            }

            if (!pending && current.requiresRight()) {
                val rightReady = rightSession != null && rightPrimed
                if (!rightReady) {
                    pending = true
                } else {
                    val rightOk = sendTo(Lens.RIGHT, current.payload)
                    if (!rightOk) {
                        pending = true
                    }
                }
            }

            if (pending) {
                requeue.add(current)
            } else if (current.mirror && current.requiresLeft()) {
                flushPendingLeftRefresh()
            }
        }
        if (requeue.isNotEmpty()) {
            mirrorLock.withLock {
                requeue.forEach { pendingMirrors.addLast(it) }
            }
        }
    }

    private suspend fun flushPendingLeftRefresh() {
        if (isIdleSleepState()) {
            return
        }
        if (!rightPrimed || !leftPrimed) {
            return
        }
        val commands = mutableListOf<ByteArray>()
        leftRefreshLock.withLock {
            if (!rightPrimed || !leftPrimed) {
                return@withLock
            }
            while (pendingLeftRefresh.isNotEmpty()) {
                commands += pendingLeftRefresh.removeFirst()
            }
        }
        commands.forEach { sendTo(Lens.LEFT, it) }
    }

    private suspend fun awaitLeftPrimeCompletion() {
        val session = leftSession ?: return
        while (sessionActive && coroutineContext.isActive) {
            if (shouldAbortConnectionAttempt()) {
                return
            }
            val state = session.client.state.value
            val ready = state.readyProbePassed && state.status == LinkStatus.READY
            if (ready) {
                return
            }
            delay(EVEN_SEQUENCE_DELAY_MS)
        }
    }

    private fun scheduleReconnect(side: Lens, reason: String = "unspecified", fromHeartbeat: Boolean = false) {
        if (!sessionActive) {
            return
        }
        if (isIdleSleepState()) {
            logger("[RECONNECT][${side.logLabel()}] gated: idle sleep or wake quiet active; skipping schedule")
            return
        }
        if (reconnectJobs[side]?.isActive == true) {
            return
        }
        if (isOperationActive(connectOpsActive, side)) {
            logger("[RECONNECT][${side.logLabel()}] duplicate connect suppressed")
            return
        }
        val session = when (side) {
            Lens.LEFT -> leftSession
            Lens.RIGHT -> rightSession
        } ?: return

        if (fromHeartbeat && !isIdleSleepState()) {
            setConnectionState(
                when (side) {
                    Lens.LEFT -> State.RecoveringLeft
                    Lens.RIGHT -> State.RecoveringRight
                },
                debounceStability = true,
            )
        }

        reconnectJobs[side] = scope.launch {
            setOperationActive(connectOpsActive, side, true)
            var backoffIndex = reconnectBackoffStep[side]?.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex) ?: 0
            var attempt = 1
            while (sessionActive && isActive) {
                val gate = reconnectGateReason(side)
                if (gate != null) {
                    logger("[RECONNECT][${side.logLabel()}] gated reason=$gate; delaying attempts")
                        reconnectBackoffStep[side] = 0
                        backoffIndex = 0
                        attempt = 1
                        if (gate == "idle_sleep") {
                            reconnectJobs.remove(side)
                            setOperationActive(connectOpsActive, side, false)
                            return@launch
                        }
                        delay(HEARTBEAT_INTERVAL_MS)
                        continue
                    }
                    val delayMs = RECONNECT_BACKOFF_MS.getOrElse(backoffIndex) { RECONNECT_BACKOFF_MS.last() }
                    logger("[RECONNECT][${side.logLabel()}] attempt=$attempt delay=${delayMs}ms reason=$reason")
                    if (delayMs > 0) {
                        delay(delayMs)
                }
                if (shouldAbortConnectionAttempt()) {
                    reconnectJobs.remove(side)
                    setOperationActive(connectOpsActive, side, false)
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                var success = false
                try {
                    if (side == Lens.RIGHT) {
                        var waitLogged = false
                        while (sessionActive && isActive && !leftPrimed) {
                            if (!waitLogged) {
                                waitLogged = true
                                logger("[BLE][${Lens.RIGHT.logLabel()}] Waiting for LEFT prime before RIGHT reconnect")
                            }
                            delay(EVEN_SEQUENCE_DELAY_MS)
                        }
                        if (waitLogged) {
                            logger("[BLE][${Lens.RIGHT.logLabel()}] LEFT prime observed; proceeding with RIGHT reconnect")
                        }
                    }
                    if (shouldRefreshGatt(side, now) && !isOperationActive(gattRefreshOpsActive, side)) {
                        setOperationActive(gattRefreshOpsActive, side, true)
                        invalidateHandshake("gatt_refresh_${side.logLabel()}")
                        try {
                            val recentFailures = failureCountInWindow(side, now)
                            val refreshed = runCatching { session.client.refreshGattCache() }.getOrDefault(false)
                            logger(
                                "[GATT][${side.logLabel()}] refresh triggered after ${recentFailures} failures in ${FAILURE_WINDOW_MS}ms refreshed=$refreshed",
                            )
                            session.client.close()
                            delay(FULL_RECONNECT_DELAY_MS)
                        } finally {
                            setOperationActive(gattRefreshOpsActive, side, false)
                        }
                    }
                    session.client.connectAndSetup()
                    delay(EVEN_SEQUENCE_DELAY_MS)
                    val ready = session.client.probeReady(side)
                    if (ready) {
                        if (!isHeartbeatSuppressed()) {
                            session.client.startKeepAlive()
                        }
                        heartbeatStates[side]?.reset()
                        telemetryRepository.recordTelemetry(side, mapOf("reconnected" to true))
                    }
                    success = ready
                } catch (t: Throwable) {
                    success = false
                }
                if (success) {
                    reconnectBackoffStep[side] = 0
                    reconnectFailures[side]?.clear()
                    requestTelemetryRefresh(side)
                    if (!isIdleSleepState()) {
                        val nextState = when (side) {
                            Lens.RIGHT -> if (leftPrimed && rightPrimed) {
                                State.Stable
                            } else {
                                State.ReadyRight
                            }
                            Lens.LEFT -> if (leftPrimed && rightPrimed) {
                                State.Stable
                            } else {
                                State.LeftOnlineUnprimed
                            }
                        }
                        if (isStabilityState(nextState)) {
                            setConnectionState(nextState, debounceStability = true)
                        } else {
                            _connectionState.value = nextState
                        }
                    }
                    reconnectJobs.remove(side)
                    setOperationActive(connectOpsActive, side, false)
                    return@launch
                }
                recordReconnectFailure(side, now)
                backoffIndex = min(backoffIndex + 1, RECONNECT_BACKOFF_MS.lastIndex)
                reconnectBackoffStep[side] = backoffIndex
                attempt += 1
            }
            reconnectJobs.remove(side)
            if (!isIdleSleepState()) {
                when (side) {
                    Lens.RIGHT -> setConnectionState(State.DegradedLeftOnly, debounceStability = true)
                    Lens.LEFT -> setConnectionState(State.DegradedRightOnly, debounceStability = true)
                }
            }
            setOperationActive(connectOpsActive, side, false)
        }
    }

    private suspend fun publishHeadsetState() {
        val left = latestLeft?.withSide(Lens.LEFT)
        val right = latestRight?.withSide(Lens.RIGHT)
        _headset.value = HeadsetState(pairKey, left, right)
    }

    private fun LensState.withSide(side: Lens): LensState {
        val id = id
        return if (id.side == side) {
            this
        } else {
            copy(id = id.copy(side = side))
        }
    }

    private suspend fun handleSleepEvent(event: BleTelemetryRepository.SleepEvent) {
        val perLens = when (event) {
            is BleTelemetryRepository.SleepEvent.SleepEntered -> event.lens
            is BleTelemetryRepository.SleepEvent.SleepExited -> event.lens
        }
        if (perLens != null) {
            return
        }

        val snapshot = telemetryRepository.snapshot.value
        val now = System.currentTimeMillis()
        when (event) {
            is BleTelemetryRepository.SleepEvent.SleepEntered -> {
                if (!sleeping && _connectionState.value.isActiveState()) {
                    enterIdleSleep(snapshot, now)
                }
            }
            is BleTelemetryRepository.SleepEvent.SleepExited -> {
                if (sleeping) {
                    exitIdleSleep()
                }
            }
        }
    }

    private suspend fun enterIdleSleep(snapshot: BleTelemetryRepository.Snapshot, now: Long) {
        sleeping = true
        wakeJob?.cancel()
        wakeJob = null
        stopHeartbeat()
        heartbeatStates.values.forEach { it.reset() }
        Lens.values().forEach { lens ->
            telemetryRepository.updateHeartbeat(lens, 0, 0, 0)
        }
        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        leftRefreshRequested = false
        awaitingWakeTelemetry = false
        wakeRefreshIssued = false
        wakeQuietActive = false
        wakeQuietUntilElapsed = 0L
        readyConfigIssued = false
        resetWakeTelemetryTracking()
        val cachedLeftMac = leftSession?.id?.mac ?: lastLeftMac
        val cachedRightMac = rightSession?.id?.mac ?: lastRightMac
        logger("[SLEEP] Headset → IdleSleep")
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectBackoffStep.keys.forEach { reconnectBackoffStep[it] = 0 }
        lastLeftMac = cachedLeftMac
        lastRightMac = cachedRightMac
        _connectionState.value = State.IdleSleep
    }

    private suspend fun exitIdleSleep() {
        awaitingWakeTelemetry = true
        wakeQuietActive = true
        wakeQuietUntilElapsed = SystemClock.elapsedRealtime() + CE_IDLE_SLEEP_QUIET_WINDOW_MS
        readyConfigIssued = false
        invalidateHandshake("wake_handshake_restart")
        resetWakeTelemetryTracking()
        logger("[WAKE] Headset → Awake (waiting for telemetry)")
    }

    private suspend fun thawFromTelemetry(side: Lens, event: ClientEvent.Telemetry) {
        if (!sleeping || !awaitingWakeTelemetry) return

        if (isQualifyingWakeTelemetry(event)) {
            wakeTelemetryObserved[side] = true
        } else {
            logger("[WAKE][${side.logLabel()}] Wake telemetry ignored: non-vitals frame while awaiting wake")
            return
        }
        val quietElapsed = !wakeQuietActive || SystemClock.elapsedRealtime() >= wakeQuietUntilElapsed
        if (!quietElapsed) {
            logger("[WAKE][${side.logLabel()}] Wake telemetry blocked: quiet window active (${wakeQuietUntilElapsed - SystemClock.elapsedRealtime()}ms remaining)")
            return
        }
        if (!wakeTelemetryObserved.values.all { it }) {
            val pendingSides = wakeTelemetryObserved.filterValues { observed -> !observed }
                .keys
                .joinToString(",") { pending -> pending.logLabel() }
            logger("[WAKE] Wake telemetry blocked: awaiting vitals from [$pendingSides]")
            return
        }
        sleeping = false
        awaitingWakeTelemetry = false
        wakeQuietActive = false
        wakeQuietUntilElapsed = 0L
        logger("[WAKE] Headset → Awake")
        val leftConnected = latestLeft?.connected == true
        val rightConnected = latestRight?.connected == true
        val leftReady = latestLeft?.isReady == true || leftPrimed
        val rightReady = latestRight?.isReady == true || rightPrimed
        when {
            leftConnected && leftReady && rightConnected && rightReady -> {
                setConnectionState(State.Stable, debounceStability = true)
            }
            leftConnected && !rightConnected -> {
                setConnectionState(State.RecoveringRight, debounceStability = true)
                scheduleReconnect(Lens.RIGHT, reason = "wake_recover")
            }
            rightConnected && !leftConnected -> {
                setConnectionState(State.RecoveringLeft, debounceStability = true)
                scheduleReconnect(Lens.LEFT, reason = "wake_recover")
            }
            else -> {
                setConnectionState(State.RecoveringLeft, debounceStability = true)
                scheduleReconnect(Lens.LEFT, reason = "wake_recover")
                scheduleReconnect(Lens.RIGHT, reason = "wake_recover")
            }
        }
        startHeartbeat()
        attemptReadyConfiguration()
        requestWakeTelemetryRefresh()
    }

    private fun isIdleSleepState(): Boolean {
        return sleeping || awaitingWakeTelemetry || wakeQuietActive || _connectionState.value is State.IdleSleep
    }

    private fun isHeartbeatSuppressed(): Boolean {
        return isIdleSleepState()
    }

    private fun reconnectGateReason(side: Lens): String? {
        if (isIdleSleepState()) {
            return "idle_sleep"
        }
        val snapshot = telemetryRepository.snapshot.value
        if (snapshot.sleepPhase == BleTelemetryRepository.SleepPhase.SLEEP_CONFIRMED) {
            return "ce_sleep"
        }
        val lensSnapshot = snapshot.lensSnapshot(side)
        val inCase = lensSnapshot.inCase ?: snapshot.inCase
        val caseClosed = lensSnapshot.caseOpen == false || snapshot.caseOpen == false
        val wearing = lensSnapshot.foldState
        return when {
            inCase == true && caseClosed -> "in_case"
            wearing == false -> "not_worn"
            else -> null
        }
    }

    private fun isStabilityState(state: State): Boolean {
        return when (state) {
            State.Stable,
            State.RecoveringLeft,
            State.RecoveringRight,
            State.DegradedLeftOnly,
            State.DegradedRightOnly -> true
            else -> false
        }
    }

    private fun setConnectionState(state: State, debounceStability: Boolean = false) {
        val current = _connectionState.value
        if (current == state) return
        val targetIsStability = isStabilityState(state)
        val now = SystemClock.elapsedRealtime()
        if (debounceStability && targetIsStability && isStabilityState(current)) {
            if (now - lastStabilityTransitionAt < STATE_FLAP_DEBOUNCE_MS) {
                return
            }
        }
        if (targetIsStability) {
            lastStabilityTransitionAt = now
        }
        _connectionState.value = state
    }

    private fun isQualifyingWakeTelemetry(event: ClientEvent.Telemetry): Boolean {
        return event.batteryPct != null || event.rssi != null || event.firmware != null
    }

    private fun resetWakeTelemetryTracking() {
        wakeTelemetryObserved.keys.forEach { lens ->
            wakeTelemetryObserved[lens] = false
        }
    }

    private fun State.isActiveState(): Boolean {
        return this !is State.Idle && this !is State.IdleSleep
    }

    private fun shouldAbortConnectionAttempt(): Boolean {
        return sleeping || !sessionActive
    }

    private fun Lens.logLabel(): String = when (this) {
        Lens.LEFT -> "L"
        Lens.RIGHT -> "R"
    }

    private fun BleTelemetryRepository.Snapshot.lensSnapshot(side: Lens): BleTelemetryRepository.LensSnapshot {
        return when (side) {
            Lens.LEFT -> left
            Lens.RIGHT -> right
        }
    }

    companion object {
        private val RECONNECT_BACKOFF_MS = longArrayOf(0L, 500L, 1_000L, 5_000L)
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val MAX_HEARTBEAT_MISSES = 3
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val GATT_REFRESH_THRESHOLD = 3
        private const val FULL_RECONNECT_DELAY_MS = 300L
        private const val STATE_FLAP_DEBOUNCE_MS = HEARTBEAT_INTERVAL_MS / 2
    }

    private data class HeartbeatState(
        var lastPingAt: Long = 0L,
        var lastAckAt: Long = 0L,
        var missedPingCount: Int = 0,
    ) {
        fun reset() {
            lastPingAt = 0L
            lastAckAt = 0L
            missedPingCount = 0
        }

        fun touchPing() {
            lastPingAt = SystemClock.elapsedRealtime()
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        if (isHeartbeatSuppressed()) {
            return
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                if (!sessionActive) {
                    break
                }
                if (isIdleSleepState()) break
                val now = SystemClock.elapsedRealtime()
                Lens.values().forEach { side ->
                    val state = heartbeatStates[side] ?: return@forEach
                    val session = when (side) {
                        Lens.LEFT -> leftSession
                        Lens.RIGHT -> rightSession
                    }
                    if (session == null) {
                        state.reset()
                        return@forEach
                    }
                    if (state.lastPingAt == 0L) {
                        state.lastPingAt = now
                    }
                    val acked = state.lastAckAt >= state.lastPingAt && state.lastPingAt != 0L
                    val rtt = if (acked) state.lastAckAt - state.lastPingAt else null
                    if (!acked && state.lastPingAt != 0L) {
                        state.missedPingCount += 1
                        logger(
                            "[WATCHDOG][${side.name}] missed heartbeat count=${state.missedPingCount} rtt=${rtt ?: -1}"
                        )
                        if (state.missedPingCount >= MAX_HEARTBEAT_MISSES) {
                            scheduleReconnect(side, reason = "heartbeat_miss", fromHeartbeat = true)
                            telemetryRepository.recordTelemetry(
                                side,
                                mapOf(
                                    "missedPingCount" to state.missedPingCount,
                                    "lastPingAt" to state.lastPingAt,
                                ),
                            )
                            state.missedPingCount = 0
                        }
                    } else if (acked) {
                        state.missedPingCount = 0
                    }
                    telemetryRepository.updateHeartbeat(side, state.lastPingAt, state.lastAckAt, state.missedPingCount)
                    logger(
                        "[WATCHDOG][${side.name}] ping=${state.lastPingAt} ack=${state.lastAckAt} miss=${state.missedPingCount}"
                    )
                    state.lastPingAt = now
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun markHeartbeatAck(side: Lens) {
        val state = heartbeatStates[side] ?: return
        state.lastAckAt = SystemClock.elapsedRealtime()
        state.missedPingCount = 0
    }

    private suspend fun invalidateHandshake(reason: String) {
        logger("[BLE] Handshake invalidated reason=$reason")
        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        rightPrimed = false
        leftPrimed = false
        leftRefreshRequested = false
        readyConfigIssued = false
        wakeRefreshIssued = false
    }

    private fun setOperationActive(map: MutableMap<Lens, Boolean>, lens: Lens, active: Boolean) {
        synchronized(operationLock) {
            map[lens] = active
        }
    }

    private fun isOperationActive(map: MutableMap<Lens, Boolean>, lens: Lens): Boolean {
        return synchronized(operationLock) { map[lens] == true }
    }

    private fun resetOperationFlags() {
        synchronized(operationLock) {
            connectOpsActive.keys.forEach { connectOpsActive[it] = false }
            bondOpsActive.keys.forEach { bondOpsActive[it] = false }
            gattRefreshOpsActive.keys.forEach { gattRefreshOpsActive[it] = false }
        }
    }

    private suspend fun handleBondEvent(side: Lens, event: ClientEvent.BondStateChanged) {
        if (event.bonded) {
            bondLossCounters[side] = 0
            bondRetryHistory[side]?.clear()
            logger("[BOND][${side.logLabel()}] bond restored; resetting retry counters")
            return
        }
        invalidateHandshake("bond_loss_${side.logLabel()}")
        val now = SystemClock.elapsedRealtime()
        val history = bondRetryHistory[side] ?: ArrayDeque()
        history.addLast(now)
        while (history.isNotEmpty() && now - history.first() > BOND_RETRY_WINDOW_MS) {
            history.removeFirst()
        }
        bondRetryHistory[side] = history
        val attemptsInWindow = history.size
        bondLossCounters[side] = attemptsInWindow
        logger(
            "[BOND][${side.logLabel()}] bond loss detected attempt=${attemptsInWindow}/" +
                "$BOND_RETRY_MAX_ATTEMPTS window=${BOND_RETRY_WINDOW_MS}ms",
        )
        if (attemptsInWindow > BOND_RETRY_MAX_ATTEMPTS) {
            logger("[BOND][${side.logLabel()}] retry suppressed; max attempts reached in window")
            return
        }
        scope.launch {
            if (isOperationActive(bondOpsActive, side)) {
                logger("[BOND][${side.logLabel()}] duplicate bond attempt suppressed")
                return@launch
            }
            if (isIdleSleepState() || shouldAbortConnectionAttempt()) {
                return@launch
            }
            if (isIdleSleepState()) {
                return@launch
            }
            setOperationActive(bondOpsActive, side, true)
            if (!isIdleSleepState()) {
                _connectionState.value = State.Repriming
            }
            val session = when (side) {
                Lens.LEFT -> leftSession
                Lens.RIGHT -> rightSession
            } ?: return@launch
            try {
                logger(
                    "[BOND][${side.logLabel()}] rebond attempt $attemptsInWindow/" +
                        "$BOND_RETRY_MAX_ATTEMPTS",
                )
                session.client.ensureBonded()
                val ready = session.client.probeReady(side)
                if (ready) {
                    if (!isHeartbeatSuppressed()) {
                        session.client.startKeepAlive()
                    }
                    heartbeatStates[side]?.reset()
                    requestTelemetryRefresh(side)
                    if (latestLeft?.isReady == true && latestRight?.isReady == true) {
                        if (!isIdleSleepState()) {
                            _connectionState.value = State.Stable
                        }
                    }
                }
            } catch (_: Throwable) {
                // reason: Bond recovery failures are handled by watchdog scheduling
            } finally {
                setOperationActive(bondOpsActive, side, false)
            }
        }
    }

    private suspend fun requestTelemetryRefresh(side: Lens) {
        if (isIdleSleepState()) return
        if (!sessionActive) return
        val commands = listOf(
            byteArrayOf(CMD_CASE_GET.toByte()),
            byteArrayOf(CMD_BATT_GET.toByte(), BATT_SUB_DETAIL.toByte()),
            byteArrayOf(CMD_WEAR_DETECT.toByte()),
        )
        val primary = when (side) {
            Lens.RIGHT -> Lens.RIGHT
            Lens.LEFT -> if (rightSession != null) Lens.RIGHT else Lens.LEFT
        }
        commands.forEach { payload ->
            when (primary) {
                Lens.RIGHT -> sendRightOrQueue(payload, mirror = true)
                Lens.LEFT -> sendTo(Lens.LEFT, payload)
            }
        }
        attemptReadyConfiguration()
        telemetryRepository.persistSnapshot()
    }

    private suspend fun requestWakeTelemetryRefresh() {
        if (wakeRefreshIssued) return
        if (!sessionActive) return
        if (isIdleSleepState()) return
        wakeRefreshIssued = true
        val commands = listOf(
            byteArrayOf(CMD_CASE_GET.toByte()),
            byteArrayOf(CMD_BATT_GET.toByte(), BATT_SUB_DETAIL.toByte()),
            byteArrayOf(CMD_WEAR_DETECT.toByte()),
        )
        commands.forEach { payload ->
            sendTo(Lens.RIGHT, payload)
        }
        attemptReadyConfiguration()
    }

    private fun attemptReadyConfiguration() {
        if (readyConfigIssued) return
        if (!sessionActive || isIdleSleepState()) return
        val leftReady = leftPrimed && leftSession != null
        val rightReady = rightPrimed && rightSession != null
        if (!leftReady || !rightReady) return
        readyConfigIssued = true
        scope.launch {
            val autoDisplayPayload = byteArrayOf(
                CMD_NOTIFICATION_AUTO_DISPLAY.toByte(),
                0x01,
                0x05,
            )
            val batteryPayload = byteArrayOf(CMD_BATT_GET.toByte(), BATT_SUB_DETAIL.toByte())
            val uptimePayload = byteArrayOf(OPC_UPTIME.toByte())

            val leftAuto = sendTo(Lens.LEFT, autoDisplayPayload)
            if (leftAuto) {
                telemetryRepository.recordTelemetry(Lens.LEFT, mapOf("autoDisplayReady" to true))
            }
            val rightAuto = sendTo(Lens.RIGHT, autoDisplayPayload)
            if (rightAuto) {
                telemetryRepository.recordTelemetry(Lens.RIGHT, mapOf("autoDisplayReady" to true))
            }

            sendTo(Lens.LEFT, batteryPayload)
            sendTo(Lens.RIGHT, batteryPayload)
            sendTo(Lens.RIGHT, uptimePayload)
        }
    }

    private fun shouldRefreshGatt(side: Lens, now: Long): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        return failureCountInWindow(side, now) >= GATT_REFRESH_THRESHOLD
    }

    private fun recordReconnectFailure(side: Lens, timestamp: Long) {
        if (isIdleSleepState()) return
        val failures = reconnectFailures[side] ?: return
        failures.addLast(timestamp)
        trimFailureWindow(failures, timestamp)
        maybeTriggerFullReset(timestamp)
    }

    private fun failureCountInWindow(side: Lens, now: Long): Int {
        val failures = reconnectFailures[side] ?: return 0
        trimFailureWindow(failures, now)
        return failures.size
    }

    private fun trimFailureWindow(failures: ArrayDeque<Long>, now: Long) {
        while (failures.isNotEmpty() && now - failures.first() > FAILURE_WINDOW_MS) {
            failures.removeFirst()
        }
    }

    private fun maybeTriggerFullReset(now: Long) {
        if (!sessionActive || isIdleSleepState() || fullResetScheduled) return
        val leftCount = failureCountInWindow(Lens.LEFT, now)
        val rightCount = failureCountInWindow(Lens.RIGHT, now)
        if (leftCount >= GATT_REFRESH_THRESHOLD && rightCount >= GATT_REFRESH_THRESHOLD) {
            val leftMac = lastLeftMac
            val rightMac = lastRightMac
            if (leftMac == null || rightMac == null) return
            fullResetScheduled = true
            scope.launch {
                try {
                    logger("[HEADSET] full reset triggered due to repeated GATT failures L=$leftCount R=$rightCount")
                    disconnectHeadset()
                    delay(FULL_RECONNECT_DELAY_MS)
                    connectHeadset(pairKey, leftMac, rightMac)
                } finally {
                    fullResetScheduled = false
                }
            }
        }
    }
}

data class LensClientEvent(
    val side: Lens,
    val event: ClientEvent,
)
