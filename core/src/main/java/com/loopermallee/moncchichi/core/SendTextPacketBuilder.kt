package com.loopermallee.moncchichi.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Utility to build 0x4E "Send Text" frames following the Even G1 protocol.
 * Maintains the rolling text sequence expected by the glasses.
 */
class SendTextPacketBuilder {
    private val sequence = AtomicInteger(0)

    fun resetSequence() {
        sequence.set(0)
    }

    fun buildSendText(
        currentPage: Int,
        totalPages: Int,
        screenStatus: Int,
        textBytes: ByteArray,
    ): ByteArray {
        val safePage = currentPage.coerceIn(0, 0xFF)
        val safeTotal = totalPages.coerceIn(0, 0xFF)
        val safeStatus = screenStatus.coerceIn(0, 0xFF)

        val payload = ByteArray(HEADER_SIZE + textBytes.size)
        payload[0] = COMMAND
        payload[1] = nextSequence()
        payload[2] = FLAGS
        payload[3] = RESERVED
        payload[4] = safeStatus.toByte()
        payload[5] = RESERVED
        payload[6] = RESERVED
        payload[7] = safePage.toByte()
        payload[8] = safeTotal.toByte()
        if (textBytes.isNotEmpty()) {
            System.arraycopy(textBytes, 0, payload, HEADER_SIZE, textBytes.size)
        }
        return payload
    }

    private fun nextSequence(): Byte {
        val value = sequence.getAndUpdate { (it + 1) and 0xFF } and 0xFF
        return value.toByte()
    }

    companion object {
        const val HEADER_SIZE = 9
        const val DEFAULT_SCREEN_STATUS = 0x71
        private val COMMAND: Byte = 0x4E
        private val FLAGS: Byte = 0x01
        private val RESERVED: Byte = 0x00
    }
}
