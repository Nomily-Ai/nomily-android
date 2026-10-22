package com.nomily.app.asr

import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.asr.AsrEmptyException
import com.nomily.app.core.asr.AsrResult
import com.nomily.app.core.asr.CustomAsrEndpoint
import com.nomily.app.core.asr.parseAzureResponse
import com.nomily.app.core.asr.parseLocalResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAG = "Asr"

/**
 * “No one is speaking in this audio” is not a parsing failure: wrapping it in a generic exception would make the UI show messages like
 * `transcript is empty` in English technical wording.
 */
private fun emptyTranscript(cause: AsrEmptyException) = AsrException(
    cause.message ?: "transcript is empty",
    cause = cause,
    messageRes = R.string.asr_transcript_empty,
)

/**
 * Throw this uniformly for upload/parsing errors; the UI displays the message directly.
 *
 * [status] is the HTTP status code returned by the provider (null for local errors). It’s kept because the orchestration layer needs to distinguish “I can’t decode this audio” (400/415/422, worth retrying after transcoding) from “I can’t perform this task” (401/429/5xx, retry is futile) — the message string alone can’t make that distinction.
 */
class AsrException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
    /**
     * Resource ID for the user‑visible reason. This layer has no Context; the final string is fetched by the ViewModel,
     * otherwise the English message would be rendered directly in the UI (even on a Chinese interface).
     */
    val messageRes: Int? = null,
) : Exception(message, cause)

/**
 * Azure Fast Transcription.
 *
 * The request shape is **copied verbatim**, not custom‑designed: `multipart/form-data` with a `definition` JSON part (disable profanity filter, enable speaker diarization up to 10 speakers) + an `audio` part.
 *
 * ⚠️ Two pitfalls that only return 422 without a clear reason:
 *  1. **The `Content‑Type` of the `audio` part determines which demultiplexer the service uses**; it cannot always be `application/octet-stream`;
 *     we send Ogg‑Opus, so it must be declared as `audio/ogg`.
 *  2. When `locales` is empty, **omit the entire key** (don’t send an empty array) — an empty value triggers Azure’s automatic multilingual detection.
 */
class AzureAsr(
    private val key: String,
    private val region: String,
    locales: List<String>? = null,
) {
    private val locales: List<String>? = locales?.take(10)?.takeIf { it.isNotEmpty() }

    fun transcribe(file: File): AsrResult {
        if (key.isEmpty() || region.isEmpty()) throw AsrException("Azure credentials are not configured.")
        val endpoint = URL(
            "https://$region.api.cognitive.microsoft.com/speechtotext/transcriptions" +
                ":transcribe?api-version=2025-10-15",
        )
        val boundary = "----nomily-${UUID.randomUUID()}"
        val definition = buildString {
            append("{\"profanityFilterMode\":\"None\",")
            append("\"diarization\":{\"enabled\":true,\"maxSpeakers\":10}")
            locales?.let { append(",\"locales\":[").append(it.joinToString(",") { l -> "\"$l\"" }).append("]") }
            append("}")
        }

        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 600_000
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        Log.i(TAG, "azure transcribe start file=${file.name} bytes=${file.length()} locales=${locales ?: "auto"}")
        try {
            conn.outputStream.buffered().use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"definition\"\r\n")
                w("Content-Type: application/json\r\n\r\n")
                w(definition)
                w("\r\n--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n")
                w("Content-Type: ${contentTypeFor(file.name)}\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                Log.e(TAG, "azure HTTP $status: ${body.take(400)}")
                throw AsrException("Azure returned HTTP $status: ${body.take(200)}", status = status)
            }
            return parseAzureResponse(body, locales?.firstOrNull() ?: "auto")
        } catch (e: AsrException) {
            throw e
        } catch (e: AsrEmptyException) {
            throw emptyTranscript(e)
        } catch (e: Exception) {
            throw AsrException(e.message ?: e.javaClass.simpleName, cause = e)
        } finally {
            conn.disconnect()
        }
    }

    private fun contentTypeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            else -> "audio/ogg"
        }
    }
}

/**
 * Local faster‑whisper server (`server/`):
 * Batch transcription uses **`port + 1`** at `/v1/transcribe` (the streaming WebSocket uses `port`).
 */
class LocalAsr(private val host: String, private val port: Int) {

