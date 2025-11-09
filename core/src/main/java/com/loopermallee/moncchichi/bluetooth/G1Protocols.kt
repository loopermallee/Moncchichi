package com.loopermallee.moncchichi.bluetooth

object G1Protocols {
    // Opcodes
    const val CMD_HELLO = 0x4D
    const val CMD_KEEPALIVE = 0xF1
    const val CMD_PING = 0x25
    const val CMD_BRIGHTNESS = 0x01
    const val CMD_SYSTEM = 0x23
    const val CMD_GLASSES_INFO = 0x2C
    const val CMD_HUD_TEXT = 0x09
    const val CMD_CLEAR = 0x25

    // Status codes
    const val STATUS_OK = 0xC9
    const val STATUS_FAIL = 0xCA

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

    fun opcodeName(opcode: Int?): String = when (opcode) {
        null -> "unknown"
        CMD_HELLO -> "CMD_HELLO"
        CMD_KEEPALIVE -> "CMD_KEEPALIVE"
        CMD_PING -> "CMD_PING"
        CMD_BRIGHTNESS -> "CMD_BRIGHTNESS"
        CMD_SYSTEM -> "CMD_SYSTEM"
        CMD_GLASSES_INFO -> "CMD_GLASSES_INFO"
        CMD_HUD_TEXT -> "CMD_HUD_TEXT"
        CMD_CLEAR -> "CMD_CLEAR"
        else -> "0x%02X".format(opcode and 0xFF)
    }
}
