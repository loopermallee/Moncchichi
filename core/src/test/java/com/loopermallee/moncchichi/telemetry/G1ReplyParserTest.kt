package com.loopermallee.moncchichi.telemetry

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class G1ReplyParserTest {

    @Test
    fun `parseNotify recognizes ack without explicit length`() {
        val ackBytes = byteArrayOf(0x1E, 0xC9.toByte())

        val result = G1ReplyParser.parseNotify(ackBytes)

        val ack = assertIs<G1ReplyParser.Parsed.Ack>(result)
        assertEquals(0x1E, ack.op)
        assertEquals(true, ack.success)
        assertNull(ack.sequence)
        assertContentEquals(byteArrayOf(), ack.payload)
    }

    @Test
    fun `parseNotify recognizes negative ack without explicit length`() {
        val nackBytes = byteArrayOf(0x1E, 0xCA.toByte())

        val result = G1ReplyParser.parseNotify(nackBytes)

        val ack = assertIs<G1ReplyParser.Parsed.Ack>(result)
        assertEquals(0x1E, ack.op)
        assertEquals(false, ack.success)
        assertNull(ack.sequence)
        assertContentEquals(byteArrayOf(), ack.payload)
    }

    @Test
    fun `parseNotify recognizes ack with trailing payload`() {
        val ackBytes = byteArrayOf(0x0E, 0xC9.toByte(), 0x01)

        val result = G1ReplyParser.parseNotify(ackBytes)

        val ack = assertIs<G1ReplyParser.Parsed.Ack>(result)
        assertEquals(0x0E, ack.op)
        assertEquals(true, ack.success)
        assertNull(ack.sequence)
        assertContentEquals(byteArrayOf(0x01), ack.payload)
    }

    @Test
    fun `parseNotify recognizes negative ack with trailing payload`() {
        val nackBytes = byteArrayOf(0x0E, 0xCA.toByte(), 0x01)

        val result = G1ReplyParser.parseNotify(nackBytes)

        val ack = assertIs<G1ReplyParser.Parsed.Ack>(result)
        assertEquals(0x0E, ack.op)
        assertEquals(false, ack.success)
        assertNull(ack.sequence)
        assertContentEquals(byteArrayOf(0x01), ack.payload)
    }

    @Test
    fun `parseNotify surfaces Even AI activation`() {
        val bytes = byteArrayOf(0xF5.toByte(), 0x17)

        val result = G1ReplyParser.parseNotify(bytes)

        val event = assertIs<G1ReplyParser.Parsed.EvenAi>(result)
        assertIs<G1ReplyParser.EvenAiEvent.ActivationRequested>(event.event)
    }

    @Test
    fun `parseNotify surfaces Even AI recording stop`() {
        val bytes = byteArrayOf(0xF5.toByte(), 0x24)

        val result = G1ReplyParser.parseNotify(bytes)

        val event = assertIs<G1ReplyParser.Parsed.EvenAi>(result)
        assertIs<G1ReplyParser.EvenAiEvent.RecordingStopped>(event.event)
    }
}