    companion object {
        /**
         * Read timeout = audio duration × 3, at least 600 s. CPU inference runs at about 0.7–1.0× real time and the
         * server queues requests serially (worst measured 0.89×), so ×3 leaves a wide margin; a long clip with a fixed
         * 600 s would time out on a slow server. The user can still cancel the job, so erring long is safe.
         */
        fun uploadTimeoutMs(durationSec: Double?): Int {
            val d = durationSec?.takeIf { it.isFinite() && it > 0 } ?: return 600_000
            return (maxOf(600.0, d * 3) * 1000).toInt()
        }

        /** Audio length for the timeout only; null when it can't be read (falls back to the 600 s floor). */
        internal fun probeDurationSec(file: File): Double? = runCatching {
            if (file.extension.lowercase() in setOf("ogg", "opus")) {
                // Ogg-Opus keeps the duration in the last page's granule position: the file tail is enough.
                val tail = file.inputStream().use { input ->
                    val skip = (file.length() - 256 * 1024).coerceAtLeast(0)
                    input.skip(skip)
                    input.readBytes()
                }
                com.nomily.app.core.audio.oggDurationMsFromTail(tail)?.let { it / 1000.0 }
            } else {
                // Not `use {}`: MediaMetadataRetriever is AutoCloseable only from API 29 (minSdk is 24).
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(file.path)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
                } finally {
                    r.release()
                }
            }
        }.getOrNull()
    }

    fun transcribe(file: File): AsrResult {
        if (host.isEmpty() || port <= 0) throw AsrException("Local ASR server is not configured.")
        val endpoint = URL("http://$host:${port + 1}/v1/transcribe")
        val timeoutMs = uploadTimeoutMs(probeDurationSec(file))
        Log.i(TAG, "local transcribe start file=${file.name} host=$host:${port + 1}")
        try {
            // Our own server takes the raw bytes; a generic FastAPI whisper server answers that with a 422 naming the
            // missing `file` field, and gets the same endpoint spoken as multipart once.
            var (status, body) = post(endpoint, timeoutMs, file, multipart = false)
            if (status == 422 && serverWantsMultipartFile(body)) {
                Log.i(TAG, "local server wants multipart 'file' — retrying as multipart")
                post(endpoint, timeoutMs, file, multipart = true).let { status = it.first; body = it.second }
            }
            if (status !in 200..299) {
                throw AsrException("Local ASR returned HTTP $status: ${body.take(200)}", status = status)
            }
            return parseLocalResponse(body)
        } catch (e: AsrException) {
            throw e
        } catch (e: AsrEmptyException) {
            throw emptyTranscript(e)
        } catch (e: Exception) {
            throw AsrException(e.message ?: e.javaClass.simpleName, cause = e)
        }
    }

    private fun post(endpoint: URL, timeoutMs: Int, file: File, multipart: Boolean): Pair<Int, String> {
        val boundary = "----nomily-${UUID.randomUUID()}"
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", if (multipart) "multipart/form-data; boundary=$boundary" else "application/octet-stream")
        }
        try {
            conn.outputStream.buffered().use { out ->
                if (!multipart) {
                    file.inputStream().use { it.copyTo(out) }
                    return@use
                }
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                // `model` is sent because servers that validate it would otherwise 422 a second time.
                w("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n")
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                w("Content-Type: ${mimeFor(file.name)}\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return status to body
        } finally {
            conn.disconnect()
        }
    }

    /** Matches FastAPI's structured "missing body.file" error, so unrelated 422s (bad parameter, quota) don't retry. */
    internal fun serverWantsMultipartFile(body: String): Boolean =
        body.contains("\"body\",\"file\"") || (body.contains("\"type\"") && body.contains("\"missing\"") && body.contains("file"))

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        else -> "audio/ogg"
    }
}

/**
 * User-defined server speaking the **OpenAI-compatible** `POST /v1/audio/transcriptions` (self-hosted whisper
 * servers, Groq, SiliconFlow…), mirroring iOS `CustomASR`. Multipart: `model`, `response_format=verbose_json`,
 * optional `language`, `file`. File transcription only.
 *
 * @param apiKey Sent as `Authorization: Bearer`; empty = no header (most self-hosted servers).
 * @param language ISO-639-1 hint; empty = the first requested locale, else server-side detection.
 */
class CustomAsr(
    private val endpoint: String,
    private val apiKey: String,
    model: String,
    private val language: String,
    private val locales: List<String>? = null,
) {
    private val model = model.ifBlank { CustomAsrEndpoint.DEFAULT_MODEL }

    fun transcribe(file: File): AsrResult {
        val url = CustomAsrEndpoint.transcriptionsUrl(endpoint)
            ?: throw AsrException("Custom server (invalid endpoint)")
        val hint = if (language.isBlank()) locales?.firstOrNull()?.let(CustomAsrEndpoint::shortCode) else CustomAsrEndpoint.shortCode(language)
        val boundary = "nomily-asr-${UUID.randomUUID()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = LocalAsr.uploadTimeoutMs(LocalAsr.probeDurationSec(file))
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        Log.i(TAG, "custom transcribe start file=${file.name} host=${URL(url).host} model=$model hint=${hint ?: "-"}")
        try {
            conn.outputStream.buffered().use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    w("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                field("model", model)
                field("response_format", "verbose_json")
                hint?.let { field("language", it) }
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                w("Content-Type: ${contentTypeFor(file)}\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw AsrException("Custom server returned HTTP $status: ${body.take(200)}", status = status)
            }
            val result = CustomAsrEndpoint.parseTranscription(body, "custom", hint ?: "auto")
            Log.i(TAG, "custom transcribe done segments=${result.segments.size} chars=${result.text.length}")
            return result
        } catch (e: AsrException) {
            throw e
        } catch (e: AsrEmptyException) {
            throw emptyTranscript(e)
        } catch (e: Exception) {
            throw AsrException(e.message ?: e.javaClass.simpleName, cause = e)
        } finally {
            conn.disconnect()
        }
    }

    /** The orchestrator may hand over an m4a after a format rejection, so the part type follows the file. */
    private fun contentTypeFor(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "audio/ogg"
    }
}
