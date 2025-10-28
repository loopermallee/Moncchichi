package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.core.SendTextPacketBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.text.Charsets

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
    private val textPacketBuilder = SendTextPacketBuilder()

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

    private const val OP_PING: Byte = 0x01
    private const val OP_BRIGHTNESS: Byte = 0x05
    private const val OP_REBOOT: Byte = 0x06

    private const val SUBCOMMAND_BATTERY: Byte = 0x01
    private const val SUBCOMMAND_FIRMWARE: Byte = 0x02
    private const val OPCODE_GLASSES_INFO: Byte = 0x2C

    fun batteryQuery(): ByteArray = byteArrayOf(OPCODE_GLASSES_INFO, SUBCOMMAND_BATTERY)
    fun firmwareQuery(): ByteArray = byteArrayOf(OPCODE_GLASSES_INFO, SUBCOMMAND_FIRMWARE)
    fun textPageUtf8(text: String): ByteArray = textPacketBuilder.buildSendText(
        currentPage = 1,
        totalPages = 1,
        screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
        textBytes = text.toByteArray(Charsets.UTF_8),
    )

    fun ping(): ByteArray = frame(OP_PING)

    fun brightness(level: Int, target: BrightnessTarget = BrightnessTarget.BOTH): ByteArray {
        val clamped = level.coerceIn(0, 100)
        val payload = byteArrayOf(target.mask, clamped.toByte())
        return frame(OP_BRIGHTNESS, payload)
    }

    fun reboot(mode: RebootMode = RebootMode.NORMAL): ByteArray = frame(OP_REBOOT, byteArrayOf(mode.code))

    enum class BrightnessTarget(val mask: Byte) {
        BOTH(0x03),
        LEFT(0x01),
        RIGHT(0x02),
    }

    enum class RebootMode(val code: Byte) {
        NORMAL(0x00),
        SAFE(0x01),
    }
}

sealed class G1Inbound {
    data class Battery(val leftPct: Int?, val rightPct: Int?, val casePct: Int?) : G1Inbound()
    data class Firmware(val version: String) : G1Inbound()
    data class Error(val code: Int, val message: String) : G1Inbound()
    data class Raw(val bytes: ByteArray) : G1Inbound()
}

object G1Parser {
    fun parse(bytes: ByteArray): G1Inbound {
        if (bytes.isEmpty()) return G1Inbound.Raw(bytes)
        if (bytes[0] != 0x2C.toByte()) return G1Inbound.Raw(bytes)
        val subcommand = bytes.getOrNull(1)?.toUByte()?.toInt() ?: return G1Inbound.Raw(bytes)
        val payload = bytes.copyOfRange(2, bytes.size)
        return when (subcommand) {
            0x01, 0x66 -> {
                val left = payload.getOrNull(0)?.toUByte()?.toInt()?.takeIf { it in 0..100 }
                val right = payload.getOrNull(1)?.toUByte()?.toInt()?.takeIf { it in 0..100 }
                val case = payload.getOrNull(2)?.toUByte()?.toInt()?.takeIf { it in 0..100 }
                G1Inbound.Battery(left, right, case)
            }
            0x02 -> {
                val version = payload.toString(Charsets.UTF_8)
                    .trim { it <= ' ' }
                    .ifBlank { "unknown" }
                G1Inbound.Firmware(version)
            }
            else -> G1Inbound.Raw(bytes)
        }
    }
}
