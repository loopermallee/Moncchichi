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
        screenStatus: ScreenStatus = DEFAULT_SCREEN_STATUS,
        textBytes: ByteArray,
    ): ByteArray {
        val safePage = currentPage.coerceIn(0, 0xFF)
        val safeTotal = totalPages.coerceIn(0, 0xFF)
        val status = screenStatus.toByte()

        val payload = ByteArray(HEADER_SIZE + textBytes.size)
        payload[0] = COMMAND
        payload[1] = nextSequence()
        payload[2] = FLAGS
        payload[3] = RESERVED
        payload[4] = status
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

    class ScreenStatus private constructor(private val raw: Int) {
        val value: Int get() = raw and 0xFF

        fun toByte(): Byte = value.toByte()

        fun withAction(action: ScreenAction): ScreenStatus = combine(context(), action)

        fun context(): ScreenContext {
            val nibble = value and 0xF0
            return ScreenContext.entries.firstOrNull { it.mask == nibble } ?: ScreenContext.Unknown
        }

        fun action(): ScreenAction {
            val nibble = value and 0x0F
            return ScreenAction.entries.firstOrNull { it.mask == nibble } ?: ScreenAction.Unknown
        }

        companion object {
            val TextShow: ScreenStatus = combine(ScreenContext.TextShow)
            val DEFAULT: ScreenStatus = TextShow

            object EvenAi {
                val Automatic: ScreenStatus = combine(ScreenContext.EvenAiAutomatic)
                val AutomaticComplete: ScreenStatus = combine(ScreenContext.EvenAiAutomaticComplete)
                val Manual: ScreenStatus = combine(ScreenContext.EvenAiManual)
                val NetworkError: ScreenStatus = combine(ScreenContext.EvenAiNetworkError)
            }

            fun combine(
                context: ScreenContext,
                action: ScreenAction = ScreenAction.DisplayNewContent,
            ): ScreenStatus {
                val upper = context.mask and 0xF0
                val lower = action.mask and 0x0F
                return ScreenStatus((upper or lower) and 0xFF)
            }

            fun fromRaw(value: Int): ScreenStatus = ScreenStatus(value and 0xFF)
        }
    }

    enum class ScreenContext(internal val mask: Int) {
        TextShow(0x70),
        EvenAiAutomatic(0x30),
        EvenAiAutomaticComplete(0x40),
        EvenAiManual(0x50),
        EvenAiNetworkError(0x60),
        Unknown(0x00),
    }

    enum class ScreenAction(internal val mask: Int) {
        DisplayNewContent(0x01),
        Unknown(0x00),
    }

    companion object {
        const val HEADER_SIZE = 9
        val DEFAULT_SCREEN_STATUS: ScreenStatus = ScreenStatus.DEFAULT
        private val COMMAND: Byte = 0x4E
        private val FLAGS: Byte = 0x01
        private val RESERVED: Byte = 0x00
    }
}
