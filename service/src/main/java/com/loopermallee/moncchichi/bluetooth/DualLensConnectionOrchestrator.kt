package com.loopermallee.moncchichi.bluetooth

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

    private suspend fun handleClientEvent(side: LensSide, event: ClientEvent) {
        when (event) {
            is ClientEvent.Telemetry -> _telemetry.update { current -> current + (side to event) }
            is ClientEvent.ConnectionStateChanged -> {
                if (!event.connected) {
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
                }
            }

            is ClientEvent.Error -> scheduleReconnect(side)
            else -> Unit
        }
        _clientEvents.emit(LensClientEvent(side, event))
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
