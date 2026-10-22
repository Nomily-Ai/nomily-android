package com.nomily.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.nomily.app.core.audio.LiveLevels
import com.nomily.app.core.audio.WavFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Records the phone microphone as 16 kHz mono PCM16 into a WAV file (iOS `PhoneMicRecorder`).
 *
 * A worker thread reads [AudioRecord] in 100 ms chunks, appends them behind a 44-byte header and hands each chunk to
 * [onPcm] (live captions) and its loudness to [onLevel]. [stop] joins the thread and patches the header's sizes, so
 * the file is a normal WAV afterwards. A write error ends the take (the audio already on disk is still patched and
 * kept) and is reported through [onError]; callers must treat [onPcm] as non-blocking.
 *
 * Needs `RECORD_AUDIO` granted before [start]; the caller is responsible for the foreground service.
 */
class PhoneMicRecorder(
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (Error) -> Unit,
) {
    enum class Error { NO_INPUT, WRITE_FAILED }

    class Result(val file: File, val pcmBytes: Long) {
        val seconds: Double get() = WavFormat.seconds(pcmBytes)
    }

    @Volatile private var running = false
    @Volatile var pcmBytes = 0L
        private set
    private var thread: Thread? = null
    private var file: File? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(target: File): Boolean {
        if (running) return false
        val chunk = WavFormat.BYTES_PER_SECOND / 10
        val min = AudioRecord.getMinBufferSize(
            WavFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, WavFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4),
            )
        }.getOrNull()
        if (min <= 0 || record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            Log.w(TAG, "AudioRecord not available (min=$min)")
            return false
        }
        val out = runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().buffered(1 shl 16).also { it.write(WavFormat.header(0)) }
        }.getOrNull()
        if (out == null) {
            record.release()
            return false
        }
        file = target
        pcmBytes = 0
        running = true
        thread = Thread({
            val buf = ByteArray(chunk)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) { onError(Error.NO_INPUT); break }
                        continue
                    }
                    out.write(buf, 0, n)
                    pcmBytes += n
                    onLevel(LiveLevels.level(WavFormat.samples(buf, n)))
                    runCatching { onPcm(buf, n) }
                }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "write failed", e)
                onError(Error.WRITE_FAILED)
            } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
                onError(Error.NO_INPUT)
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { out.close() }
            }
        }, "phone-mic").also { it.start() }
        return true
    }

    /** Ends the take and returns the finished file; null when nothing was started. */
    fun stop(): Result? {
        val f = file ?: return null
        running = false
        thread?.join(2_000)
        thread = null
        file = null
        val bytes = pcmBytes
        runCatching { RandomAccessFile(f, "rw").use { it.write(WavFormat.header(bytes)) } }
            .onFailure { Log.w(TAG, "WAV header patch failed: ${it.message}") }
        return Result(f, bytes)
    }

    private companion object {
        const val TAG = "PhoneMicRecorder"
    }
}
