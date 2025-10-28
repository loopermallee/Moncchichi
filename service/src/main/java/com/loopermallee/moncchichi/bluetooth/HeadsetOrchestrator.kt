package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * Step 1 skeleton orchestrator that combines the per-lens client states.
 * Functional integration will follow after the legacy single-device path is refactored.
 */
class HeadsetOrchestrator(
    private val pairKey: PairKey,
    private val leftClient: BleClient,
    private val rightClient: BleClient,
    scope: CoroutineScope,
) {
    private val _headset = MutableStateFlow(
        HeadsetState(
            pair = pairKey,
            left = leftClient.state.value,
            right = rightClient.state.value,
        ),
    )
    val headset: StateFlow<HeadsetState> = _headset

    private val _clientEvents = MutableSharedFlow<LensClientEvent>(extraBufferCapacity = 32)
    val clientEvents: SharedFlow<LensClientEvent> = _clientEvents.asSharedFlow()

    private val _reconnectSignals = MutableSharedFlow<LensSide>(extraBufferCapacity = 4)
    val reconnectSignals: SharedFlow<LensSide> = _reconnectSignals.asSharedFlow()

    private val _telemetry = MutableStateFlow<Map<LensSide, ClientEvent.Telemetry>>(emptyMap())
    val telemetry: StateFlow<Map<LensSide, ClientEvent.Telemetry>> = _telemetry.asStateFlow()

    private val combineJob: Job = scope.launch {
        combine(leftClient.state, rightClient.state) { l, r ->
            HeadsetState(pairKey, l, r)
        }.collect { _headset.value = it }
    }

    private val eventJobs: List<Job> = listOf(
        scope.launch { leftClient.events.collect { handleClientEvent(LensSide.LEFT, it) } },
        scope.launch { rightClient.events.collect { handleClientEvent(LensSide.RIGHT, it) } },
    )

    suspend fun connectBoth() = coroutineScope {
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
            _reconnectSignals.emit(LensSide.LEFT)
        }
        if (!rightReady.await()) {
            _reconnectSignals.emit(LensSide.RIGHT)
        }
    }

    suspend fun disconnectBoth() {
        leftClient.close()
        rightClient.close()
    }

    fun close() {
        combineJob.cancel()
        eventJobs.forEach { it.cancel() }
        leftClient.close()
        rightClient.close()
    }

    private suspend fun handleClientEvent(side: LensSide, event: ClientEvent) {
        when (event) {
            is ClientEvent.Telemetry -> _telemetry.update { current -> current + (side to event) }
            is ClientEvent.ConnectionStateChanged -> if (!event.connected) {
                _reconnectSignals.emit(side)
            }
            else -> Unit
        }
        _clientEvents.emit(LensClientEvent(side, event))
    }
}

data class LensClientEvent(
    val side: LensSide,
    val event: ClientEvent,
)
