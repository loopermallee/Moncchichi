package com.loopermallee.moncchichi.bluetooth

object G1Protocols {
    // Opcodes
    const val CMD_HELLO = 0x4D
    const val CMD_KEEPALIVE = 0xF1
    const val CMD_PING = 0x25
    const val CMD_BRIGHTNESS = 0x01
    const val CMD_SYSTEM = 0x23
    const val CMD_SYS_INFO = CMD_SYSTEM
    const val SYS_SUB_INFO = 0x74
    const val CMD_DISPLAY = 0x26
    const val CMD_DISPLAY_GET = 0x29
    const val CMD_SERIAL_LENS = 0x33
    const val CMD_SERIAL_FRAME = 0x34
    const val CMD_GLASSES_INFO = 0x2C
    const val CMD_CASE_GET = 0x2B
    const val CMD_BATT_GET = CMD_GLASSES_INFO
    const val BATT_SUB_DETAIL = 0x01
    const val CMD_WEAR_DETECT = 0x27
    const val CMD_HUD_TEXT = 0x09
    const val CMD_CLEAR = 0x25
    const val OPC_ACK_CONTINUE = 0xCB
    const val OPC_ACK_COMPLETE = 0xC0
    const val OPC_DEVICE_STATUS = 0x2B
    const val OPC_CASE_STATE = OPC_DEVICE_STATUS
    const val OPC_BATTERY = 0x2C
    const val OPC_CASE_BATTERY = OPC_BATTERY
    const val OPC_UPTIME = 0x37
    const val OPC_ENV_RANGE_START = 0x32
    const val OPC_ENV_RANGE_END = 0x36
    const val OPC_EVENT = 0xF5
    const val OPC_GESTURE = OPC_EVENT
    const val EVT_GESTURE = OPC_GESTURE
    const val OPC_SYSTEM_STATUS = 0x39
    const val OPC_DEBUG_REBOOT = 0x23
    const val SUBCMD_SYSTEM_DEBUG = 0x6C
    const val SUBCMD_SYSTEM_REBOOT = 0x72
    const val SUBCMD_SYSTEM_FIRMWARE = 0x74

    const val SUBCMD_DISPLAY_HEIGHT_DEPTH = 0x02
    const val SUBCMD_DISPLAY_BRIGHTNESS = 0x04
    const val SUBCMD_DISPLAY_DOUBLE_TAP = 0x05
    const val SUBCMD_DISPLAY_LONG_PRESS = 0x07
    const val SUBCMD_DISPLAY_MIC_ON_LIFT = 0x08

    // Status codes
    const val STATUS_OK = 0xC9
    const val STATUS_BUSY = 0xCA

    // Timing / retry policy
    const val DEVICE_KEEPALIVE_INTERVAL_MS = 1_000L
    const val DEVICE_KEEPALIVE_ACK_TIMEOUT_MS = 1_500L
    const val HOST_HEARTBEAT_INTERVAL_MS = 30_000L
    const val ACK_TIMEOUT_MS = 7_500L
    const val HELLO_TIMEOUT_MS = 5_000L
    const val WARMUP_DELAY_MS = 500L
    const val DEFAULT_MTU = 247
    const val MAX_RETRIES = 3
    const val RETRY_BACKOFF_MS = 150L
    const val WRITE_LOCK_TIMEOUT_MS = 1_000L

    const val MTU_ACK_TIMEOUT_MS = 1_500L
    const val MTU_WARMUP_GRACE_MS = 7_500L
    const val MTU_RETRY_DELAY_MS = 200L

    private val opcodeLabels = mapOf(
        CMD_HELLO to "CMD_HELLO",
        CMD_KEEPALIVE to "CMD_KEEPALIVE",
        CMD_PING to "CMD_PING/CMD_CLEAR",
        CMD_BRIGHTNESS to "CMD_BRIGHTNESS",
        CMD_SYSTEM to "CMD_SYSTEM/OPC_DEBUG_REBOOT",
        CMD_DISPLAY to "CMD_DISPLAY",
        CMD_SERIAL_LENS to "CMD_SERIAL_LENS",
        CMD_SERIAL_FRAME to "CMD_SERIAL_FRAME",
        CMD_HUD_TEXT to "CMD_HUD_TEXT",
        OPC_ACK_CONTINUE to "OPC_ACK_CONTINUE",
        OPC_ACK_COMPLETE to "OPC_ACK_COMPLETE",
        OPC_DEVICE_STATUS to "OPC_DEVICE_STATUS/OPC_CASE_STATE",
        OPC_BATTERY to "OPC_BATTERY/CMD_GLASSES_INFO/OPC_CASE_BATTERY",
        OPC_UPTIME to "OPC_UPTIME",
        OPC_EVENT to "OPC_EVENT/OPC_GESTURE",
        OPC_SYSTEM_STATUS to "OPC_SYSTEM_STATUS",
    )

    fun opcodeName(opcode: Int?): String {
        val normalized = opcode?.and(0xFF) ?: return "unknown"
        return opcodeLabels[normalized] ?: "0x%02X".format(normalized)
    }

    fun isAckContinuation(opcode: Int?): Boolean {
        val normalized = opcode?.and(0xFF) ?: return false
        return normalized == OPC_ACK_CONTINUE
    }

    fun isAckComplete(opcode: Int?): Boolean {
        val normalized = opcode?.and(0xFF) ?: return false
        return normalized == OPC_ACK_COMPLETE
    }

    fun isTelemetry(opcode: Int?): Boolean {
        val normalized = opcode?.and(0xFF) ?: return false
        if (normalized == CMD_KEEPALIVE || normalized == OPC_EVENT) return true
        if (normalized in OPC_ENV_RANGE_START..OPC_ENV_RANGE_END) return true
        if (normalized in OPC_DEVICE_STATUS..OPC_SYSTEM_STATUS) return true
        return normalized == CMD_SYSTEM ||
            normalized == CMD_DISPLAY ||
            normalized == CMD_SERIAL_LENS ||
            normalized == CMD_SERIAL_FRAME
    }

    enum class F5EventType {
        GESTURE,
        SYSTEM,
        CASE,
        UNKNOWN,
    }

    fun f5EventType(subcommand: Int?): F5EventType {
        val normalized = subcommand ?: return F5EventType.UNKNOWN
        return when (normalized) {
            in 0x00..0x05, in 0x1E..0x20 -> F5EventType.GESTURE
            0x08, 0x09, 0x0E, 0x0F -> F5EventType.CASE
            in 0x06..0x0B -> F5EventType.SYSTEM
            else -> F5EventType.UNKNOWN
        }
    }
}
