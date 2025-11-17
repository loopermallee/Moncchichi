package com.loopermallee.moncchichi.bluetooth

import android.os.SystemClock
import com.loopermallee.moncchichi.bluetooth.G1Protocols.BATT_SUB_DETAIL
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_BATT_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_CASE_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_WEAR_DETECT
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

    @Volatile
    private var sessionActive: Boolean = false

    private var heartbeatJob: Job? = null
    private var wakeJob: Job? = null
    private var lastLeftMac: String? = null
    private var lastRightMac: String? = null

    @Volatile
    private var sleeping: Boolean = false

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

        sessionActive = true
        _connectionState.value = State.ConnectingLeft
        _telemetry.value = emptyMap()
        startHeartbeat()

        if (shouldAbortConnectionAttempt()) {
            return@coroutineScope
        }

        startTracking(Lens.LEFT, leftClient)
        startTracking(Lens.RIGHT, rightClient)

        val leftResult = runCatching {
            leftClient.ensureBonded()
            delay(EVEN_SEQUENCE_DELAY_MS)
            leftClient.connectAndSetup()
            _connectionState.value = State.LeftOnlineUnprimed
            delay(EVEN_SEQUENCE_DELAY_MS)
            val primed = leftClient.probeReady(Lens.LEFT)
            if (primed) {
                leftClient.startKeepAlive()
            }
            primed
        }.getOrElse { false }

        leftPrimed = leftResult
        if (leftPrimed) {
            flushPendingMirrors()
        } else {
            if (!isIdleSleepState()) {
                _connectionState.value = State.DegradedRightOnly
            }
            scheduleReconnect(Lens.LEFT)
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
        val rightResult = runCatching {
            rightClient.ensureBonded()
            delay(EVEN_SEQUENCE_DELAY_MS)
            rightClient.connectAndSetup()
            _connectionState.value = State.RightOnlineUnprimed
            delay(EVEN_SEQUENCE_DELAY_MS)
            val primed = rightClient.probeReady(Lens.RIGHT)
            if (primed) {
                rightClient.startKeepAlive()
                _connectionState.value = State.ReadyRight
            }
            primed
        }.getOrElse { false }

        rightPrimed = rightResult
        if (rightPrimed) {
            flushPendingMirrors()
        } else {
            if (!isIdleSleepState()) {
                _connectionState.value = State.DegradedLeftOnly
            }
            scheduleReconnect(Lens.RIGHT)
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
            _connectionState.value = State.Stable
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

    private suspend fun sendDuringIdleSleep(payload: ByteArray): Boolean {
        val opcode = payload.first().toInt() and 0xFF
        val subOpcode = payload.getOrNull(1)?.toInt()?.and(0xFF)
        val decision = commandRouter.classify(opcode, subOpcode)
        return when (decision.family) {
            CommandRouter.Family.RIGHT_ONLY -> sendRightImmediate(payload)
            CommandRouter.Family.BOTH -> {
                val leftOk = leftSession?.let { sendTo(Lens.LEFT, payload) } ?: false
                val rightOk = rightSession?.let { sendTo(Lens.RIGHT, payload) } ?: false
                leftOk || rightOk
            }
            CommandRouter.Family.EVENTS -> sendEventsImmediate(payload)
            CommandRouter.Family.UNKNOWN -> sendRightImmediate(payload)
        }
    }

    private suspend fun handleClientEvent(side: Lens, event: ClientEvent) {
        when (event) {
            is ClientEvent.Telemetry -> _telemetry.update { current -> current + (side to event) }
            is ClientEvent.ConnectionStateChanged -> {
                if (!event.connected) {
                    when (side) {
                        Lens.RIGHT -> markRightPrimed(false)
                        Lens.LEFT -> markLeftPrimed(false)
                    }
                    scheduleReconnect(side)
                    if (!isIdleSleepState()) {
                        _connectionState.value = when (side) {
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
                    }
                    heartbeatStates[side]?.reset()
                    telemetryRepository.recordTelemetry(
                        side,
                        mapOf("connected" to false),
                    )
                    if (!isIdleSleepState() && latestLeft?.connected != true && latestRight?.connected != true) {
                        _connectionState.value = State.Idle
                    }
                } else {
                    reconnectJobs.remove(side)?.cancel()
                    val nextState = when (side) {
                        Lens.RIGHT -> if (latestLeft?.isReady == true) {
                            _connectionState.value = State.ReadyBoth
                            State.Stable
                        } else {
                            State.ReadyRight
                        }
                        Lens.LEFT -> if (latestRight?.isReady == true) {
                            _connectionState.value = State.ReadyBoth
                            State.Stable
                        } else {
                            State.LeftOnlineUnprimed
                        }
                    }
                    if (!isIdleSleepState()) {
                        _connectionState.value = nextState
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
                    if (event.ready && latestLeft?.isReady == true && latestRight?.isReady == true) {
                        _connectionState.value = State.ReadyBoth
                        _connectionState.value = State.Stable
                    } else if (!event.ready) {
                        _connectionState.value = State.Repriming
                    }
                }
                if (event.ready) {
                    requestTelemetryRefresh(side)
                }
            }

            is ClientEvent.Error -> scheduleReconnect(side)
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
            is ClientEvent.KeepAliveStarted -> heartbeatStates[side]?.touchPing()
            else -> Unit
        }
        _clientEvents.emit(LensClientEvent(side, event))
    }

    private suspend fun sendRightOrQueue(payload: ByteArray, mirror: Boolean): Boolean {
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
        return sendTo(Lens.RIGHT, payload)
    }

    private suspend fun sendTo(side: Lens, payload: ByteArray): Boolean {
        val session = when (side) {
            Lens.LEFT -> leftSession
            Lens.RIGHT -> rightSession
        } ?: return false
        val success = runCatching { session.client.sendCommand(payload.copyOf()) }.getOrElse { false }
        if (!success) {
            scheduleReconnect(side)
        }
        return success
    }

    private suspend fun markRightPrimed(primed: Boolean) {
        rightPrimed = primed
        if (isIdleSleepState()) {
            if (!primed) {
                leftRefreshRequested = false
            }
            return
        }
        if (primed) {
            flushPendingMirrors()
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
                scheduleReconnect(Lens.RIGHT)
            }
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

    private fun scheduleReconnect(side: Lens, fromHeartbeat: Boolean = false) {
        if (!sessionActive) {
            return
        }
        if (isIdleSleepState()) {
            return
        }
        if (reconnectJobs[side]?.isActive == true) {
            return
        }
        val session = when (side) {
            Lens.LEFT -> leftSession
            Lens.RIGHT -> rightSession
        } ?: return

        if (fromHeartbeat && !isIdleSleepState()) {
            _connectionState.value = when (side) {
                Lens.LEFT -> State.RecoveringLeft
                Lens.RIGHT -> State.RecoveringRight
            }
        }

        reconnectJobs[side] = scope.launch {
            var backoffIndex = reconnectBackoffStep[side] ?: 0
            while (sessionActive && isActive) {
                val delayMs = RECONNECT_BACKOFF_MS.getOrElse(backoffIndex) { RECONNECT_BACKOFF_MS.last() }
                if (delayMs > 0) {
                    delay(delayMs)
                }
                if (shouldAbortConnectionAttempt()) {
                    reconnectJobs.remove(side)
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
                    if (shouldRefreshGatt(side, now)) {
                        logger("[BLE][${side.name}] refreshing GATT cache before reconnect")
                        session.client.close()
                        delay(FULL_RECONNECT_DELAY_MS)
                    }
                    session.client.connectAndSetup()
                    delay(EVEN_SEQUENCE_DELAY_MS)
                    val ready = session.client.probeReady(side)
                    if (ready) {
                        session.client.startKeepAlive()
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
                        _connectionState.value = when (side) {
                            Lens.RIGHT -> if (latestLeft?.isReady == true) {
                                State.Stable
                            } else {
                                State.ReadyRight
                            }
                            Lens.LEFT -> if (latestRight?.isReady == true) {
                                State.Stable
                            } else {
                                State.LeftOnlineUnprimed
                            }
                        }
                    }
                    reconnectJobs.remove(side)
                    return@launch
                }
                recordReconnectFailure(side, now)
                backoffIndex = min(backoffIndex + 1, RECONNECT_BACKOFF_MS.lastIndex)
                reconnectBackoffStep[side] = backoffIndex
            }
            reconnectJobs.remove(side)
            if (!isIdleSleepState()) {
                when (side) {
                    Lens.RIGHT -> _connectionState.value = State.DegradedLeftOnly
                    Lens.LEFT -> _connectionState.value = State.DegradedRightOnly
                }
            }
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
            logger("[SLEEP][${perLens.logLabel()}] Ignoring per-lens SleepEvent; expecting headset scope")
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
                    exitIdleSleep(snapshot)
                }
            }
        }
    }

    private suspend fun enterIdleSleep(snapshot: BleTelemetryRepository.Snapshot, now: Long) {
        sleeping = true
        wakeJob?.cancel()
        wakeJob = null
        stopHeartbeat()
        val cachedLeftMac = leftSession?.id?.mac ?: lastLeftMac
        val cachedRightMac = rightSession?.id?.mac ?: lastRightMac
        val leftReason = resolveSleepReason(snapshot, Lens.LEFT, now)
        val rightReason = resolveSleepReason(snapshot, Lens.RIGHT, now)
        logger("[SLEEP] Headset → IdleSleep")
        logger("[SLEEP][${Lens.LEFT.logLabel()}] $leftReason")
        logger("[SLEEP][${Lens.RIGHT.logLabel()}] $rightReason")
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectBackoffStep.keys.forEach { reconnectBackoffStep[it] = 0 }
        lastLeftMac = cachedLeftMac
        lastRightMac = cachedRightMac
        _connectionState.value = State.IdleSleep
    }

    private suspend fun exitIdleSleep(snapshot: BleTelemetryRepository.Snapshot) {
        sleeping = false
        val leftReason = resolveWakeReason(snapshot, Lens.LEFT)
        val rightReason = resolveWakeReason(snapshot, Lens.RIGHT)
        logger("[WAKE] Headset → Awake")
        logger("[WAKE][${Lens.LEFT.logLabel()}] $leftReason")
        logger("[WAKE][${Lens.RIGHT.logLabel()}] $rightReason")
        val leftConnected = latestLeft?.connected == true
        val rightConnected = latestRight?.connected == true
        val leftReady = latestLeft?.isReady == true || leftPrimed
        val rightReady = latestRight?.isReady == true || rightPrimed
        when {
            leftConnected && leftReady && rightConnected && rightReady -> {
                _connectionState.value = State.Stable
                requestTelemetryRefresh(Lens.RIGHT)
            }
            leftConnected && !rightConnected -> {
                _connectionState.value = State.RecoveringRight
                scheduleReconnect(Lens.RIGHT)
            }
            rightConnected && !leftConnected -> {
                _connectionState.value = State.RecoveringLeft
                scheduleReconnect(Lens.LEFT)
            }
            !leftConnected && !rightConnected -> {
                _connectionState.value = State.ConnectingLeft
                scheduleReconnect(Lens.LEFT)
                wakeJob = scope.launch {
                    while (sessionActive && !sleeping) {
                        if (latestLeft?.connected == true && (latestLeft?.isReady == true || leftPrimed)) {
                            scheduleReconnect(Lens.RIGHT)
                            break
                        }
                        delay(EVEN_SEQUENCE_DELAY_MS)
                    }
                }
            }
            else -> {
                _connectionState.value = State.Repriming
                requestTelemetryRefresh(Lens.RIGHT)
            }
        }
        startHeartbeat()
    }

    private fun isIdleSleepState(): Boolean {
        return sleeping || _connectionState.value is State.IdleSleep
    }

    private fun State.isActiveState(): Boolean {
        return this !is State.Idle && this !is State.IdleSleep
    }

    private fun shouldAbortConnectionAttempt(): Boolean {
        return sleeping || !sessionActive
    }

    private fun resolveSleepReason(
        snapshot: BleTelemetryRepository.Snapshot,
        side: Lens,
        now: Long,
    ): String {
        val lensSnapshot = when (side) {
            Lens.LEFT -> snapshot.left
            Lens.RIGHT -> snapshot.right
        }
        val caseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        if (caseOpen == false) {
            return "CaseClosed"
        }
        val inCase = lensSnapshot.inCase ?: snapshot.inCase
        if (inCase == true) {
            return "InCase"
        }
        val foldState = lensSnapshot.foldState ?: snapshot.foldState
        if (foldState == true) {
            return "Folded"
        }
        val lastVitals = lensSnapshot.lastVitalsTimestamp ?: snapshot.lastVitalsTimestamp
        val vitalsExpired = lastVitals?.let { now - it > SLEEP_VITALS_TIMEOUT_MS } ?: false
        if (vitalsExpired) {
            return "VitalsTimeout"
        }
        return "Unknown"
    }

    private fun resolveWakeReason(
        snapshot: BleTelemetryRepository.Snapshot,
        side: Lens,
    ): String {
        val lensSnapshot = when (side) {
            Lens.LEFT -> snapshot.left
            Lens.RIGHT -> snapshot.right
        }
        val caseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        if (caseOpen == true) {
            return "CaseOpen"
        }
        val inCase = lensSnapshot.inCase ?: snapshot.inCase
        if (inCase == false) {
            return "CaseOpen"
        }
        val foldState = lensSnapshot.foldState ?: snapshot.foldState
        if (foldState == false) {
            return "Unfolded"
        }
        return "Signal"
    }

    private fun Lens.logLabel(): String = when (this) {
        Lens.LEFT -> "L"
        Lens.RIGHT -> "R"
    }

    companion object {
        private val RECONNECT_BACKOFF_MS = longArrayOf(0L, 500L, 1_000L, 5_000L)
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val MAX_HEARTBEAT_MISSES = 3
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val FULL_RECONNECT_DELAY_MS = 300L
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
        if (isIdleSleepState()) {
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
                            scheduleReconnect(side, fromHeartbeat = true)
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

    private fun handleBondEvent(side: Lens, event: ClientEvent.BondStateChanged) {
        if (event.bonded) {
            bondLossCounters[side] = 0
            return
        }
        val failures = (bondLossCounters[side] ?: 0) + 1
        bondLossCounters[side] = failures
        if (failures < 3) {
            return
        }
        bondLossCounters[side] = 0
        scope.launch {
            if (isIdleSleepState() || shouldAbortConnectionAttempt()) {
                return@launch
            }
            if (!isIdleSleepState()) {
                _connectionState.value = State.Repriming
            }
            val session = when (side) {
                Lens.LEFT -> leftSession
                Lens.RIGHT -> rightSession
            } ?: return@launch
            try {
                session.client.ensureBonded()
                val ready = session.client.probeReady(side)
                if (ready) {
                    session.client.startKeepAlive()
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
        telemetryRepository.persistSnapshot()
    }

    private fun shouldRefreshGatt(side: Lens, now: Long): Boolean {
        if (isIdleSleepState()) {
            return false
        }
        val failures = reconnectFailures[side] ?: return false
        while (failures.isNotEmpty() && now - failures.first() > FAILURE_WINDOW_MS) {
            failures.removeFirst()
        }
        return failures.size > 3
    }

    private fun recordReconnectFailure(side: Lens, timestamp: Long) {
        val failures = reconnectFailures[side] ?: return
        failures.addLast(timestamp)
        while (failures.isNotEmpty() && timestamp - failures.first() > FAILURE_WINDOW_MS) {
            failures.removeFirst()
        }
    }
}

data class LensClientEvent(
    val side: Lens,
    val event: ClientEvent,
)
