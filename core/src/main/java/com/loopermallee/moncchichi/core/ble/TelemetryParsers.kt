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
    private const val T_FIRMWARE: Byte = 0x11

    fun parse(bytes: ByteArray): DeviceVitals? {
        if (bytes.isEmpty()) return null

        // ---------- Try Text ----------
        val text = bytes.toString(Charsets.UTF_8).trim()
        parseText(text)?.let { return it }

        // ---------- Try JSON-ish (very lenient) ----------
        parseJsonish(text)?.let { return it }

        // ---------- Try Binary TLV ----------
        parseBinary(bytes)?.let { return it }

        return null
    }

    private fun parseText(t: String): DeviceVitals? {
        val bat = Regex("""\\b(BAT|Battery)\\s*[:=]\\s*(\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val fw = Regex("""\\b(FW|Firmware)\\s*[:=]\\s*([A-Za-z0-9\\.\-_]+)\\b""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(2)

        return if (bat != null || fw != null) DeviceVitals(bat?.coerceIn(0, 100), fw) else null
    }

    private fun parseJsonish(t: String): DeviceVitals? {
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val bat = Regex(""""bat"\\s*:\\s*(\\d{1,3})""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fw = Regex(""""fw"\\s*:\\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1)
        return if (bat != null || fw != null) DeviceVitals(bat?.coerceIn(0, 100), fw) else null
    }

    private fun parseBinary(b: ByteArray): DeviceVitals? {
        if (b.size < 3 || b[0] != BIN_MAGIC) return null
        var i = 1
        var battery: Int? = null
        var firmware: String? = null

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
                T_FIRMWARE -> firmware = payload.toString(Charsets.UTF_8)
            }
            i = end
        }

        return if (battery != null || firmware != null) DeviceVitals(battery, firmware) else null
    }
}
