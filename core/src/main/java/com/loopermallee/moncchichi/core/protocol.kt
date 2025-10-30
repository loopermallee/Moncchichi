package com.loopermallee.moncchichi.core

import com.loopermallee.moncchichi.core.crc.Crc32Xz
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.text.Charsets

// bluetooth device name ---------------------------------------------------------------------------

const val DEVICE_NAME_PREFIX = "Even G1_"

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// request protocol --------------------------------------------------------------------------------

enum class OutgoingPacketType(val label: String) {
    EXIT("EXIT"),                                                                             // x18
    GET_BATTERY_LEVEL("GET_BATTERY_LEVEL"),                                                   // x2C 01
    GET_FIRMWARE_INFO("GET_FIRMWARE_INFO"),                                                   // x2C 02
    SEND_AI_RESULT("SEND_AI_RESULT"),                                                         // x4E
    SEND_HEARTBEAT("SEND_HEARTBEAT"),                                                         // x25
    MIC_CONTROL("MIC_CONTROL"),                                                              // x0E
    SEND_BMP_DATA("SEND_BMP_DATA"),                                                          // x15
    SEND_BMP_TERMINATOR("SEND_BMP_TERMINATOR"),                                              // x20
    SEND_BMP_CRC("SEND_BMP_CRC"),                                                            // x16
    ;
    override fun toString() = label
}

                                        // still unmapped ------------------------------------------

                                                        //    BRIGHTNESS(0x01),
                                                                                // x02 -- antishake?
                                                        //    SILENT_MODE(0x03),
                                                        //    SETUP(0x04),
                                                                  // x05 -- turn on and off logging?
                                                        //    SHOW_DASHBOARD(0x06),
                                                                          // x07 -- start countdown?
                                                                                              // x08
                                                        //    TELEPROMPTER(0x09),
                                                        //    NAVIGATION(0x0A),
                                                        //    HEADUP_ANGLE(0x0B),
                                                                                              // x0C
                                                                      // x0D -- translation related?
                                                        //    MICROPHONE(0x0E),
                                                                      // x0F -- translation related?
                                                                                         // x10-0x14
                                                        //    BMP(0x15),
                                                        //    CRC(0x16),
                                                                                         // x17-0x1D
                                                        //    ADD_QUICK_NOTE(0x1E),
                                                                                         // x1F-0x20
                                                        //    QUICK_NOTE(0x21),
                                                        //    DASHBOARD(0x22),
                                                        //    FIRMWARE(0x23),
                                                                                              // x24
                                                        //    HEARTBEAT(0x25),
                                                        //    DASHBOARD_POSITION(0x26),
                                                        //    GLASS_WEARING(0x27),
                                                                                         // x28-0x2B
                                                        //    NOTIFICATION(0x4B),
                                                                                              // x4C
                                                        //    INITIALIZE(0x4D),
                                                                                         // x4F-0xF0
                                                        //    RECEIVE_MIC_DATA(0xF1.toByte()),
                                                                                         // xF2-0xF4
                                                        //    START_AI(0xF5.toByte()),

// response protocol -------------------------------------------------------------------------------

internal fun hasFirst(byte: Byte): (ByteArray) -> Boolean = { bytes ->
    bytes.isNotEmpty() && bytes[0] == byte
}

enum class IncomingPacketType(
    val label: String,
    val isType: (bytes: ByteArray) -> Boolean,
    val factory: (bytes: ByteArray) -> (IncomingPacket?)
) {
    EXIT("EXIT", hasFirst(0x18), { ExitResponsePacket(it) }),
    GLASSES_INFO("GLASSES_INFO", hasFirst(0x2C), { GlassesInfoResponsePacket.from(it) }),
    AI_RESULT_RECEIVED("AI_RESULT_RECEIVED", hasFirst(0x4E), { SendTextResponsePacket(it) }),
    LC3_AUDIO("LC3_AUDIO", hasFirst(0xF1.toByte()), { Lc3AudioPacket.from(it) }),
;
    override fun toString() = label
}

                                        // still unmapped ------------------------------------------

                                                                                        // x00-0x24
                                                        //    HEARTBEAT(0x25),
                                                                                        // x26-0x28
                                                        // x29 - observed use by ER app
                                                                                        // x2A
                                                        // x2B - observed use by ER app
                                                                                        // x2D-0x36
                                                        // x37 - observed use by ER app
                                                                                        // x38-0x4D
                                                                                        // x38-0x6D
                                                        // x6E - observed use by ER app
                                                                                        // x6F-0xFF

