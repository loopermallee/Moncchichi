package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `parseAckOutcome detects ack byte within framed payload`() {
        val payload = byteArrayOf(0x26, 0x06, 0x00, 0x01, 0x08, 0xC9.toByte(), 0x00, 0x00)

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome detects nack byte within framed payload`() {
        val payload = byteArrayOf(0x26, 0x06, 0x00, 0x01, 0x08, 0xCA.toByte(), 0x01, 0x00)

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
    fun `parseAckOutcome recognizes ok embedded in multiline response`() {
        val payload = "ver 1.6.5\r\nOK\r\n>".encodeToByteArray()

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome recognizes ok surrounded by whitespace`() {
        val payload = "MTU set:498\n   OK   ".encodeToByteArray()

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
    fun `parseAckOutcome recognizes textual ready warm-up`() {
        val payload = "READY".encodeToByteArray()

        val result = payload.parseAckOutcome()

        val success = assertIs<AckOutcome.Success>(result)
        assertTrue(success.warmupPrompt)
    }

    @Test
    fun `parseAckOutcome recognizes textual hello warm-up`() {
        val payload = "hello there".encodeToByteArray()

        val result = payload.parseAckOutcome()

        val success = assertIs<AckOutcome.Success>(result)
        assertTrue(success.warmupPrompt)
    }

    @Test
    fun `parseAckOutcome recognizes textual welcome warm-up`() {
        val payload = " welcome back".encodeToByteArray()

        val result = payload.parseAckOutcome()

        val success = assertIs<AckOutcome.Success>(result)
        assertTrue(success.warmupPrompt)
    }

    @Test
    fun `parseAckOutcome recognizes bare status ack byte`() {
        val payload = byteArrayOf(G1Protocols.STATUS_OK.toByte())

        val result = payload.parseAckOutcome()

        assertIs<AckOutcome.Success>(result)
    }

    @Test
    fun `parseAckOutcome recognizes legacy ack control byte`() {
        val payload = byteArrayOf(0x04)

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
    fun `parseAckOutcome returns null when ok is absent from multiline response`() {
        val payload = "ver 1.6.5\r\n>".encodeToByteArray()

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
        val mismatchedStatus = byteArrayOf(0x04, 0x05)

        assertNull(mismatchedStatus.parseAckOutcome())
    }

    @Test
    fun `parseAckOutcome returns null for malformed text without markers`() {
        val payload = byteArrayOf(0xC3.toByte(), 0x28)

        val result = payload.parseAckOutcome()

        assertNull(result)
    }
}
