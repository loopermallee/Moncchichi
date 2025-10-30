package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G1BleClientAckDetectionTest {

    @Test
    fun `detectAck returns true for success ack with payload`() {
        val payload = byteArrayOf(0x0E, 0xC9.toByte(), 0x01)

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck returns true for failure ack`() {
        val payload = byteArrayOf(0x1E, 0xCA.toByte())

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
    fun `detectAck recognizes firmware ack ping`() {
        val payload = "ACK:PING".encodeToByteArray()

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck recognizes firmware ack keepalive with whitespace`() {
        val payload = "\r\nACK:KEEPALIVE\n".encodeToByteArray()

        assertTrue(payload.detectAck())
    }

    @Test
    fun `detectAck returns false for unrelated text`() {
        val payload = "not ack".encodeToByteArray()

        assertFalse(payload.detectAck())
    }

    @Test
    fun `detectAck ignores mixed case ack tokens`() {
        val payload = "ACK:keepAlive".encodeToByteArray()

        assertFalse(payload.detectAck())
    }

    @Test
    fun `detectAck returns false for partial binary markers`() {
        val onlyOpcode = byteArrayOf(0x04)
        val onlyStatus = byteArrayOf(0xC9.toByte())
        val mismatchedStatus = byteArrayOf(0x04, 0x05)

        assertFalse(onlyOpcode.detectAck())
        assertFalse(onlyStatus.detectAck())
        assertFalse(mismatchedStatus.detectAck())
    }

    @Test
    fun `detectAck returns false for malformed text without markers`() {
        val payload = byteArrayOf(0xC3.toByte(), 0x28)

        assertFalse(payload.detectAck())
    }
}
