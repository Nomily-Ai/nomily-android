package com.nomily.app.llm

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * “Validate key → fetch the model list from this provider.”
 *
 * The sole purpose is to let users **select** a model on the settings page instead of typing a string. Manual entry means a typo in the model name only surfaces during summarization, with the error coming from the provider and not obvious as a typo.
 *
 * Endpoints, auth headers, and response parsing must follow each API’s documentation; do not invent your own scheme:
 * OpenAI / OpenRouter use `Bearer`, Claude uses `x-api-key + anthropic-version`, Gemini places the key in the query, Ollama uses `/api/tags` (local service, no key).
 */
object LlmModelService {

    /**
     * [errorRes] is the **resource ID** for the failure reason, not the final text — this object has no Context, so the UI layer must resolve the string to match the app’s language. [httpCode] is included only as secondary information in parentheses.
     */
    data class Result(
        val ok: Boolean,
        val models: List<String> = emptyList(),
        val error: String? = null,
        val errorRes: Int? = null,
        val httpCode: Int? = null,
    )

    private const val TAG = "LlmModelService"
    private const val TIMEOUT_MS = 15_000

    fun openai(apiKey: String): Result =
        bearer("https://api.openai.com/v1/models", apiKey, ::parseOpenAi)

    fun claude(apiKey: String): Result = perform(
        "https://api.anthropic.com/v1/models?limit=100",
        headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
        parse = ::parseOpenAi,
    )

    fun gemini(apiKey: String): Result = perform(
        "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=100",
        parse = ::parseGemini,
    )

    /** OpenRouter’s list is long (hundreds); filter by provider prefix. */
    fun openRouter(apiKey: String, providerPrefix: String = ""): Result {
        val r = bearer("https://openrouter.ai/api/v1/models", apiKey, ::parseOpenAi)
        if (!r.ok || providerPrefix.isEmpty()) return r
        val p = providerPrefix.lowercase()
        return r.copy(models = r.models.filter { it.lowercase().startsWith(p) })
    }

    /**
     * Azure Speech “validation”: use the key to call the region’s `issueToken` endpoint for a token; a 2xx response indicates validity. It **does not return a model list** (Azure has no such concept), only an OK or failure reason.
     */
    fun azure(key: String, region: String): Result = try {
        val url = URL("https://$region.api.cognitive.microsoft.com/sts/v1.0/issueToken")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            setRequestProperty("Content-Length", "0")
            doOutput = true
            outputStream.close()
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..299) Result(true) else Result(false, errorRes = azureReason(code), httpCode = code)
    } catch (e: Exception) {
        Log.w(TAG, "Azure authentication failed: ${e.message}")
        // The hostname is `<region>.api.cognitive.microsoft.com`; failure to resolve usually means the region is misspelled,
        // but it also occurs when offline, so this line flags both region and network issues without drawing conclusions for the user.
        val res = if (e is java.net.UnknownHostException) {
            com.nomily.app.R.string.azure_verify_host_unresolved
        } else {
            com.nomily.app.R.string.azure_verify_network
        }
        Result(false, errorRes = res)
    }

    /**
     * Azure status code → user‑actionable reason. A raw `HTTP 401` is cryptic; the user doesn’t know whether to change the key or the region. Keeping the status code in parentheses allows troubleshooting to align with logs.
     */
    private fun azureReason(code: Int): Int = when (code) {
        401, 403 -> com.nomily.app.R.string.azure_verify_bad_credentials
        404 -> com.nomily.app.R.string.azure_verify_region_not_found
        429 -> com.nomily.app.R.string.azure_verify_rate_limited
        in 500..599 -> com.nomily.app.R.string.azure_verify_service_unavailable
        else -> com.nomily.app.R.string.azure_verify_unexpected
    }

    fun ollama(endpoint: String): Result =
        perform("${endpoint.trimEnd('/')}/api/tags", parse = ::parseOllama)

    /** Custom endpoint: test `/v1/models` using the OpenAI‑compatible convention. */
    fun custom(endpoint: String, apiKey: String?): Result {
        val base = endpoint.trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        return if (apiKey.isNullOrEmpty()) perform(url, parse = ::parseOpenAi) else bearer(url, apiKey, ::parseOpenAi)
    }

    private fun bearer(url: String, apiKey: String, parse: (JSONObject) -> List<String>) =
        perform(url, mapOf("Authorization" to "Bearer $apiKey"), parse)

    private fun perform(
        url: String,
        headers: Map<String, String> = emptyMap(),
        parse: (JSONObject) -> List<String>,
    ): Result = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            Result(false, error = "HTTP $code")
        } else {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            Result(true, runCatching { parse(JSONObject(body)) }.getOrDefault(emptyList()))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to pull model list $url: ${e.message}")
        Result(false, error = e.message ?: e.javaClass.simpleName)
    }

    private fun parseOpenAi(json: JSONObject): List<String> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifEmpty { null } }.sorted()
    }

    private fun parseGemini(json: JSONObject): List<String> {
        val arr = json.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifEmpty { null } }
            .map { it.removePrefix("models/") }
            .sorted()
    }

    private fun parseOllama(json: JSONObject): List<String> {
        val arr = json.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifEmpty { null } }.sorted()
    }
}
