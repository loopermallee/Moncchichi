package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min
import kotlin.text.Charsets

/**
 * Aggregates BLE telemetry packets (battery %, uptime, firmware, RSSI) emitted by [MoncchichiBleService].
 */
class BleTelemetryRepository(
    private val logger: (String) -> Unit = {},
) {

    data class LensTelemetry(
        val batteryPercent: Int? = null,
        val caseBatteryPercent: Int? = null,
        val lastUpdated: Long? = null,
        val rssi: Int? = null,
    )

    data class Snapshot(
        val left: LensTelemetry = LensTelemetry(),
        val right: LensTelemetry = LensTelemetry(),
        val uptimeSeconds: Long? = null,
        val lastLens: Lens? = null,
        val lastFrameHex: String? = null,
        val firmwareVersion: String? = null,
        val notes: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _uartText = MutableSharedFlow<UartLine>(extraBufferCapacity = 64)
    val uartText: SharedFlow<UartLine> = _uartText.asSharedFlow()

    data class UartLine(val lens: Lens, val text: String)

    private val versionRegex = Pattern.compile(
        "ver\\s+([0-9]+\\.[0-9]+\\.[0-9]+).*?(DeviceID|DeviceId|DevId)\\s+(\\d+)",
        Pattern.CASE_INSENSITIVE
    )
    private val buildRegex = Pattern.compile(
        "net\\s+build\\s+time\\s*:\\s*(.+?)(?:,|$)",
        Pattern.CASE_INSENSITIVE
    )

    private var frameJob: Job? = null
    private var stateJob: Job? = null
    private var lastConnected = false

    private val leftBuffer = ByteArrayOutputStream()
    private val rightBuffer = ByteArrayOutputStream()

    fun reset() {
        _snapshot.value = Snapshot()
        _events.tryEmit("[BLE][DIAG] telemetry reset")
        clearBuffers()
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
                mergeRssi(Lens.LEFT, state.left.rssi)
                mergeRssi(Lens.RIGHT, state.right.rssi)
            }
        }
    }

    fun unbind() {
        frameJob?.cancel(); frameJob = null
        stateJob?.cancel(); stateJob = null
        lastConnected = false
        clearBuffers()
    }

    fun onFrame(lens: Lens, frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame.first().toInt() and 0xFF) {
            BATTERY_OPCODE -> handleBattery(lens, frame)
            UPTIME_OPCODE -> handleUptime(lens, frame)
            FIRMWARE_OPCODE -> handleFirmware(lens, frame)
            else -> {
                val emittedDirect = maybeEmitUtf8(lens, frame)
                if (!emittedDirect) {
                    val emittedBuffered = maybeAssembleUtf8Buffered(lens, frame)
                    if (!emittedBuffered) {
                        logRaw(lens, frame)
                    }
                }
            }
        }
    }

    private fun handleBattery(lens: Lens, frame: ByteArray) {
        val primary = frame.getOrNull(2)?.toInt()?.takeIf { it in 0..100 }
        val case = frame.getOrNull(3)?.toInt()?.takeIf { it in 0..100 }
        if (primary == null && case == null) {
            logRaw(lens, frame)
            return
        }
        val timestamp = System.currentTimeMillis()
        val hex = frame.toHex()
        _snapshot.update { current ->
            val existing = current.lens(lens)
            val updatedLens = existing.copy(
                batteryPercent = primary ?: existing.batteryPercent,
                caseBatteryPercent = case ?: existing.caseBatteryPercent,
                lastUpdated = timestamp,
            )
            val next = if (updatedLens == existing) current else current.updateLens(lens, updatedLens)
            next.withFrame(lens, hex)
        }
        val mainLabel = primary?.let { "$it%" } ?: "?"
        val caseLabel = case?.let { "$it%" } ?: "?"
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} battery=$mainLabel case=$caseLabel")
    }

    private fun handleUptime(lens: Lens, frame: ByteArray) {
        val uptime = parseLittleEndianUInt(frame, start = 2, length = min(4, frame.size - 2))
        if (uptime == null) {
            logRaw(lens, frame)
            return
        }
        val hex = frame.toHex()
        _snapshot.update { current ->
            if (current.uptimeSeconds == uptime && current.lastLens == lens && current.lastFrameHex == hex) {
                current
            } else {
                current.copy(uptimeSeconds = uptime).withFrame(lens, hex)
            }
        }
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} uptime=${uptime}s")
    }

    private fun handleFirmware(lens: Lens, frame: ByteArray) {
        if (frame.size <= 2) {
            logRaw(lens, frame)
            return
        }
        val raw = frame.copyOfRange(2, frame.size)
        val decoded = runCatching { raw.toString(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        val version = if (decoded.isBlank()) raw.toHex() else decoded
        val hex = frame.toHex()
        _snapshot.update { current ->
            if (current.firmwareVersion == version && current.lastLens == lens && current.lastFrameHex == hex) {
                current
            } else {
                current.copy(firmwareVersion = version).withFrame(lens, hex)
            }
        }
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} firmware=$version")
    }

    private fun logRaw(lens: Lens, frame: ByteArray) {
        val hex = frame.toHex()
        logger("[BLE][RAW] ${lens.name}: $hex")
        _snapshot.update { current -> current.withFrame(lens, hex) }
    }

    private fun mergeRssi(lens: Lens, newValue: Int?) {
        _snapshot.update { current ->
            val existing = current.lens(lens)
            if (existing.rssi == newValue) {
                current
            } else {
                current.updateLens(lens, existing.copy(rssi = newValue))
            }
        }
    }

    private fun Snapshot.lens(lens: Lens): LensTelemetry = when (lens) {
        Lens.LEFT -> left
        Lens.RIGHT -> right
    }

    private fun Snapshot.updateLens(
        lens: Lens,
        telemetry: LensTelemetry,
    ): Snapshot = when (lens) {
        Lens.LEFT -> copy(left = telemetry)
        Lens.RIGHT -> copy(right = telemetry)
    }

    private fun Snapshot.withFrame(lens: Lens, hex: String): Snapshot {
        return if (lastLens == lens && lastFrameHex == hex) {
            this
        } else {
            copy(lastLens = lens, lastFrameHex = hex)
        }
    }

    private fun maybeEmitUtf8(lens: Lens, frame: ByteArray): Boolean {
        val first = frame.first().toInt() and 0xFF
        if (first == BATTERY_OPCODE || first == UPTIME_OPCODE || first == FIRMWARE_OPCODE) return false
        if (frame.size > 64) return false
        val printable = frame.all { byte -> byte.toInt().isAsciiOrCrlf() }
        if (!printable) return false
        val text = runCatching { frame.toString(Charsets.UTF_8).trim() }.getOrNull()
        if (text.isNullOrBlank()) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        lines.forEach { line ->
            parseTextMetadata(line)
            _uartText.tryEmit(UartLine(lens, line))
        }
        return true
    }

    private fun maybeAssembleUtf8Buffered(lens: Lens, frame: ByteArray): Boolean {
        val buffer = if (lens == Lens.LEFT) leftBuffer else rightBuffer
        if (!frame.all { byte -> byte.toInt().isAsciiOrCrlf() }) {
            buffer.reset()
            return false
        }
        if (buffer.size() + frame.size > 64) {
            buffer.reset()
            return false
        }
        buffer.write(frame)
        val accumulated = buffer.toByteArray().toString(Charsets.UTF_8)
        val parts = accumulated.split("\r", "\n")
        if (parts.isEmpty()) return false
        val hasTerminator = accumulated.endsWith("\n") || accumulated.endsWith("\r")
        var emitted = false
        parts.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            val isLast = index == parts.lastIndex
            if (isLast && !hasTerminator) {
                buffer.reset()
                if (raw.isNotEmpty()) {
                    buffer.write(raw.toByteArray(Charsets.UTF_8))
                }
            } else {
                if (trimmed.isNotEmpty()) {
                    parseTextMetadata(trimmed)
                    _uartText.tryEmit(UartLine(lens, trimmed))
                    emitted = true
                }
                if (isLast) {
                    buffer.reset()
                }
            }
        }
        return emitted
    }

    private fun clearBuffers() {
        leftBuffer.reset()
        rightBuffer.reset()
    }

    private fun Int.isAsciiOrCrlf(): Boolean {
        val unsigned = this and 0xFF
        return unsigned == 0x0A || unsigned == 0x0D || unsigned in 0x20..0x7E
    }

    private fun parseTextMetadata(line: String) {
        _snapshot.update { current ->
            var firmware = current.firmwareVersion
            var notes = current.notes
            val versionMatch = versionRegex.matcher(line)
            if (versionMatch.find()) {
                firmware = versionMatch.group(1)
                notes = "DeviceID ${versionMatch.group(3)}"
            } else {
                val buildMatch = buildRegex.matcher(line)
                if (buildMatch.find()) {
                    notes = "Build ${buildMatch.group(1)}"
                }
            }
            if (firmware != current.firmwareVersion || notes != current.notes) {
                current.copy(firmwareVersion = firmware, notes = notes)
            } else {
                current
            }
        }
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
