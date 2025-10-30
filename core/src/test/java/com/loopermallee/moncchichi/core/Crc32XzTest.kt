package com.loopermallee.moncchichi.core

import com.loopermallee.moncchichi.core.crc.Crc32Xz
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
class Crc32XzTest {
    @Test
    fun `crc32xz matches reference samples`() {
        val sample1 = "Moncchichi".toByteArray(Charsets.UTF_8)
        val sample2 = ByteArray(256) { it.toByte() }

        val crc1 = Crc32Xz.compute(sample1)
        val crc2 = Crc32Xz.compute(sample2)

        assertEquals(0xC6FCFA39u.toLong(), crc1, "CRC mismatch for UTF-8 sample")
        assertEquals(0x29058C73u.toLong(), crc2, "CRC mismatch for sequential bytes sample")

        assertContentEquals(
            byteArrayOf(0xC6.toByte(), 0xFC.toByte(), 0xFA.toByte(), 0x39),
            Crc32Xz.toBigEndianBytes(crc1),
        )
        assertContentEquals(
            byteArrayOf(0x29, 0x05, 0x8C.toByte(), 0x73),
            Crc32Xz.toBigEndianBytes(crc2),
        )
    }
}
