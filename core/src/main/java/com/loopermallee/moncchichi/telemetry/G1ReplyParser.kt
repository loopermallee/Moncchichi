package com.loopermallee.moncchichi.telemetry

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.min
import kotlin.text.Charsets

object G1ReplyParser {

    private const val TAG = "G1ReplyParser"

    data class DeviceVitals(
        val batteryPercent: Int? = null,
        val caseBatteryPercent: Int? = null,
        val firmwareVersion: String? = null,
        val signalRssi: Int? = null,
        val deviceId: String? = null,
        val connectionState: String? = null,
        val wearing: Boolean? = null,
        val inCradle: Boolean? = null,
        val charging: Boolean? = null,
        val silentMode: Boolean? = null,
        val caseOpen: Boolean? = null,
    )

    data class StateFlags(
        val wearing: Boolean,
        val inCradle: Boolean,
        val silentMode: Boolean,
        val caseOpen: Boolean,
    )

    data class BatteryStatus(
        val batteryPercent: Int?,
        val caseBatteryPercent: Int?,
    )

    data class AudioPacket(
        val sequence: Int?,
        val payload: ByteArray,
    )

    data class F5Payload(
        val subcommand: Int?,
        val vitals: DeviceVitals?,
        val evenAiEvent: EvenAiEvent?,
    )

    sealed class Parsed {
        data class Vitals(val vitals: DeviceVitals) : Parsed()
        data class Mode(val name: String) : Parsed()
        data class Ack(
            val op: Int,
            val success: Boolean,
            val sequence: Int?,
            val payload: ByteArray,
        ) : Parsed()
        data class EvenAi(val event: EvenAiEvent) : Parsed()
        data class Unknown(val op: Int, val frame: ByteArray) : Parsed()
    }

    sealed class EvenAiEvent {
        data object ActivationRequested : EvenAiEvent()
        data object RecordingStopped : EvenAiEvent()
        data class ManualExit(val gesture: TapGesture = TapGesture.DOUBLE) : EvenAiEvent()
        data class ManualPaging(val gesture: TapGesture = TapGesture.SINGLE) : EvenAiEvent()
        data class SilentModeToggle(val gesture: TapGesture = TapGesture.TRIPLE) : EvenAiEvent()
        data class Unknown(val subcommand: Int, val payload: ByteArray) : EvenAiEvent()
    }

    enum class TapGesture {
        SINGLE,
        DOUBLE,
        TRIPLE,
    }

    val vitalsFlow = MutableStateFlow(DeviceVitals())

    fun resetVitals() {
        vitalsFlow.value = DeviceVitals()
    }

    fun parseNotify(bytes: ByteArray): Parsed? {
        val frame = decodeFrame(bytes) ?: return null

        detectAck(frame)?.let { return it }

        return when (frame.opcode) {
            0x06 -> parseBatteryOrStatus(frame)
            0x4E -> Parsed.Mode("Text")
            0x25 -> Parsed.Mode("Idle")
            0x15, 0x20, 0x16 -> Parsed.Mode("Image")
            0xF5 -> parseF5(frame)
            else -> Parsed.Unknown(frame.opcode, frame.raw)
        }
    }

    fun parseState(frame: NotifyFrame): StateFlags? {
        if (frame.payload.isEmpty()) return null
        val flags = frame.payload.first().toUnsignedInt()
        return StateFlags(
            wearing = (flags and 0x02) != 0,
            inCradle = (flags and 0x01) != 0,
            silentMode = (flags and 0x04) != 0,
            caseOpen = (flags and 0x08) != 0,
        )
    }

    fun parseBattery(frame: NotifyFrame): BatteryStatus? {
        val payload = frame.payload
        val (primary, case) = parseBatteryPayloadInternal(payload)
        val battery = primary ?: frame.raw.getOrNull(2)?.toUnsignedInt()?.takeIf { it in 0..100 }
        val caseBattery = case ?: frame.raw.getOrNull(3)?.toUnsignedInt()?.takeIf { it in 0..100 }
        if (battery == null && caseBattery == null) return null
        return BatteryStatus(battery, caseBattery)
    }

