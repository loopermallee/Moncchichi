package com.loopermallee.moncchichi.core.crc

import java.util.zip.CRC32

object Crc32Xz {
    private const val BYTE_MASK = 0xFF
    private const val CRC_MASK = 0xFFFFFFFFL

    fun compute(data: ByteArray): Long {
        val crc32 = CRC32()
        crc32.update(data)
        return crc32.value and CRC_MASK
    }

    fun toBigEndianBytes(value: Long): ByteArray {
        val normalized = value and CRC_MASK
        return byteArrayOf(
            ((normalized shr 24) and BYTE_MASK.toLong()).toByte(),
            ((normalized shr 16) and BYTE_MASK.toLong()).toByte(),
            ((normalized shr 8) and BYTE_MASK.toLong()).toByte(),
            (normalized and BYTE_MASK.toLong()).toByte(),
        )
    }
}
