package com.nomily.app.audio

import android.util.Log
import com.nomily.app.core.audio.VadMath
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local silence trimming before upload — the half that **requires a decoder**
 * (threshold/hysteresis/merge/timestamp remapping are in `:core`’s [VadMath]; that half is pure functions with unit tests).
 *
 * Pipeline: decode to **16 kHz mono PCM** ([PcmCodec]) → windowed RMS calculation → determine speech intervals
 * → concatenate the speech segments and encode to m4a (AAC) for ASR. This saves transcription cost: silence is billed per second as well.
 *
 * Here we directly trim and splice the 16 kHz mono PCM used for analysis, rather than operating on the original track. Reason: we already have to decode once for RMS, decoding a second time at the original fidelity would be wasteful; this audio is **only fed to ASR** (both Azure fast transcription and faster‑whisper internally down‑sample to 16 k mono), while the original file stored locally remains untouched.
 *
 * ⚠️ This section cannot be unit‑tested; correctness can only be verified by running a real recording on a device.
 */
object LocalVad {

    private const val TAG = "LocalVad"

    /** Trim result: the file handed to ASR + the positions of each speech segment on the **original** timeline (used for timestamp remapping). */
    class TrimResult(val file: File, val ranges: List<VadMath.SpeechRange>)

    /** Analyze an audio file: decode → RMS → report. The debug preview page uses this directly (adjustable gain and hysteresis). */
    fun analyze(
        source: File,
        thresholdMultiplier: Double = VadMath.DEFAULT_THRESHOLD_MULTIPLIER,
        hangoverSec: Double = VadMath.DEFAULT_HANGOVER_SEC,
        abort: () -> Boolean = { false },
    ): VadMath.VadReport {
        val tmp = File.createTempFile("vad-pcm", ".raw", source.parentFile)
        try {
            val pcm = PcmCodec.decodeToPcm(
                source, tmp, targetRate = VadMath.SAMPLE_RATE.toInt(), mono = true, abort = abort,
            )
            return VadMath.report(rmsOf(tmp, abort), pcm.durationSec, thresholdMultiplier, hangoverSec)
        } finally {
            tmp.delete()
        }
    }

    /**
     * The trimmed version for ASR; **if trimming isn’t worthwhile, return null**, and the caller uploads the original file unchanged
     * (speech too short, or less than 10 % savings).
     *
     * The returned file is the caller’s responsibility to clean up.
     */
    fun processForUpload(source: File, outDir: File): TrimResult? {
        val tmp = File.createTempFile("vad-pcm", ".raw", source.parentFile)
        return try {
            val pcm = PcmCodec.decodeToPcm(source, tmp, targetRate = VadMath.SAMPLE_RATE.toInt(), mono = true)
            val report = VadMath.report(rmsOf(tmp), pcm.durationSec)
            if (!VadMath.worthTrimming(report)) {
                Log.i(
                    TAG,
                    "VAD: not worth cutting (speech %.2fs / total %.2fs), uploading as is"
                        .format(report.speechDuration, pcm.durationSec),
                )
                null
            } else {
                outDir.mkdirs()
                val out = File(outDir, "vad-${source.nameWithoutExtension}.m4a")
                if (out.exists()) out.delete()
                encodeRanges(tmp, report.ranges, out)
                Log.i(TAG, "VAD: %.2fs → %.2fs (%s)".format(pcm.durationSec, report.speechDuration, out.name))
                TrimResult(out, report.ranges)
            }
        } catch (e: Exception) {
            // Trimming is only a cost‑saving optimization: **any failure at any step falls back to the original file**, it must not block transcription.
            Log.w(TAG, "VAD failed, reverting to original file: ${e.message}")
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Streamed RMS calculation: read one window at a time (960 bytes), **without loading the entire PCM into memory**.
     *
     * The first version stored the decoded result in an `ArrayList<Short>`; a 26‑minute segment (≈ 75 million samples)
     * caused the app to crash (`OutOfMemoryError`, reproduced on a real device 2026‑08‑04) — each boxed Short consumes many bytes.
     * Now the decode is written to a temporary PCM file, so memory usage is independent of duration.
     */
    private fun rmsOf(pcm: File, abort: () -> Boolean = { false }): DoubleArray {
        val out = ArrayList<Double>((pcm.length() / (VadMath.WINDOW_SAMPLES * 2L)).toInt().coerceAtLeast(16))
        val buf = ByteArray(VadMath.WINDOW_SAMPLES * 2)
        val samples = ShortArray(VadMath.WINDOW_SAMPLES)
        var sinceCheck = 0
        pcm.inputStream().buffered(1 shl 16).use { ins ->
            while (readFully(ins, buf)) {
                // One window is 30 ms; querying per window is too granular; querying every 1000 windows (≈ 30 s of audio) is fast enough.
                if (++sinceCheck >= 1000) {
                    sinceCheck = 0
                    if (abort()) throw PcmCodec.Cancelled()
                }
                ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                out += VadMath.windowRms(samples, 0, samples.size)
            }
        }
        return out.toDoubleArray()
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** Concatenate each speech segment **end‑to‑end** and encode to m4a — the concatenation matches the shape assumed by [VadMath.remapToOriginal]. */
    private fun encodeRanges(pcm: File, ranges: List<VadMath.SpeechRange>, out: File) {
        val total = pcm.length() / 2
        val plan = ranges.mapNotNull { r ->
            val from = (r.start * VadMath.SAMPLE_RATE).toLong().coerceIn(0, total)
            val to = (r.end * VadMath.SAMPLE_RATE).toLong().coerceIn(0, total)
            if (to > from) from to to else null
        }
        RandomAccessFile(pcm, "r").use { raf ->
            val reader = PcmCodec.rangeReader(pcm, plan)
            PcmCodec.encodePcmToM4a(out, VadMath.SAMPLE_RATE.toInt()) { buf, limit ->
                reader(raf, buf, limit)
            }
        }
    }
}
