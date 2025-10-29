package com.loopermallee.moncchichi.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SendTextPacketBuilderTest {

    @Test
    fun `buildSendText writes supplied page metadata`() {
        val builder = SendTextPacketBuilder()
        val payload = "Hello".encodeToByteArray()

        val frame = builder.buildSendText(
            currentPage = 3,
            totalPages = 5,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = payload,
        )

        assertEquals(3, frame[7].toUByte().toInt())
        assertEquals(5, frame[8].toUByte().toInt())
    }

    @Test
    fun `sequence byte increments across sequential frames`() {
        val builder = SendTextPacketBuilder()

        val first = builder.buildSendText(
            currentPage = 1,
            totalPages = 2,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Page1".encodeToByteArray(),
        )
        val second = builder.buildSendText(
            currentPage = 2,
            totalPages = 2,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Page2".encodeToByteArray(),
        )

        assertEquals(0, first[1].toUByte().toInt())
        assertEquals(1, second[1].toUByte().toInt())
    }

    @Test
    fun `even ai screen statuses use expected nibbles`() {
        val auto = SendTextPacketBuilder.ScreenStatus.EvenAi.Automatic
        val complete = SendTextPacketBuilder.ScreenStatus.EvenAi.AutomaticComplete
        val manual = SendTextPacketBuilder.ScreenStatus.EvenAi.Manual
        val error = SendTextPacketBuilder.ScreenStatus.EvenAi.NetworkError

        assertEquals(0x31, auto.value)
        assertEquals(0x41, complete.value)
        assertEquals(0x51, manual.value)
        assertEquals(0x61, error.value)
    }
}