// outgoing ////////////////////////////////////////////////////////////////////////////////////////

abstract class OutgoingPacket(
    val type: OutgoingPacketType,
    val bytes: ByteArray = byteArrayOf()
)

// exit

class ExitRequestPacket: OutgoingPacket(
    // EXAMPLE: 18
    OutgoingPacketType.EXIT,
    byteArrayOf(0x18)
)

// battery level request

class BatteryLevelRequestPacket: OutgoingPacket(
    // EXAMPLE: 2C 01
    OutgoingPacketType.GET_BATTERY_LEVEL,
    byteArrayOf(0x2C, 0x01)
)

class FirmwareInfoRequestPacket: OutgoingPacket(
    // EXAMPLE: 2C 02
    OutgoingPacketType.GET_FIRMWARE_INFO,
    byteArrayOf(0x2C, 0x02)
)

class HeartbeatPacket private constructor(sequence: Int): OutgoingPacket(
    OutgoingPacketType.SEND_HEARTBEAT,
    byteArrayOf(0x25, sequence.toByte())
) {
    companion object {
        private val sequences = ConcurrentHashMap<String, AtomicInteger>()

        fun forDevice(identifier: String): HeartbeatPacket {
            val next = sequences
                .getOrPut(identifier) { AtomicInteger(0) }
                .getAndUpdate { (it + 1) and 0xFF } and 0xFF
            return HeartbeatPacket(next)
        }

        fun resetSequence(identifier: String) {
            sequences[identifier]?.set(0)
        }
    }
}

// send text

class SendTextPacket(
    text: String,
    pageNumber: Int,
    maxPages: Int,
    screenStatus: SendTextPacketBuilder.ScreenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
): OutgoingPacket(
    OutgoingPacketType.SEND_AI_RESULT,
    builder.buildSendText(
        currentPage = pageNumber,
        totalPages = maxPages,
        totalPackageCount = 1,
        currentPackageIndex = 0,
        screenStatus = screenStatus,
        textBytes = text.encodeToByteArray(),
    )
) {
    companion object {
        private val builder = SendTextPacketBuilder()

        fun resetSequence() {
            builder.resetSequence()
        }
    }
}

class MicControlPacket(enabled: Boolean) : OutgoingPacket(
    OutgoingPacketType.MIC_CONTROL,
    byteArrayOf(0x0E, if (enabled) 0x01 else 0x00)
)

// bmp/image frames --------------------------------------------------------------------------------

class BmpPacketBuilder(
    private val chunkSize: Int = DATA_CHUNK_SIZE,
    private val addressPrefix: ByteArray = ADDRESS_PREFIX,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    fun buildFrames(imageBytes: ByteArray): List<ByteArray> {
        if (imageBytes.isEmpty()) {
            return emptyList()
        }
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var sequence = 0
        while (offset < imageBytes.size) {
            val end = min(offset + chunkSize, imageBytes.size)
            val slice = imageBytes.copyOfRange(offset, end)
            val header = if (sequence == 0) {
                byteArrayOf(OPCODE_BMP, sequence.toByte()) + addressPrefix
            } else {
                byteArrayOf(OPCODE_BMP, sequence.toByte())
            }
            frames += header + slice
            offset = end
            sequence = (sequence + 1) and 0xFF
        }
        return frames
    }

    fun buildTerminator(): ByteArray = TERMINATOR.copyOf()

    fun buildCrcFrame(imageBytes: ByteArray): ByteArray {
        val crcInput = ByteArray(addressPrefix.size + imageBytes.size)
        addressPrefix.copyInto(crcInput)
        imageBytes.copyInto(crcInput, destinationOffset = addressPrefix.size)
        val crcValue = Crc32Xz.compute(crcInput)
        val crcBytes = Crc32Xz.toBigEndianBytes(crcValue)
        return byteArrayOf(OPCODE_CRC) + crcBytes
    }

    companion object {
        private const val OPCODE_BMP: Byte = 0x15
        private const val OPCODE_CRC: Byte = 0x16
        private val TERMINATOR: ByteArray = byteArrayOf(0x20, 0x0D, 0x0E)
        private val ADDRESS_PREFIX: ByteArray = byteArrayOf(0x00, 0x1C, 0x00, 0x00)
        const val DATA_CHUNK_SIZE: Int = 194
    }
}

