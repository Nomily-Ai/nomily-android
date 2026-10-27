package com.nomily.app.core.audio

/**
 * Loudness strip of the live page (iOS `LiveModel.pushAudioLevels` + `OnDeviceASR.loudness`).
 *
 * Values are RMS of decoded 16-bit PCM normalised to 0..1 with a ×4 display gain: speech sits around
 * 0.05–0.3 RMS, so ordinary talking fills most of a bar without clipping on laughter.
 */
object LiveLevels {

    /** Bars on screen; also the number of samples kept. */
    const val SLOTS = 28

    /** A batch is squeezed into at most this many slots, so the strip advances by half its width instead of jumping. */
    const val BATCH_SLOTS = 14

    private const val DISPLAY_GAIN = 4.0

    /** Level of `samples[from, to)`; 0 for an empty range. */
    fun level(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return levelOf(sum, to - from)
    }

    /** Level from an accumulated sum of squares over [count] samples. */
    fun levelOf(sumOfSquares: Double, count: Int): Float {
        if (count <= 0) return 0f
        val rms = kotlin.math.sqrt(sumOfSquares / count) / 32_768.0
        return minOf(rms * DISPLAY_GAIN, 1.0).toFloat()
    }

    /**
     * Append a batch to the strip: batches longer than [BATCH_SLOTS] are reduced to the peak of each group,
     * then only the newest [SLOTS] values are kept.
     */
    fun push(current: List<Float>, batch: List<Float>): List<Float> {
        if (batch.isEmpty()) return current
        val squeezed = if (batch.size > BATCH_SLOTS) {
            val group = (batch.size + BATCH_SLOTS - 1) / BATCH_SLOTS
            batch.chunked(group) { it.max() }
        } else {
            batch
        }
        return (current + squeezed).takeLast(SLOTS)
    }
}
