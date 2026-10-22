package com.nomily.app.asr

import android.content.Context
import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.asr.CustomAsrEndpoint
import com.nomily.app.core.asr.RemoteLiveEndpoint
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Settings-page probes for the two user-supplied servers: the OpenAI-compatible file transcription server
 * (iOS `CustomASR.probe/listModels`) and the remote secure live server (iOS `RemoteLiveASR.probe`).
 * Blocking — call off the main thread.
 */
object CustomAsrProbe {

    /** `GET …/v1/models`: any 2xx proves reachability and the key; 401/403 means "server fine, key wrong". */
    fun probe(ctx: Context, endpoint: String, apiKey: String): LocalAsrProbe.Result {
        val url = CustomAsrEndpoint.modelsUrl(endpoint)
            ?: return LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_local_probe_no_host))
        return try {
            val status = get(url, apiKey.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") }, 6_000).first
            when (status) {
                in 200..299 -> LocalAsrProbe.Result(true, detail = "${URL(url).host} · $status")
                401, 403 -> LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_custom_probe_auth))
                // Reached *something* HTTP — reachable, but not an OpenAI-compatible API.
                else -> LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_custom_probe_http, status))
            }
        } catch (e: Exception) {
            LocalAsrProbe.Result(false, error = e.message ?: ctx.getString(R.string.asr_verify_failed))
        }
    }

    /** Model ids for the dropdown (see [CustomAsrEndpoint.dropdownModels]); `null` when the server offers no usable list. */
    fun listModels(endpoint: String, apiKey: String): List<String>? {
        val url = CustomAsrEndpoint.modelsUrl(endpoint) ?: return null
        return try {
            val (status, body) = get(url, apiKey.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") }, 8_000)
            if (status != 200) return null
            CustomAsrEndpoint.dropdownModels(CustomAsrEndpoint.parseModelIds(body)).ifEmpty { null }
        } catch (e: Exception) {
            Log.i("Asr", "custom listModels failed: ${e.message}")
            null
        }
    }
}

object RemoteLiveProbe {

    /** Classified failure of the remote live service; [describe] turns it into the sentence the user acts on. */
    enum class Failure { INVALID_ENDPOINT, MISSING_KEY, UNAUTHORIZED, HANDSHAKE, TIMEOUT, SERVER_CLOSED }

    fun describe(ctx: Context, failure: Failure, detail: String? = null): String {
        val base = ctx.getString(
            when (failure) {
                Failure.INVALID_ENDPOINT -> R.string.asr_remote_err_invalid_scheme
                Failure.MISSING_KEY -> R.string.asr_remote_err_missing_key
                Failure.UNAUTHORIZED -> R.string.asr_remote_err_auth
                Failure.HANDSHAKE -> R.string.asr_remote_err_handshake
                Failure.TIMEOUT -> R.string.asr_remote_err_timeout
                Failure.SERVER_CLOSED -> R.string.asr_remote_err_server
            },
        )
        return detail?.let { "$base ($it)" } ?: base
    }

    /** Settings "Test connection": scheme check, then HTTPS `/health` with the key in both headers. */
    fun probe(ctx: Context, endpoint: String, apiKey: String): LocalAsrProbe.Result {
        if (RemoteLiveEndpoint.listenUrl(endpoint) == null) {
            return LocalAsrProbe.Result(false, error = describe(ctx, Failure.INVALID_ENDPOINT))
        }
        if (apiKey.isEmpty()) return LocalAsrProbe.Result(false, error = describe(ctx, Failure.MISSING_KEY))
        val url = RemoteLiveEndpoint.healthUrl(endpoint)
            ?: return LocalAsrProbe.Result(false, error = describe(ctx, Failure.INVALID_ENDPOINT))
        return try {
            val status = get(url, authHeaders(apiKey), 8_000).first
            when (status) {
                in 200..299 -> LocalAsrProbe.Result(true, detail = "${URL(url).host} · $status")
                401, 403 -> LocalAsrProbe.Result(false, error = describe(ctx, Failure.UNAUTHORIZED))
                else -> LocalAsrProbe.Result(false, error = describe(ctx, Failure.HANDSHAKE, "HTTP $status"))
            }
        } catch (e: SocketTimeoutException) {
            LocalAsrProbe.Result(false, error = describe(ctx, Failure.TIMEOUT))
        } catch (e: Exception) {
            LocalAsrProbe.Result(false, error = describe(ctx, Failure.HANDSHAKE, e.message))
        }
    }

    /**
     * Verdict for a WebSocket that failed before any frame arrived: ask `/health` directly — 401/403 ⇒ key wrong,
     * reachable ⇒ path/protocol mismatch, timeout ⇒ no answer. `null` when the probe adds nothing (transport error).
     */
    fun healthVerdict(endpoint: String, apiKey: String): Pair<Failure, String?>? {
        val url = RemoteLiveEndpoint.healthUrl(endpoint) ?: return Failure.INVALID_ENDPOINT to null
        return try {
            when (val status = get(url, authHeaders(apiKey), 8_000).first) {
                in 200..299 -> Failure.HANDSHAKE to null
                401, 403 -> Failure.UNAUTHORIZED to null
                else -> Failure.HANDSHAKE to "HTTP $status"
            }
        } catch (e: SocketTimeoutException) {
            Failure.TIMEOUT to null
        } catch (e: Exception) {
            null
        }
    }

    /** `Authorization: Bearer` is the primary; `X-API-Key` covers servers whose WS layer can't read `Authorization`. */
    fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey", "X-API-Key" to apiKey)
}

/** One uncached GET → (status, body). TLS validation is the platform default and is never relaxed. */
private fun get(url: String, headers: Map<String, String>?, timeoutMs: Int): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        useCaches = false
        setRequestProperty("Connection", "close")
        headers?.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    return try {
        val status = conn.responseCode
        val body = runCatching { (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() } }.getOrDefault("")
        status to body
    } finally {
        conn.disconnect()
    }
}
