package com.nomily.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Common pipeline: decode → raw PCM file / raw PCM stream → m4a (AAC).
 *
 * Extracted because there are already **three** places that need the same MediaCodec round‑trip: VAD silence removal, segment merging,
 * and (future) any time‑based trimming feature. Android lacks a one‑stop API, so we implement it once and reuse.
 *
 * **PCM is always written to a temporary file, not kept in memory**: a 26‑minute segment yields over 70 million samples,
 * and the first version using an `ArrayList<Short>` caused OOM (2026‑08‑04 real‑device reproduction).
 */
object PcmCodec {

    private const val IN_TIMEOUT_US = 2_000L
    private const val OUT_TIMEOUT_US = 0L
    private const val AAC_BITRATE_PER_CHANNEL = 32_000

    class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * The caller requested abort (coroutine cancelled). **A distinct type** so the upper layer can differentiate it from genuine decode failures:
     * on decode failure we fall back to the original file and continue the flow, while cancellation should end quietly.
     */
    class Cancelled : Exception("cancelled")

    /** The format of the decoded PCM. [file] is 16‑bit little‑endian, [channels] are interleaved. */
    class Pcm(val file: File, val rate: Int, val channels: Int) {
        val sampleCount: Long get() = file.length() / 2 / channels
        val durationSec: Double get() = if (rate > 0) sampleCount.toDouble() / rate else 0.0
    }

    /**
     * Decode to a raw PCM file.
     *
     * @param targetRate Desired sample rate; `null` = keep the source rate. Resampling uses **nearest‑neighbor** ——
     *   The two uses in this app (energy envelope, speech stitching) sound indistinguishable from linear interpolation,
     *   and nearest‑neighbor avoids caching the previous sample and simplifies cross‑buffer boundary handling.
     * @param mono Whether to downmix to mono (multi‑channel **averaged**, not just dropping the right channel).
     * @param abort Called each loop iteration to ask “continue?”; returning true throws [Cancelled] to stop.
     *   Decoding a multi‑hour recording takes tens of seconds, and the caller (VAD preview page entering/exiting repeatedly) must be able to truly stop after cancellation,
     *   otherwise several full‑length decodes could run concurrently.
     */
    fun decodeToPcm(
        source: File,
        dest: File,
        targetRate: Int? = null,
        mono: Boolean = true,
        abort: () -> Boolean = { false },
    ): Pcm {
        if (!source.isFile) throw CodecException("source not found: ${source.name}")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var sink: OutputStream? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw CodecException("no audio track in ${source.name}")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(
                inFormat.getString(MediaFormat.KEY_MIME) ?: throw CodecException("no mime"),
            ).apply {
                configure(inFormat, null, null, 0)
                start()
            }

            var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = runCatching { inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            sink = dest.outputStream().buffered(1 shl 16)
            // Phase accumulator for resampling: **continuous across buffers**; rounding each buffer individually would cause over‑ or under‑sampling at boundaries.
            var phase = 0.0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (abort()) throw Cancelled()
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(IN_TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val idx = decoder.dequeueOutputBuffer(info, OUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> if (idx >= 0) {
                        val buf = decoder.getOutputBuffer(idx)
                        if (info.size > 0 && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            phase = writeResampled(
                                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(),
                                srcChannels, srcRate, targetRate ?: srcRate, mono, phase, sink,
                            )
                        }
                        decoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            sink.flush()
            return Pcm(dest, targetRate ?: srcRate, if (mono) 1 else srcChannels)
        } catch (e: Cancelled) {
            dest.delete()
            throw e
        } catch (e: CodecException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw CodecException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            runCatching { sink?.close() }
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeResampled(
        src: java.nio.ShortBuffer,
        srcChannels: Int,
        srcRate: Int,
        dstRate: Int,
        mono: Boolean,
        phase: Double,
        sink: OutputStream,
    ): Double {
        val ch = srcChannels.coerceAtLeast(1)
        val step = if (srcRate > 0 && dstRate > 0) srcRate.toDouble() / dstRate else 1.0
        var p = phase
        var frameIndex = 0
        val frame = ShortArray(ch)
        val bytes = ByteArray(2 * if (mono) 1 else ch)
        while (src.remaining() >= ch) {
            var sum = 0
            for (c in 0 until ch) {
                frame[c] = src.get()
                sum += frame[c].toInt()
            }
            while (p <= frameIndex) {
                if (mono) {
                    putLE(bytes, 0, sum / ch)
                } else {
                    for (c in 0 until ch) putLE(bytes, c * 2, frame[c].toInt())
                }
                sink.write(bytes)
                p += step
            }
            frameIndex++
        }
        return p - frameIndex
    }

    private fun putLE(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v and 0xFF).toByte()
        dst[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /**
     * PCM stream → m4a (AAC). [next] fills the provided array with up to `limit` samples each call and returns the actual count,
     * returning 0 indicates end of stream — this lets the caller concatenate any desired segments without first assembling the PCM in memory.
     */
    fun encodePcmToM4a(out: File, rate: Int, channels: Int = 1, next: (ShortArray, Int) -> Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE_PER_CHANNEL * channels)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var read = 0L                    // Number of samples (frames) already fed to the encoder, also used to compute pts
        val chunk = ShortArray(4096)
        var inputDone = false
        var done = false
        try {
            while (!done) {
                if (!inputDone) {
                    val idx = encoder.dequeueInputBuffer(IN_TIMEOUT_US)
                    if (idx >= 0) {
                        val dst: ByteBuffer = encoder.getInputBuffer(idx)!!
                        dst.clear()
                        val want = minOf(dst.remaining() / 2, chunk.size)
                        val n = next(chunk, want)
                        if (n <= 0) {
                            encoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = read * 1_000_000L / rate
                            dst.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk, 0, n)
                            encoder.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            read += n / channels
                        }
                    }
                }
                val idx = encoder.dequeueOutputBuffer(info, OUT_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && buf != null && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                    else -> Thread.sleep(1)
                }
            }
            if (muxerStarted) muxer.stop()
        } finally {
            runCatching { encoder.stop() }; runCatching { encoder.release() }
            runCatching { muxer.release() }
        }
    }

    /** Read sequentially several intervals of a PCM file (sample indices `[from, to)`) and feed them to [encodePcmToM4a]. */
    fun rangeReader(pcm: File, plan: List<Pair<Long, Long>>): (java.io.RandomAccessFile, ShortArray, Int) -> Int {
        var index = 0
        var offset = 0L
        return { raf, buf, limit ->
            var written = 0
            while (written < limit && index < plan.size) {
                val (from, to) = plan[index]
                val pos = from + offset
                if (pos >= to) { index++; offset = 0; continue }
                val n = minOf((to - pos).toInt(), limit - written)
                raf.seek(pos * 2)
                val bytes = ByteArray(n * 2)
                raf.readFully(bytes)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buf, written, n)
                written += n
                offset += n
            }
            written
        }
    }
}
