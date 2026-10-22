package com.nomily.app.core.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WavFormatTest {

    private fun u32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    @Test
    fun headerCarriesFormatAndSizes() {
        val h = WavFormat.header(32_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals(36L + 32_000, u32(h, 4))
        assertEquals(16_000L, u32(h, 24))
        assertEquals(32_000L, u32(h, 28))
        assertEquals("data", String(h, 36, 4))
        assertEquals(32_000L, u32(h, 40))
    }

    @Test
    fun streamingHeaderHasUnknownSizes() {
        val h = WavFormat.header(null)
        assertEquals(0xFFFF_FFFFL, u32(h, 4))
        assertEquals(0xFFFF_FFFFL, u32(h, 40))
        // Everything but the two size fields is identical.
        val known = WavFormat.header(0)
        for (i in h.indices) if (i !in 4..7 && i !in 40..43) assertEquals(known[i], h[i])
    }

    @Test
    fun samplesAreLittleEndianSigned() {
        val pcm = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0x7F)
        assertContentEquals(shortArrayOf(1, -1, Short.MIN_VALUE), WavFormat.samples(pcm))
        assertEquals(2, WavFormat.samples(pcm, 4).size)
    }

    @Test
    fun secondsFromBytes() = assertEquals(1.5, WavFormat.seconds(48_000))
}