    fun parseUptime(frame: NotifyFrame): Long? {
        if (frame.opcode != 0x37) return null
        if (frame.payload.isNotEmpty()) {
            return parseLittleEndianUInt(frame.payload, 0, min(4, frame.payload.size))
        }
        val available = (frame.raw.size - 2).coerceAtLeast(0)
        if (available <= 0) return null
        return parseLittleEndianUInt(frame.raw, 2, min(4, available))
    }

    fun parseAudio(frame: NotifyFrame): AudioPacket? {
        if (frame.opcode != 0xF1) return null
        val payload = if (frame.raw.size > 2) {
            frame.raw.copyOfRange(2, frame.raw.size)
        } else {
            ByteArray(0)
        }
        return AudioPacket(sequence = frame.raw.getOrNull(1)?.toUnsignedInt(), payload = payload)
    }

    fun parseF5Payload(frame: NotifyFrame): F5Payload? {
        if (frame.opcode != 0xF5) return null
        val status = frame.status?.toUnsignedInt()
        val payload = frame.payload
        val battery = parseBatteryFromF5(status, payload)
        val charging = parseChargingFromF5(status, payload)
        val firmware = parseFirmwareBanner(payload)
        val vitals = if (battery != null || charging != null || firmware != null) {
            DeviceVitals(
                batteryPercent = battery,
                charging = charging,
                firmwareVersion = firmware,
            )
        } else {
            null
        }
        val subcommand = status ?: payload.firstOrNull()?.toUnsignedInt()
        val evenAi = if (vitals == null && subcommand != null) {
            parseEvenAiEvent(subcommand, payload)
        } else {
            null
        }
        return F5Payload(subcommand, vitals, evenAi)
    }

    private fun parseF5(frame: NotifyFrame): Parsed {
        val payload = frame.payload
        val status = frame.status?.toUnsignedInt()

        parseBatteryFromF5(status, payload)?.let { percent ->
            val vitals = updateVitals(DeviceVitals(batteryPercent = percent))
            return Parsed.Vitals(vitals)
        }

        parseChargingFromF5(status, payload)?.let { charging ->
            val vitals = updateVitals(DeviceVitals(charging = charging))
            return Parsed.Vitals(vitals)
        }

        parseFirmwareBanner(payload)?.let { banner ->
            val vitals = updateVitals(DeviceVitals(firmwareVersion = banner))
            return Parsed.Vitals(vitals)
        }

        val subcommand = status ?: payload.firstOrNull()?.toUnsignedInt()
            ?: return Parsed.Unknown(frame.opcode, frame.raw)

        return Parsed.EvenAi(parseEvenAiEvent(subcommand, payload))
    }

    private fun parseEvenAiEvent(subcommand: Int, payload: ByteArray): EvenAiEvent {
        return when (subcommand) {
            0x17, 0x23 -> EvenAiEvent.ActivationRequested
            0x24 -> EvenAiEvent.RecordingStopped
            0x00 -> EvenAiEvent.ManualExit()
            0x01 -> EvenAiEvent.ManualPaging()
            0x04, 0x05 -> EvenAiEvent.SilentModeToggle()
            else -> EvenAiEvent.Unknown(subcommand, payload.copyOf())
        }
    }

    private fun parseBatteryFromF5(status: Int?, payload: ByteArray): Int? {
        val (command, valueIndex) = when {
            status == 0x0A -> 0x0A to 0
            payload.firstOrNull()?.toUnsignedInt() == 0x0A -> 0x0A to 1
            else -> null
        } ?: return null
        val percent = payload.getOrNull(valueIndex)?.toUnsignedInt()
        return percent?.takeIf { command == 0x0A && it in 0..100 }
    }

