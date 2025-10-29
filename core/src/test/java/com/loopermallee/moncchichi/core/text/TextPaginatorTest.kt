package com.loopermallee.moncchichi.core.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TextPaginatorTest {

    private val config = TextPaginator.Config(
        targetWidthPx = 30f,
        fontSizePx = 21f,
        linesPerScreen = listOf(3, 2),
    )

    private val fakeMeasurer = object : TextPaginator.TextMeasurer {
        override fun measure(text: String): Float = text.length * 10f
    }

    @Test
    fun `paginate splits text into 3+2 line packets`() {
        val paginator = TextPaginator(config, fakeMeasurer)
        val text = "A1 B2 C3 D4 E5 F6 G7 H8 I9"

        val result = paginator.paginate(text)

        assertEquals(4, result.packets.size)
        assertEquals(listOf("A1", "B2", "C3"), result.packets[0].lines)
        assertEquals(listOf("D4", "E5"), result.packets[1].lines)
        assertEquals(listOf("F6", "G7", "H8"), result.packets[2].lines)
        assertEquals(listOf("I9", ""), result.packets[3].lines)

        val frames = result.toByteArrays(chunkCapacity = 64)
        assertEquals(4, frames.size)
        assertContentEquals("A1\nB2\nC3".encodeToByteArray(), frames[0])
        assertContentEquals("D4\nE5".encodeToByteArray(), frames[1])
        assertContentEquals("F6\nG7\nH8".encodeToByteArray(), frames[2])
        assertContentEquals("I9\n".encodeToByteArray(), frames[3])
    }

    @Test
    fun `paginate preserves blank content`() {
        val paginator = TextPaginator(config, fakeMeasurer)

        val result = paginator.paginate("")

        assertEquals(2, result.packets.size)
        assertEquals(listOf("", "", ""), result.packets.first().lines)
        assertEquals(listOf("", ""), result.packets.last().lines)

        val frames = result.toByteArrays(chunkCapacity = 64)
        assertEquals(2, frames.size)
        assertContentEquals("\n\n".encodeToByteArray(), frames[0])
        assertContentEquals("\n".encodeToByteArray(), frames[1])
    }

    @Test
    fun `paginate spills packets across mtu bound`() {
        val paginator = TextPaginator(config, fakeMeasurer)
        val text = "A1 B2 C3 D4 E5 F6 G7 H8 I9"

        val result = paginator.paginate(text)

        val frames = result.toByteArrays(chunkCapacity = 5)
        assertEquals(6, frames.size)
        assertContentEquals("A1\nB2".encodeToByteArray(), frames[0])
        assertContentEquals("\nC3".encodeToByteArray(), frames[1])
        assertContentEquals("D4\nE5".encodeToByteArray(), frames[2])
        assertContentEquals("F6\nG7".encodeToByteArray(), frames[3])
        assertContentEquals("\nH8".encodeToByteArray(), frames[4])
        assertContentEquals("I9\n".encodeToByteArray(), frames[5])
    }
}
