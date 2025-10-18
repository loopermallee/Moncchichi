package com.loopermallee.moncchichi.ble

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decodes Even G1 BLE UART replies into structured vitals.
 * Recognizes textual prefixes like +i (heartbeat), +b (battery), +v (firmware).
 */
object G1ReplyParser {

    data class DeviceVitals(
        val battery: Int? = null,
        val firmware: String? = null,
        val lastKeepAlive: Long? = null
    )

    val vitalsFlow = MutableStateFlow(DeviceVitals())

    fun parse(bytes: ByteArray, logger: (String) -> Unit) {
        val text = bytes.toString(Charsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }

        when {
            text.startsWith("+i") -> {
                vitalsFlow.value = vitalsFlow.value.copy(lastKeepAlive = System.currentTimeMillis())
                logger("[DEVICE] Keep-alive OK")
            }
            text.startsWith("+b") -> {
                val level = bytes.getOrNull(2)?.toUByte()?.toInt()
                if (level != null && level in 0..100) {
                    vitalsFlow.value = vitalsFlow.value.copy(battery = level)
                    logger("[DEVICE] Battery = $level %")
                } else logger("[DEVICE] Battery packet malformed: $text")
            }
            text.startsWith("+v") -> {
                val fw = text.removePrefix("+v")
                vitalsFlow.value = vitalsFlow.value.copy(firmware = fw)
                logger("[DEVICE] Firmware = $fw")
            }
            else -> logger("[DEVICE] Unknown reply: $text (${bytes.toHex()})")
        }
    }
}
