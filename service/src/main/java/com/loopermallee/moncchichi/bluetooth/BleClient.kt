package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Step 1 stub: interface representing a per-lens BLE client with a minimal stub implementation.
 * Real connections will replace this in Step 2 when the legacy DeviceManager is refactored.
 */
interface BleClient {
    val state: StateFlow<LensState>
    suspend fun bondAndConnect()
    suspend fun disconnect()
    fun close()
}

class BleClientStub(private val initial: LensState) : BleClient {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LensState> = _state

    override suspend fun bondAndConnect() {
        _state.value = _state.value.copy(
            status = LinkStatus.READY,
            bonded = true,
            connected = true,
            mtu = _state.value.mtu ?: 251,
            readyProbePassed = true,
            lastSeenRssi = _state.value.lastSeenRssi ?: -55,
        )
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(
            status = LinkStatus.DISCONNECTED,
            connected = false,
            readyProbePassed = false,
        )
    }

    override fun close() {
        // No-op for the stub implementation.
    }
}
