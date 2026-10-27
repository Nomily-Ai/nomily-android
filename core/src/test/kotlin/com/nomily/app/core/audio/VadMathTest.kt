package com.nomily.app.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixed-vector tests for the pure VAD algorithm.
 *
 * These cases target **decision boundaries** (threshold floor, hysteresis
 * expansion, neighbor merging, timestamp back-mapping), not "whether
 * decoding is correct" (that half lives in `:app` and cannot be tested here).
 */
class VadMathTest {

    private val windowDur = VadMath.WINDOW_DUR   // 0.03 s

    @Test
    fun `quiet segment threshold does not collapse to 0`() {
        // All minimal noise: noise floor ≈ 0. Without a hard lower bound, the threshold would also be 0 → the entire segment would be classified as speech.
        val rms = DoubleArray(100) { 1.0 }
        val r = VadMath.report(rms, totalDuration = 3.0)
        assertEquals(150.0, r.threshold, 0.0001)
        assertTrue(r.ranges.isEmpty(), "All under threshold, should not have speech segments")
    }

    @Test
    fun `windows exceeding threshold form segments and expand with hysteresis`() {
        // Indices 10..19 are speech (well above the threshold), the rest are noise floor
        val rms = DoubleArray(100) { if (it in 10..19) 5000.0 else 100.0 }
        val r = VadMath.report(rms, totalDuration = 3.0, hangoverSec = 0.1)
        assertEquals(1, r.ranges.size)
        val seg = r.ranges.first()
        assertEquals(10 * windowDur - 0.1, seg.start, 1e-9)
        assertEquals(20 * windowDur + 0.1, seg.end, 1e-9)
    }

    @Test
    fun `intervals less than 0_3s are merged`() {
        val near = listOf(VadMath.SpeechRange(0.0, 1.0), VadMath.SpeechRange(1.2, 2.0))
        val far = listOf(VadMath.SpeechRange(0.0, 1.0), VadMath.SpeechRange(2.0, 3.0))
        assertEquals(1, VadMath.expandAndMerge(near, 5.0, 0.0).size)
        assertEquals(2, VadMath.expandAndMerge(far, 5.0, 0.0).size)
    }

    @Test
    fun `hysteresis expansion does not cross segment boundaries`() {
        val r = VadMath.expandAndMerge(listOf(VadMath.SpeechRange(0.05, 4.95)), 5.0, 0.3)
        assertEquals(0.0, r.first().start, 1e-9)
        assertEquals(5.0, r.first().end, 1e-9)
    }

    @Test
    fun `timestamp remapped from cropped to original timeline`() {
        val ranges = listOf(VadMath.SpeechRange(10.0, 12.0), VadMath.SpeechRange(20.0, 23.0))
        assertEquals(10.0, VadMath.remapToOriginal(0.0, ranges), 1e-9)   // First paragraph start
        assertEquals(11.5, VadMath.remapToOriginal(1.5, ranges), 1e-9)   // Middle of the first segment
        assertEquals(20.5, VadMath.remapToOriginal(2.5, ranges), 1e-9)   // Falls into the second paragraph
        assertEquals(23.0, VadMath.remapToOriginal(99.0, ranges), 1e-9)  // Super tail clamped to the end
        assertEquals(7.0, VadMath.remapToOriginal(7.0, emptyList()), 1e-9)
    }

    @Test
    fun `saving too little is not worth re-encoding`() {
        fun report(speech: List<VadMath.SpeechRange>, total: Double) = VadMath.VadReport(
            rms = DoubleArray(0), windowDur = windowDur, noiseFloor = 0.0, threshold = 0.0,
            thresholdMultiplier = 3.0, hangoverSec = 0.3, ranges = speech, totalDuration = total,
        )
        // 60 seconds saves only 0.5 seconds → skip trimming
        assertFalse(VadMath.worthTrimming(report(listOf(VadMath.SpeechRange(0.0, 59.5)), 60.0)))
        // 20 seconds saved in 60 seconds → trim
        assertTrue(VadMath.worthTrimming(report(listOf(VadMath.SpeechRange(0.0, 40.0)), 60.0)))
        // Voice segment less than 0.5 seconds → likely a false positive, upload as-is
        assertFalse(VadMath.worthTrimming(report(listOf(VadMath.SpeechRange(0.0, 0.2)), 60.0)))
    }

    @Test
    fun `window rms is root mean square`() {
        val samples = shortArrayOf(3, 4, 3, 4)
        assertEquals(3.5355, VadMath.windowRms(samples, 0, 4), 1e-4)
        assertEquals(0.0, VadMath.windowRms(samples, 0, 0), 0.0)
    }

    @Test
    fun `tail shorter than window does not produce rms point`() {
        val samples = ShortArray(VadMath.WINDOW_SAMPLES + 100) { 1000 }
        assertEquals(1, VadMath.rmsWindows(samples).size)
    }
}
