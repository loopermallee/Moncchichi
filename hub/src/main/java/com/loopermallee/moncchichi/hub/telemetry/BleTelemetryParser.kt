package com.loopermallee.moncchichi.hub.telemetry

import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_KEEPALIVE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_COMPLETE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ACK_CONTINUE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_BATTERY
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_DEVICE_STATUS
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ENV_RANGE_END
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_ENV_RANGE_START
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_GESTURE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_SYSTEM_STATUS
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_UPTIME
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_OK
import com.loopermallee.moncchichi.bluetooth.G1Protocols.STATUS_BUSY
import com.loopermallee.moncchichi.bluetooth.G1Protocols.isTelemetry
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import java.util.Locale
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.text.Charsets

class BleTelemetryParser(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _events = MutableSharedFlow<TelemetryEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TelemetryEvent> = _events.asSharedFlow()

    data class Result(
        val handlerFound: Boolean,
        val eventsEmitted: Boolean,
    ) {
        val handled: Boolean get() = handlerFound && eventsEmitted
    }

    sealed interface TelemetryEvent {
        val lens: MoncchichiBleService.Lens
        val timestampMs: Long
        val rawFrame: ByteArray

        data class DeviceStatusEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val flags: G1ReplyParser.StateFlags,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class BatteryEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val batteryPercent: Int?,
            val caseBatteryPercent: Int?,
            val info: G1ReplyParser.BatteryInfo?,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class EnvironmentSnapshotEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val key: String,
            val textValue: String?,
            val numericValue: Long?,
            val payload: ByteArray,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class UptimeEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val uptimeSeconds: Long,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class GestureEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val gesture: G1ReplyParser.GestureEvent,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class SystemEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val eventCode: Int,
            val wearing: Boolean?,
            val caseOpen: Boolean?,
            val charging: Boolean?,
            val caseBatteryPercent: Int?,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class AudioPacketEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val sequence: Int?,
            val channel: Int?,
            val declaredLength: Int?,
            val payload: ByteArray,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class AckEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val ackCode: Int?,
            val success: Boolean?,
            val busy: Boolean,
            val sequence: Int?,
            val payload: ByteArray,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class F5Event(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val subcommand: Int?,
            val vitals: G1ReplyParser.DeviceVitals?,
            val evenAiEvent: G1ReplyParser.EvenAiEvent?,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent
    }

    fun parse(
        lens: MoncchichiBleService.Lens,
        frameBytes: ByteArray,
        timestampMs: Long = clock(),
        onEvent: ((TelemetryEvent) -> Unit)? = null,
    ): Result {
        if (frameBytes.isEmpty()) {
            return Result(handlerFound = false, eventsEmitted = false)
        }
        val frame = G1ReplyParser.decodeFrame(frameBytes)
            ?: return Result(handlerFound = false, eventsEmitted = false)
        val opcode = frame.opcode
        val telemetryOpcode = isTelemetry(opcode)
        if (!telemetryOpcode) {
            return Result(handlerFound = false, eventsEmitted = false)
        }

        val events = when {
            opcode == OPC_DEVICE_STATUS -> parseDeviceStatus(lens, frame, timestampMs)
            opcode == OPC_BATTERY -> parseBattery(lens, frame, timestampMs)
            opcode in OPC_ENV_RANGE_START..OPC_ENV_RANGE_END -> parseEnvironmentSnapshot(lens, frame, timestampMs)
            opcode == OPC_UPTIME -> parseUptime(lens, frame, timestampMs)
            opcode == OPC_SYSTEM_STATUS -> parseAck(lens, frame, timestampMs)
            opcode == OPC_GESTURE -> parseF5(lens, frame, timestampMs)
            opcode == CMD_KEEPALIVE -> parseAudio(lens, frame, timestampMs)
            else -> emptyList()
        }

        events.forEach { event ->
            onEvent?.invoke(event)
            _events.tryEmit(event)
        }

        return Result(handlerFound = true, eventsEmitted = events.isNotEmpty())
    }

    private fun parseDeviceStatus(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val flags = G1ReplyParser.parseState(frame) ?: return emptyList()
        return listOf(
            TelemetryEvent.DeviceStatusEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                flags = flags,
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseBattery(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val status = G1ReplyParser.parseBattery(frame)
        val infoPayload = frame.payload.dropBatterySubcommand()
        val info = infoPayload?.let { payload ->
            runCatching { G1ReplyParser.parseBattery(payload) }.getOrNull()
        }
        if (status == null && info == null) {
            return emptyList()
        }
        return listOf(
            TelemetryEvent.BatteryEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                batteryPercent = status?.batteryPercent,
                caseBatteryPercent = status?.caseBatteryPercent,
                info = info,
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseEnvironmentSnapshot(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val snapshot = frame.toEnvironmentSnapshot() ?: return emptyList()
        return listOf(
            TelemetryEvent.EnvironmentSnapshotEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                key = snapshot.key,
                textValue = snapshot.textValue,
                numericValue = snapshot.numericValue,
                payload = snapshot.payload,
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseUptime(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val uptime = G1ReplyParser.parseUptime(frame) ?: return emptyList()
        return listOf(
            TelemetryEvent.UptimeEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                uptimeSeconds = uptime,
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseAck(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val ackCode = frame.resolveAckCode()
        val busy = ackCode == STATUS_BUSY
        val success = when (ackCode) {
            STATUS_OK, OPC_ACK_COMPLETE, OPC_ACK_CONTINUE -> true
            STATUS_BUSY -> null
            else -> null
        }
        return listOf(
            TelemetryEvent.AckEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                ackCode = ackCode,
                success = success,
                busy = busy,
                sequence = frame.sequence,
                payload = frame.payload.copyOf(),
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseAudio(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val payload = frame.payload
        if (payload.size < 3) {
            return emptyList()
        }
        val declaredLength = payload.readLittleEndianUInt(0, 2)
        val channel = payload.getOrNull(2)?.toUnsignedInt()
        val audioStart = 3
        val available = (payload.size - audioStart).coerceAtLeast(0)
        val length = when {
            declaredLength == null -> available
            declaredLength <= available -> declaredLength
            else -> available
        }
        val audioPayload = if (length > 0) {
            payload.copyOfRange(audioStart, audioStart + length)
        } else {
            ByteArray(0)
        }
        return listOf(
            TelemetryEvent.AudioPacketEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                sequence = frame.sequence,
                channel = channel,
                declaredLength = declaredLength,
                payload = audioPayload,
                rawFrame = frame.raw.copyOf(),
            ),
        )
    }

    private fun parseF5(
        lens: MoncchichiBleService.Lens,
        frame: G1ReplyParser.NotifyFrame,
        timestampMs: Long,
    ): List<TelemetryEvent> {
        val parsed = G1ReplyParser.parseF5Payload(frame)
        val subcommand = parsed?.subcommand ?: frame.payload.firstOrNull()?.toUnsignedInt()
        val gesture = if (frame.payload.isNotEmpty()) {
            runCatching { G1ReplyParser.parseGesture(frame.payload) }.getOrNull()
        } else {
            null
        }
        val isGestureEvent = subcommand != null && subcommand in gestureEventCodes
        if (parsed == null && (!isGestureEvent || gesture == null) && subcommand !in systemEventCodes && subcommand !in specialSystemCodes) {
            return emptyList()
        }
        val events = mutableListOf<TelemetryEvent>()
        parsed?.let { payload ->
            if (payload.vitals != null || payload.evenAiEvent != null) {
                events += TelemetryEvent.F5Event(
                    lens = lens,
                    timestampMs = timestampMs,
                    opcode = frame.opcode,
                    subcommand = payload.subcommand,
                    vitals = payload.vitals,
                    evenAiEvent = payload.evenAiEvent,
                    rawFrame = frame.raw.copyOf(),
                )
            }
        }
        if (isGestureEvent && gesture != null) {
            events += TelemetryEvent.GestureEvent(
                lens = lens,
                timestampMs = timestampMs,
                opcode = frame.opcode,
                gesture = gesture,
                rawFrame = frame.raw.copyOf(),
            )
        }
        subcommand?.let { code ->
            val systemEvent = buildSystemEvent(
                lens = lens,
                timestampMs = timestampMs,
                frame = frame,
                code = code,
            )
            if (systemEvent != null) {
                events += systemEvent
            }
        }
        return events
    }

    private fun buildSystemEvent(
        lens: MoncchichiBleService.Lens,
        timestampMs: Long,
        frame: G1ReplyParser.NotifyFrame,
        code: Int,
    ): TelemetryEvent.SystemEvent? {
        if (code !in systemEventCodes && code !in specialSystemCodes) {
            return null
        }
        val valueIndex = frame.resolveF5ValueIndex(code)
        val rawValue = valueIndex?.let { frame.payload.getOrNull(it)?.toUnsignedInt() }
        var wearing: Boolean? = null
        var caseOpen: Boolean? = null
        var charging: Boolean? = null
        var caseBattery: Int? = null
        when (code) {
            0x06 -> wearing = true
            0x07 -> wearing = false
            0x08 -> caseOpen = true
            0x09 -> caseOpen = false
            0x0A -> charging = true
            0x0B -> charging = false
            0x0E -> charging = rawValue?.let { it == 1 }
            0x0F -> caseBattery = rawValue?.takeIf { it in 0..100 }
        }
        if (wearing == null && caseOpen == null && charging == null && caseBattery == null) {
            return null
        }
        return TelemetryEvent.SystemEvent(
            lens = lens,
            timestampMs = timestampMs,
            opcode = frame.opcode,
            eventCode = code,
            wearing = wearing,
            caseOpen = caseOpen,
            charging = charging,
            caseBatteryPercent = caseBattery,
            rawFrame = frame.raw.copyOf(),
        )
    }

    private fun G1ReplyParser.NotifyFrame.resolveF5ValueIndex(command: Int): Int? {
        return when {
            status?.toUnsignedInt() == command -> 0
            payload.firstOrNull()?.toUnsignedInt() == command -> 1
            else -> null
        }
    }
    private val gestureEventCodes = setOf(
        0x00,
        0x01,
        0x02,
        0x03,
        0x04,
        0x05,
        0x12,
        0x17,
        0x18,
        0x1E,
        0x1F,
    )

    private val systemEventCodes = 0x06..0x0B
    private val specialSystemCodes = setOf(0x0E, 0x0F)

    private fun G1ReplyParser.NotifyFrame.toEnvironmentSnapshot(): EnvironmentSnapshot? {
        val payloadCopy = payload.copyOf()
        val key = environmentLabels[opcode] ?: "opcode_%02X".format(Locale.US, opcode and 0xFF)
        val text = payloadCopy.decodeAsciiOrNull()
        val numeric = payloadCopy.toTelemetryNumber(text != null)
        return EnvironmentSnapshot(
            key = key,
            payload = payloadCopy,
            textValue = text,
            numericValue = numeric,
        )
    }

    private fun ByteArray.dropBatterySubcommand(): ByteArray? {
        if (isEmpty()) return null
        val hasSubcommand = first().toUnsignedInt() in 0x01..0x02
        val startIndex = if (hasSubcommand) 1 else 0
        if (size <= startIndex) return null
        return copyOfRange(startIndex, size)
    }

    private fun G1ReplyParser.NotifyFrame.resolveAckCode(): Int? {
        status?.toUnsignedInt()?.let { statusByte ->
            if (statusByte.isAckCode()) {
                return statusByte
            }
        }
        return payload.lastOrNull { it.toUnsignedInt().isAckCode() }?.toUnsignedInt()
    }

    private fun Int.isAckCode(): Boolean {
        return this == STATUS_OK || this == STATUS_BUSY || this == OPC_ACK_COMPLETE || this == OPC_ACK_CONTINUE
    }

    private fun ByteArray.readLittleEndianUInt(start: Int, length: Int): Int? {
        if (start < 0 || length <= 0) return null
        if (start + length > size) return null
        var value = 0
        for (index in 0 until length) {
            val byte = getOrNull(start + index)?.toUnsignedInt() ?: return null
            value = value or (byte shl (index * 8))
        }
        return value
    }

    private fun ByteArray.decodeAsciiOrNull(): String? {
        if (isEmpty()) return null
        if (!all { it.toInt().isPrintableTelemetryChar() }) {
            return null
        }
        return toString(Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
            .ifEmpty { null }
    }

    private fun ByteArray.toTelemetryNumber(hasText: Boolean): Long? {
        if (hasText) return null
        if (isEmpty() || size > 4) return null
        var value = 0L
        forEachIndexed { index, byte ->
            value = value or ((byte.toUnsignedInt().toLong() and 0xFF) shl (index * 8))
        }
        return value
    }

    private fun Int.isPrintableTelemetryChar(): Boolean {
        val value = this and 0xFF
        return value == 0x0A || value == 0x0D || value in 0x20..0x7E
    }

    private fun Byte.toUnsignedInt(): Int = toUByte().toInt()

    private data class EnvironmentSnapshot(
        val key: String,
        val payload: ByteArray,
        val textValue: String?,
        val numericValue: Long?,
    )

    private val environmentLabels = mapOf(
        OPC_ENV_RANGE_START to "activation_angle",
        (OPC_ENV_RANGE_START + 1) to "lens_serial",
        (OPC_ENV_RANGE_START + 2) to "device_serial",
        (OPC_ENV_RANGE_START + 3) to "esb_channel",
        (OPC_ENV_RANGE_START + 4) to "esb_notification_count",
    )
}