    private fun parseChargingFromF5(status: Int?, payload: ByteArray): Boolean? {
        val valueIndex = when {
            status == 0x09 -> 0
            payload.firstOrNull()?.toUnsignedInt() == 0x09 -> 1
            else -> return null
        }
        return payload.getOrNull(valueIndex)?.let { byte ->
            when (byte.toUnsignedInt()) {
                0 -> false
                1 -> true
                else -> null
            }
        }
    }

    private fun parseFirmwareBanner(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val bytes = when {
            payload.first().toUnsignedInt() in 0x09..0x0A -> payload.copyOfRange(1, payload.size)
            else -> payload.copyOf()
        }
        if (bytes.isEmpty()) return null
        if (!bytes.all { it.toInt().isAsciiOrTerminator() }) {
            return null
        }
        return bytes.toString(Charsets.UTF_8).trim { it <= ' ' }
            .takeIf { it.isNotEmpty() }
    }

    private fun Int.isAsciiOrTerminator(): Boolean {
        val value = this and 0xFF
        return value == 0x0A || value == 0x0D || value in 0x20..0x7E
    }

    private fun parseBatteryOrStatus(frame: NotifyFrame): Parsed {
        if (frame.payload.isEmpty()) return Parsed.Unknown(frame.opcode, frame.raw)

        val flagsByte = frame.payload.getOrNull(0)?.toInt() ?: 0
        val hasStatusFlags = frame.payload.size >= 2
        val wearing = hasStatusFlags && (flagsByte and 0x02) != 0
        val inCradle = hasStatusFlags && (flagsByte and 0x01) != 0
        val silent = hasStatusFlags && (flagsByte and 0x04) != 0
        val caseOpen = hasStatusFlags && (flagsByte and 0x08) != 0
        val batteryIndex = if (hasStatusFlags) 1 else 0
        val battery = frame.payload.getOrNull(batteryIndex)?.toUnsignedInt()

        if (!hasStatusFlags && battery == null) {
            return Parsed.Unknown(frame.opcode, frame.raw)
        }

        val vitals = updateVitals(
            DeviceVitals(
                batteryPercent = battery,
                wearing = if (hasStatusFlags) wearing else null,
                inCradle = if (hasStatusFlags) inCradle else null,
                charging = null,
                silentMode = if (hasStatusFlags) silent else null,
                caseOpen = if (hasStatusFlags) caseOpen else null,
            )
        )

        return Parsed.Vitals(vitals)
    }

    private fun updateVitals(partial: DeviceVitals): DeviceVitals {
        val current = vitalsFlow.value
        val next = DeviceVitals(
            batteryPercent = partial.batteryPercent ?: current.batteryPercent,
            caseBatteryPercent = partial.caseBatteryPercent ?: current.caseBatteryPercent,
            firmwareVersion = partial.firmwareVersion ?: current.firmwareVersion,
            signalRssi = partial.signalRssi ?: current.signalRssi,
            deviceId = partial.deviceId ?: current.deviceId,
            connectionState = partial.connectionState ?: current.connectionState,
            wearing = partial.wearing ?: current.wearing,
            inCradle = partial.inCradle ?: current.inCradle,
            charging = partial.charging ?: current.charging,
            silentMode = partial.silentMode ?: current.silentMode,
            caseOpen = partial.caseOpen ?: current.caseOpen,
        )
        vitalsFlow.value = next
        return next
    }

    private fun detectAck(frame: NotifyFrame): Parsed.Ack? {
        frame.status?.let { statusByte ->
            val success = when (statusByte) {
                0xC9.toByte() -> true
                0xCA.toByte() -> false
                else -> return null
            }
            if (!success) {
                Log.w(TAG, "Command 0x${frame.opcode.toString(16)} failed with status 0xCA")
            }
            return Parsed.Ack(frame.opcode, success, frame.sequence, frame.payload)
        }

        val status = frame.payload.lastOrNull { it.toInt() != 0 }
            ?: frame.raw.drop(1).lastOrNull { it.toInt() != 0 }
            ?: return null
        val success = when (status) {
            0xC9.toByte() -> true
            0xCA.toByte() -> false
            else -> return null
        }
        if (!success) {
            Log.w(TAG, "Command 0x${frame.opcode.toString(16)} failed with status 0xCA")
        }
        return Parsed.Ack(frame.opcode, success, frame.sequence, frame.payload)
    }

