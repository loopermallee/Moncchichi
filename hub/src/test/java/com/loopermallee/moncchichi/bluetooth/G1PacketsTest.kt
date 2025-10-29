package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals

class G1PacketsTest {

    @Test
    fun `brightness command uses documented opcode`() {
        val payload = G1Packets.brightness(55)

        assertContentEquals(byteArrayOf(0x01, 0x03, 55), payload)
    }

    @Test
    fun `brightness command respects target mask`() {
        val payload = G1Packets.brightness(10, G1Packets.BrightnessTarget.LEFT)

        assertContentEquals(byteArrayOf(0x01, 0x01, 10), payload)
    }

    @Test
    fun `reboot command emits reboot sequence`() {
        val payload = G1Packets.reboot()

        assertContentEquals(byteArrayOf(0x23, 0x72, 0x00), payload)
    }

    @Test
    fun `reboot command includes mode code`() {
        val payload = G1Packets.reboot(G1Packets.RebootMode.SAFE)

        assertContentEquals(byteArrayOf(0x23, 0x72, 0x01), payload)
    }
}
