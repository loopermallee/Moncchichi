package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class G1ParserTest {

    @Test
    fun `battery frame parses percentages`() {
        val inbound = G1Parser.parse(
            byteArrayOf(0x2C, 0x01, 55.toByte(), 60.toByte(), 70.toByte())
        )

        val battery = assertIs<G1Inbound.Battery>(inbound)
        assertEquals(55, battery.leftPct)
        assertEquals(60, battery.rightPct)
        assertEquals(70, battery.casePct)
    }

    @Test
    fun `battery frame tolerates out of range values`() {
        val inbound = G1Parser.parse(
            byteArrayOf(0x2C, 0x01, (-1).toByte(), 101.toByte(), 50.toByte())
        )

        val battery = assertIs<G1Inbound.Battery>(inbound)
        assertNull(battery.leftPct)
        assertNull(battery.rightPct)
        assertEquals(50, battery.casePct)
    }

    @Test
    fun `firmware frame parses utf8 string`() {
        val payload = "\u0000\u0000v1.23\u0000\u0000".toByteArray()
        val frame = byteArrayOf(0x2C, 0x02) + payload

        val firmware = assertIs<G1Inbound.Firmware>(G1Parser.parse(frame))
        assertEquals("v1.23", firmware.version)
    }
}
