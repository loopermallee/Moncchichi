package com.loopermallee.moncchichi.hub.tools.impl

import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BleToolLiveImplMapCommandTest {

    @Test
    fun `ping command maps to heartbeat frame for both lenses`() {
        G1Packets.resetPingSequenceForTests()
        val (payload, target) = BleToolLiveImpl.mapCommand("ping").single()

        assertContentEquals(byteArrayOf(0x25, 0x00), payload)
        assertEquals(MoncchichiBleService.Target.Both, target)
    }

    @Test
    fun `left lens brightness uses left target`() {
        val (payload, target) = BleToolLiveImpl.mapCommand("LENS_LEFT_ON").single()

        assertContentEquals(byteArrayOf(0x01, 0x01, 80), payload)
        assertEquals(MoncchichiBleService.Target.Left, target)
    }

    @Test
    fun `brightness up defaults to right target`() {
        val (payload, target) = BleToolLiveImpl.mapCommand("BRIGHTNESS_UP").single()

        assertContentEquals(byteArrayOf(0x01, 0x02, 80), payload)
        assertEquals(MoncchichiBleService.Target.Right, target)
    }

    @Test
    fun `brightness down defaults to right target`() {
        val (payload, target) = BleToolLiveImpl.mapCommand("BRIGHTNESS_DOWN").single()

        assertContentEquals(byteArrayOf(0x01, 0x02, 30), payload)
        assertEquals(MoncchichiBleService.Target.Right, target)
    }

    @Test
    fun `reboot command maps to system reboot frame`() {
        val (payload, target) = BleToolLiveImpl.mapCommand("REBOOT").single()

        assertContentEquals(byteArrayOf(0x23, 0x72), payload)
        assertEquals(MoncchichiBleService.Target.Both, target)
    }
}
