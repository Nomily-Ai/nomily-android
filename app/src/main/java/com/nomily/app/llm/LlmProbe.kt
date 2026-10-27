package com.nomily.app.llm

import android.content.Context
import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.llm.LlmProviderSpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * "Is this provider usable right now, and how fast?" — one real chat request capped at 1 token, timed.
 *
 * `/models` can succeed while chats are refused (wrong tier, model not enabled, quota gone), and it times a metadata
 * endpoint rather than the model. Each provider family gets the wire format [LlmClient] really sends, otherwise the
 * probe would clear a provider that still fails at summary time.
 */
object LlmProbe {

    private const val TAG = "LlmProbe"
    private const val PROMPT = "hi"
    private const val CLOUD_TIMEOUT_MS = 30_000
    private const val LOCAL_TIMEOUT_MS = 12_000

    /** [millis] is meaningful only when [ok]. [error] is already localized or the vendor's own message. */
    data class Result(val ok: Boolean, val millis: Int = 0, val detail: String? = null, val error: String? = null)

    fun test(ctx: Context, spec: LlmProviderSpec, endpoint: String, apiKey: String, model: String): Result {
        if (model.isEmpty()) return fail(ctx.getString(R.string.llm_test_need_model))
        if (spec.keyRequired && apiKey.isEmpty()) return fail(ctx.getString(R.string.llm_test_need_key))
        val base = endpoint.trim().trimEnd('/')
        if (base.isEmpty() || runCatching { URL(base).host }.getOrNull().isNullOrEmpty()) {
            return fail(ctx.getString(R.string.llm_test_bad_endpoint))
        }
        val timeout = if (spec.local) LOCAL_TIMEOUT_MS else CLOUD_TIMEOUT_MS
        val (url, headers, body) = build(spec, base, apiKey, model)
        val started = System.nanoTime()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeout
                readTimeout = timeout
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val millis = ((System.nanoTime() - started) / 1_000_000).toInt()
            when {
                code !in 200..299 ->
                    Result(false, millis, "HTTP $code", message(text) ?: ctx.getString(R.string.llm_test_http, code))
                !answered(text, spec.id) ->
                    Result(false, millis, "HTTP $code", ctx.getString(R.string.llm_test_unexpected_body))
                else -> Result(true, millis, "HTTP $code · $model")
            }
        } catch (e: SocketTimeoutException) {
            fail(ctx.getString(R.string.llm_test_timeout))
        } catch (e: UnknownHostException) {
            fail(ctx.getString(R.string.llm_test_unreachable))
        } catch (e: java.net.ConnectException) {
            fail(ctx.getString(R.string.llm_test_unreachable))
        } catch (e: Exception) {
            Log.w(TAG, "probe ${spec.id} failed: ${e.message}")
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(error: String) = Result(false, error = error)

    private data class Req(val url: String, val headers: Map<String, String>, val body: String)

    private fun build(spec: LlmProviderSpec, base: String, apiKey: String, model: String): Req {
        fun openAi(url: String) = Req(
            url,
            if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"),
            // stream:false — a stream would finish the HTTP response before the model did anything.
            JSONObject().put("model", model).put("max_tokens", 1).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", PROMPT))).toString(),
        )
        return when (spec.id) {
            "openai" -> openAi("https://api.openai.com/v1/chat/completions")
            "claude" -> Req(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", 1)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", PROMPT))).toString(),
            )
            "gemini" -> Req(
                "https://generativelanguage.googleapis.com/v1beta/" +
                    (if (model.startsWith("models/")) model else "models/$model") +
                    ":generateContent?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8"),
                emptyMap(),
                JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PROMPT)))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 1)).toString(),
            )
            // Same path the completion uses (`/v1/chat/completions`, not `/api/chat`).
            "ollama" -> openAi("$base/v1/chat/completions")
            else -> openAi("$base/chat/completions")
        }
    }

    /** A 2xx body that isn't a completion (a proxy answering `{"ok":true}`) must not count as a working provider. */
    private fun answered(text: String, provider: String): Boolean {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return when (provider) {
            "claude" -> json.optJSONArray("content") != null || json.optString("type") == "message"
            "gemini" -> json.optJSONArray("candidates") != null
            else -> json.optJSONArray("choices") != null
        }
    }

    /** Vendors bury the readable reason under `error`; show it verbatim. */
    private fun message(text: String): String? {
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) {
            json.optJSONObject("error")?.let { err ->
                for (k in listOf("message", "msg", "detail", "type")) err.optString(k).takeIf { it.isNotEmpty() }?.let { return it }
                err.optString("error").takeIf { it.isNotEmpty() }?.let { return it }
            }
            json.optString("error").takeIf { it.isNotEmpty() && json.optJSONObject("error") == null }?.let { return it }
            json.optString("message").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return text.take(180).trim().ifEmpty { null }
    }
}

/**
 * Last measured latency per provider, shown on the list row so "which of these is fast" is answerable at a glance.
 * One SharedPreferences string (`llm.latency.byProvider`, `{id: {millis, at}}`); a value older than two days reads as none.
 */
class LlmLatencyMemory(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("llm-latency", Context.MODE_PRIVATE)

    fun save(id: String, millis: Int) = write(load().put(id, JSONObject().put("millis", millis).put("at", System.currentTimeMillis())))

    fun clear(id: String) = write(load().apply { remove(id) })

    fun millis(id: String): Int? {
        val e = load().optJSONObject(id) ?: return null
        if (System.currentTimeMillis() - e.optLong("at") > MAX_AGE_MS) return null
        return e.optInt("millis")
    }

    private fun load(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun write(o: JSONObject) = prefs.edit().putString(KEY, o.toString()).apply()

    private companion object {
        const val KEY = "llm.latency.byProvider"
        const val MAX_AGE_MS = 2 * 24 * 3600 * 1000L
    }
}
