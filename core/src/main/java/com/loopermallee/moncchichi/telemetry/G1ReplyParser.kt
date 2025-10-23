package com.loopermallee.moncchichi.telemetry

import kotlinx.coroutines.flow.MutableStateFlow

object G1ReplyParser {

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
        data class Unknown(val op: Int, val frame: ByteArray) : Parsed()
    }

    val vitalsFlow = MutableStateFlow(DeviceVitals())

    fun resetVitals() {
        vitalsFlow.value = DeviceVitals()
    }

    /** Parse a single notify frame like: 55 <op> <len_hi> <len_lo> <payload...> <chk> */
    fun parseNotify(bytes: ByteArray): Parsed? {
        if (bytes.isEmpty()) return null
        val opIndex = when {
            bytes.size > 1 && bytes[0] == HEADER -> 1
            else -> 0
        }
        val op = bytes.getOrNull(opIndex)?.toUByte()?.toInt() ?: return null

        return when (op) {
            0x06 -> parseBatteryOrStatus(bytes)
            0x4E -> Parsed.Mode("Text")
            0x25 -> Parsed.Mode("Idle")
            0x15, 0x20, 0x16 -> Parsed.Mode("Image")
            0xF5 -> Parsed.Mode("Dashboard")
            else -> Parsed.Unknown(op, bytes)
        }
    }

    private fun parseBatteryOrStatus(bytes: ByteArray): Parsed {
        return when {
            bytes.size >= 6 && bytes[0] == HEADER && bytes[1] == 0x06.toByte() -> {
                val percent = bytes.getOrNull(4)?.toUnsignedInt()
                val vitals = updateVitals(DeviceVitals(batteryPercent = percent))
                Parsed.Vitals(vitals)
            }
            bytes.size >= 6 && bytes[0] == 0x06.toByte() && bytes.getOrNull(1) == 0x90.toByte() -> {
                val flags = bytes.getOrNull(4)?.toInt() ?: 0
                val wearing = flags and 0x01 != 0
                val inCradle = flags and 0x02 != 0
                val charging = flags and 0x04 != 0
                val battery = bytes.getOrNull(5)?.toUnsignedInt()
                val vitals = updateVitals(
                    DeviceVitals(
                        batteryPercent = battery,
                        wearing = wearing,
                        inCradle = inCradle,
                        charging = charging
                    )
                )
                Parsed.Vitals(vitals)
            }
            else -> Parsed.Unknown(0x06, bytes)
        }
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

    private fun Byte.toUnsignedInt(): Int = this.toUByte().toInt()

    private const val HEADER: Byte = 0x55
}