    private fun parseBatteryPayloadInternal(payload: ByteArray): Pair<Int?, Int?> {
        if (payload.isEmpty()) return null to null
        val startIndex = when (payload.first().toUnsignedInt()) {
            0x01, 0x02 -> 1
            else -> 0
        }
        val primary = payload.getOrNull(startIndex)?.toUnsignedInt()?.takeIf { it in 0..100 }
        val case = payload.getOrNull(startIndex + 1)?.toUnsignedInt()?.takeIf { it in 0..100 }
        return primary to case
    }

    private fun parseLittleEndianUInt(bytes: ByteArray, start: Int, length: Int): Long? {
        if (start < 0 || length <= 0) return null
        var value = 0L
        for (index in 0 until length) {
            val byte = bytes.getOrNull(start + index)?.toUnsignedInt() ?: return null
            value = value or ((byte.toLong() and 0xFF) shl (8 * index))
        }
        return value
    }

    fun decodeFrame(bytes: ByteArray): NotifyFrame? {
        if (bytes.isEmpty()) return null
        val opcode = bytes[0].toUnsignedInt()
        val secondByte = bytes.getOrNull(1)

        if (opcode == 0xF1) {
            val sequence = secondByte?.toUnsignedInt()
            val payload = if (bytes.size > 2) {
                bytes.copyOfRange(2, bytes.size)
            } else {
                ByteArray(0)
            }
            return NotifyFrame(
                opcode = opcode,
                length = null,
                sequence = sequence,
                payload = payload,
                status = null,
                raw = bytes,
            )
        }
        if (secondByte == null) {
            return NotifyFrame(
                opcode = opcode,
                length = null,
                sequence = null,
                payload = ByteArray(0),
                status = null,
                raw = bytes,
            )
        }

        val remaining = (bytes.size - 2).coerceAtLeast(0)
        val possibleLength = secondByte.toUnsignedInt()
        val isStatusByte = secondByte == 0xC9.toByte() || secondByte == 0xCA.toByte()
        val looksLikeStatus = isStatusByte || possibleLength > remaining

        if (looksLikeStatus) {
            val payload = if (remaining > 0) {
                bytes.copyOfRange(2, bytes.size)
            } else {
                ByteArray(0)
            }
            return NotifyFrame(
                opcode = opcode,
                length = null,
                sequence = null,
                payload = payload,
                status = secondByte,
                raw = bytes,
            )
        }

        val length = possibleLength

        var payloadStart = 2
        var payloadLength = length
        var sequence: Int? = null
        if (length >= 2 && bytes.size >= 4) {
            sequence = (bytes[2].toUnsignedInt() shl 8) or bytes[3].toUnsignedInt()
            payloadStart = 4
            payloadLength = (length - 2).coerceAtLeast(0)
        }

        val available = (bytes.size - payloadStart).coerceAtLeast(0)
        val actualLength = minOf(available, payloadLength)
        val payload = if (actualLength > 0) {
            bytes.copyOfRange(payloadStart, payloadStart + actualLength)
        } else {
            ByteArray(0)
        }

        return NotifyFrame(opcode, length, sequence, payload, null, bytes)
    }

    private fun Byte.toUnsignedInt(): Int = this.toUByte().toInt()

    data class NotifyFrame(
        val opcode: Int,
        val length: Int?,
        val sequence: Int?,
        val payload: ByteArray,
        val status: Byte?,
        val raw: ByteArray,
    )
}
