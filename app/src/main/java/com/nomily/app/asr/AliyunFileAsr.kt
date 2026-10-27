package com.nomily.app.asr

import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.asr.AliyunAsr
import com.nomily.app.core.asr.AliyunConfig
import com.nomily.app.core.asr.AsrEmptyException
import com.nomily.app.core.asr.AsrResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val TAG = "Asr-Aliyun"

/**
 * Aliyun NLS file transcription. The service takes no uploads — `SubmitTask` wants a public `file_link` — so the audio
 * is staged in the **user's own OSS bucket** (private object, header-signed PUT), a 2-hour presigned GET becomes the
 * link, the task is submitted and polled, and the staged object is deleted afterwards (best effort).
 *
 * A task still running after [AliyunAsr.MAX_WAIT_MS] is reported, never resubmitted: a resubmit would bill the same
 * audio twice. Signing and parsing are in `:core` ([AliyunAsr]). Blocking — call off the main thread.
 */
class AliyunFileAsr(private val cfg: AliyunConfig, private val locales: List<String>? = null) {

    fun transcribe(file: File): AsrResult {
        if (!cfg.isComplete) throw AsrException("Aliyun credentials are not configured.")
        val started = System.currentTimeMillis()
        val region = cfg.effectiveRegion
        val ext = file.extension.lowercase().ifEmpty { "bin" }
        val key = "nomily-asr/${UUID.randomUUID()}.$ext"
        var keepObject = false
        try {
            ossPut(file, AliyunAsr.mimeType(ext), key)
            Log.i(TAG, "oss upload ok key=$key bytes=${file.length()}")
            val link = AliyunAsr.presignedGetUrl(
                cfg.ossBucket, region, key, cfg.accessKeyId, cfg.accessKeySecret,
                System.currentTimeMillis() / 1000 + AliyunAsr.PRESIGN_VALIDITY_SECONDS,
            )
            val submit = popCall("SubmitTask", listOf("Task" to AliyunAsr.submitTaskJson(cfg.appKey, link)), "POST")
            val (taskId, why) = AliyunAsr.parseSubmit(submit)
            if (taskId == null) throw taskFailed(why)
            Log.i(TAG, "submitted task=$taskId file=${file.name}")

            val deadline = started + AliyunAsr.MAX_WAIT_MS
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                when (val poll = AliyunAsr.parsePoll(popCall("GetTaskResult", listOf("TaskId" to taskId), "GET"))) {
                    is AliyunAsr.Poll.Done -> {
                        val result = AliyunAsr.parseResult(poll.resultJson, locales?.firstOrNull().orEmpty())
                        Log.i(TAG, "result ok segments=${result.segments.size} in ${(System.currentTimeMillis() - started) / 1000}s")
                        return result
                    }
                    AliyunAsr.Poll.Running -> {
                        if (System.currentTimeMillis() > deadline) {
                            // The task keeps going server-side and still needs the file.
                            keepObject = true
                            val minutes = (AliyunAsr.MAX_WAIT_MS / 60_000).toInt()
                            throw AsrException(
                                "Aliyun task still running after $minutes min; not resubmitted.",
                                messageRes = R.string.asr_still_running, messageArgs = listOf(minutes), retriable = false,
                            )
                        }
                        Thread.sleep(AliyunAsr.POLL_INTERVAL_MS)
                    }
                    is AliyunAsr.Poll.Failed -> throw taskFailed(poll.detail)
                }
            }
        } catch (e: AsrException) {
            throw e
        } catch (e: AsrEmptyException) {
            throw AsrException(e.message ?: "transcript is empty", cause = e, messageRes = R.string.asr_transcript_empty)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AsrException("Cancelled", cause = e)
        } catch (e: Exception) {
            throw AsrException(e.message ?: e.javaClass.simpleName, cause = e)
        } finally {
            if (!keepObject) ossDelete(key)
        }
    }

    private fun taskFailed(detail: String) = AsrException(
        "Aliyun task failed: $detail", messageRes = R.string.asr_task_failed, messageArgs = listOf(detail), retriable = false,
    )

    // ── POP RPC ───────────────────────────────────────────────────────

    private fun popCall(action: String, extra: List<Pair<String, String>>, method: String): String {
        val region = cfg.effectiveRegion
        val params = AliyunAsr.rpcParams(
            action, AliyunAsr.API_VERSION, region, cfg.accessKeyId, UUID.randomUUID().toString(), utcTimestamp(), extra,
        )
        val query = AliyunAsr.signedQuery(method, params, cfg.accessKeySecret)
        val base = "https://filetrans.$region.aliyuncs.com"
        // GET carries the query; POST carries the identical string as a form body.
        val conn = (URL(if (method == "GET") "$base/?$query" else "$base/").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 30_000
            useCaches = false
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (method == "POST") conn.outputStream.use { it.write(query.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = runCatching { (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            if (status !in 200..299) throw AsrException("Aliyun returned HTTP $status: ${body.take(200)}", status = status)
            return body
        } finally {
            conn.disconnect()
        }
    }

    // ── OSS ───────────────────────────────────────────────────────────

    private fun ossPut(file: File, contentType: String, key: String) {
        val date = rfc1123Now()
        val conn = ossConnection("PUT", contentType, date, key).apply {
            doOutput = true
            readTimeout = 300_000
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", contentType)
        }
        try {
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val status = conn.responseCode
            if (status !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                Log.e(TAG, "oss put failed HTTP $status: ${body.take(180)}")
                throw AsrException("Aliyun OSS returned HTTP $status: ${body.take(200)}", status = status)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** The staged audio is private and short-lived; failing to delete it (e.g. an AccessKey without delete rights) is not an error. */
    private fun ossDelete(key: String) {
        runCatching {
            val conn = ossConnection("DELETE", "", rfc1123Now(), key)
            try {
                Log.i(TAG, "oss delete key=$key -> HTTP ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "oss delete failed: ${it.message}") }
    }

    private fun ossConnection(verb: String, contentType: String, date: String, key: String): HttpURLConnection {
        val url = URL(AliyunAsr.ossObjectUrl(cfg.ossBucket, cfg.effectiveRegion, key))
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = verb
            connectTimeout = 30_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Date", date)
            setRequestProperty(
                "Authorization",
                AliyunAsr.ossAuthorization(verb, contentType, date, cfg.ossBucket, key, cfg.accessKeyId, cfg.accessKeySecret),
            )
        }
    }

    private fun utcTimestamp(): String = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun rfc1123Now(): String = fmt("EEE, dd MMM yyyy HH:mm:ss 'GMT'")

    private fun fmt(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}

/**
 * Credential check for the settings page: a signed `CreateToken` against nls-meta. The token it returns is what
 * Aliyun's own transcription calls use, so passing proves the AccessKey pair, not just reachability. It doesn't
 * touch the Appkey or the OSS bucket — those are only exercised by a real transcription.
 */
object AliyunProbe {
    fun probe(ctx: android.content.Context, cfg: AliyunConfig): LocalAsrProbe.Result {
        if (cfg.accessKeyId.isBlank()) return LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_cloud_missing, ctx.getString(R.string.asr_aliyun_access_key_id)))
        if (cfg.accessKeySecret.isBlank()) return LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_cloud_missing, ctx.getString(R.string.asr_aliyun_access_key_secret)))
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val params = AliyunAsr.rpcParams(
            "CreateToken", "2019-02-28", null, cfg.accessKeyId, UUID.randomUUID().toString(), fmt.format(Date()),
        )
        val query = AliyunAsr.signedQuery("GET", params, cfg.accessKeySecret)
        val started = System.currentTimeMillis()
        val conn = (URL("${AliyunAsr.DEFAULT_ENDPOINT}/?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            useCaches = false
        }
        return try {
            val status = conn.responseCode
            val millis = System.currentTimeMillis() - started
            val body = runCatching { (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            if (status in 200..299) {
                Log.i(TAG, "probe ok in $millis ms")
                LocalAsrProbe.Result(true, detail = "HTTP $status · $millis ms")
            } else {
                val reason = Regex("\"Message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?: ctx.getString(R.string.asr_cloud_http, status)
                LocalAsrProbe.Result(false, error = reason)
            }
        } catch (e: java.net.SocketTimeoutException) {
            LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_cloud_timeout))
        } catch (e: java.net.UnknownHostException) {
            LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_cloud_unreachable))
        } catch (e: java.net.ConnectException) {
            LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_cloud_unreachable))
        } catch (e: Exception) {
            LocalAsrProbe.Result(false, error = e.message ?: ctx.getString(R.string.asr_verify_failed))
        } finally {
            conn.disconnect()
        }
    }
}
