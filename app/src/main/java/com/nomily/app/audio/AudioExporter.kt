package com.nomily.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Export a segment as **M4A (AAC)**.
 *
 * Why transcode instead of sharing the `.ogg` directly: Ogg‑Opus is essentially unreadable in other apps (WeChat, email,
 * most players), and the purpose of exporting is that “others can play it”.
 *
 * Pipeline: `MediaExtractor` (Ogg‑Opus) → `MediaCodec` decode to PCM → `MediaCodec` encode to AAC
 * → `MediaMuxer` package into MP4/M4A. Fully streamed, without loading the entire audio into memory.
 *
 * ⚠️ This section is not a pure function and lacks unit‑test coverage: correctness can only be verified by exporting on a real device and playing it back.
 */
object AudioExporter {

    private const val TAG = "AudioExporter"
    /**
     * If an input buffer isn’t available, wait briefly; **output always uses a zero‑timeout poll**.
     *
     * In the first version all three timeouts were 10 ms, causing a 2:54 segment to take 110 s —
     * each loop processed one frame but spent about 10 ms on the timeout, and a 174 s audio has roughly 8700 frames.
     * Switching to “output zero‑timeout + sleep 1 ms only after a loop makes no progress” moved the bottleneck back to the actual codec work.
     */
    private const val IN_TIMEOUT_US = 2_000L
    private const val OUT_TIMEOUT_US = 0L
    private const val AAC_BITRATE = 64_000
    /**
     * Watchdog: if all three pipelines remain idle for this long, treat it as a deadlock and throw an error instead of leaving the UI stuck on “exporting…”.
     * Before 2026‑08‑12 there was no such layer; a dead loop on a wav source manifested as “exporting…” never ending,
     * producing an unreadable corrupted file, and the user had no indication of what went wrong.
     */
    private const val STALL_LIMIT_MS = 30_000L

