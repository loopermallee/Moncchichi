package com.loopermallee.moncchichi.telemetry

import android.util.Log
import com.loopermallee.moncchichi.bluetooth.G1Protocols
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.experimental.and
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

    private const val BIN_MAGIC: Byte = 0xF1.toByte()
    private const val T_BATTERY: Byte = 0x10
    private const val T_CASE_BATTERY: Byte = 0x12
    private const val T_FIRMWARE: Byte = 0x11
    private const val T_SIGNAL: Byte = 0x13
    private const val T_DEVICE_ID: Byte = 0x14
    private const val T_CONNECTION_STATE: Byte = 0x15

    data class StateFlags(
        val wearing: Boolean,
        val inCradle: Boolean,
        val silentMode: Boolean,
        val caseOpen: Boolean,
        val charging: Boolean = false,
    )

    data class BatteryStatus(
        val batteryPercent: Int?,
        val caseBatteryPercent: Int?,
    )

    data class AudioPacket(
        val sequence: Int?,
        val payload: ByteArray,
    )

    data class BatteryInfo(
        val voltage: Int,
        val isCharging: Boolean,
    )

    data class GestureEvent(
        val code: Int,
        val name: String,
    ) {
        companion object {
            fun fromCode(code: Int): GestureEvent {
                val label = G1Protocols.gestureLabel(code)
                val name = if (label.startsWith("gesture", ignoreCase = true)) {
                    String.format(Locale.US, "Gesture 0x%02X", code and 0xFF)
                } else {
                    label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                }
                return GestureEvent(code, name)
            }
        }
    }

    data class CaseStateTelemetry(
        val flags: StateFlags?,
        val silentMode: Boolean?,
        val lidOpen: Boolean?,
        val charging: Boolean?,
    )

    data class CaseBatteryTelemetry(
        val percent: Int?,
        val voltageMv: Int?,
        val charging: Boolean?,
    )

    data class DisplaySettings(
        val subcommand: Int?,
        val height: Int?,
        val depth: Int?,
        val preview: Boolean?,
        val brightness: Int?,
        val action: Int?,
        val enabled: Boolean?,
        val payload: ByteArray,
    )

    data class SystemCommandResult(
        val subcommand: Int?,
        val text: String?,
        val payload: ByteArray,
    )

    data class SerialResponse(
        val opcode: Int,
        val serial: String?,
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

    fun parse(bytes: ByteArray): DeviceVitals? {
        val vitals = extractLegacyVitals(bytes) ?: return null
        updateVitals(vitals)
        return vitals
    }

    fun parse(bytes: ByteArray, logger: (String) -> Unit) {
        val text = bytes.toString(Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }

        when {
            text.startsWith("+i") -> {
                logger("[DEVICE] Keep-alive OK")
            }

            text.startsWith("+b") -> {
                val level = bytes.getOrNull(2)?.toUnsignedInt()
                if (level != null && level in 0..100) {
                    updateVitals(DeviceVitals(batteryPercent = level))
                    logger("[DEVICE] Battery = $level %")
                } else {
                    logger("[DEVICE] Battery packet malformed: $text")
                }
            }

            text.startsWith("+c") -> {
                val level = bytes.getOrNull(2)?.toUnsignedInt()
                if (level != null && level in 0..100) {
                    updateVitals(DeviceVitals(caseBatteryPercent = level))
                    logger("[DEVICE] Case battery = $level %")
                } else {
                    logger("[DEVICE] Case packet malformed: $text")
                }
            }

            text.startsWith("+v") -> {
                val fw = text.removePrefix("+v").trim()
                if (fw.isNotBlank()) {
                    updateVitals(DeviceVitals(firmwareVersion = fw))
                }
                logger("[DEVICE] Firmware = ${fw.ifBlank { "unknown" }}")
            }

            else -> {
                val parsed = extractLegacyVitals(bytes)
                if (parsed != null) {
                    updateVitals(parsed)
                    logger(
                        "[DEVICE] " + buildString {
                            parsed.batteryPercent?.let { append("Battery $it% ") }
                            parsed.caseBatteryPercent?.let { append("Case $it% ") }
                            parsed.firmwareVersion?.let { append("FW $it ") }
                            parsed.signalRssi?.let { append("RSSI $it ") }
                            parsed.deviceId?.let { append("ID $it ") }
                            parsed.connectionState?.let { append("State ${it.uppercase()} ") }
                        }.trim()
                    )
                } else {
                    logger("[DEVICE] Unknown reply: $text (${bytes.toHexString()})")
                }
            }
        }
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
            G1Protocols.CMD_SILENT_MODE_GET -> parseSilentMode(frame)
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
            charging = (flags and 0x10) != 0,
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

    fun parseBattery(payload: ByteArray): BatteryInfo {
        val low = payload.getOrNull(0)?.toUnsignedInt() ?: 0
        val high = payload.getOrNull(1)?.toUnsignedInt() ?: 0
        val voltage = (high shl 8) or low
        val isCharging = payload.getOrNull(2)?.toUnsignedInt() == 1
        return BatteryInfo(voltage = voltage, isCharging = isCharging)
    }

    fun parseUptime(payload: ByteArray): Int {
        val low = payload.getOrNull(0)?.toUnsignedInt() ?: 0
        val high = payload.getOrNull(1)?.toUnsignedInt() ?: 0
        return low or (high shl 8)
    }

    fun parseGesture(payload: ByteArray): GestureEvent {
        val code = payload.firstOrNull()?.toUnsignedInt() ?: 0
        return GestureEvent.fromCode(code)
    }

    fun parseCaseStateTelemetry(frame: NotifyFrame): CaseStateTelemetry? {
        val flags = parseState(frame)
        val payload = frame.payload
        if (flags == null && payload.isEmpty()) return null
        var silent = flags?.silentMode
        var lid = flags?.caseOpen
        if (payload.size > 1) {
            var index = 1
            while (index + 1 < payload.size) {
                val key = payload[index].toUnsignedInt()
                val value = payload[index + 1].toUnsignedInt()
                when (key) {
                    0x0A -> silent = when (value) {
                        0 -> false
                        1 -> true
                        else -> silent
                    }

                    0x0B -> lid = when (value) {
                        0 -> false
                        1 -> true
                        else -> lid
                    }
                }
                index += 2
            }
        }
        val charging = flags?.charging
        return CaseStateTelemetry(flags, silent, lid, charging)
    }

    fun parseCaseBattery(frame: NotifyFrame): CaseBatteryTelemetry? {
        val payload = frame.payload
        if (payload.isEmpty()) return null
        val startIndex = when (payload.first().toUnsignedInt()) {
            0x01, 0x02 -> 1
            else -> 0
        }
        val percent = payload.getOrNull(startIndex + 1)?.toUnsignedInt()?.takeIf { it in 0..100 }
        val voltageLow = payload.getOrNull(startIndex + 2)?.toUnsignedInt()
        val voltageHigh = payload.getOrNull(startIndex + 3)?.toUnsignedInt()
        val voltage = if (voltageLow != null && voltageHigh != null) {
            val candidate = voltageLow or (voltageHigh shl 8)
            candidate.takeIf { it in 1_000..6_000 }
        } else {
            null
        }
        val charging = payload.getOrNull(startIndex + 4)?.toUnsignedInt()?.let { value ->
            when (value) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        if (percent == null && voltage == null && charging == null) return null
        return CaseBatteryTelemetry(percent, voltage, charging)
    }

    private fun parseSilentMode(frame: NotifyFrame): Parsed? {
        val silentValue = frame.raw.getOrNull(2)?.toUnsignedInt()
        val stateCode = frame.raw.getOrNull(3)?.toUnsignedInt()
        val silentMode = when (silentValue) {
            0x0C -> true
            0x0A -> false
            else -> null
        }
        val wearing = when (stateCode) {
            0x06 -> true
            0x07, 0x08, 0x0A, 0x0B -> false
            else -> null
        }
        val inCradle = when (stateCode) {
            0x08, 0x0A, 0x0B -> true
            0x06, 0x07 -> false
            else -> null
        }
        val caseOpen = when (stateCode) {
            0x08 -> true
            0x0A, 0x0B -> false
            else -> null
        }
        val charging = when (stateCode) {
            0x0B -> true
            else -> null
        }
        if (silentMode == null && wearing == null && inCradle == null && caseOpen == null && charging == null) {
            return null
        }
        val vitals = updateVitals(
            DeviceVitals(
                wearing = wearing,
                inCradle = inCradle,
                charging = charging,
                silentMode = silentMode,
                caseOpen = caseOpen,
            ),
        )
        return Parsed.Vitals(vitals)
    }

    fun parseDisplaySettings(frame: NotifyFrame): DisplaySettings? {
        if (frame.opcode != 0x26) return null
        val payload = frame.payload
        if (payload.isEmpty()) {
            return DisplaySettings(null, null, null, null, null, null, null, payload.copyOf())
        }
        val subcommand = payload.first().toUnsignedInt()
        var height: Int? = null
        var depth: Int? = null
        var preview: Boolean? = null
        var brightness: Int? = null
        var action: Int? = null
        var enabled: Boolean? = null
        when (subcommand) {
            0x02 -> {
                val heightLow = payload.getOrNull(1)?.toUnsignedInt()
                val heightHigh = payload.getOrNull(2)?.toUnsignedInt()
                val depthLow = payload.getOrNull(3)?.toUnsignedInt()
                val depthHigh = payload.getOrNull(4)?.toUnsignedInt()
                val previewFlag = payload.getOrNull(5)?.toUnsignedInt()
                height = if (heightLow != null && heightHigh != null) {
                    heightLow or (heightHigh shl 8)
                } else {
                    null
                }
                depth = if (depthLow != null && depthHigh != null) {
                    depthLow or (depthHigh shl 8)
                } else {
                    null
                }
                preview = previewFlag?.let { (it and 0x01) == 0x01 }
            }

            0x04 -> {
                brightness = payload.getOrNull(1)?.toUnsignedInt()
            }

            0x05, 0x07 -> {
                action = payload.getOrNull(1)?.toUnsignedInt()
            }

            0x08 -> {
                enabled = payload.getOrNull(1)?.toUnsignedInt()?.let { value ->
                    when (value) {
                        0 -> false
                        1 -> true
                        else -> null
                    }
                }
            }
        }
        return DisplaySettings(
            subcommand = subcommand,
            height = height,
            depth = depth,
            preview = preview,
            brightness = brightness,
            action = action,
            enabled = enabled,
            payload = payload.copyOf(),
        )
    }

    fun parseSystemCommand(frame: NotifyFrame): SystemCommandResult? {
        if (frame.opcode != 0x23) return null
        val payload = frame.payload
        if (payload.isEmpty()) {
            return SystemCommandResult(frame.status?.toUnsignedInt(), null, ByteArray(0))
        }
        val asciiPayload = payload.decodeTelemetryAsciiOrNull()
        return if (asciiPayload != null && asciiPayload.length == payload.size) {
            SystemCommandResult(frame.status?.toUnsignedInt(), asciiPayload, payload.copyOf())
        } else {
            val subcommand = payload.firstOrNull()?.toUnsignedInt()
            val tail = if (payload.size > 1) payload.copyOfRange(1, payload.size) else ByteArray(0)
            val text = tail.decodeTelemetryAsciiOrNull()
            SystemCommandResult(subcommand, text, payload.copyOf())
        }
    }

    fun parseSerialResponse(frame: NotifyFrame): SerialResponse? {
        if (frame.opcode != 0x33 && frame.opcode != 0x34) return null
        val payload = frame.payload
        val serial = payload.decodeTelemetryAsciiOrNull()
            ?: if (payload.size > 1) payload.copyOfRange(1, payload.size).decodeTelemetryAsciiOrNull() else null
        val normalized = serial?.trim()?.takeIf { it.isNotEmpty() }
        return SerialResponse(frame.opcode, normalized, payload.copyOf())
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

    private fun extractLegacyVitals(bytes: ByteArray): DeviceVitals? {
        if (bytes.isEmpty()) return null
        val text = bytes.toString(Charsets.UTF_8).trim()

        var result: DeviceVitals? = null
        result = mergeLegacyVitals(result, parseLegacyText(text))
        result = mergeLegacyVitals(result, parseLegacyJson(text))
        result = mergeLegacyVitals(result, parseLegacyKeyValue(text))
        result = mergeLegacyVitals(result, parseLegacyBinary(bytes))

        return result
    }

    private fun parseLegacyText(text: String): DeviceVitals? {
        val battery = Regex("""\\b(BAT|Battery)\\s*[:=]\\s*(\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100)
        val case = Regex("""\\b(CASE|Cradle)\\s*[:=]\\s*(\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100)
        val firmware = Regex("""\\b(FW|Firmware)\\s*[:=]\\s*([A-Za-z0-9\\.\-_]+)\\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)
        val rssi = Regex("""\\b(RSSI|Signal)\\s*[:=]\\s*(-?\\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val id = Regex("""\\b(ID|Device|DEV)\\s*[:=]\\s*([A-Za-z0-9\-_:]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)
        val state = Regex("""\\b(State|Status)\\s*[:=]\\s*([A-Za-z]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(2)

        return if (listOf(battery, case, firmware, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = battery,
                caseBatteryPercent = case,
                firmwareVersion = firmware?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.uppercase(),
            )
        } else {
            null
        }
    }

    private fun parseLegacyJson(text: String): DeviceVitals? {
        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val battery = Regex(""""bat"\\s*:\\s*(\\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
        val case = Regex(""""case"\\s*:\\s*(\\d{1,3})"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
        val firmware = Regex(""""fw"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val rssi = Regex(""""rssi"\\s*:\\s*(-?\\d{1,3})"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val id = Regex(""""id"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val state = Regex(""""state"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)

        return if (listOf(battery, case, firmware, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = battery,
                caseBatteryPercent = case,
                firmwareVersion = firmware?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.uppercase(),
            )
        } else {
            null
        }
    }

    private fun parseLegacyBinary(bytes: ByteArray): DeviceVitals? {
        if (bytes.size < 3 || bytes[0] != BIN_MAGIC) return null

        var index = 1
        var battery: Int? = null
        var case: Int? = null
        var firmware: String? = null
        var rssi: Int? = null
        var id: String? = null
        var state: String? = null

        while (index + 1 < bytes.size) {
            val tag = bytes[index]
            val length = (bytes[index + 1] and 0xFF.toByte()).toInt()
            val start = index + 2
            val end = start + length
            if (end > bytes.size) break
            val payload = bytes.copyOfRange(start, end)

            when (tag) {
                T_BATTERY -> if (payload.isNotEmpty()) {
                    battery = (payload[0].toInt() and 0xFF).coerceIn(0, 100)
                }

                T_CASE_BATTERY -> if (payload.isNotEmpty()) {
                    case = (payload[0].toInt() and 0xFF).coerceIn(0, 100)
                }

                T_FIRMWARE -> firmware = payload.toString(Charsets.UTF_8)
                T_SIGNAL -> if (payload.isNotEmpty()) {
                    rssi = payload[0].toInt()
                }

                T_DEVICE_ID -> id = payload.toString(Charsets.UTF_8)
                T_CONNECTION_STATE -> state = payload.toString(Charsets.UTF_8)
            }
            index = end
        }

        return if (listOf(battery, case, firmware, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = battery,
                caseBatteryPercent = case,
                firmwareVersion = firmware?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.ifBlank { null },
            )
        } else {
            null
        }
    }

    private fun parseLegacyKeyValue(text: String): DeviceVitals? {
        if (text.isBlank()) return null
        val entries = text.split(',', ';', '|')
            .map { it.trim() }
            .filter { '=' in it }
        if (entries.isEmpty()) return null

        var battery: Int? = null
        var case: Int? = null
        var firmware: String? = null
        var rssi: Int? = null
        var id: String? = null
        var state: String? = null

        entries.forEach { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()
            when {
                key.contains("case") -> case = value.toIntOrNull()?.coerceIn(0, 100)
                key.contains("bat") -> battery = value.toIntOrNull()?.coerceIn(0, 100)
                key.contains("fw") || key.contains("firm") -> firmware = value.ifBlank { null }
                key.contains("rssi") || key.contains("signal") -> rssi = value.toIntOrNull()
                key == "id" || key.contains("device") -> id = value.ifBlank { null }
                key.contains("state") || key.contains("status") -> state = value.uppercase()
            }
        }

        return if (listOf(battery, case, firmware, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = battery,
                caseBatteryPercent = case,
                firmwareVersion = firmware,
                signalRssi = rssi,
                deviceId = id,
                connectionState = state,
            )
        } else {
            null
        }
    }

    private fun mergeLegacyVitals(current: DeviceVitals?, next: DeviceVitals?): DeviceVitals? {
        if (current == null) return next
        if (next == null) return current
        return DeviceVitals(
            batteryPercent = next.batteryPercent ?: current.batteryPercent,
            caseBatteryPercent = next.caseBatteryPercent ?: current.caseBatteryPercent,
            firmwareVersion = next.firmwareVersion ?: current.firmwareVersion,
            signalRssi = next.signalRssi ?: current.signalRssi,
            deviceId = next.deviceId ?: current.deviceId,
            connectionState = next.connectionState ?: current.connectionState,
            wearing = next.wearing ?: current.wearing,
            inCradle = next.inCradle ?: current.inCradle,
            charging = next.charging ?: current.charging,
            silentMode = next.silentMode ?: current.silentMode,
            caseOpen = next.caseOpen ?: current.caseOpen,
        )
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

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }

    private fun ByteArray.decodeTelemetryAsciiOrNull(): String? {
        if (isEmpty()) return null
        if (!all { it.toInt().isAsciiOrTerminator() }) {
            return null
        }
        return toString(Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
            .takeIf { it.isNotEmpty() }
    }

    data class NotifyFrame(
        val opcode: Int,
        val length: Int?,
        val sequence: Int?,
        val payload: ByteArray,
        val status: Byte?,
        val raw: ByteArray,
    )
}
