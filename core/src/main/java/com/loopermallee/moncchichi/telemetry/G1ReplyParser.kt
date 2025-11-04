package com.loopermallee.moncchichi.telemetry

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
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
        val frame = parseFrame(bytes) ?: return null

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

    private fun parseF5(frame: Frame): Parsed {
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

    private fun parseBatteryOrStatus(frame: Frame): Parsed {
        if (frame.payload.isEmpty()) return Parsed.Unknown(frame.opcode, frame.raw)

        val flagsByte = frame.payload.getOrNull(0)?.toInt() ?: 0
        val hasStatusFlags = frame.payload.size >= 2
        val wearing = hasStatusFlags && (flagsByte and 0x01) != 0
        val inCradle = hasStatusFlags && (flagsByte and 0x02) != 0
        val charging = hasStatusFlags && (flagsByte and 0x04) != 0
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
                charging = if (hasStatusFlags) charging else null,
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
        )
        vitalsFlow.value = next
        return next
    }

    private fun detectAck(frame: Frame): Parsed.Ack? {
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

    private fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.isEmpty()) return null
        val opcode = bytes[0].toUnsignedInt()
        val secondByte = bytes.getOrNull(1)
        if (secondByte == null) {
            return Frame(
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
            return Frame(
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

        return Frame(opcode, length, sequence, payload, null, bytes)
    }

    private fun Byte.toUnsignedInt(): Int = this.toUByte().toInt()

    private data class Frame(
        val opcode: Int,
        val length: Int?,
        val sequence: Int?,
        val payload: ByteArray,
        val status: Byte?,
        val raw: ByteArray,
    )
}
