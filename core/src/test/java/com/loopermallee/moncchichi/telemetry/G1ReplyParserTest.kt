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
        assertContentEquals(byteArrayOf(0xC9.toByte()), ack.payload)
    }

    @Test
    fun `parseNotify recognizes negative ack without explicit length`() {
        val nackBytes = byteArrayOf(0x1E, 0xCA.toByte())

        val result = G1ReplyParser.parseNotify(nackBytes)

        val ack = assertIs<G1ReplyParser.Parsed.Ack>(result)
        assertEquals(0x1E, ack.op)
        assertEquals(false, ack.success)
        assertNull(ack.sequence)
        assertContentEquals(byteArrayOf(0xCA.toByte()), ack.payload)
    }
}
