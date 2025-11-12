package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.core.BmpPacketBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

class BleClientImpl(initialState: LensState) : BleClient {
    constructor(lensId: LensId) : this(LensState(lensId))

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<LensState> = _state

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 32)
    override val events: Flow<ClientEvent> = _events.asSharedFlow()

    override suspend fun ensureBonded() {
        val next = _state.value.copy(
            status = LinkStatus.BONDING,
            bonded = true,
        )
        _state.value = next
        _events.emit(ClientEvent.BondStateChanged(bonded = true))
    }

    override suspend fun connectAndSetup(targetMtu: Int) {
        val mtu = if (targetMtu <= 0) 251 else targetMtu
        val connecting = _state.value.copy(status = LinkStatus.CONNECTING)
        _state.value = connecting
        val ready = connecting.copy(
            status = LinkStatus.SERVICES_READY,
            connected = true,
            mtu = mtu,
        )
        _state.value = ready
        _events.emit(ClientEvent.ConnectionStateChanged(connected = true))
        _events.emit(ClientEvent.ServicesReady(mtu = mtu))
    }

    override suspend fun probeReady(lens: Lens): Boolean {
        val ready = _state.value.copy(
            status = LinkStatus.READY,
            readyProbePassed = true,
            lastSeenRssi = _state.value.lastSeenRssi ?: -58,
        )
        _state.value = ready
        _events.emit(ClientEvent.ReadyProbeResult(lens = lens, ready = true))
        _events.emit(
            ClientEvent.Telemetry(
                batteryPct = ready.batteryPct,
                firmware = ready.firmware,
                rssi = ready.lastSeenRssi,
            ),
        )
        return true
    }

    override suspend fun sendCommand(payload: ByteArray): Boolean {
        return true
    }

    override suspend fun sendImage(imageBytes: ByteArray): Boolean {
        BmpPacketBuilder().apply {
            buildFrames(imageBytes)
            buildCrcFrame(imageBytes)
        }
        return true
    }

    override fun startKeepAlive() {
        _events.tryEmit(ClientEvent.KeepAliveStarted)
    }

    override fun close() {
        val closed = _state.value.copy(
            status = LinkStatus.DISCONNECTED,
            connected = false,
            readyProbePassed = false,
        )
        _state.value = closed
        _events.tryEmit(ClientEvent.ConnectionStateChanged(connected = false))
    }
}
