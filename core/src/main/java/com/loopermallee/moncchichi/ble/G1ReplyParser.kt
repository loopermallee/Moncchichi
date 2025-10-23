package com.loopermallee.moncchichi.ble

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decodes Even G1 BLE UART replies into structured vitals.
 * Recognizes textual prefixes like +i (heartbeat), +b (battery), +v (firmware).
 */
object G1ReplyParser {

    data class DeviceVitals(
        val battery: Int? = null,
        val caseBattery: Int? = null,
        val firmware: String? = null,
        val signalRssi: Int? = null,
        val deviceId: String? = null,
        val connectionState: String? = null,
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
            text.startsWith("+c") -> {
                val level = bytes.getOrNull(2)?.toUByte()?.toInt()
                if (level != null && level in 0..100) {
                    vitalsFlow.value = vitalsFlow.value.copy(caseBattery = level)
                    logger("[DEVICE] Case battery = $level %")
                } else logger("[DEVICE] Case packet malformed: $text")
            }
            text.startsWith("+v") -> {
                val fw = text.removePrefix("+v").trim()
                vitalsFlow.value = vitalsFlow.value.copy(firmware = fw.ifBlank { null })
                logger("[DEVICE] Firmware = ${fw.ifBlank { "unknown" }}")
            }
            else -> {
                val normalized = text.lowercase()
                val battery = Regex("""battery\\s*[:=]\\s*(\\d{1,3})""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val case = Regex("""case\\s*[:=]\\s*(\\d{1,3})""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val fw = Regex("""firmware\\s*[:=]\\s*([A-Za-z0-9\\.\-_]+)""")
                    .find(text)?.groupValues?.getOrNull(1)
                val rssi = Regex("""rssi\\s*[:=]\\s*(-?\\d{1,3})""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val id = Regex("""id\\s*[:=]\\s*([A-Za-z0-9\-_:]+)""")
                    .find(text)?.groupValues?.getOrNull(1)
                val state = Regex("""state\\s*[:=]\\s*([A-Za-z]+)""")
                    .find(text)?.groupValues?.getOrNull(1)

                if (listOf(battery, case, fw, rssi, id, state).any { it != null }) {
                    val current = vitalsFlow.value
                    vitalsFlow.value = current.copy(
                        battery = battery ?: current.battery,
                        caseBattery = case ?: current.caseBattery,
                        firmware = fw ?: current.firmware,
                        signalRssi = rssi ?: current.signalRssi,
                        deviceId = id ?: current.deviceId,
                        connectionState = state?.uppercase() ?: current.connectionState,
                    )
                    logger(
                        "[DEVICE] ${buildString {
                            battery?.let { append("Battery $it% ") }
                            case?.let { append("Case $it% ") }
                            fw?.let { append("FW $it ") }
                            rssi?.let { append("RSSI $it ") }
                            id?.let { append("ID $it ") }
                            state?.let { append("State ${it.uppercase()} ") }
                        }.trim()}"
                    )
                } else {
                    logger("[DEVICE] Unknown reply: $text (${bytes.toHex()})")
                }
            }
        }
    }
}
