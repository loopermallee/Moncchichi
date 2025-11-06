package com.loopermallee.moncchichi.core.ble

import kotlin.experimental.and
import kotlin.text.Charsets

/**
 * Parse replies coming from the G1 Nordic UART TX characteristic.
 *
 * Supports a handful of formats so we can read vitals regardless of firmware build.
 */
object G1ReplyParser {

    private const val BIN_MAGIC: Byte = 0xF1.toByte()
    private const val T_BATTERY: Byte = 0x10
    private const val T_CASE_BATTERY: Byte = 0x12
    private const val T_FIRMWARE: Byte = 0x11
    private const val T_SIGNAL: Byte = 0x13
    private const val T_DEVICE_ID: Byte = 0x14
    private const val T_CONNECTION_STATE: Byte = 0x15

    fun parse(bytes: ByteArray): DeviceVitals? {
        if (bytes.isEmpty()) return null

        val text = bytes.toString(Charsets.UTF_8).trim()

        var result: DeviceVitals? = null
        result = merge(result, parseText(text))
        result = merge(result, parseJsonish(text))
        result = merge(result, parseKeyValue(text))
        result = merge(result, parseBinary(bytes))

        return result
    }

    private fun parseText(t: String): DeviceVitals? {
        val bat = Regex("""\\b(BAT|Battery)\\s*[:=]\\s*(\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val case = Regex("""\\b(CASE|Cradle)\\s*[:=]\\s*(\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val fw = Regex("""\\b(FW|Firmware)\\s*[:=]\\s*([A-Za-z0-9\\.\-_]+)\\b""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)
        val rssi = Regex("""\\b(RSSI|Signal)\\s*[:=]\\s*(-?\\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val id = Regex("""\\b(ID|Device|DEV)\\s*[:=]\\s*([A-Za-z0-9\-_:]+)""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)
        val state = Regex("""\\b(State|Status)\\s*[:=]\\s*([A-Za-z]+)""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)

        return if (listOf(bat, case, fw, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = bat?.coerceIn(0, 100),
                caseBatteryPercent = case?.coerceIn(0, 100),
                firmwareVersion = fw?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.uppercase()
            )
        } else {
            null
        }
    }

    private fun parseJsonish(t: String): DeviceVitals? {
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val bat = Regex(""""bat"\\s*:\\s*(\\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val case = Regex(""""case"\\s*:\\s*(\\d{1,3})"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fw = Regex(""""fw"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)
        val rssi = Regex(""""rssi"\\s*:\\s*(-?\\d{1,3})"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val id = Regex(""""id"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)
        val state = Regex(""""state"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)

        return if (listOf(bat, case, fw, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = bat?.coerceIn(0, 100),
                caseBatteryPercent = case?.coerceIn(0, 100),
                firmwareVersion = fw?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.uppercase()
            )
        } else null
    }

    private fun parseBinary(b: ByteArray): DeviceVitals? {
        if (b.size < 3 || b[0] != BIN_MAGIC) return null
        var i = 1
        var battery: Int? = null
        var case: Int? = null
        var firmware: String? = null
        var rssi: Int? = null
        var id: String? = null
        var state: String? = null

        while (i + 1 < b.size) {
            val tag = b[i]
            val len = (b[i + 1] and 0xFF.toByte()).toInt()
            val start = i + 2
            val end = start + len
            if (end > b.size) break
            val payload = b.copyOfRange(start, end)

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
            i = end
        }

        return if (listOf(battery, case, firmware, rssi, id, state).any { it != null }) {
            DeviceVitals(
                batteryPercent = battery,
                caseBatteryPercent = case,
                firmwareVersion = firmware?.ifBlank { null },
                signalRssi = rssi,
                deviceId = id?.ifBlank { null },
                connectionState = state?.ifBlank { null }
            )
        } else null
    }

    private fun parseKeyValue(text: String): DeviceVitals? {
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
        } else null
    }

    private fun merge(current: DeviceVitals?, next: DeviceVitals?): DeviceVitals? {
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
}
