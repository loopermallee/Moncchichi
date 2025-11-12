package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.bluetooth.G1Protocols.BATT_SUB_DETAIL
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_BATT_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_CASE_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_WEAR_DETECT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.ArrayDeque
import kotlin.math.min

/**
 * Coordinates dual-lens connections while honouring Even parity requirements.
 */
class DualLensConnectionOrchestrator(
    private val pairKey: PairKey,
    private val bleFactory: (LensId) -> BleClient,
    private val scope: CoroutineScope,
) {
    sealed class State {
        data object Idle : State()
        data object Scanning : State()
        data object ConnectingRight : State()
        data object ConnectingLeft : State()
        data object RightOnlineUnprimed : State()
        data object LeftOnlineUnprimed : State()
        data object ReadyRight : State()
        data object ReadyBoth : State()
        data object DegradedRightOnly : State()
        data object DegradedLeftOnly : State()
    }

    private data class LensSession(
        val id: LensId,
        val client: BleClient,
    )

    private data class PendingCommand(
        val payload: ByteArray,
        val family: CommandRouter.Family,
        val mirror: Boolean,
    )

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

    private val _telemetry = MutableStateFlow<Map<LensSide, ClientEvent.Telemetry>>(emptyMap())
    val telemetry: StateFlow<Map<LensSide, ClientEvent.Telemetry>> = _telemetry.asStateFlow()

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

    private val stateJobs: MutableMap<LensSide, Job> = mutableMapOf()
    private val eventJobs: MutableMap<LensSide, Job> = mutableMapOf()
    private val reconnectJobs: MutableMap<LensSide, Job> = mutableMapOf()

    @Volatile
    private var sessionActive: Boolean = false

    suspend fun connectHeadset(pairKey: PairKey, leftMac: String, rightMac: String) = coroutineScope {
        require(pairKey == this@DualLensConnectionOrchestrator.pairKey) {
            "Attempted to connect mismatched pair $pairKey for orchestrator ${this@DualLensConnectionOrchestrator.pairKey}"
        }

        disconnectHeadset()

        val leftId = LensId(leftMac, LensSide.LEFT)
        val rightId = LensId(rightMac, LensSide.RIGHT)
        val leftClient = bleFactory(leftId)
        val rightClient = bleFactory(rightId)
        leftSession = LensSession(leftId, leftClient)
        rightSession = LensSession(rightId, rightClient)

        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        rightPrimed = false
        leftPrimed = false

        sessionActive = true
        _connectionState.value = State.ConnectingRight
        _telemetry.value = emptyMap()

        startTracking(LensSide.LEFT, leftClient)
        startTracking(LensSide.RIGHT, rightClient)

        val rightResult = runCatching {
            rightClient.ensureBonded()
            rightClient.connectAndSetup()
            _connectionState.value = State.RightOnlineUnprimed
            val primed = rightClient.probeReady(LensSide.RIGHT)
            if (primed) {
                rightClient.startKeepAlive()
                _connectionState.value = State.ReadyRight
            }
            primed
        }.getOrElse { false }

        val leftResult = runCatching {
            _connectionState.value = State.ConnectingLeft
            leftClient.ensureBonded()
            leftClient.connectAndSetup()
            _connectionState.value = State.LeftOnlineUnprimed
            val primed = leftClient.probeReady(LensSide.LEFT)
            if (primed) {
                leftClient.startKeepAlive()
            }
            primed
        }.getOrElse { false }

        rightPrimed = rightResult
        leftPrimed = leftResult
        if (rightPrimed) {
            flushPendingMirrors()
        }
        flushPendingLeftRefresh()

        when {
            rightResult && leftResult -> _connectionState.value = State.ReadyBoth
            rightResult && !leftResult -> {
                _connectionState.value = State.DegradedRightOnly
                scheduleReconnect(LensSide.LEFT)
            }
            !rightResult && leftResult -> {
                _connectionState.value = State.DegradedLeftOnly
                scheduleReconnect(LensSide.RIGHT)
            }
            else -> {
                scheduleReconnect(LensSide.LEFT)
                scheduleReconnect(LensSide.RIGHT)
            }
        }
    }

    suspend fun disconnectHeadset() {
        sessionActive = false
        _connectionState.value = State.Idle

        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()

        val leftClient = leftSession?.client
        val rightClient = rightSession?.client

        leftClient?.close()
        rightClient?.close()

        eventJobs.values.forEach { it.cancel() }
        eventJobs.clear()

        stateJobs.values.forEach { it.cancel() }
        stateJobs.clear()

        leftSession = null
        rightSession = null

        stateLock.withLock {
            latestLeft = leftClient?.state?.value?.withSide(LensSide.LEFT)
            latestRight = rightClient?.state?.value?.withSide(LensSide.RIGHT)
            publishHeadsetState()
        }

        mirrorLock.withLock { pendingMirrors.clear() }
        leftRefreshLock.withLock { pendingLeftRefresh.clear() }
        rightPrimed = false
        leftPrimed = false
    }

    fun close() {
        runBlocking { disconnectHeadset() }
    }

    private fun startTracking(side: LensSide, client: BleClient) {
        stateJobs.remove(side)?.cancel()
        eventJobs.remove(side)?.cancel()

        stateJobs[side] = scope.launch {
            stateLock.withLock {
                when (side) {
                    LensSide.LEFT -> latestLeft = client.state.value.withSide(side)
                    LensSide.RIGHT -> latestRight = client.state.value.withSide(side)
                }
                publishHeadsetState()
            }
            client.state.collect { state ->
                stateLock.withLock {
                    when (side) {
                        LensSide.LEFT -> latestLeft = state.withSide(side)
                        LensSide.RIGHT -> latestRight = state.withSide(side)
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
        val opcode = payload.first().toInt() and 0xFF
        val subOpcode = payload.getOrNull(1)?.toInt()?.and(0xFF)
        val decision = commandRouter.classify(opcode, subOpcode)
        return when (decision.family) {
            CommandRouter.Family.RIGHT_ONLY -> sendRightOrQueue(payload, decision.mirror)
            CommandRouter.Family.BOTH -> sendBothOrQueue(payload, decision.mirror)
            CommandRouter.Family.EVENTS -> sendEventsImmediate(payload)
            CommandRouter.Family.UNKNOWN -> sendRightOrQueue(payload, decision.mirror)
        }
    }

    private suspend fun handleClientEvent(side: LensSide, event: ClientEvent) {
        when (event) {
            is ClientEvent.Telemetry -> _telemetry.update { current -> current + (side to event) }
            is ClientEvent.ConnectionStateChanged -> {
                if (!event.connected) {
                    when (side) {
                        LensSide.RIGHT -> markRightPrimed(false)
                        LensSide.LEFT -> markLeftPrimed(false)
                    }
                    scheduleReconnect(side)
                    _connectionState.value = when (side) {
                        LensSide.RIGHT -> if (latestLeft?.isReady == true) {
                            State.DegradedLeftOnly
                        } else {
                            State.ConnectingRight
                        }
                        LensSide.LEFT -> if (latestRight?.isReady == true) {
                            State.DegradedRightOnly
                        } else {
                            State.ConnectingLeft
                        }
                    }
                } else {
                    reconnectJobs.remove(side)?.cancel()
                    _connectionState.value = when (side) {
                        LensSide.RIGHT -> if (latestLeft?.isReady == true) {
                            State.ReadyBoth
                        } else {
                            State.ReadyRight
                        }
                        LensSide.LEFT -> if (latestRight?.isReady == true) {
                            State.ReadyBoth
                        } else {
                            State.LeftOnlineUnprimed
                        }
                    }
                    when (side) {
                        LensSide.RIGHT -> markRightPrimed(false)
                        LensSide.LEFT -> {
                            markLeftPrimed(false)
                            scheduleLeftRefresh()
                        }
                    }
                }
            }

            is ClientEvent.ReadyProbeResult -> {
                when (side) {
                    LensSide.RIGHT -> markRightPrimed(event.ready)
                    LensSide.LEFT -> {
                        markLeftPrimed(event.ready)
                        if (event.ready) {
                            scheduleLeftRefresh()
                        }
                    }
                }
            }

            is ClientEvent.Error -> scheduleReconnect(side)
            else -> Unit
        }
        _clientEvents.emit(LensClientEvent(side, event))
    }

    private suspend fun sendRightOrQueue(payload: ByteArray, mirror: Boolean): Boolean {
        val shouldQueue = mirrorLock.withLock {
            val available = rightSession != null
            if (!available || !rightPrimed) {
                pendingMirrors.addLast(PendingCommand(payload.copyOf(), CommandRouter.Family.RIGHT_ONLY, mirror))
                true
            } else {
                false
            }
        }
        if (shouldQueue) {
            return true
        }
        return sendRightImmediate(payload, mirror)
    }

    private suspend fun sendBothOrQueue(payload: ByteArray, mirror: Boolean): Boolean {
        val shouldQueue = mirrorLock.withLock {
            val available = rightSession != null
            if (!available || !rightPrimed) {
                pendingMirrors.addLast(PendingCommand(payload.copyOf(), CommandRouter.Family.BOTH, mirror))
                true
            } else {
                false
            }
        }
        if (shouldQueue) {
            return true
        }
        return sendBothImmediate(payload, mirror)
    }

    private suspend fun sendEventsImmediate(payload: ByteArray): Boolean {
        var sent = false
        rightSession?.let {
            sent = sendTo(LensSide.RIGHT, payload) || sent
        }
        leftSession?.let {
            sent = sendTo(LensSide.LEFT, payload) || sent
        }
        return sent
    }

    private suspend fun sendRightImmediate(payload: ByteArray, mirror: Boolean): Boolean {
        val success = sendTo(LensSide.RIGHT, payload)
        if (success) {
            mirrorToLeft(payload, mirror)
        }
        return success
    }

    private suspend fun sendBothImmediate(payload: ByteArray, mirror: Boolean): Boolean {
        val rightOk = sendTo(LensSide.RIGHT, payload)
        if (!rightOk) {
            return false
        }
        mirrorToLeft(payload, true)
        if (mirror) {
            flushPendingLeftRefresh()
        }
        return true
    }

    private suspend fun processPendingImmediate(command: PendingCommand) {
        when (command.family) {
            CommandRouter.Family.RIGHT_ONLY -> sendRightImmediate(command.payload, command.mirror)
            CommandRouter.Family.BOTH -> sendBothImmediate(command.payload, command.mirror)
            CommandRouter.Family.EVENTS -> sendEventsImmediate(command.payload)
            CommandRouter.Family.UNKNOWN -> sendRightImmediate(command.payload, command.mirror)
        }
    }

    private suspend fun mirrorToLeft(payload: ByteArray, force: Boolean) {
        if (!force) {
            return
        }
        val shouldQueue = leftRefreshLock.withLock {
            val available = leftSession != null
            if (!available || !leftPrimed || !rightPrimed) {
                pendingLeftRefresh.addLast(payload.copyOf())
                true
            } else {
                false
            }
        }
        if (shouldQueue) {
            flushPendingLeftRefresh()
        } else {
            sendTo(LensSide.LEFT, payload)
        }
    }

    private suspend fun sendTo(side: LensSide, payload: ByteArray): Boolean {
        val session = when (side) {
            LensSide.LEFT -> leftSession
            LensSide.RIGHT -> rightSession
        } ?: return false
        return runCatching { session.client.sendCommand(payload.copyOf()) }.getOrElse { false }
    }

    private suspend fun markRightPrimed(primed: Boolean) {
        rightPrimed = primed
        if (primed) {
            flushPendingMirrors()
        }
        flushPendingLeftRefresh()
    }

    private suspend fun markLeftPrimed(primed: Boolean) {
        leftPrimed = primed
        if (primed) {
            flushPendingLeftRefresh()
        }
    }

    private suspend fun scheduleLeftRefresh() {
        if (!sessionActive) {
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
        flushPendingLeftRefresh()
    }

    private suspend fun flushPendingMirrors() {
        if (!rightPrimed) {
            return
        }
        val commands = mutableListOf<PendingCommand>()
        mirrorLock.withLock {
            if (!rightPrimed) {
                return@withLock
            }
            while (pendingMirrors.isNotEmpty()) {
                commands += pendingMirrors.removeFirst()
            }
        }
        commands.forEach { processPendingImmediate(it) }
    }

    private suspend fun flushPendingLeftRefresh() {
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
        commands.forEach { sendTo(LensSide.LEFT, it) }
    }

    private fun scheduleReconnect(side: LensSide) {
        if (!sessionActive) {
            return
        }
        if (reconnectJobs[side]?.isActive == true) {
            return
        }
        val session = when (side) {
            LensSide.LEFT -> leftSession
            LensSide.RIGHT -> rightSession
        } ?: return

        reconnectJobs[side] = scope.launch {
            var delayMs = INITIAL_RECONNECT_DELAY_MS
            while (sessionActive && isActive) {
                val success = runCatching {
                    session.client.connectAndSetup()
                    val ready = session.client.probeReady(side)
                    if (ready) {
                        session.client.startKeepAlive()
                    }
                    ready
                }.getOrElse { false }
                if (success) {
                    _connectionState.value = when (side) {
                        LensSide.RIGHT -> if (latestLeft?.isReady == true) {
                            State.ReadyBoth
                        } else {
                            State.ReadyRight
                        }
                        LensSide.LEFT -> if (latestRight?.isReady == true) {
                            State.ReadyBoth
                        } else {
                            State.LeftOnlineUnprimed
                        }
                    }
                    reconnectJobs.remove(side)
                    return@launch
                }
                delay(delayMs)
                delayMs = min(delayMs * 2, MAX_RECONNECT_DELAY_MS)
            }
            reconnectJobs.remove(side)
            when (side) {
                LensSide.RIGHT -> _connectionState.value = State.DegradedLeftOnly
                LensSide.LEFT -> _connectionState.value = State.DegradedRightOnly
            }
        }
    }

    private suspend fun publishHeadsetState() {
        val left = latestLeft?.withSide(LensSide.LEFT)
        val right = latestRight?.withSide(LensSide.RIGHT)
        _headset.value = HeadsetState(pairKey, left, right)
    }

    private fun LensState.withSide(side: LensSide): LensState {
        val id = id
        return if (id.side == side) {
            this
        } else {
            copy(id = id.copy(side = side))
        }
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 8_000L
    }
}

data class LensClientEvent(
    val side: LensSide,
    val event: ClientEvent,
)