    class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param source Input audio (the Ogg‑Opus file saved by this app)
     * @param outDir Output directory (use a cache subdirectory; can be cleared after sharing)
     * @param stem   Output file name (without extension)
     */
    fun exportAsM4a(source: File, outDir: File, stem: String): File {
        if (!source.isFile) throw ExportException("source not found: ${source.name}")
        outDir.mkdirs()
        val out = File(outDir, "$stem.m4a")
        if (out.exists()) out.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ExportException("no audio track in ${source.name}")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw ExportException("no mime")

            // If the source is already PCM (imported wav), don’t “decode” it again:
            //            // decoder either doesn’t exist, or (as tested on Pixel 3 / Android 12) can accept the input
            //            // but never produces output nor EOS — the transcode loop never finishes,
            //            // leaving the UI stuck on “exporting…”, and the muxer never receives the final chunk, resulting in an unreadable corrupted file.
            //            // Feed PCM directly to the AAC encoder. (2026‑08‑12)
            if (inMime != MediaFormat.MIMETYPE_AUDIO_RAW) {
                decoder = MediaCodec.createDecoderByType(inMime).apply {
                    configure(inFormat, null, null, 0)
                    start()
                }
            }

            val outFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dec = decoder
            if (dec != null) {
                transcode(extractor, dec, encoder, muxer)
            } else {
                transcodePcm(extractor, encoder, muxer, sampleRate, channels)
            }
            return out
        } catch (e: ExportException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            Log.e(TAG, "Export failed", e)
            throw ExportException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun transcode(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
    ) {
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var muxTrack = -1
        var muxerStarted = false
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        // The encoder must compute its own timestamps: decoded PCM frames and frames fed to the encoder are not one‑to‑one.
        var encodedSamples = 0L
        var sampleRate = 0
        var channels = 1
        var lastProgressAt = System.currentTimeMillis()

        // ⚠️ 2026‑08‑03: We tried a version that emptied each of the three pipelines once to speed up,
        // but the output was truncated (a 2:54 segment produced only 4 KB, afinfo couldn’t open), so we reverted to the current frame‑by‑frame approach.
        // **Two rounds of optimization didn’t improve speed**: reducing the three dequeue timeouts from 10 ms to 0/2 ms still resulted in ~107 s,
        // indicating the bottleneck isn’t the timeout waits but elsewhere (likely the fixed overhead of a MediaCodec buffer round‑trip per frame —
        // 20 ms per frame, ~8700 times for 174 s). Real speed gains require a different strategy, not just tuning parameters.
        while (!encoderDone) {
            var progressed = false
            // 1) Original Opus package → decoder
            if (!extractorDone) {
                val idx = decoder.dequeueInputBuffer(IN_TIMEOUT_US)
                if (idx >= 0) {
                    progressed = true
                    val buf = decoder.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 2) Decoded PCM → encoder
            if (!decoderDone) {
                val idx = decoder.dequeueOutputBuffer(decInfo, OUT_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    idx >= 0 -> {
                        progressed = true
                        val pcm: ByteBuffer? = decoder.getOutputBuffer(idx)
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0 && pcm != null) {
                            pcm.position(decInfo.offset)
                            pcm.limit(decInfo.offset + decInfo.size)
                            feedEncoder(encoder, pcm) { bytes ->
                                val pts = if (sampleRate > 0 && channels > 0) {
                                    encodedSamples * 1_000_000L / sampleRate
                                } else {
                                    0L
                                }
                                encodedSamples += bytes / (2L * channels)   // 16-bit PCM
                                pts
                            }
                        }
                        decoder.releaseOutputBuffer(idx, false)
                        if (eos) {
                            decoderDone = true
                            val ei = encoder.dequeueInputBuffer(IN_TIMEOUT_US)
                            if (ei >= 0) {
                                encoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                    }
                }
            }

            // 3) Encoded AAC → muxer
            val idx = encoder.dequeueOutputBuffer(encInfo, OUT_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    progressed = true
                    val outBuf = encoder.getOutputBuffer(idx)
                    // codec‑config frames have already been placed in the track format and must not be written to the muxer
                    val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && encInfo.size > 0 && outBuf != null && muxerStarted) {
                        outBuf.position(encInfo.offset)
                        outBuf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxTrack, outBuf, encInfo)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }

            // Yield the CPU only when all three pipelines are idle — otherwise it would be pure busy‑waiting
            if (progressed) {
                lastProgressAt = System.currentTimeMillis()
            } else {
                if (System.currentTimeMillis() - lastProgressAt > STALL_LIMIT_MS) {
                    throw ExportException("Export stalled for more than ${STALL_LIMIT_MS / 1000} seconds, aborted")
                }
                Thread.sleep(1)
            }
        }
        if (muxerStarted) muxer.stop()
    }

    /**
     * The path used when the source is already PCM: extractor → encoder → muxer, with no decoder in between.
     *
     * The only difference from [transcode] is the missing stage; timestamps are still derived from the number of encoded samples,
     * without using the extractor’s sampleTime (wav timestamps are unreliable at the sample granularity).
     */
    private fun transcodePcm(
        extractor: MediaExtractor,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        sampleRate: Int,
        channels: Int,
    ) {
        val encInfo = MediaCodec.BufferInfo()
        var muxTrack = -1
        var muxerStarted = false
        var extractorDone = false
        var encoderDone = false
        var encodedSamples = 0L
        var lastProgressAt = System.currentTimeMillis()
        val buf = ByteBuffer.allocate(64 * 1024)

        while (!encoderDone) {
            var progressed = false

            if (!extractorDone) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    extractorDone = true
                    // Empty the output first before sending EOS: when the output buffer is full the encoder stops providing input buffers,
                    // causing both sides to wait on each other, resulting in another never‑ending “exporting…”.
                    var queued = false
                    while (!queued) {
                        drainEncoder(encoder, muxer, encInfo, muxTrack, muxerStarted)?.let { (t, s) ->
                            muxTrack = t; muxerStarted = s
                        }
                        val ei = encoder.dequeueInputBuffer(IN_TIMEOUT_US)
                        if (ei >= 0) {
                            encoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            queued = true
                        }
                    }
                } else {
                    progressed = true
                    buf.position(0)
                    buf.limit(size)
                    feedEncoder(
                        encoder, buf,
                        onStall = {
                            drainEncoder(encoder, muxer, encInfo, muxTrack, muxerStarted)?.let { (t, s) ->
                                muxTrack = t; muxerStarted = s
                            }
                        },
                    ) { bytes ->
                        val pts = encodedSamples * 1_000_000L / sampleRate
                        encodedSamples += bytes / (2L * channels)   // 16-bit PCM
                        pts
                    }
                    extractor.advance()
                }
            }

            val idx = encoder.dequeueOutputBuffer(encInfo, OUT_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    progressed = true
                    val outBuf = encoder.getOutputBuffer(idx)
                    val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && encInfo.size > 0 && outBuf != null && muxerStarted) {
                        outBuf.position(encInfo.offset)
                        outBuf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxTrack, outBuf, encInfo)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }

            if (progressed) {
                lastProgressAt = System.currentTimeMillis()
            } else {
                if (System.currentTimeMillis() - lastProgressAt > STALL_LIMIT_MS) {
                    throw ExportException("Export stalled for more than ${STALL_LIMIT_MS / 1000} seconds, aborted")
                }
                Thread.sleep(1)
            }
        }
        if (muxerStarted) muxer.stop()
    }

    /**
     * Drain one round of encoder output. Used only in the inner loop that is “waiting to feed EOS” — if not drained there,
     * the encoder’s output buffer will fill and no more input buffers can be obtained, causing both sides to wait.
     *
     * Returns the updated (track, started); returns null if the format hasn’t changed.
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        muxTrack: Int,
        muxerStarted: Boolean,
    ): Pair<Int, Boolean>? {
        val idx = encoder.dequeueOutputBuffer(info, OUT_TIMEOUT_US)
        return when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val t = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                t to true
            }
            idx >= 0 -> {
                val outBuf = encoder.getOutputBuffer(idx)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0 && outBuf != null && muxerStarted) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    muxer.writeSampleData(muxTrack, outBuf, info)
                }
                encoder.releaseOutputBuffer(idx, false)
                null
            }
            else -> null
        }
    }

    /** Feed a PCM segment into the encoder; the encoder’s input buffer may be smaller than the segment, so it must be fed in chunks. */
    private inline fun feedEncoder(
        encoder: MediaCodec,
        pcm: ByteBuffer,
        // Call once when an input buffer isn’t available — the PCM path needs to drain the encoder’s output here,
        // otherwise once the output buffer fills the encoder stops delivering input buffers, and this while becomes an infinite loop.
        // The decoding path only feeds one frame at a time (≈ 20 ms), never filling the output, so feeding empty data suffices.
        onStall: () -> Unit = {},
        ptsFor: (Int) -> Long,
    ) {
        while (pcm.hasRemaining()) {
            val idx = encoder.dequeueInputBuffer(IN_TIMEOUT_US)
            if (idx < 0) { onStall(); continue }
            val dst = encoder.getInputBuffer(idx)!!
            dst.clear()
            val n = minOf(dst.remaining(), pcm.remaining())
            val slice = pcm.slice()
            slice.limit(n)
            dst.put(slice)
            pcm.position(pcm.position() + n)
            encoder.queueInputBuffer(idx, 0, n, ptsFor(n), 0)
        }
    }
}
