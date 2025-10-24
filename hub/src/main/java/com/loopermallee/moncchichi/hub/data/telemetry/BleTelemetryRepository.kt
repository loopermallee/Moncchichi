package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min
import kotlin.text.Charsets

/**
 * Aggregates BLE telemetry packets (battery %, uptime, RSSI) emitted by [MoncchichiBleService].
 */
class BleTelemetryRepository(
    private val logger: (String) -> Unit = {},
) {

    data class LensTelemetry(
        val batteryPercent: Int? = null,
        val caseBatteryPercent: Int? = null,
        val lastUpdated: Long? = null,
    )

    data class Snapshot(
        val left: LensTelemetry = LensTelemetry(),
        val right: LensTelemetry = LensTelemetry(),
        val uptimeSeconds: Long? = null,
        val lastLens: MoncchichiBleService.Lens? = null,
        val lastFrameHex: String? = null,
        val firmwareVersion: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private var lastConnected = false

    fun reset() {
        _snapshot.value = Snapshot()
    }

    fun bindToService(service: MoncchichiBleService, scope: CoroutineScope) {
        unbind()
        lastConnected = service.state.value.left.isConnected || service.state.value.right.isConnected
        frameJob = scope.launch {
            service.incoming.collect { frame ->
                onFrame(frame.lens, frame.payload)
            }
        }
        stateJob = scope.launch {
            service.state.collectLatest { state ->
                val connected = state.left.isConnected || state.right.isConnected
                if (!connected && lastConnected) {
                    reset()
                }
                lastConnected = connected
            }
        }
    }

    fun unbind() {
        frameJob?.cancel()
        frameJob = null
        stateJob?.cancel()
        stateJob = null
        lastConnected = false
    }

    fun onFrame(lens: MoncchichiBleService.Lens, frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame.first().toInt() and 0xFF) {
            BATTERY_OPCODE -> handleBattery(lens, frame)
            UPTIME_OPCODE -> handleUptime(lens, frame)
            FIRMWARE_OPCODE -> handleFirmware(lens, frame)
            else -> logRaw(lens, frame)
        }
    }

    private fun handleBattery(lens: MoncchichiBleService.Lens, frame: ByteArray) {
        val primary = frame.getOrNull(2)?.toInt()?.takeIf { it in 0..100 }
        val case = frame.getOrNull(3)?.toInt()?.takeIf { it in 0..100 }
        if (primary == null && case == null) {
            logRaw(lens, frame)
            return
        }
        val timestamp = System.currentTimeMillis()
        _snapshot.update { current ->
            val updatedLens = current.lens(lens).copy(
                batteryPercent = primary ?: current.lens(lens).batteryPercent,
                caseBatteryPercent = case ?: current.lens(lens).caseBatteryPercent,
                lastUpdated = timestamp,
            )
            current.updateLens(lens, updatedLens).copy(
                lastLens = lens,
                lastFrameHex = frame.toHex(),
            )
        }
        val mainLabel = primary?.let { "$it%" } ?: "?"
        val caseLabel = case?.let { "$it%" } ?: "?"
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} battery=$mainLabel case=$caseLabel")
    }

    private fun handleUptime(lens: MoncchichiBleService.Lens, frame: ByteArray) {
        val uptime = parseLittleEndianUInt(frame, start = 2, length = min(4, frame.size - 2))
        if (uptime == null) {
            logRaw(lens, frame)
            return
        }
        _snapshot.update { current ->
            current.copy(
                uptimeSeconds = uptime,
                lastLens = lens,
                lastFrameHex = frame.toHex(),
            )
        }
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} uptime=${uptime}s")
    }

    private fun handleFirmware(lens: MoncchichiBleService.Lens, frame: ByteArray) {
        if (frame.size <= 2) {
            logRaw(lens, frame)
            return
        }
        val version = frame.copyOfRange(2, frame.size).toString(Charsets.UTF_8).trim()
        if (version.isEmpty()) {
            logRaw(lens, frame)
            return
        }
        _snapshot.update { current ->
            current.copy(
                firmwareVersion = version,
                lastLens = lens,
                lastFrameHex = frame.toHex(),
            )
        }
        val lensLabel = lens.name.lowercase(Locale.US)
        _events.tryEmit("[DIAG] ${lensLabel} firmware=${version}")
    }

    private fun logRaw(lens: MoncchichiBleService.Lens, frame: ByteArray) {
        val hex = frame.toHex()
        logger("[BLE][RAW] ${lens.name}: $hex")
        _snapshot.update { current ->
            current.copy(lastLens = lens, lastFrameHex = hex)
        }
    }

    private fun Snapshot.lens(lens: MoncchichiBleService.Lens): LensTelemetry = when (lens) {
        MoncchichiBleService.Lens.LEFT -> left
        MoncchichiBleService.Lens.RIGHT -> right
    }

    private fun Snapshot.updateLens(
        lens: MoncchichiBleService.Lens,
        telemetry: LensTelemetry,
    ): Snapshot = when (lens) {
        MoncchichiBleService.Lens.LEFT -> copy(left = telemetry)
        MoncchichiBleService.Lens.RIGHT -> copy(right = telemetry)
    }

    private fun parseLittleEndianUInt(frame: ByteArray, start: Int, length: Int): Long? {
        if (start >= frame.size || length <= 0) return null
        var value = 0L
        for (index in 0 until length) {
            val byte = frame.getOrNull(start + index)?.toLong() ?: return null
            value = value or ((byte and 0xFF) shl (8 * index))
        }
        return value
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xFF).toString(16)).padStart(2, '0')
    }

    companion object {
        private const val BATTERY_OPCODE = 0x2C
        private const val UPTIME_OPCODE = 0x37
        private const val FIRMWARE_OPCODE = 0x11
    }
}
