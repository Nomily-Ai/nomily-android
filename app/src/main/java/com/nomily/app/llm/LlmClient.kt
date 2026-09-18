package com.nomily.app.llm

import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Invoke a user‑configured LLM.
 *
 * The six providers fall into **three request shapes**:
 *  - OpenAI‑compatible (OpenAI / OpenRouter / Ollama / custom endpoint): `/chat/completions` + `messages`
 *  - Anthropic: `/v1/messages`, system as a separate field, `x-api-key` + `anthropic-version`
 *  - Gemini: `:generateContent?key=`, `system_instruction` + `contents`
 *
 * Endpoint construction, field names, and response‑path extraction must be verified verbatim against each provider’s documentation — these are the most error‑prone areas, and divergences can cause parsing failures that are only discovered by comparing on a real device.
 */
object LlmClient {

    private const val MAX_TOKENS = 4096
    private const val TIMEOUT_MS = 120_000

    class LlmException(message: String) : Exception(message)

    /**
     * Result of a single completion.
     *
     * [truncated] = the model stopped because it hit the token limit (`finish_reason=length` /
     * `stop_reason=max_tokens` / `finishReason=MAX_TOKENS`). Previously we only extracted the text and discarded this signal, causing a half‑finished summary to be stored and counted against quota, with the user unaware of the truncation.
     */
    data class Completion(val text: String, val truncated: Boolean)

    /** Summaries, titles, and translations all use this: a system prompt + user content → generated text. */
    fun complete(systemPrompt: String, userContent: String, config: AppConfig): Completion {
        val p = config.llmProviders
        val primary = p.primary?.takeIf { it.isNotEmpty() }
            ?: throw LlmException("No active LLM provider configured.")

        return when (primary) {
            "openai" -> {
                val c = p.openai ?: throw LlmException("No active LLM provider configured.")
                openAiCompatible(
                    "https://api.openai.com/v1/chat/completions",
                    c.apiKey, model(c.model), systemPrompt, userContent,
                )
            }
            "claude" -> {
                val c = p.claude ?: throw LlmException("No active LLM provider configured.")
                anthropic(c.apiKey, model(c.model), systemPrompt, userContent)
            }
            "gemini" -> {
                val c = p.gemini ?: throw LlmException("No active LLM provider configured.")
                gemini(c.apiKey, model(c.model), systemPrompt, userContent)
            }
            "openRouter" -> {
                val c = p.openRouter ?: throw LlmException("No active LLM provider configured.")
                val key = c.apiKey?.takeIf { it.isNotEmpty() }
                    ?: throw LlmException("No API key configured for the active provider.")
                val ep = if (c.endpoint.isEmpty()) {
                    "https://openrouter.ai/api/v1/chat/completions"
                } else {
                    "${c.endpoint}/chat/completions"
                }
                openAiCompatible(ep, key, model(c.model), systemPrompt, userContent)
            }
            "ollama" -> {
                val c = p.ollama ?: throw LlmException("No active LLM provider configured.")
                openAiCompatible(
                    "${c.endpoint.trimEnd('/')}/v1/chat/completions",
                    c.apiKey ?: "", model(c.model), systemPrompt, userContent,
                )
            }
            "custom" -> {
                val c = p.custom ?: throw LlmException("No active LLM provider configured.")
                openAiCompatible(
                    "${c.endpoint.trimEnd('/')}/chat/completions",
                    c.apiKey ?: "", model(c.model), systemPrompt, userContent,
                )
            }
            else -> throw LlmException("No active LLM provider configured.")
        }
    }

    /**
     * Translation: **preserve the original formatting** (paragraphs, line breaks, markdown headings, lists, emphasis); return only the translated text without explanations, quotes, or language tags.
     *
     * `targetLanguage` should be the English name (e.g., `Spanish (Spain)`) rather than a BCP‑47 code: the model understands names more reliably than codes.
     */
    fun translate(text: String, targetLanguage: String, config: AppConfig): Completion {
        val system = "You are a professional translator. Translate the user's text into $targetLanguage. " +
            "Preserve the original formatting — paragraphs, line breaks, markdown headings, lists, " +
            "and bold/italic emphasis must all be retained. " +
            "Do not add commentary, do not wrap the output in quotes, do not prefix with a language label. " +
            "Reply with ONLY the translation."
        return complete(system, text, config)
    }

    /** An empty response **is an error** and should not be recorded as a successful completion. */
    private fun completion(text: String, truncated: Boolean): Completion {
        if (text.isBlank()) throw LlmException("The model returned an empty response.")
        return Completion(text, truncated)
    }

    private fun model(m: String?): String =
        m?.takeIf { it.isNotEmpty() } ?: throw LlmException("No model selected for the active provider.")

    // ── Three request shapes ────────────────────────────────────────────────

    private fun openAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
    ): Completion {
        val body = writeJson(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userContent),
                ),
                "max_tokens" to MAX_TOKENS,
            ),
        )
        val headers = buildMap {
            if (apiKey.isNotEmpty()) put("Authorization", "Bearer $apiKey")
        }
        val json = post(endpoint, headers, body)
        val choices = json["choices"] as? List<*> ?: throw decodeError("choices[0].message.content")
        val first = choices.firstOrNull() as? Map<*, *>
        val message = first?.get("message") as? Map<*, *>
        val text = message?.get("content") as? String ?: throw decodeError("choices[0].message.content")
        return completion(text, (first["finish_reason"] as? String) == "length")
    }

    private fun anthropic(apiKey: String, model: String, systemPrompt: String, userContent: String): Completion {
        val body = writeJson(
            mapOf(
                "model" to model,
                "system" to systemPrompt,
                "messages" to listOf(mapOf("role" to "user", "content" to userContent)),
                "max_tokens" to MAX_TOKENS,
            ),
        )
        val json = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body,
        )
        val content = json["content"] as? List<*> ?: throw decodeError("content[0].text")
        // **Merge all text blocks**: when the model first outputs a thinking block, reading only content[0] would report a normal answer as “missing text”.
                // 
        val text = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
            .takeIf { it.isNotEmpty() } ?: throw decodeError("content[].text")
        return completion(text, (json["stop_reason"] as? String) == "max_tokens")
    }

    private fun gemini(apiKey: String, model: String, systemPrompt: String, userContent: String): Completion {
        val body = writeJson(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to userContent)))),
                "generationConfig" to mapOf("maxOutputTokens" to MAX_TOKENS),
            ),
        )
        val json = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey",
            emptyMap(),
            body,
        )
        val candidates = json["candidates"] as? List<*> ?: throw decodeError("candidates[0].content.parts[0].text")
        val candidate = candidates.firstOrNull() as? Map<*, *>
        val content = candidate?.get("content") as? Map<*, *>
        val parts = content?.get("parts") as? List<*>
        // Like Anthropic: concatenate the text of all parts; don’t only consider the first segment.
        val text = parts?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() } ?: throw decodeError("candidates[0].content.parts[].text")
        return completion(text, (candidate["finishReason"] as? String) == "MAX_TOKENS")
    }

    // ── HTTP ────────────────────────────────────────────────────────

    private fun post(endpoint: String, headers: Map<String, String>, body: String): Map<*, *> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw LlmException("HTTP $code: ${msg.take(500)}")
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return JsonParser(text).parse() as? Map<*, *>
                ?: throw LlmException("Failed to parse response: not a JSON object")
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeError(path: String) = LlmException("Failed to parse response: Missing $path")
}
