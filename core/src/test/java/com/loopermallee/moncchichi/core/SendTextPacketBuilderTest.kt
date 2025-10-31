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
            totalPackageCount = 1,
            currentPackageIndex = 0,
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
            totalPackageCount = 1,
            currentPackageIndex = 0,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Page1".encodeToByteArray(),
        )
        val second = builder.buildSendText(
            currentPage = 1,
            totalPages = 2,
            totalPackageCount = 1,
            currentPackageIndex = 0,
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

    @Test
    fun `package count metadata written for multi-frame pages`() {
        val builder = SendTextPacketBuilder()

        val first = builder.buildSendText(
            currentPage = 0,
            totalPages = 3,
            totalPackageCount = 3,
            currentPackageIndex = 0,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "First".encodeToByteArray(),
        )
        val middle = builder.buildSendText(
            currentPage = 0,
            totalPages = 3,
            totalPackageCount = 3,
            currentPackageIndex = 1,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Second".encodeToByteArray(),
        )
        val last = builder.buildSendText(
            currentPage = 0,
            totalPages = 3,
            totalPackageCount = 3,
            currentPackageIndex = 2,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = "Third".encodeToByteArray(),
        )

        assertEquals(3, first[2].toUByte().toInt())
        assertEquals(0, first[3].toUByte().toInt())
        assertEquals(3, middle[2].toUByte().toInt())
        assertEquals(1, middle[3].toUByte().toInt())
        assertEquals(3, last[2].toUByte().toInt())
        assertEquals(2, last[3].toUByte().toInt())
    }

    @Test
    fun `default screen status encodes manual text show`() {
        val builder = SendTextPacketBuilder()

        val frame = builder.buildSendText(
            currentPage = 0,
            totalPages = 1,
            totalPackageCount = 1,
            currentPackageIndex = 0,
            textBytes = ByteArray(0),
        )

        assertEquals(0x71, frame[4].toUByte().toInt())
    }

    @Test
    fun `even ai statuses emit expected header bytes`() {
        val builder = SendTextPacketBuilder()

        val automatic = builder.buildSendText(
            currentPage = 0,
            totalPages = 1,
            totalPackageCount = 1,
            currentPackageIndex = 0,
            screenStatus = EvenAiScreenStatus.AUTOMATIC,
            textBytes = ByteArray(0),
        )
        val automaticComplete = builder.buildSendText(
            currentPage = 0,
            totalPages = 1,
            totalPackageCount = 1,
            currentPackageIndex = 0,
            screenStatus = EvenAiScreenStatus.AUTOMATIC_COMPLETE,
            textBytes = ByteArray(0),
        )

        assertEquals(0x31, automatic[4].toUByte().toInt())
        assertEquals(0x41, automaticComplete[4].toUByte().toInt())
    }
}
