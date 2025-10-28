package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Step 1 skeleton orchestrator that combines the per-lens client states.
 * Functional integration will follow after the legacy single-device path is refactored.
 */
class HeadsetOrchestrator(
    private val pairKey: PairKey,
    private val bleFactory: (LensId) -> BleClient,
    private val scope: CoroutineScope,
) {
    private data class LensSession(
        val id: LensId,
        val client: BleClient,
    )

    private val stateLock = Mutex()

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
        require(pairKey == this@HeadsetOrchestrator.pairKey) {
            "Attempted to connect mismatched pair $pairKey for orchestrator ${this@HeadsetOrchestrator.pairKey}"
        }

        disconnectHeadset()

        val leftId = LensId(leftMac, LensSide.LEFT)
        val rightId = LensId(rightMac, LensSide.RIGHT)
        val leftClient = bleFactory(leftId)
        val rightClient = bleFactory(rightId)
        leftSession = LensSession(leftId, leftClient)
        rightSession = LensSession(rightId, rightClient)

        sessionActive = true
        _telemetry.value = emptyMap()

        startTracking(LensSide.LEFT, leftClient)
        startTracking(LensSide.RIGHT, rightClient)

        awaitAll(
            async { leftClient.ensureBonded() },
            async { rightClient.ensureBonded() },
        )

        awaitAll(
            async { leftClient.connectAndSetup() },
            async { rightClient.connectAndSetup() },
        )

        val leftReady = async { leftClient.probeReady(LensSide.LEFT) }
        val rightReady = async { rightClient.probeReady(LensSide.RIGHT) }

        leftClient.startKeepAlive()
        rightClient.startKeepAlive()

        if (!leftReady.await()) {
            scheduleReconnect(LensSide.LEFT)
        }
        if (!rightReady.await()) {
            scheduleReconnect(LensSide.RIGHT)
        }
    }

    suspend fun disconnectHeadset() {
        sessionActive = false

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
                } else {
                    reconnectJobs.remove(side)?.cancel()
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
                    reconnectJobs.remove(side)
                    return@launch
                }
                delay(delayMs)
                delayMs = min(delayMs * 2, MAX_RECONNECT_DELAY_MS)
            }
            reconnectJobs.remove(side)
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
        private const val INITIAL_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 10_000L
    }
}

data class LensClientEvent(
    val side: LensSide,
    val event: ClientEvent,
)