// incoming ////////////////////////////////////////////////////////////////////////////////////////

abstract class IncomingPacket(val type: IncomingPacketType, val bytes: ByteArray, val responseTo: OutgoingPacketType? = null) {
    companion object {
        fun fromBytes(bytes: ByteArray): IncomingPacket? =
            IncomingPacketType.entries.firstOrNull { it.isType(bytes) }?.factory?.invoke(bytes)
    }
}

// exit

class ExitResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.EXIT,
    bytes,
    OutgoingPacketType.EXIT
) {
    // EXAMPLE: 18 c9 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
    //          18 -> packet id
    //             c9 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 -> TODO UNKNOWN
}

// glasses info ------------------------------------------------------------------------------------

sealed class GlassesInfoResponsePacket(
    bytes: ByteArray,
    responseTo: OutgoingPacketType?
) : IncomingPacket(IncomingPacketType.GLASSES_INFO, bytes, responseTo) {
    val subcommand: Int = bytes.getOrNull(1)?.toUByte()?.toInt() ?: -1

    companion object {
        fun from(bytes: ByteArray): IncomingPacket? {
            val sub = bytes.getOrNull(1)?.toUByte()?.toInt() ?: return null
            return when (sub) {
                0x01, 0x66 -> BatteryLevelResponsePacket(bytes)
                0x02 -> FirmwareInfoResponsePacket(bytes)
                else -> UnknownGlassesInfoResponsePacket(bytes)
            }
        }
    }
}

class BatteryLevelResponsePacket(bytes: ByteArray): GlassesInfoResponsePacket(
    bytes,
    OutgoingPacketType.GET_BATTERY_LEVEL
) {
    // EXAMPLE: 2c 01 5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00
    //          2c 01 -> packet id
    //                5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00

    val level = bytes.getOrNull(2)?.toUByte()?.toInt() ?: -1
    override fun toString(): String {
        return "${type} => ${level}%"
    }
}

class FirmwareInfoResponsePacket(bytes: ByteArray): GlassesInfoResponsePacket(
    bytes,
    OutgoingPacketType.GET_FIRMWARE_INFO
) {
    val firmware: String = bytes.copyOfRange(2, bytes.size)
        .toString(Charsets.UTF_8)
        .trim { it <= ' ' }

    override fun toString(): String {
        return "${type} => ${firmware}"
    }
}

class UnknownGlassesInfoResponsePacket(bytes: ByteArray): GlassesInfoResponsePacket(
    bytes,
    null
)

// send text

class SendTextResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.AI_RESULT_RECEIVED,
    bytes,
    OutgoingPacketType.SEND_AI_RESULT
)

class Lc3AudioPacket private constructor(
    bytes: ByteArray,
    val sequence: Int,
    val payload: ByteArray,
) : IncomingPacket(IncomingPacketType.LC3_AUDIO, bytes, null) {
    companion object {
        fun from(bytes: ByteArray): IncomingPacket? {
            if (bytes.isEmpty() || bytes[0] != 0xF1.toByte()) return null
            val sequence = bytes.getOrNull(1)?.toUByte()?.toInt() ?: 0
            val payload = if (bytes.size > 2) bytes.copyOfRange(2, bytes.size) else byteArrayOf()
            return Lc3AudioPacket(bytes, sequence, payload)
        }
    }
}
