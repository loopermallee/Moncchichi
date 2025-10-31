package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class G1BleClientAckDetectionTest {

    @Test
    fun `parseAckOutcome returns success for status 0xC9`() {
        val payload = byteArrayOf(0x0E, 0xC9.toByte(), 0x01)

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome returns failure for status 0xCA`() {
        val payload = byteArrayOf(0x1E, 0xCA.toByte())

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Failure>(result)
    }

    @Test
    fun `parseAckOutcome recognizes textual ok`() {
        val payload = "ok".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome trims ascii whitespace when matching ok`() {
        val payload = "\t Ok\r\n".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome recognizes firmware ack ping`() {
        val payload = "ACK:PING".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome recognizes firmware ack keepalive with whitespace`() {
        val payload = "\r\nACK:KEEPALIVE\n".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome returns null for unrelated text`() {
        val payload = "not ack".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertNull(result)
    }

    @Test
    fun `parseAckOutcome ignores mixed case ack tokens`() {
        val payload = "ACK:keepAlive".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertNull(result)
    }

    @Test
    fun `parseAckOutcome returns null for partial binary markers`() {
        val onlyOpcode = byteArrayOf(0x04)
        val onlyStatus = byteArrayOf(0xC9.toByte())
        val mismatchedStatus = byteArrayOf(0x04, 0x05)

        assertNull(onlyOpcode.parseAckOutcome())
        assertNull(onlyStatus.parseAckOutcome())
        assertNull(mismatchedStatus.parseAckOutcome())
    }

    @Test
    fun `parseAckOutcome returns null for malformed text without markers`() {
        val payload = byteArrayOf(0xC3.toByte(), 0x28)

        val result = payload.parseAckOutcome()

        assertNull(result)
    }
}
