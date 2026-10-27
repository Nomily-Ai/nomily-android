package com.nomily.app.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Peak envelope of an audio file — the bars of the clip page's waveform scrubber (iOS `AudioEnvelope`).
 *
 * The data comes from the file itself, so the picture is honest; a file the device can't decode yields null and
 * the caller keeps the plain slider. Decoding runs once per file version off the main thread: results are kept in
 * memory, and a decode in flight is shared, so reopening the page or a second player doesn't decode again. The
 * decode is not tied to the page — leaving mid-way lets it finish into the cache.
 */
object AudioEnvelope {

    /** Bars at full width: dense enough to read as a waveform, cheap enough to draw inside a scrolling page. */
    const val BUCKETS = 360

    /** The envelope only needs the shape, so the PCM is decimated hard (also keeps the temp file small). */
    private const val DECODE_RATE = 8_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val inFlight = HashMap<String, Deferred<FloatArray?>>()

    private fun key(file: File) = "${file.absolutePath}:${file.lastModified()}:${file.length()}"

    fun cached(file: File): FloatArray? = cache[key(file)]

    /** Peaks normalised to 0…1 (loudest bar = 1), or null when the file can't be decoded or is silent. */
    suspend fun load(file: File): FloatArray? {
        val key = key(file)
        cache[key]?.let { return it }
        val task = synchronized(inFlight) { inFlight.getOrPut(key) { scope.async { extract(file) } } }
        val result = task.await()
        synchronized(inFlight) { inFlight.remove(key) }
        if (result != null) cache[key] = result
        return result
    }

    private fun extract(file: File): FloatArray? {
        val tmp = File.createTempFile("envelope-pcm", ".raw", file.parentFile)
        return try {
            val pcm = PcmCodec.decodeToPcm(file, tmp, targetRate = DECODE_RATE, mono = true)
            val total = pcm.sampleCount
            if (total <= 1) return null
            val perBucket = maxOf(1L, total / BUCKETS)
            val peaks = ArrayList<Float>(BUCKETS + 1)
            var running = 0
            var inBucket = 0L
            tmp.inputStream().buffered(1 shl 16).use { input ->
                val buf = ByteArray(1 shl 14)
                // 16-bit little-endian; a read can end between the two bytes of a sample.
                var low = -1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        if (low < 0) {
                            low = buf[i].toInt() and 0xFF
                            continue
                        }
                        val sample = ((buf[i].toInt() shl 8) or low).toShort().toInt()
                        low = -1
                        running = maxOf(running, abs(sample))
                        if (++inBucket >= perBucket) {
                            peaks += running.toFloat()
                            running = 0
                            inBucket = 0
                        }
                    }
                }
            }
            if (inBucket > 0) peaks += running.toFloat()
            val loudest = peaks.maxOrNull() ?: 0f
            if (loudest <= 0f || peaks.size <= 1) null else FloatArray(peaks.size) { minOf(1f, peaks[it] / loudest) }
        } catch (e: Exception) {
            Log.w("AudioEnvelope", "No waveform for ${file.name}: ${e.message}")
            null
        } finally {
            tmp.delete()
        }
    }
}
