package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

    private val job = scope.launch {
        combine(leftClient.state, rightClient.state) { l, r ->
            HeadsetState(pairKey, l, r)
        }.collect { _headset.value = it }
    }

    suspend fun connectBoth() {
        leftClient.bondAndConnect()
        rightClient.bondAndConnect()
    }

    suspend fun disconnectBoth() {
        leftClient.disconnect()
        rightClient.disconnect()
    }

    fun close() {
        job.cancel()
        leftClient.close()
        rightClient.close()
    }
}
