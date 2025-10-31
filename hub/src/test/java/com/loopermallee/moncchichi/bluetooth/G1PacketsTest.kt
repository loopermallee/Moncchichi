package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class G1PacketsTest {

    @Test
    fun `uuids mirror nordic uart service`() {
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", G1Uuids.service.toString().lowercase())
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", G1Uuids.writeCharacteristic.toString().lowercase())
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", G1Uuids.notifyCharacteristic.toString().lowercase())
    }

    @Test
    fun `brightness command uses documented opcode`() {
        val payload = G1Packets.brightness(55)

        assertContentEquals(byteArrayOf(0x01, 0x02, 55), payload)
    }

    @Test
    fun `brightness command respects target mask`() {
        val payload = G1Packets.brightness(10, G1Packets.BrightnessTarget.LEFT)

        assertContentEquals(byteArrayOf(0x01, 0x01, 10), payload)
    }

    @Test
    fun `ping command emits heartbeat opcode and sequence`() {
        G1Packets.resetPingSequenceForTests()
        val first = G1Packets.ping()
        val second = G1Packets.ping()

        assertContentEquals(byteArrayOf(0x25, 0x00), first)
        assertContentEquals(byteArrayOf(0x25, 0x01), second)
    }

    @Test
    fun `reboot command emits reboot sequence`() {
        val payload = G1Packets.reboot()

        assertContentEquals(byteArrayOf(0x23, 0x72), payload)
    }
}
