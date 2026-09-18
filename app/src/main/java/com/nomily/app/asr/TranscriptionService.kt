package com.nomily.app.asr

import android.util.Log
import com.nomily.app.core.asr.AsrResult
import com.nomily.app.core.audio.oggDurationMs
import com.nomily.app.core.config.AppConfig
import com.nomily.app.audio.AudioExporter
import com.nomily.app.audio.LocalVad
import com.nomily.app.core.audio.VadMath
import java.io.File

/**
 * Transcription orchestration.
 *
 * A `primary → fallbacks` chain performs three tasks:
 *  1. **Minimum duration gate**: clips shorter than `min_transcribe_duration` are skipped outright (saves cost and avoids empty results)
 *  2. **Run only providers with credentials**: those without configuration are removed from the chain ——
 *     Without this filter, an unconfigured Local provider would forever mask the real Azure error
 *     (the chain reaches the end, the last error wins, and the user sees “local server not configured”)
 *  3. **Persist artifacts**: `{base}.txt` (readable text) + `{base}.asr.json` (structured).
 *
 * ⚠️ **Local VAD silence trimming has not been ported**. Without it we just send extra silence,
 * spend more on ASR, but the result is still correct — it’s a P2 item, scheduled separately.
 */
class TranscriptionService(private val clipsDir: File) {

    class TooShortException(val duration: Double, val minimum: Int) :
        Exception("Clip is ${"%.1f".format(duration)}s (under the ${minimum}s minimum).")

    /** Result of a single transcription: artifact paths + any “downgrade” notifications from the chain. */
    data class Outcome(val result: AsrResult, val warnings: List<String>)

    /**
     * @param audio Local audio file (`filesDir/clips/xxx.ogg`)
     * @param enforceMinDuration Enforce the minimum‑duration gate only for automatic transcription; manual “Transcribe” clicks bypass it
     */
    fun transcribe(
        audio: File,
        config: AppConfig,
        locales: List<String>? = null,
        enforceMinDuration: Boolean = true,
    ): Outcome {
        if (enforceMinDuration && config.minTranscribeDuration > 0) {
            val ms = runCatching { oggDurationMs(audio.readBytes()) }.getOrNull()
            if (ms != null) {
                val seconds = ms / 1000.0
                if (seconds < config.minTranscribeDuration) {
                    throw TooShortException(seconds, config.minTranscribeDuration)
                }
            }
        }

        val chain = providerChain(config)
        if (chain.isEmpty()) {
            throw AsrException(
                "No ASR provider is configured.",
                messageRes = com.nomily.app.R.string.asr_no_provider_configured,
            )
        }

        // Perform local silence removal before upload (optional). If trimming isn’t possible, upload the original — [LocalVad.processForUpload] returns null.
        // Timestamps must be **mapped back to the original timeline**: the provider reports the trimmed time, and writing that directly to the artifact would misalign with the local audio.
        val trim = if (config.localVadEnabled) {
            LocalVad.processForUpload(audio, File(clipsDir.parentFile, "vad-cache"))
        } else {
            null
        }
        var upload = trim?.file ?: audio

        // Any container that no provider on the chain can decode: transcode first then upload, don't use a single round‑trip to replace an inevitable 422.
        // CAF is the one QA actually hit — the system can decode it, Azure simply won't accept it.
        var transcoded: File? = null
        if (upload.extension.lowercase() in ALWAYS_TRANSCODE) {
            transcodeForUpload(upload)?.let { transcoded = it; upload = it }
        }

        val warnings = mutableListOf<String>()
        var last: Exception? = null
        try {
        chain.forEachIndexed { index, name ->
            try {
                var result = try {
                    runProvider(name, upload, config, locales)
                } catch (e: AsrException) {
                    // The whole chain only attempts remediation once: when a provider rejects **due to format** (not because of credentials/quota/
                    // network issues), transcode to AAC/M4A and try the same provider again.
                    // Azure's refusal to handle those .opus variants is resolved by this step, rather than stopping at 422 InvalidAudioFormat.
                    val rescue = if (isFormatRejection(e) && transcoded == null) {
                        transcodeForUpload(upload)
                    } else {
                        null
                    }
                    if (rescue == null) throw e
                    Log.i(TAG, "Provider $name rejected format, retrying after transcoding to m4a")
                    transcoded = rescue
                    upload = rescue
                    runProvider(name, upload, config, locales)
                }
                trim?.let { result = remapTimestamps(result, it.ranges) }
                writeArtefacts(result, audio)
                return Outcome(result, warnings)
            } catch (e: Exception) {
                Log.w(TAG, "provider $name failed: ${e.message}")
                last = e
                // If there's another one, just note it; the UI can continuously show “using fallback provider” without interruption.
                if (index < chain.size - 1) warnings += "$name: ${e.message}"
            }
        }
        throw last ?: AsrException("Transcription failed.")
        } finally {
            trim?.file?.delete()
            transcoded?.delete()
        }
    }

