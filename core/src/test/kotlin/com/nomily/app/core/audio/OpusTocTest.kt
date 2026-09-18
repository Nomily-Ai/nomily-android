package com.nomily.app.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decoding for RFC 6716 §3.1 Table 2 / §3.2.
 *
 * ⚠️ Only the first item here is a true authoritative assertion (copied verbatim from the RFC table and verified line by line).
 * The subsequent items are "the decoder matches OggOpus constants" and "the decoder matches real device data" —
 * these are consistency checks, not authoritative. The two categories are mixed.
 */
class OpusTocTest {

    /**
     * Table 2: All 32 configs tested per core — expected values are copied directly from the RFC specification table, not derived from the DUT code.
     *
     * Each entry: config → (Mode, Bandwidth, Frame Length in microseconds)
     */
    @Test
    fun `rfc 6716 table 2 all 32 configs`() {
        val silkSizes = intArrayOf(10_000, 20_000, 40_000, 60_000)
        val celtSizes = intArrayOf(2_500, 5_000, 10_000, 20_000)
        val hybridSizes = intArrayOf(10_000, 20_000)

        data class Row(val range: IntRange, val mode: OpusMode, val bw: OpusBandwidth, val sizes: IntArray)
        val table = listOf(
            Row(0..3, OpusMode.SILK_ONLY, OpusBandwidth.NB, silkSizes),
            Row(4..7, OpusMode.SILK_ONLY, OpusBandwidth.MB, silkSizes),
            Row(8..11, OpusMode.SILK_ONLY, OpusBandwidth.WB, silkSizes),
            Row(12..13, OpusMode.HYBRID, OpusBandwidth.SWB, hybridSizes),
            Row(14..15, OpusMode.HYBRID, OpusBandwidth.FB, hybridSizes),
            Row(16..19, OpusMode.CELT_ONLY, OpusBandwidth.NB, celtSizes),
            Row(20..23, OpusMode.CELT_ONLY, OpusBandwidth.WB, celtSizes),
            Row(24..27, OpusMode.CELT_ONLY, OpusBandwidth.SWB, celtSizes),
            Row(28..31, OpusMode.CELT_ONLY, OpusBandwidth.FB, celtSizes),
        )

        var covered = 0
        for (row in table) {
            for ((i, config) in row.range.withIndex()) {
                val toc = config shl 3                       // s=0, c=0
                val got = parseOpusToc(toc)
                assertEquals(config, got.config, "config field decoded incorrectly")
                assertEquals(row.mode, got.mode, "Mode of config $config")
                assertEquals(row.bw, got.bandwidth, "Bandwidth of config $config")
                assertEquals(row.sizes[i], got.frameDurationUs, "frame duration of config $config")
                covered++
            }
        }
        assertEquals(32, covered, "Table 2 must cover all 32 configs")
    }

    /** Bit order: `config`(bit 0-4) + `s`(bit 5) + `c`(bit 6-7); bit 0 is the most significant bit. */
    @Test
    fun `toc bit order`() {
        // 0x4b = 0100 1011 → config=01001=9, s=0, c=11=3
        parseOpusToc(0x4b).let {
            assertEquals(9, it.config)
            assertEquals(false, it.stereo)
            assertEquals(3, it.frameCountCode)
        }
        // 0x48 = 0100 1000 → config=9, s=0, c=0
        parseOpusToc(0x48).let {
            assertEquals(9, it.config)
            assertEquals(0, it.frameCountCode)
        }
        // Stereo bit
        assertTrue(parseOpusToc(0x4c).stereo)   // 0100 1100
        // Only look at the lower 8 bits: when converting Byte to Int with sign extension, 0x8b should not become negative
        assertEquals(parseOpusToc(0x8b), parseOpusToc((0x8b).toByte().toInt() and 0xff))
    }

    /** Frame count: code 0/1/2 is fixed, code 3 reads the lower 6 bits of the second byte. */
    @Test
    fun `number of frames in packet`() {
        assertEquals(1, opusPacketFrameCount(byteArrayOf(0x48)))
        assertEquals(2, opusPacketFrameCount(byteArrayOf(0x49)))
        assertEquals(2, opusPacketFrameCount(byteArrayOf(0x4a)))
        assertEquals(1, opusPacketFrameCount(byteArrayOf(0x4b, 0x01)))
        assertEquals(3, opusPacketFrameCount(byteArrayOf(0x4b, 0x03)))
        // The two high bits of v/p should not be included in M
        assertEquals(1, opusPacketFrameCount(byteArrayOf(0x4b, 0xc1.toByte())))

        // RFC: "M MUST NOT be zero"
        assertEquals(
            OpusPacketError.Kind.ZERO_FRAME_COUNT,
            assertFailsWith<OpusPacketError> { opusPacketFrameCount(byteArrayOf(0x4b, 0x00)) }.kind,
        )
        assertEquals(
            OpusPacketError.Kind.MISSING_FRAME_COUNT_BYTE,
            assertFailsWith<OpusPacketError> { opusPacketFrameCount(byteArrayOf(0x4b)) }.kind,
        )
        assertEquals(
            OpusPacketError.Kind.EMPTY,
            assertFailsWith<OpusPacketError> { opusPacketFrameCount(byteArrayOf()) }.kind,
        )
    }

    /**
     * Consistency (not authority): `OPUS_FRAME_DURATION_MS` must equal
     * the frame duration derived from the device's actual TOC. If this fails,
     * it indicates a mismatch between the constant and the data.
     */
    @Test
    fun `ogg opus frame length constant matches device toc`() {
        val info = parseOpusToc(0x48)      // The first byte of code 0 appearing in the clip on a real device
        assertEquals(OPUS_FRAME_DURATION_MS * 1000, info.frameDurationUs)
        assertEquals(960, info.frameSamples48k, "48kHz granule step size")
        assertEquals(OpusBandwidth.WB, info.bandwidth, "a comment stating 12kHz (MB) would be incorrect")
        assertEquals(16_000, info.bandwidth.sampleRateHz)
    }

    /**
     * Consistency: every 40-byte boundary in the real-device 250-frame clip must be valid.
     * Config 9 packet header, with exactly 1 frame per packet — this is the basis for "40 bytes = 20ms".
     *
     * Here we only use a summary of the real-device data (two possible values for the first byte sequence + count),
     * without copying the full 10000 bytes into the unit test;
     * the complete data is a 250-frame clip captured from the real device.
     */
    @Test
    fun `real device first byte two values both are one frame 20ms`() {
        for ((toc, second) in listOf(0x4b to 0x01, 0x48 to 0x00)) {
            val info = parseOpusToc(toc)
            assertEquals(9, info.config)
            assertEquals(20_000, info.frameDurationUs)
            assertEquals(1, opusPacketFrameCount(byteArrayOf(toc.toByte(), second.toByte())))
        }
    }
}
