package com.nomily.app.asr

import android.content.Context
import com.nomily.app.R
import com.nomily.app.core.asr.normalizedLocalHost
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * "Test connection" for the local ASR server, mirroring iOS `LocalASR.probe`.
 *
 * The server answers on two ports: [port] for live streaming and `port + 1` for file transcription. Both get a
 * `GET /health`; either answering counts as reachable, and a silent one becomes a warning, since half the feature
 * would fail. Blocking — call off the main thread.
 */
object LocalAsrProbe {

    /** [warning] non-null still counts as reachable. */
    data class Result(val ok: Boolean, val detail: String? = null, val error: String? = null, val warning: String? = null)

    private class PortProbe(val responded: Boolean, val health: JSONObject? = null, val latencyMs: Long? = null, val error: Exception? = null)

    /** 6 s: on a LAN anything slower is effectively unreachable, and a longer wait reads as a broken button. */
    fun probe(ctx: Context, host: String, port: Int, timeoutMs: Int = 6_000): Result {
        val bare = normalizedLocalHost(host)
        if (bare.isEmpty() || port !in 1..65535) return Result(false, error = ctx.getString(R.string.asr_local_probe_no_host))
        val live = hitHealth(bare, port, timeoutMs)
        val batchPort = port + 1
        // port == 65535 has no next port — not a failure.
        val batch = if (batchPort <= 65535) hitHealth(bare, batchPort, timeoutMs) else PortProbe(true)
        if (!live.responded && !batch.responded) return Result(false, error = describe(ctx, live.error))

        // Details come from the streaming port when it answered, else from the batch port.
        val primary = if (live.responded) live else batch
        val parts = mutableListOf<String>()
        primary.health?.let { h ->
            h.optString("model").takeIf { it.isNotEmpty() }?.let(parts::add)
            h.optString("compute_type").takeIf { it.isNotEmpty() }?.let(parts::add)
            if (h.has("cpu_threads")) parts += ctx.getString(R.string.asr_local_probe_threads, h.optInt("cpu_threads"))
            h.optString("model_state").takeIf { it.isNotEmpty() && it != "ready" }?.let {
                parts += ctx.getString(R.string.asr_local_probe_model_loading)
            }
        }
        primary.latencyMs?.let { parts += "$it ms" }
        val warning = when {
            !batch.responded -> ctx.getString(R.string.asr_local_probe_warn_batch, batchPort)
            !live.responded -> ctx.getString(R.string.asr_local_probe_warn_live, port)
            else -> null
        }
        return Result(true, detail = parts.joinToString(" · ").ifEmpty { null }, warning = warning)
    }

    private fun hitHealth(host: String, port: Int, timeoutMs: Int): PortProbe {
        val started = System.currentTimeMillis()
        return try {
            val conn = (URL("http", host, port, "/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                // The probe must not leave a keep-alive connection that would make a dead service look alive next time.
                setRequestProperty("Connection", "close")
            }
            try {
                conn.responseCode
                val body = runCatching { (conn.errorStream ?: conn.inputStream).bufferedReader().readText() }.getOrNull()
                // Any HTTP answer means the server is there; the body is optional detail (third-party servers may differ).
                PortProbe(true, body?.let { runCatching { JSONObject(it) }.getOrNull() }, System.currentTimeMillis() - started)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            PortProbe(false, error = e)
        }
    }

    /** One sentence that says what to do next, rather than the platform's exception text. */
    private fun describe(ctx: Context, e: Exception?): String = when (e) {
        is SocketTimeoutException -> ctx.getString(R.string.asr_local_probe_timeout)
        is ConnectException -> ctx.getString(R.string.asr_local_probe_refused)
        is UnknownHostException -> ctx.getString(R.string.asr_local_probe_bad_host)
        is NoRouteToHostException -> ctx.getString(R.string.asr_local_probe_offline)
        null -> ctx.getString(R.string.asr_verify_failed)
        else -> e.message ?: ctx.getString(R.string.asr_verify_failed)
    }
}
