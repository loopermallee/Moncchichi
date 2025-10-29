package com.loopermallee.moncchichi.bluetooth

import java.util.UUID

internal object BluetoothConstants {
    const val DEVICE_PREFIX = "Even G1_"

    val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val UART_WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val UART_READ_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OPCODE_CLEAR_SCREEN: Byte = 0x18
    const val OPCODE_HEARTBEAT: Byte = 0x25
    const val OPCODE_SET_MTU: Byte = 0x4D
    const val OPCODE_SEND_TEXT: Byte = 0x4E

    const val OPCODE_GLASSES_INFO: Byte = 0x2C
    val BATTERY_QUERY: ByteArray = byteArrayOf(OPCODE_GLASSES_INFO, 0x01)
    val FIRMWARE_QUERY: ByteArray = byteArrayOf(OPCODE_GLASSES_INFO, 0x02)

    const val DEFAULT_MTU = 23
    const val DESIRED_MTU = 498
    private const val ATT_HEADER_OVERHEAD = 3

    fun payloadCapacityFor(mtu: Int): Int = (mtu - ATT_HEADER_OVERHEAD).coerceAtLeast(1)

    fun buildSetMtuPayload(mtu: Int): ByteArray {
        val lower = (mtu and 0xFF).toByte()
        val upper = ((mtu shr 8) and 0xFF).toByte()
        return byteArrayOf(OPCODE_SET_MTU, lower, upper)
    }

    const val HEARTBEAT_INTERVAL_SECONDS = 28L
    const val HEARTBEAT_TIMEOUT_SECONDS = 10L
}
