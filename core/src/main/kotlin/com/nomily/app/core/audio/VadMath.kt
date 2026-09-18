package com.nomily.app.core.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The **pure‑algorithmic half** of the local VAD.
 *
 * The reason for moving it to `:core` is the same as for [OggOpus]: the steps of energy thresholding, hangover, and merging are pure functions, allowing unit tests with a fixed RMS sequence without requiring a device or decoding.
 * The decoding / re‑encoding half resides in `:app`'s `LocalVad` (which needs MediaCodec and cannot be unit‑tested).
 *
 * Algorithm: 30 ms window RMS + adaptive noise floor + hangover expansion + neighboring‑frame merging.
 * **Not libwebrtc‑vad / Silero**: this is merely a cost‑saving filter for transcription; RMS alone blocks about 90 % of obvious silence.
 */
object VadMath {

    /** At 16 kHz, 30 ms equals 480 samples. Short enough to capture word beginnings and endings, long enough to avoid bias from single‑sample jitter. */
    const val WINDOW_SAMPLES = 480
    const val SAMPLE_RATE = 16_000.0

    const val DEFAULT_THRESHOLD_MULTIPLIER = 3.0
    const val DEFAULT_HANGOVER_SEC = 0.3

    /** Two speech segments separated by less than this interval are merged directly: cutting out intra‑sentence pauses actually makes ASR harder. */
    private const val MERGE_GAP_SEC = 0.3

    /** Hard lower bound for the threshold: recordings that are extremely clean (noise floor near 0) must not be classified as 100 % speech. */
    private const val THRESHOLD_MINIMUM = 150.0

    /** If the total speech duration is below this, skip trimming — likely a false positive, better to upload unchanged. */
    const val MIN_SPEECH_SEC = 0.5

    /** If less than 10 % (and at least 1 s) cannot be saved, re‑encoding is not worthwhile (re‑encoding loses a bit of quality). */
    const val MIN_TRIM_FRACTION = 0.1
    const val MIN_TRIM_SEC = 1.0

    val WINDOW_DUR: Double get() = WINDOW_SAMPLES / SAMPLE_RATE

    data class SpeechRange(val start: Double, val end: Double)

    /**
     * The complete result of a single analysis — the debug preview draws the [rms] and [threshold] here,
     * and the upload‑path decision uses **the same signal**.
     */
    data class VadReport(
        val rms: DoubleArray,
        val windowDur: Double,
        val noiseFloor: Double,
        val threshold: Double,
        val thresholdMultiplier: Double,
        val hangoverSec: Double,
        val ranges: List<SpeechRange>,
        val totalDuration: Double,
    ) {
        val speechDuration: Double get() = ranges.sumOf { it.end - it.start }
        val savedDuration: Double get() = max(0.0, totalDuration - speechDuration)

        /** Value classes containing arrays need custom equals/hashCode (Kotlin only compares references). */
        override fun equals(other: Any?): Boolean =
            other is VadReport && rms.contentEquals(other.rms) && ranges == other.ranges &&
                threshold == other.threshold && totalDuration == other.totalDuration

        override fun hashCode(): Int = rms.contentHashCode() * 31 + ranges.hashCode()
    }

    /** RMS of a window. `samples[from until from+count]` is 16‑bit PCM. */
    fun windowRms(samples: ShortArray, from: Int, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in from until from + count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    /** Whole PCM → one RMS every 30 ms. **Discard the tail shorter than a full window**: only full windows are processed. */
    fun rmsWindows(samples: ShortArray): DoubleArray {
        val n = samples.size / WINDOW_SAMPLES
        return DoubleArray(n) { windowRms(samples, it * WINDOW_SAMPLES, WINDOW_SAMPLES) }
    }

    /**
     * RMS sequence → complete report.
     *
     * Adaptive threshold: the noise floor is the **10th percentile** of RMS values; threshold = noise floor × multiplier, then clamped to a hard lower bound.
     */
    fun report(
        rms: DoubleArray,
        totalDuration: Double,
        thresholdMultiplier: Double = DEFAULT_THRESHOLD_MULTIPLIER,
        hangoverSec: Double = DEFAULT_HANGOVER_SEC,
    ): VadReport {
        if (rms.isEmpty()) {
            return VadReport(
                rms = rms, windowDur = WINDOW_DUR, noiseFloor = 0.0, threshold = 0.0,
                thresholdMultiplier = thresholdMultiplier, hangoverSec = hangoverSec,
                ranges = emptyList(), totalDuration = totalDuration,
            )
        }
        val sorted = rms.sortedArray()
        val floor = sorted[sorted.size / 10]
        val threshold = max(floor * thresholdMultiplier, THRESHOLD_MINIMUM)
        val raw = rawSpeechRanges(rms, WINDOW_DUR, threshold)
        val merged = expandAndMerge(raw, totalDuration, hangoverSec)
        return VadReport(
            rms = rms, windowDur = WINDOW_DUR, noiseFloor = floor, threshold = threshold,
            thresholdMultiplier = thresholdMultiplier, hangoverSec = hangoverSec,
            ranges = merged, totalDuration = totalDuration,
        )
    }

    fun rawSpeechRanges(rms: DoubleArray, windowDur: Double, threshold: Double): List<SpeechRange> {
        val ranges = mutableListOf<SpeechRange>()
        var start: Double? = null
        rms.forEachIndexed { i, v ->
            val t = i * windowDur
            if (v >= threshold) {
                if (start == null) start = t
            } else if (start != null) {
                ranges += SpeechRange(start!!, t)
                start = null
            }
        }
        start?.let { ranges += SpeechRange(it, rms.size * windowDur) }
        return ranges
    }

    /** Extend each segment by [hangoverSec] at both ends, then merge intervals ≤ [MERGE_GAP_SEC]. */
    fun expandAndMerge(
        ranges: List<SpeechRange>,
        totalDur: Double,
        hangoverSec: Double,
    ): List<SpeechRange> {
        val expanded = ranges.map {
            SpeechRange(max(0.0, it.start - hangoverSec), min(totalDur, it.end + hangoverSec))
        }
        val merged = mutableListOf<SpeechRange>()
        for (r in expanded) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end + MERGE_GAP_SEC) {
                merged[merged.size - 1] = SpeechRange(last.start, max(last.end, r.end))
            } else {
                merged += r
            }
        }
        return merged
    }

    /**
     * Map a moment on the trimmed timeline back to the original timeline.
     *
     * ASR returns timestamps that are **post‑trim**, and writing them directly into the output would misalign segment times with the original audio.
     * Segments are contiguous on the trimmed side, so this is a piecewise linear mapping.
     */
    fun remapToOriginal(trimmedTime: Double, ranges: List<SpeechRange>): Double {
        if (ranges.isEmpty()) return trimmedTime
        var cumulative = 0.0
        for (r in ranges) {
            val dur = r.end - r.start
            if (trimmedTime <= cumulative + dur) return r.start + max(0.0, trimmedTime - cumulative)
            cumulative += dur
        }
        return ranges.last().end
    }

    /** Whether trimming is worthwhile: speech is long enough and the saved time meets the threshold. */
    fun worthTrimming(report: VadReport): Boolean {
        if (report.speechDuration < MIN_SPEECH_SEC) return false
        return report.savedDuration >= max(report.totalDuration * MIN_TRIM_FRACTION, MIN_TRIM_SEC)
    }
}
