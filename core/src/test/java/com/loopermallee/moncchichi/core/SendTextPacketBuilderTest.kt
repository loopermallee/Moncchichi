package com.loopermallee.moncchichi.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SendTextPacketBuilderTest {

    @Test
    fun `buildSendText writes supplied page metadata`() {
        val builder = SendTextPacketBuilder()
        val payload = "Hello".encodeToByteArray()

        val frame = builder.buildSendText(
            currentPage = 0,
            totalPages = 4,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = payload,
        )

        assertEquals(0, frame[7].toUByte().toInt())
        assertEquals(4, frame[8].toUByte().toInt())
    }

    @Test
    fun `sequence byte increments across sequential frames`() {
        val builder = SendTextPacketBuilder()

        val first = builder.buildSendText(
            currentPage = 0,
            totalPages = 2,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Page1".encodeToByteArray(),
        )
        val second = builder.buildSendText(
            currentPage = 1,
            totalPages = 2,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Page2".encodeToByteArray(),
        )

        assertEquals(0, first[1].toUByte().toInt())
        assertEquals(1, second[1].toUByte().toInt())
    }

    @Test
    fun `even ai screen statuses use expected nibbles`() {
        val auto = EvenAiScreenStatus.AUTOMATIC
        val complete = EvenAiScreenStatus.AUTOMATIC_COMPLETE
        val manual = EvenAiScreenStatus.MANUAL
        val error = EvenAiScreenStatus.NETWORK_ERROR

        assertEquals(0x31, auto.value)
        assertEquals(0x41, complete.value)
        assertEquals(0x51, manual.value)
        assertEquals(0x61, error.value)
    }
}