    /**
     * Only when “I can’t decode this audio segment” does it merit a transcode retry. Azure uses 400/415/422 to indicate the payload cannot be decoded;
     * 401/429/5xx and local exceptions (status is null) yield the same result no matter how many retries.
     */
    private fun isFormatRejection(e: AsrException): Boolean =
        e.status == 400 || e.status == 415 || e.status == 422

    /**
     * Transcode to AAC/M4A. If the platform still can’t decode it, return null — in that case propagating the provider’s original error to the user is more useful than throwing a generic “transcode failed”.
     */
    private fun transcodeForUpload(source: File): File? = runCatching {
        AudioExporter.exportAsM4a(
            source = source,
            outDir = File(clipsDir.parentFile, "asr-transcode"),
            stem = "asr-${source.nameWithoutExtension}-${System.nanoTime()}",
        )
    }.onFailure { Log.w(TAG, "Transcoding to ASR failed: ${it.message}") }.getOrNull()

    /** Move each segment’s timestamps from the trimmed timeline back to the original timeline. */
    private fun remapTimestamps(result: AsrResult, ranges: List<VadMath.SpeechRange>): AsrResult =
        result.copy(
            segments = result.segments.map { seg ->
                val start = VadMath.remapToOriginal(seg.start, ranges)
                val end = VadMath.remapToOriginal(seg.end, ranges)
                seg.copy(start = start, end = end, duration = end - start)
            },
        )

    /** Start with `primary`, deduplicate and append `fallbacks`, **then drop any without credentials**. */
    private fun providerChain(config: AppConfig): List<String> {
        val chain = mutableListOf(config.defaults.asrPrimary)
        config.defaults.asrFallbacks.forEach { if (it != config.defaults.asrPrimary) chain += it }
        return chain.filter { it.isNotEmpty() && hasCredentials(it, config) }
    }

    private fun hasCredentials(name: String, config: AppConfig): Boolean = when (name.lowercase()) {
        // If this set of credentials was validated on the settings page and then rejected, don’t use it for transcription — that round will inevitably return 401,
        // // The user would only see a single unexplained failure. Credentials that haven’t been validated can still be used.
        "azure" -> config.asrProviders.azure
            ?.let { it.key.isNotEmpty() && it.region.isNotEmpty() && it.verification != false } ?: false
        "local" -> config.asrProviders.local?.let { it.host.isNotEmpty() && it.port > 0 } ?: false
        else -> false
    }

    private fun runProvider(
        name: String,
        audio: File,
        config: AppConfig,
        locales: List<String>?,
    ): AsrResult = when (name.lowercase()) {
        "azure" -> {
            val a = config.asrProviders.azure ?: throw AsrException("Azure credentials are not configured.")
            AzureAsr(a.key, a.region, locales).transcribe(audio)
        }
        "local" -> {
            val l = config.asrProviders.local ?: throw AsrException("Local ASR server is not configured.")
            LocalAsr(l.host, l.port).transcribe(audio)
        }
        else -> throw AsrException("Unknown ASR provider: $name")
    }

    /** The output is placed beside the audio file with the same base name. */
    private fun writeArtefacts(result: AsrResult, audio: File) {
        val base = audio.name.substringBeforeLast('.')
        File(clipsDir, "$base.txt").writeText(result.formatTranscript())
        File(clipsDir, "$base.asr.json").writeText(result.toJsonString())
        Log.i(TAG, "Wrote transcript $base.txt / $base.asr.json (${result.segments.size} segments)")
    }

    private companion object {
        const val TAG = "Asr"

        /**
         * File extensions that are transcoded before the first upload: no provider on the chain accepts them. All others are uploaded as‑is,
         * and only when a provider actually complains do we transcode — the device‑generated Ogg‑Opus is accepted today,
         * so we shouldn’t needlessly re‑encode it.
         */
        val ALWAYS_TRANSCODE = setOf("caf")
    }
}
