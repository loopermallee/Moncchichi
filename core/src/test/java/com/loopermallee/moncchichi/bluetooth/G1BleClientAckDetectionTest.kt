package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G1BleClientAckDetectionTest {

    @Test
    fun `detectAck returns true for forward binary marker`() {
        val payload = byteArrayOf(0x01, 0x02, 0xC9.toByte(), 0x04, 0x05)

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck returns true for reverse binary marker`() {
        val payload = byteArrayOf(0x10, 0x04, 0xCA.toByte(), 0x20)

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck recognizes textual ok`() {
        val payload = "ok".encodeToByteArray()

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck trims ascii whitespace when matching ok`() {
        val payload = "\t Ok\r\n".encodeToByteArray()

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck returns false for unrelated text`() {
        val payload = "not ack".encodeToByteArray()

        assertFalse(payload.detectAck())
    }

    @Test
    fun `detectAck returns false for partial binary markers`() {
        val onlyFirst = byteArrayOf(0xC9.toByte())
        val onlySecond = byteArrayOf(0x04)
        val mismatchedPair = byteArrayOf(0xC9.toByte(), 0x05)

        assertFalse(onlyFirst.detectAck())
        assertFalse(onlySecond.detectAck())
        assertFalse(mismatchedPair.detectAck())
    }

    @Test
    fun `detectAck returns false for malformed text without markers`() {
        val payload = byteArrayOf(0xC3.toByte(), 0x28)

        assertFalse(payload.detectAck())
    }
}
