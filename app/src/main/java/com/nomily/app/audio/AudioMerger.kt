package com.nomily.app.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Merge **two adjacent recordings** into one.
 *
 * Use case: “I accidentally paused during a meeting and then resumed recording” — the two halves belong to the same session and should be combined for easier transcription and listening.
 * Specification:
 *  - Order is **old first, new second**;
 *  - Output **M4A (AAC)**, filename is `oldBase+newBase.m4a` so it’s clear which two were merged;
 *  - **Transcriptions/summaries are not inherited**: the merged audio is treated as a new item, prompting the user to transcribe again,
 *    rather than stitching together two outdated transcriptions (bug qg96jrp);
 *  - The source files and all their derived products are deleted by the **caller** after a successful merge.
 *
 * Implemented via [PcmCodec]: each recording is decoded to PCM (**using the old recording’s sample rate as the reference**, the new one is resampled to match),
 * then concatenated and encoded to AAC.
 */
object AudioMerger {

    private const val TAG = "AudioMerger"

    class MergeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @return The merged file (saved in [outDir], usually the clips directory)
     */
    fun merge(older: File, newer: File, outDir: File): File {
        val tmpA = File.createTempFile("merge-a", ".raw", outDir)
        val tmpB = File.createTempFile("merge-b", ".raw", outDir)
        val out = AudioImporter.uniqueDestination(
            outDir,
            "${older.nameWithoutExtension}+${newer.nameWithoutExtension}.m4a",
        )
        try {
            val a = PcmCodec.decodeToPcm(older, tmpA, mono = true)
            // Decode the second track at the first track’s sample rate — directly concatenating tracks with different rates would change speed
            val b = PcmCodec.decodeToPcm(newer, tmpB, targetRate = a.rate, mono = true)

            // **Both segments must actually produce decoded data**. The decoder sometimes emits zero bytes without error,
            // resulting in a corrupted file, and the caller would delete the two source recordings immediately after a successful merge
            // — the user would end up with an empty file instead of two real recordings. Better to skip merging.
            if (tmpA.length() == 0L || tmpB.length() == 0L) {
                throw MergeException(
                    "Decoded result empty (${older.name}=${tmpA.length()}B, ${newer.name}=${tmpB.length()}B), not merged, source files not deleted",
                )
            }

            RandomAccessFile(tmpA, "r").use { rafA ->
                RandomAccessFile(tmpB, "r").use { rafB ->
                    val readA = PcmCodec.rangeReader(tmpA, listOf(0L to tmpA.length() / 2))
                    val readB = PcmCodec.rangeReader(tmpB, listOf(0L to tmpB.length() / 2))
                    var firstDone = false
                    PcmCodec.encodePcmToM4a(out, a.rate) { buf, limit ->
                        if (!firstDone) {
                            val n = readA(rafA, buf, limit)
                            if (n > 0) return@encodePcmToM4a n
                            firstDone = true
                        }
                        readB(rafB, buf, limit)
                    }
                }
            }
            // Before completing, read back the product once: if the exporter reports “success” but outputs an empty file,
            // then deleting the two source recordings would be a pure loss.
            if (!out.isFile || out.length() == 0L) {
                out.delete()
                throw MergeException("Merged file is empty, discarded, source files not deleted")
            }
            Log.i(TAG, "Merged ${older.name} + ${newer.name} → ${out.name} (%.1fs + %.1fs, %dB)"
                .format(a.durationSec, b.durationSec, out.length()))
            return out
        } catch (e: Exception) {
            out.delete()
            throw MergeException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            tmpA.delete()
            tmpB.delete()
        }
    }
}
