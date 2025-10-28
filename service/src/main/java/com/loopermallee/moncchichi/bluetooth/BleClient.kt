package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Step 1 stub: interface representing a per-lens BLE client with a minimal stub implementation.
 * Real connections will replace this in Step 2 when the legacy DeviceManager is refactored.
 */
interface BleClient {
    val state: StateFlow<LensState>
    val events: Flow<ClientEvent>

    suspend fun ensureBonded()
    suspend fun connectAndSetup(targetMtu: Int = 251)
    suspend fun probeReady(side: LensSide): Boolean
    fun startKeepAlive()
    fun close()
}

sealed interface ClientEvent {
    data class BondStateChanged(val bonded: Boolean) : ClientEvent
    data class ConnectionStateChanged(val connected: Boolean) : ClientEvent
    data class ServicesReady(val mtu: Int) : ClientEvent
    data class ReadyProbeResult(val side: LensSide, val ready: Boolean) : ClientEvent
    data class Telemetry(val batteryPct: Int?, val firmware: String?, val rssi: Int?) : ClientEvent
    object KeepAliveStarted : ClientEvent
    data class Error(val message: String, val cause: Throwable? = null) : ClientEvent
}

class BleClientStub(private val initial: LensState) : BleClient {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LensState> = _state

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)
    override val events: Flow<ClientEvent> = _events.asSharedFlow()

    override suspend fun ensureBonded() {
        val bondedState = _state.value.copy(
            status = LinkStatus.BONDING,
            bonded = true,
        )
        _state.value = bondedState
        _events.emit(ClientEvent.BondStateChanged(bonded = true))
    }

    override suspend fun connectAndSetup(targetMtu: Int) {
        val mtu = if (targetMtu <= 0) 251 else targetMtu
        val servicesReady = _state.value.copy(
            status = LinkStatus.SERVICES_READY,
            connected = true,
            mtu = mtu,
        )
        _state.value = servicesReady
        _events.emit(ClientEvent.ConnectionStateChanged(connected = true))
        _events.emit(ClientEvent.ServicesReady(mtu = mtu))
    }

    override suspend fun probeReady(side: LensSide): Boolean {
        val readyState = _state.value.copy(
            status = LinkStatus.READY,
            readyProbePassed = true,
            lastSeenRssi = _state.value.lastSeenRssi ?: -55,
            batteryPct = _state.value.batteryPct ?: 100,
            firmware = _state.value.firmware ?: "stub-1.0",
        )
        _state.value = readyState
        _events.emit(ClientEvent.ReadyProbeResult(side = side, ready = true))
        _events.emit(
            ClientEvent.Telemetry(
                batteryPct = readyState.batteryPct,
                firmware = readyState.firmware,
                rssi = readyState.lastSeenRssi,
            ),
        )
        return true
    }

    override fun startKeepAlive() {
        _events.tryEmit(ClientEvent.KeepAliveStarted)
    }

    override fun close() {
        val closedState = _state.value.copy(
            status = LinkStatus.DISCONNECTED,
            connected = false,
            readyProbePassed = false,
        )
        _state.value = closedState
        _events.tryEmit(ClientEvent.ConnectionStateChanged(connected = false))
    }
}
