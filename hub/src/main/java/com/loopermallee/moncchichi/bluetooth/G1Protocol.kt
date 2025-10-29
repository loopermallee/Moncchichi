package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.core.SendTextPacketBuilder
import kotlin.text.Charsets

/**
 * Minimal G1 protocol helpers used by the in-app Data Console.
 */
object G1Packets {
    private val textPacketBuilder = SendTextPacketBuilder()

    private const val OP_PING: Byte = 0x25
    private const val OP_BRIGHTNESS: Byte = 0x01
    private const val OPCODE_SYSTEM_COMMAND: Byte = 0x23
    private const val SYSTEM_COMMAND_REBOOT: Byte = 0x72
    private var pingSequence: Byte = 0x00

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

    fun ping(): ByteArray {
        val seq = pingSequence
        pingSequence = (seq + 1).toInt().and(0xFF).toByte()
        return byteArrayOf(OP_PING, seq)
    }

    internal fun resetPingSequenceForTests() {
        pingSequence = 0x00
    }

    fun brightness(level: Int, target: BrightnessTarget = BrightnessTarget.BOTH): ByteArray {
        val clamped = level.coerceIn(0, 100)
        return byteArrayOf(OP_BRIGHTNESS, target.mask, clamped.toByte())
    }

    fun reboot(): ByteArray =
        byteArrayOf(OPCODE_SYSTEM_COMMAND, SYSTEM_COMMAND_REBOOT)

    enum class BrightnessTarget(val mask: Byte) {
        BOTH(0x03),
        LEFT(0x01),
        RIGHT(0x02),
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
