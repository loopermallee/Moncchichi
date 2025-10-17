package com.loopermallee.moncchichi.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Minimal G1 protocol helpers used by the in-app Data Console.
 * Replace UUIDs/opcodes with authoritative values from your protocol spec if needed.
 */
object G1Uuids {
    val SERVICE_G1: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val CHAR_TX: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHAR_RX: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
}

object G1Packets {
    private fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun frame(op: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val header = byteArrayOf(0x55, 0xAA.toByte())
        val length = (1 + payload.size).toShort()
        val bufferSize = header.size + Short.SIZE_BYTES + 1 + payload.size + Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header)
        buffer.putShort(length)
        buffer.put(op)
        buffer.put(payload)
        val crcSource = buffer.array().sliceArray(0 until buffer.position())
        val crc = crc16Ccitt(crcSource)
        buffer.putShort(crc.toShort())
        return buffer.array()
    }

    private const val OP_BATTERY: Byte = 0x10
    private const val OP_FIRMWARE: Byte = 0x11
    private const val OP_TEXT_PAGE: Byte = 0x30

    fun batteryQuery(): ByteArray = frame(OP_BATTERY)
    fun firmwareQuery(): ByteArray = frame(OP_FIRMWARE)
    fun textPageUtf8(text: String): ByteArray = frame(OP_TEXT_PAGE, text.toByteArray(Charsets.UTF_8))
}

sealed class G1Inbound {
    data class Battery(val leftPct: Int?, val rightPct: Int?, val casePct: Int?) : G1Inbound()
    data class Firmware(val version: String) : G1Inbound()
    data class Ack(val op: Int) : G1Inbound()
    data class Error(val code: Int, val message: String) : G1Inbound()
    data class Raw(val bytes: ByteArray) : G1Inbound()
}

object G1Parser {
    fun parse(bytes: ByteArray): G1Inbound {
        if (bytes.size < 6) return G1Inbound.Raw(bytes)
        if (bytes[0] != 0x55.toByte() || bytes[1] != 0xAA.toByte()) return G1Inbound.Raw(bytes)
        val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (length + 6 != bytes.size) return G1Inbound.Raw(bytes)
        val op = bytes[4].toInt() and 0xFF
        val payload = bytes.sliceArray(5 until (5 + length - 1))
        return when (op) {
            0x10 -> {
                val left = payload.getOrNull(0)?.toInt()?.takeIf { it in 0..100 }
                val right = payload.getOrNull(1)?.toInt()?.takeIf { it in 0..100 }
                val case = payload.getOrNull(2)?.toInt()?.takeIf { it in 0..100 }
                G1Inbound.Battery(left, right, case)
            }
            0x11 -> {
                val version = payload.toString(Charsets.UTF_8).ifBlank { "unknown" }
                G1Inbound.Firmware(version)
            }
            else -> G1Inbound.Ack(op)
        }
    }
}
