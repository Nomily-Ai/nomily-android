package com.nomily.app.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round‑trip check of `oggDurationMs`.
 *
 * **Why this counts as valid evidence**: the two paths are **independent algorithms**, not a self‑comparison —
 *   Path A: `rawOpusDurationMs` computed as **frame count × 20 ms** (count frames)
 *   Path B: `oggDurationMs` parses **the container we generate**, reads the last page's granule and subtracts preskip
 * If the logic for writing page tables / segment tables / granule accumulation is wrong, the two results will mismatch.
 *
 * ⚠️ However, it **is not an external authority**: there is no reference implementation to
 * compare against, but the two paths are at least independent.
 * The semantics of granule are defined by RFC 3533 / RFC 7845.
 */
class OggDurationTest {

    private fun frames(n: Int): ByteArray {
        val out = ByteArray(n * OPUS_FRAME_SIZE)
        for (i in 0 until n) {
            // TOC uses 0x48 (config 9 = SILK WB 20ms, **code 0 = single frame**).
            // Previously it was written as 0x4B with the comment "valid TOC" — that is code 3, which must be followed by a frame count byte,
            // but here the remaining bytes are 0 → M=0, and RFC 6716 §3.2.5 explicitly states "M MUST NOT be zero", making it an illegal packet.
            // This does not affect the Ogg container itself (we only split into 40‑byte chunks and never parse TOC), but the comment must not remain incorrect.
            out[i * OPUS_FRAME_SIZE] = 0x48
            for (k in 1 until OPUS_FRAME_SIZE) {
                out[i * OPUS_FRAME_SIZE + k] = ((i + k) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun roundTrip(n: Int) {
        val raw = frames(n)
        val ogg = rawOpusToOggBytes(raw)
        assertEquals(
            rawOpusDurationMs(raw),
            oggDurationMs(ogg),
            "$n frames: duration computed from frame count must match duration computed from parsing container",
        )
    }

    @Test fun `single frame round-trips`() = roundTrip(1)
    @Test fun `full page round-trips`() = roundTrip(50)
    @Test fun `across a page boundary round-trips`() = roundTrip(51)

    /** On a real device that clip is 250 frames = 5 seconds, spanning 5 audio pages. */
    @Test
    fun `real device length round-trips`() {
        roundTrip(250)
        assertEquals(5_000, oggDurationMs(rawOpusToOggBytes(frames(250))))
    }

    @Test
    fun `not an ogg stream yields null rather than throwing`() {
        // The library may contain arbitrary user‑imported files — if duration cannot be obtained, fall back to a reduced display; must not crash the screen
        assertNull(oggDurationMs(ByteArray(0)))
        assertNull(oggDurationMs("not an ogg file at all".toByteArray()))
    }

    // ── Tail parsing ────────────────────────────────────────────────────
    //
    // The reference here is the full‑file version [oggDurationMs]: both must yield the same answer.
    // Only the tail is sliced to avoid loading a 25 GB library into memory; the semantics must not change because of this.

    /** Only the last N bytes are provided; the answer must match parsing the entire file. */
    private fun tailMatchesWhole(frames: Int, tailBytes: Int) {
        val ogg = rawOpusToOggBytes(frames(frames))
        val tail = ogg.copyOfRange(maxOf(0, ogg.size - tailBytes), ogg.size)
        assertEquals(
            oggDurationMs(ogg),
            oggDurationMsFromTail(tail),
            "$frames frames, tail $tailBytes bytes: tail parsing must be consistent with full file parsing",
        )
    }

    @Test fun `tail parse matches whole file on one page`() = tailMatchesWhole(50, 4096)
    @Test fun `tail parse matches whole file across pages`() = tailMatchesWhole(250, 4096)

    /** The tail cut in the middle of a page must also be correct — real calls count a fixed number of bytes from the file end, not aligned to page boundaries. */
    @Test
    fun `tail parse works when the window starts mid-page`() {
        // 250 frames = 5 pages, each about 2 KB; a 3000‑byte window will cut in the middle of the second‑last page
        tailMatchesWhole(250, 3000)
    }

    /**
     * Window smaller than the last page → there is no page header inside → return null.
     *
     * This is not a bug; it's a **precondition** of this function: the caller must provide a sufficiently large window
     * (Ogg single‑page maximum is about 64 KB, so 128 KB will certainly include the last page's header),
     * and if null is returned, fall back to the full‑file [oggDurationMs]. This test case pins that precondition.
     */
    @Test
    fun `tail parse yields null when the window is smaller than the last page`() {
        val ogg = rawOpusToOggBytes(frames(250))
        assertNull(oggDurationMsFromTail(ogg.copyOfRange(ogg.size - 1500, ogg.size)))
    }

    /** When the entire file is provided, it is equivalent to the full-file version. */
    @Test
    fun `tail parse over the whole buffer equals whole file parse`() {
        val ogg = rawOpusToOggBytes(frames(250))
        assertEquals(oggDurationMs(ogg), oggDurationMsFromTail(ogg))
    }

    @Test
    fun `tail parse yields null when there is no page header in the window`() {
        assertNull(oggDurationMsFromTail(ByteArray(0)))
        assertNull(oggDurationMsFromTail(ByteArray(4096)))
        assertNull(oggDurationMsFromTail("not an ogg file at all".toByteArray()))
    }
}
