package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Lightweight bridge on top of [DeviceManager] to expose parsed G1 protocol events
 * and helper writers for the developer console.
 */
class DeviceIoFacade(
    private val deviceManager: DeviceManager,
    private val scope: CoroutineScope,
) {
    private val _inbound = MutableSharedFlow<G1Inbound>(extraBufferCapacity = 32)
    val inbound: SharedFlow<G1Inbound> = _inbound.asSharedFlow()

    private var inboundJob: Job? = null

    fun start() {
        if (inboundJob != null) return
        inboundJob = scope.launch {
            deviceManager.notifications.collect { payload ->
                val copy = payload.copyOf()
                _inbound.emit(G1Parser.parse(copy))
            }
        }
    }

    fun stop() {
        inboundJob?.cancel()
        inboundJob = null
    }

    fun requestBattery(): Boolean {
        if (deviceManager.state.value != G1ConnectionState.CONNECTED) {
            return false
        }
        scope.launch {
            val ok = deviceManager.sendRawCommand(G1Packets.batteryQuery(), "BatteryQuery")
            if (!ok) {
                _inbound.emit(G1Inbound.Error(-1, "Battery request failed"))
            }
        }
        return true
    }

    fun requestFirmware(): Boolean {
        if (deviceManager.state.value != G1ConnectionState.CONNECTED) {
            return false
        }
        scope.launch {
            val ok = deviceManager.sendRawCommand(G1Packets.firmwareQuery(), "FirmwareQuery")
            if (!ok) {
                _inbound.emit(G1Inbound.Error(-1, "Firmware request failed"))
            }
        }
        return true
    }

    fun sendTextPage(text: String): Boolean {
        if (deviceManager.state.value != G1ConnectionState.CONNECTED) {
            return false
        }
        scope.launch {
            val ok = deviceManager.sendRawCommand(G1Packets.textPageUtf8(text), "TextPage")
            if (!ok) {
                _inbound.emit(G1Inbound.Error(-1, "Send text failed"))
            }
        }
        return true
    }
}
