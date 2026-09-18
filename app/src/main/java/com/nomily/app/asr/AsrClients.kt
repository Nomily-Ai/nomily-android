package com.nomily.app.asr

import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.asr.AsrEmptyException
import com.nomily.app.core.asr.AsrResult
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

    fun transcribe(file: File): AsrResult {
        if (host.isEmpty() || port <= 0) throw AsrException("Local ASR server is not configured.")
        val endpoint = URL("http://$host:${port + 1}/v1/transcribe")
        val boundary = "----nomily-${UUID.randomUUID()}"
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 600_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        Log.i(TAG, "local transcribe start file=${file.name} host=$host:${port + 1}")
        try {
            conn.outputStream.buffered().use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n")
                w("Content-Type: audio/ogg\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
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
        } finally {
            conn.disconnect()
        }
    }
}
