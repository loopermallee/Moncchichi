package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class G1MessageParserTest {

    @Test
    fun asciiOkVariantsRecognized() {
        val payloads = listOf(
            "OK".encodeToByteArray(),
            "OK\r\n".encodeToByteArray(),
            "> OK\r\n".encodeToByteArray(),
            " ok \r\n".encodeToByteArray(),
        )

        payloads.forEach { payload ->
            assertTrue(G1MessageParser.isAsciiOk(payload), "Expected OK token for ${payload.decodeToString()}")
            val ack = payload.toAckFromAsciiOrNull()
            assertIs<AckOutcome.Success>(ack)
            assertEquals(G1Protocols.CMD_SYS_INFO, ack.opcode)
            assertEquals(G1Protocols.STATUS_OK, ack.status)
        }
    }

    @Test
    fun asciiBusyAndErrorRecognized() {
        val busy = "BUSY\r\n".encodeToByteArray().toAckFromAsciiOrNull()
        assertIs<AckOutcome.Busy>(busy)
        assertEquals(G1Protocols.CMD_SYS_INFO, busy.opcode)

        val error = "ERROR\r\n".encodeToByteArray().toAckFromAsciiOrNull()
        assertIs<AckOutcome.Failure>(error)
        assertEquals(G1Protocols.CMD_SYS_INFO, error.opcode)
    }

    @Test
    fun nonAsciiPayloadsReturnNullAck() {
        val binary = byteArrayOf(0x4D, 0xC9.toByte())
        assertNull(binary.toAckFromAsciiOrNull())
        assertTrue(G1MessageParser.isAsciiOk(byteArrayOf()).not())
    }
}
