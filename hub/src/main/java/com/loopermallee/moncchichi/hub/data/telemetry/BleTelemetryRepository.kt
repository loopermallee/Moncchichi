package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
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
import kotlinx.coroutines.flow.updateAndGet
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
    private val memory: MemoryRepository,
    private val persistenceScope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {

    data class LensTelemetry(
        val batteryPercent: Int? = null,
        val caseBatteryPercent: Int? = null,
        val lastUpdated: Long? = null,
        val rssi: Int? = null,
        val firmwareVersion: String? = null,
        val notes: String? = null,
        val bonded: Boolean = false,
        val disconnectReason: Int? = null,
        val bondTransitions: Int = 0,
        val bondTimeouts: Int = 0,
        val refreshCount: Int = 0,
        val smpFrames: Int = 0,
        val lastSmpOpcode: Int? = null,
    )

    data class Snapshot(
        val left: LensTelemetry = LensTelemetry(),
        val right: LensTelemetry = LensTelemetry(),
        val uptimeSeconds: Long? = null,
        val lastLens: Lens? = null,
        val lastFrameHex: String? = null,
        val connectionSequence: String? = null,
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
    private var ackJob: Job? = null
    private var lastConnected = false

    private val leftBuffer = ByteArrayOutputStream()
    private val rightBuffer = ByteArrayOutputStream()
    private data class KeepAliveSnapshot(
        var lastAt: Long? = null,
        var rtt: Long? = null,
        var failures: Int = 0,
        var lockSkips: Int = 0,
        var ackTimeouts: Int = 0,
    )
    private val keepAliveSnapshots = mutableMapOf<Lens, KeepAliveSnapshot>()

    fun reset() {
        _snapshot.value = Snapshot()
        _events.tryEmit("[BLE][DIAG] telemetry reset")
        clearBuffers()
        keepAliveSnapshots.clear()
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
                mergeKeepAlive(Lens.LEFT, state.left)
                mergeKeepAlive(Lens.RIGHT, state.right)
                mergeBondState(Lens.LEFT, state.left.bonded)
                mergeBondState(Lens.RIGHT, state.right.bonded)
                mergeDisconnectReason(Lens.LEFT, state.left.disconnectStatus)
                mergeDisconnectReason(Lens.RIGHT, state.right.disconnectStatus)
                mergeBondDiagnostics(Lens.LEFT, state.left)
                mergeBondDiagnostics(Lens.RIGHT, state.right)
                updateConnectionSequence(state.connectionOrder)
            }
        }
        ackJob = scope.launch {
            service.ackEvents.collect { event ->
                onAck(event)
            }
        }
    }

    fun unbind() {
        frameJob?.cancel(); frameJob = null
        stateJob?.cancel(); stateJob = null
        ackJob?.cancel(); ackJob = null
        lastConnected = false
        clearBuffers()
        keepAliveSnapshots.clear()
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
        updateSnapshot(eventTimestamp = timestamp) { current ->
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
        val eventTimestamp = System.currentTimeMillis()
        updateSnapshot(eventTimestamp = eventTimestamp) { current ->
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
        val eventTimestamp = System.currentTimeMillis()
        updateSnapshot(eventTimestamp = eventTimestamp) { current ->
            val existing = current.lens(lens)
            if (existing.firmwareVersion == version && current.lastLens == lens && current.lastFrameHex == hex) {
                current
            } else {
                current.updateLens(lens, existing.copy(firmwareVersion = version)).withFrame(lens, hex)
            }
        }
        _events.tryEmit("[DIAG] ${lens.name.lowercase(Locale.US)} firmware=$version")
    }

    private fun logRaw(lens: Lens, frame: ByteArray) {
        val hex = frame.toHex()
        logger("[BLE][RAW] ${lens.name}: $hex")
        updateSnapshot(persist = false) { current -> current.withFrame(lens, hex) }
    }

    private fun onAck(event: MoncchichiBleService.AckEvent) {
        val lensTag = if (event.lens == Lens.LEFT) "L" else "R"
        val opcode = event.opcode?.let { String.format("0x%02X", it) } ?: "n/a"
        val status = event.status?.let { String.format("0x%02X", it) } ?: "n/a"
        val outcome = if (event.success) "OK" else "FAIL"
        val message = "[ACK][$lensTag] opcode=$opcode status=$status → $outcome"
        logger("[BLE][ACK][$lensTag] opcode=$opcode status=$status success=${event.success}")
        _uartText.tryEmit(UartLine(event.lens, message))
    }

    private fun mergeKeepAlive(lens: Lens, status: MoncchichiBleService.LensStatus) {
        val snapshot = keepAliveSnapshots.getOrPut(lens) { KeepAliveSnapshot() }
        if (
            snapshot.lastAt == status.lastKeepAliveAt &&
            snapshot.rtt == status.keepAliveRttMs &&
            snapshot.failures == status.consecutiveKeepAliveFailures &&
            snapshot.lockSkips == status.keepAliveLockSkips &&
            snapshot.ackTimeouts == status.keepAliveAckTimeouts
        ) {
            return
        }
        snapshot.lastAt = status.lastKeepAliveAt
        snapshot.rtt = status.keepAliveRttMs
        snapshot.failures = status.consecutiveKeepAliveFailures
        snapshot.lockSkips = status.keepAliveLockSkips
        snapshot.ackTimeouts = status.keepAliveAckTimeouts
        val now = System.currentTimeMillis()
        val agoLabel = status.lastKeepAliveAt?.let { "${now - it}ms ago" } ?: "n/a"
        val rttLabel = status.keepAliveRttMs?.let { "${it}ms" } ?: "n/a"
        val failures = status.consecutiveKeepAliveFailures
        val lockSkips = status.keepAliveLockSkips
        val ackTimeouts = status.keepAliveAckTimeouts
        val line =
            "keepalive last=$agoLabel rtt=$rttLabel failures=$failures lockSkips=$lockSkips ackTimeouts=$ackTimeouts"
        _events.tryEmit("[BLE][DIAG] ${lens.name.lowercase(Locale.US)} $line")
        if (status.lastKeepAliveAt != null || failures > 0) {
            _uartText.tryEmit(UartLine(lens, line))
        }
    }

    private fun mergeRssi(lens: Lens, newValue: Int?) {
        updateSnapshot { current ->
            val existing = current.lens(lens)
            if (existing.rssi == newValue) {
                current
            } else {
                current.updateLens(lens, existing.copy(rssi = newValue))
            }
        }
    }

    private fun mergeBondState(lens: Lens, bonded: Boolean) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            if (existing.bonded == bonded) {
                return@updateSnapshot current
            }
            val updated = existing.copy(bonded = bonded)
            val next = current.updateLens(lens, updated)
            val lensLabel = lens.name.lowercase(Locale.US)
            if (!bonded) {
                _events.tryEmit("[BLE][PAIR] ${lensLabel} bond missing ⚠️")
            } else if (next.left.bonded && next.right.bonded) {
                _events.tryEmit("[BLE][PAIR] bonded ✅ (both lenses)")
            }
            next
        }
    }

    private fun mergeDisconnectReason(lens: Lens, reason: Int?) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            if (existing.disconnectReason == reason) {
                return@updateSnapshot current
            }
            val updated = existing.copy(disconnectReason = reason)
            val next = current.updateLens(lens, updated)
            reason?.let {
                val lensLabel = lens.name.lowercase(Locale.US)
                val label = formatGattStatus(it)
                _events.tryEmit("[BLE][LINK] ${lensLabel} disconnect status=$label")
            }
            next
        }
    }

    private fun mergeBondDiagnostics(lens: Lens, status: MoncchichiBleService.LensStatus) {
        updateSnapshot(persist = false) { current ->
            val existing = current.lens(lens)
            var updated = existing
            var changed = false
            val lensLabel = lens.name.lowercase(Locale.US)

            if (existing.bondTransitions != status.bondTransitions) {
                updated = updated.copy(bondTransitions = status.bondTransitions)
                _events.tryEmit("[BLE][PAIR] ${lensLabel} bond transitions=${status.bondTransitions}")
                changed = true
            }
            if (existing.bondTimeouts != status.bondTimeouts) {
                updated = updated.copy(bondTimeouts = status.bondTimeouts)
                _events.tryEmit("[BLE][PAIR] ${lensLabel} bond timeouts=${status.bondTimeouts}")
                changed = true
            }
            if (existing.refreshCount != status.refreshCount) {
                updated = updated.copy(refreshCount = status.refreshCount)
                _events.tryEmit("[BLE][PAIR] ${lensLabel} refresh invoked=${status.refreshCount}")
                changed = true
            }
            if (
                existing.smpFrames != status.smpFrameCount ||
                existing.lastSmpOpcode != status.lastSmpOpcode
            ) {
                updated = updated.copy(
                    smpFrames = status.smpFrameCount,
                    lastSmpOpcode = status.lastSmpOpcode,
                )
                val opcodeLabel = formatOpcode(status.lastSmpOpcode)
                _events.tryEmit("[BLE][SMP] ${lensLabel} frames=${status.smpFrameCount} last=$opcodeLabel")
                changed = true
            }

            if (!changed) {
                current
            } else {
                current.updateLens(lens, updated)
            }
        }
    }

    private fun updateConnectionSequence(order: List<Lens>) {
        val label = if (order.isEmpty()) null else order.joinToString(separator = "→") { it.name.lowercase(Locale.US) }
        val previous = _snapshot.value.connectionSequence
        if (previous == label) {
            return
        }
        updateSnapshot(persist = false) { current -> current.copy(connectionSequence = label) }
        label?.let {
            _events.tryEmit("[BLE][PAIR] connect sequence $it")
        }
    }

    private fun Snapshot.lens(lens: Lens): LensTelemetry = when (lens) {
        Lens.LEFT -> left
        Lens.RIGHT -> right
    }

    private fun formatOpcode(value: Int?): String = value?.let { String.format("0x%02X", it) } ?: "n/a"

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

    private fun formatGattStatus(code: Int): String = "0x%02X".format(code and 0xFF)

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
            parseTextMetadata(lens, line)
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
                    parseTextMetadata(lens, trimmed)
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

    private fun parseTextMetadata(lens: Lens, line: String) {
        updateSnapshot { current ->
            val existing = current.lens(lens)
            var firmware = existing.firmwareVersion
            var notes = existing.notes
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
            val updated = existing.copy(firmwareVersion = firmware, notes = notes)
            if (updated != existing) {
                current.updateLens(lens, updated)
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

    private fun updateSnapshot(
        eventTimestamp: Long? = null,
        persist: Boolean = true,
        transform: (Snapshot) -> Snapshot,
    ) {
        val previous = _snapshot.value
        val updated = _snapshot.updateAndGet(transform)
        if (persist && updated != previous) {
            persistSnapshot(updated, eventTimestamp)
        }
    }

    private fun persistSnapshot(snapshot: Snapshot, eventTimestamp: Long?) {
        val recordedAt = eventTimestamp ?: System.currentTimeMillis()
        val record = MemoryRepository.TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = snapshot.uptimeSeconds,
            left = snapshot.left.toRecord(),
            right = snapshot.right.toRecord(),
        )
        persistenceScope.launch {
            memory.addTelemetrySnapshot(record)
        }
    }

    private fun LensTelemetry.toRecord(): MemoryRepository.LensSnapshot =
        MemoryRepository.LensSnapshot(
            batteryPercent = batteryPercent,
            caseBatteryPercent = caseBatteryPercent,
            lastUpdated = lastUpdated,
            rssi = rssi,
            firmwareVersion = firmwareVersion,
            notes = notes,
        )

    companion object {
        private const val BATTERY_OPCODE = 0x2C
        private const val UPTIME_OPCODE = 0x37
        private const val FIRMWARE_OPCODE = 0x11
    }
}
