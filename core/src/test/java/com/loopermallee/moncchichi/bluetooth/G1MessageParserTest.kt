package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun telemetryUpdateMapsCaseOpen() {
        val update = byteArrayOf(0x0E, 0x01).toTelemetryUpdateOrNull()
        assertNotNull(update)
        assertEquals(true, update.caseOpen)
        assertNull(update.inCase)
        assertNull(update.foldState)
    }

    @Test
    fun telemetryUpdateMapsInCase() {
        val update = byteArrayOf(0x0F, 0x00).toTelemetryUpdateOrNull()
        assertNotNull(update)
        assertEquals(false, update.inCase)
        assertNull(update.caseOpen)
        assertNull(update.foldState)
    }

    @Test
    fun telemetryUpdateMapsFoldStateFromWearDetectOpcodes() {
        val candidates = listOf(0x18, G1Protocols.CMD_WEAR_DETECT)
        candidates.forEach { opcode ->
            val update = byteArrayOf(opcode.toByte(), 0x01).toTelemetryUpdateOrNull()
            assertNotNull(update, "Expected fold state for opcode=0x%02X".format(opcode))
            assertEquals(true, update.foldState)
            assertNull(update.caseOpen)
            assertNull(update.inCase)
        }
    }

    @Test
    fun nonAsciiPayloadsReturnNullAck() {
        val binary = byteArrayOf(0x4D, 0xC9.toByte())
        assertNull(binary.toAckFromAsciiOrNull())
        assertTrue(G1MessageParser.isAsciiOk(byteArrayOf()).not())
    }
}
