package com.nomily.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.nomily.app.core.audio.LiveLevels
import com.nomily.app.core.audio.OPUS_CHANNELS
import com.nomily.app.core.audio.OPUS_FRAME_SIZE
import com.nomily.app.core.audio.OPUS_PRESKIP
import com.nomily.app.core.audio.OPUS_SAMPLE_RATE
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the live OPUS frames just far enough to measure loudness for the level bars.
 *
 * iOS samples its bars from the PCM its on-device recogniser decodes anyway; Android has no on-device path, so
 * the stream is decoded here with the platform Opus decoder. Purely cosmetic: any codec failure turns the meter
 * off for the rest of the session and never touches the recording or the ASR.
 *
 * Not thread-safe — call [feed] and [release] from the stream collector.
 */
class LiveLevelMeter {

    private var codec: MediaCodec? = runCatching { open() }
        .onFailure { Log.w(TAG, "Opus decoder unavailable, level bars stay idle: ${it.message}") }
        .getOrNull()
    private var ptsUs = 0L
    private var sumOfSquares = 0.0
    private var samples = 0
    private var pcm = ShortArray(0)

    /** Feed raw frames (a multiple of [OPUS_FRAME_SIZE]); returns the levels completed by them. */
    fun feed(frames: ByteArray): List<Float> {
        val c = codec ?: return emptyList()
        val out = ArrayList<Float>(2)
        try {
            var off = 0
            while (off + OPUS_FRAME_SIZE <= frames.size) {
                // No free input buffer → skip this frame; the bars only need a sample of the signal.
                val inIdx = c.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    c.getInputBuffer(inIdx)!!.apply { clear(); put(frames, off, OPUS_FRAME_SIZE) }
                    c.queueInputBuffer(inIdx, 0, OPUS_FRAME_SIZE, ptsUs, 0)
                }
                ptsUs += FRAME_US
                off += OPUS_FRAME_SIZE
                drain(c, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Opus level decode failed, level bars off for this session: ${e.message}")
            release()
        }
        return out
    }

    fun release() {
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
    }

    private fun drain(c: MediaCodec, out: MutableList<Float>) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) return            // INFO_TRY_AGAIN_LATER / format or buffers changed
            if (info.size > 0) {
                val shorts = c.getOutputBuffer(idx)!!.apply { position(info.offset); limit(info.offset + info.size) }
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val n = shorts.remaining()
                if (pcm.size < n) pcm = ShortArray(n)
                shorts.get(pcm, 0, n)
                for (i in 0 until n) {
                    val v = pcm[i].toDouble()
                    sumOfSquares += v * v
                }
                samples += n
                if (samples >= WINDOW_SAMPLES) {
                    out += LiveLevels.levelOf(sumOfSquares, samples)
                    sumOfSquares = 0.0
                    samples = 0
                }
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    private fun open(): MediaCodec {
        val head = ByteArray(19).also { h ->
            "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            h[8] = 1
            h[9] = OPUS_CHANNELS.toByte()
            h[10] = (OPUS_PRESKIP and 0xFF).toByte(); h[11] = (OPUS_PRESKIP shr 8).toByte()
            ByteBuffer.wrap(h, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(OPUS_SAMPLE_RATE)
        }
        fun nanos(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_SAMPLE_RATE, OPUS_CHANNELS).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", ByteBuffer.wrap(nanos(OPUS_PRESKIP * 1_000_000_000L / OPUS_SAMPLE_RATE)))
            setByteBuffer("csd-2", ByteBuffer.wrap(nanos(80_000_000L)))
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private companion object {
        const val TAG = "LiveLevelMeter"
        const val FRAME_US = 20_000L
        /** One bar per 60 ms of audio at the decoder's 48 kHz output. */
        const val WINDOW_SAMPLES = OPUS_SAMPLE_RATE * 60 / 1000
    }
}
