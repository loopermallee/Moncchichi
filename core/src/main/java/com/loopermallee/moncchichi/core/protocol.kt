package com.loopermallee.moncchichi.core

// bluetooth device name ---------------------------------------------------------------------------

const val DEVICE_NAME_PREFIX = "Even G1_"

// hardware ids ------------------------------------------------------------------------------------

const val UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_WRITE_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
const val UART_READ_CHARACTERISTIC_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// request protocol --------------------------------------------------------------------------------

enum class OutgoingPacketType(val label: String) {
    EXIT("EXIT"),                                                                             // x18
    GET_BATTERY_LEVEL("GET_BATTERY_LEVEL"),                                                   // x2C
    SEND_AI_RESULT("SEND_AI_RESULT"),                                                         // x4E
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

internal fun hasFirstTwo(first: Byte, second: Byte): (ByteArray) -> Boolean = { bytes ->
    bytes.size > 1 && bytes[0] == first && bytes[1] == second
}

enum class IncomingPacketType(
    val label: String,
    val isType: (bytes: ByteArray) -> Boolean,
    val factory: (bytes: ByteArray) -> (IncomingPacket?)
) {
    EXIT("EXIT", hasFirst(0x18), { ExitResponsePacket(it) }),
    GLASSES_BATTERY_LEVEL("GLASSES_BATTERY_LEVEL", hasFirstTwo(0x2C, 0x66), { BatteryLevelResponsePacket(it) }),
    AI_RESULT_RECEIVED("AI_RESULT_RECEIVED", hasFirst(0x4E), { SendTextResponsePacket(it) }),
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
    // EXAMPLE: 2C
    OutgoingPacketType.GET_BATTERY_LEVEL,
    byteArrayOf(0x2C)
)

// send text

class SendTextPacket(
    text: String,
    pageNumber: Int,
    maxPages: Int,
    screenStatus: Int = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
): OutgoingPacket(
    OutgoingPacketType.SEND_AI_RESULT,
    builder.buildSendText(
        currentPage = pageNumber,
        totalPages = maxPages,
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

// battery level

class BatteryLevelResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.GLASSES_BATTERY_LEVEL,
    bytes,
    OutgoingPacketType.GET_BATTERY_LEVEL
) {
    // EXAMPLE: 2c 66 5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00
    //          2c 66 -> packet id
    //                5e 00 e6 a0 19 00 00 00 01 05 00 00 00 00 00 00 00 00 -> TODO UNKNOWN

    val level = bytes[2].toInt()
    override fun toString(): String {
        return "${type} => ${level}%"
    }
}

// send text

class SendTextResponsePacket(bytes: ByteArray): IncomingPacket(
    IncomingPacketType.AI_RESULT_RECEIVED,
    bytes,
    OutgoingPacketType.SEND_AI_RESULT
)
