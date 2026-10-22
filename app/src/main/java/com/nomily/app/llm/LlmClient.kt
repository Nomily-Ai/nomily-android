package com.nomily.app.llm

import android.content.Context
import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import com.nomily.app.core.llm.LlmChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Invoke a user‑configured LLM.
 *
 * The six providers fall into **three request shapes**:
 *  - OpenAI‑compatible (OpenAI / OpenRouter / Ollama / custom endpoint): `/chat/completions` + `messages`, streamed
 *  - Anthropic: `/v1/messages`, system as a separate field, `x-api-key` + `anthropic-version`
 *  - Gemini: `:generateContent?key=`, `system_instruction` + `contents`
 *
 * Endpoint construction, field names, and response‑path extraction must be verified verbatim against each provider’s documentation — these are the most error‑prone areas, and divergences can cause parsing failures that are only discovered by comparing on a real device.
 *
 * Summaries, titles and manual translation go through [completeResilient]: primary → local providers → other
 * cloud providers, chunking long transcripts. Live translation uses the primary only ([translate] with
 * `allowFallback = false`), because falling back to a slow local model every few seconds only builds a backlog.
 */
object LlmClient {

    private const val TAG = "LlmClient"

    /** Cloud: gap between bytes before a request counts as stuck. */
    private const val CLOUD_TIMEOUT_MS = 120_000

    /**
     * Local models stream, so this is "how long without a byte" rather than a total budget: prefill of a long prompt
     * on a CPU-only box takes minutes before the first token, and every token after resets the timer.
     */
    private const val LOCAL_TIMEOUT_MS = 600_000

    class LlmException(message: String, val streamPathFailed: Boolean = false) : Exception(message)

    /**
     * Result of a single completion.
     *
     * [truncated] = the model stopped because it hit the token limit (`finish_reason=length` /
     * `stop_reason=max_tokens` / `finishReason=MAX_TOKENS`); a half‑finished summary must not look complete.
     */
    data class Completion(val text: String, val truncated: Boolean)

    /**
     * Result of a completion with fallback. [providerKey] is the provider that actually did the work — it may be a
     * fallback — and [warnings] holds each provider that failed before it, already as "Label: reason".
     */
    data class Outcome(
        val completion: Completion,
        val providerKey: String,
        val warnings: List<String>,
        val chunked: Boolean,
        val chunkCount: Int,
    )

    fun gapText(ctx: Context, gap: LlmChain.Gap): String = when (gap) {
        LlmChain.Gap.NoProvider -> ctx.getString(R.string.llm_error_no_provider)
        is LlmChain.Gap.NoApiKey -> ctx.getString(R.string.llm_error_no_api_key, gap.label)
        is LlmChain.Gap.NoModel -> ctx.getString(R.string.llm_error_no_model, gap.label)
        is LlmChain.Gap.NoEndpoint -> ctx.getString(R.string.llm_error_no_endpoint, gap.label)
    }

    /** Single request to the **primary** provider. */
    suspend fun complete(ctx: Context, systemPrompt: String, userContent: String, config: AppConfig): Completion {
        val primary = config.llmProviders.primary?.takeIf { it.isNotEmpty() }
            ?: throw LlmException(ctx.getString(R.string.llm_error_no_provider))
        return complete(ctx, primary, systemPrompt, userContent, config)
    }

    /**
     * Completion with fallback, for actions where "the user tapped and must get a result".
     *
     * @param preferred the provider that did the work last time; it goes first so a title right after a summary
     *   doesn't wait for the primary to fail again.
     * @param allowSectionReuse reuse chunk sections finished by an earlier attempt (resume a long summary); a deliberate
     *   "generate again" passes false so it doesn't hand back the old text.
     * @param onChunk `(completed, total)` while a long transcript is chunked; the merge step counts as one more unit.
     */
    suspend fun completeResilient(
        ctx: Context,
        systemPrompt: String,
        userContent: String,
        config: AppConfig,
        allowChunking: Boolean = true,
        preferred: String? = null,
        allowSectionReuse: Boolean = true,
        onChunk: ((Int, Int) -> Unit)? = null,
    ): Outcome {
        val chain = LlmChain.preferring(LlmChain.candidateChain(config), preferred)
        if (chain.isEmpty()) {
            throw LlmException(ctx.getString(R.string.llm_error_all_failed, ctx.getString(R.string.llm_error_no_provider)))
        }
        if (!allowChunking || userContent.length <= LlmChain.MAX_CHARS_PER_REQUEST) {
            return runChain(ctx, chain, systemPrompt, userContent, config, chunkCount = 1)
        }
        val chunks = LlmChain.split(userContent)
        if (chunks.size == 1) return runChain(ctx, chain, systemPrompt, userContent, config, chunkCount = 1)

        Log.i(TAG, "Summarising a long transcript in ${chunks.size} chunks")
        onChunk?.invoke(0, chunks.size + 1)
        var warnings = emptyList<String>()
        val sections = chunks.mapIndexed { i, chunk ->
            val sectionPrompt = LlmChain.sectionSystemPrompt(systemPrompt, i, chunks.size)
            val key = LlmChain.sectionCacheKey(sectionPrompt, chunk)
            val hit = if (allowSectionReuse) cachedSection(ctx, key) else null
            if (hit != null) {
                Log.i(TAG, "chunk ${i + 1}/${chunks.size} reused from cache")
                onChunk?.invoke(i + 1, chunks.size + 1)
                return@mapIndexed hit
            }
            val out = runChain(ctx, chain, sectionPrompt, chunk, config, chunkCount = chunks.size, seeded = warnings)
            warnings = out.warnings
            storeSection(ctx, key, out.completion.text)
            onChunk?.invoke(i + 1, chunks.size + 1)
            out.completion.text
        }
        val merged = runChain(
            ctx, chain, systemPrompt, LlmChain.mergePrompt(sections), config,
            chunkCount = chunks.size, seeded = warnings,
        )
        onChunk?.invoke(chunks.size + 1, chunks.size + 1)
        return merged
    }

    // ── Per-chunk section cache (resume a long summary) ────────────────

    private const val SECTION_PRUNE_AFTER_FILES = 40
    private const val SECTION_MAX_AGE_MS = 2 * 24 * 3600 * 1000L

    private fun sectionDir(ctx: Context) = java.io.File(ctx.cacheDir, "llm-sections").apply { mkdirs() }

    /** A hit is touched so the age-based prune keeps what is in use. The system may purge the cache: recomputing is only slow. */
    private fun cachedSection(ctx: Context, key: String): String? = runCatching {
        val f = java.io.File(sectionDir(ctx), "$key.txt")
        f.takeIf { it.isFile }?.readText()?.takeIf { it.isNotEmpty() }?.also { f.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    private fun storeSection(ctx: Context, key: String, text: String) {
        if (text.isEmpty()) return
        runCatching {
            val dir = sectionDir(ctx)
            java.io.File(dir, "$key.txt").writeText(text)
            val files = dir.listFiles().orEmpty()
            if (files.size > SECTION_PRUNE_AFTER_FILES) {
                val cutoff = System.currentTimeMillis() - SECTION_MAX_AGE_MS
                files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
            }
        }
    }

    /**
     * Translation: **preserve the original formatting** (paragraphs, line breaks, markdown headings, lists, emphasis); return only the translated text without explanations, quotes, or language tags.
     *
     * `targetLanguage` should be the English name (e.g., `Spanish (Spain)`) rather than a BCP‑47 code: the model understands names more reliably than codes.
     * Translation is never chunked: splitting breaks pronoun references and terminology across the whole text.
     */
    suspend fun translate(
        ctx: Context,
        text: String,
        targetLanguage: String,
        config: AppConfig,
        allowFallback: Boolean = true,
    ): Completion {
        val system = "You are a professional translator. Translate the user's text into $targetLanguage. " +
            "Preserve the original formatting — paragraphs, line breaks, markdown headings, lists, " +
            "and bold/italic emphasis must all be retained. " +
            "Do not add commentary, do not wrap the output in quotes, do not prefix with a language label. " +
            "Reply with ONLY the translation."
        return if (allowFallback) {
            completeResilient(ctx, system, text, config, allowChunking = false).completion
        } else {
            complete(ctx, system, text, config)
        }
    }

    /** Notes shown with a result: a fallback provider stood in, or the transcript was chunked. */
    fun resultNotes(ctx: Context, outcome: Outcome, config: AppConfig): List<String> = buildList {
        val primary = config.llmProviders.primary.orEmpty()
        if (primary.isNotEmpty() && primary != outcome.providerKey) {
            add(ctx.getString(R.string.llm_fell_back, LlmChain.providerLabel(primary), LlmChain.providerLabel(outcome.providerKey)))
        }
        if (outcome.chunked) add(ctx.getString(R.string.llm_chunked_note, outcome.chunkCount))
    }

    // ── Chain ───────────────────────────────────────────────────────────

    private suspend fun runChain(
        ctx: Context,
        chain: List<String>,
        systemPrompt: String,
        userContent: String,
        config: AppConfig,
        chunkCount: Int,
        seeded: List<String> = emptyList(),
    ): Outcome {
        val warnings = seeded.toMutableList()
        for (key in chain) {
            // An incomplete provider sends nothing, but what it is missing is the most useful line in the final error.
            LlmChain.configGap(key, config)?.let {
                warnings += "${LlmChain.providerLabel(key)}: ${gapText(ctx, it)}"
                continue
            }
            try {
                val completion = complete(ctx, key, systemPrompt, userContent, config)
                if (warnings.isNotEmpty()) Log.i(TAG, "LLM fell back to $key after: ${warnings.joinToString(" | ")}")
                return Outcome(completion, key, warnings, chunked = chunkCount > 1, chunkCount = chunkCount)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user: don't go on spending quota on the next provider.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LLM provider $key failed: ${e.message}")
                warnings += "${LlmChain.providerLabel(key)}: ${e.message}"
            }
        }
        throw LlmException(ctx.getString(R.string.llm_error_all_failed, warnings.joinToString("\n")))
    }

    // ── Single provider ─────────────────────────────────────────────────

    private suspend fun complete(
        ctx: Context,
        key: String,
        systemPrompt: String,
        userContent: String,
        config: AppConfig,
    ): Completion = withContext(Dispatchers.IO) {
        LlmChain.configGap(key, config)?.let { throw LlmException(gapText(ctx, it)) }
        val p = config.llmProviders
        val local = LlmChain.isLocal(key)
        val maxTokens = if (local) LlmChain.LOCAL_MAX_OUTPUT_TOKENS else LlmChain.MAX_OUTPUT_TOKENS
        val timeout = if (local) LOCAL_TIMEOUT_MS else CLOUD_TIMEOUT_MS
        when (key) {
            "openai" -> {
                val c = p.openai!!
                openAiCompatible(
                    ctx, "https://api.openai.com/v1/chat/completions",
                    c.apiKey, c.model!!, systemPrompt, userContent, maxTokens, timeout,
                )
            }
            "claude" -> p.claude!!.let { anthropic(ctx, it.apiKey, it.model!!, systemPrompt, userContent) }
            "gemini" -> p.gemini!!.let { gemini(ctx, it.apiKey, it.model!!, systemPrompt, userContent) }
            "openRouter" -> {
                val c = p.openRouter!!
                val ep = if (c.endpoint.isEmpty()) "https://openrouter.ai/api/v1/chat/completions" else "${c.endpoint}/chat/completions"
                openAiCompatible(ctx, ep, c.apiKey!!, c.model!!, systemPrompt, userContent, maxTokens, timeout)
            }
            "ollama" -> {
                val c = p.ollama!!
                openAiCompatible(
                    ctx, "${c.endpoint.trimEnd('/')}/v1/chat/completions",
                    c.apiKey.orEmpty(), c.model!!, systemPrompt, userContent, maxTokens, timeout,
                )
            }
            "custom" -> {
                val c = p.custom!!
                openAiCompatible(
                    ctx, "${c.endpoint.trimEnd('/')}/chat/completions",
                    c.apiKey.orEmpty(), c.model!!, systemPrompt, userContent, maxTokens, timeout,
                )
            }
            else -> throw LlmException(ctx.getString(R.string.llm_error_no_provider))
        }
    }

    /** An empty response **is an error** and should not be recorded as a successful completion. */
    private fun completion(ctx: Context, text: String, truncated: Boolean, streamed: Boolean = false): Completion {
        if (text.isBlank()) throw LlmException(ctx.getString(R.string.llm_error_empty), streamPathFailed = streamed)
        return Completion(text, truncated)
    }

    // ── OpenAI-compatible: streamed, with a one-shot fallback ───────────

    /**
     * Streams (`stream: true` + SSE) so a slow local model is judged by "no byte for a while", not by a total
     * budget it can never meet. If the stream itself yields nothing usable (an old proxy that ignores `stream` and
     * returns one JSON body), retry once without streaming. HTTP errors are not retried — that would only burn quota.
     */
    private suspend fun openAiCompatible(
        ctx: Context,
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        timeout: Int,
    ): Completion = try {
        openAiCompatibleRequest(ctx, endpoint, apiKey, model, systemPrompt, userContent, maxTokens, timeout, stream = true)
    } catch (e: LlmException) {
        if (!e.streamPathFailed) throw e
        Log.i(TAG, "Streaming failed for $endpoint; retrying without stream: ${e.message}")
        openAiCompatibleRequest(ctx, endpoint, apiKey, model, systemPrompt, userContent, maxTokens, timeout, stream = false)
    }

    private suspend fun openAiCompatibleRequest(
        ctx: Context,
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        maxTokens: Int,
        timeout: Int,
        stream: Boolean,
    ): Completion {
        val body = writeJson(
            buildMap {
                put("model", model)
                put(
                    "messages",
                    listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userContent),
                    ),
                )
                put("max_tokens", maxTokens)
                if (stream) put("stream", true)
            },
        )
        val headers = buildMap {
            if (apiKey.isNotEmpty()) put("Authorization", "Bearer $apiKey")
            if (stream) put("Accept", "text/event-stream")
        }
        return openConnection(ctx, endpoint, headers, body, timeout) { reader ->
            if (stream) readStream(ctx, reader) else {
                val json = parseObject(ctx, reader.readText())
                val first = (json["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
                val text = (first?.get("message") as? Map<*, *>)?.get("content") as? String
                    ?: throw LlmException(ctx.getString(R.string.llm_error_decoding, ctx.getString(R.string.llm_error_missing_choices)))
                completion(ctx, text, first["finish_reason"] == "length")
            }
        }
    }

    private suspend fun readStream(ctx: Context, reader: BufferedReader): Completion {
        val text = StringBuilder()
        var finish: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val obj = runCatching { JsonParser(payload).parse() as? Map<*, *> }.getOrNull() ?: continue
            val first = (obj["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
            // Most servers send `delta.content`; some Ollama versions put the text in `message.content`.
            val piece = (first["delta"] as? Map<*, *>)?.get("content") as? String
                ?: (first["message"] as? Map<*, *>)?.get("content") as? String
            if (!piece.isNullOrEmpty()) text.append(piece)
            (first["finish_reason"] as? String)?.let { finish = it }
        }
        return completion(ctx, text.toString(), finish == "length" || finish == "max_tokens", streamed = true)
    }

    private suspend fun anthropic(ctx: Context, apiKey: String, model: String, systemPrompt: String, userContent: String): Completion {
        val body = writeJson(
            mapOf(
                "model" to model,
                "system" to systemPrompt,
                "messages" to listOf(mapOf("role" to "user", "content" to userContent)),
                "max_tokens" to LlmChain.MAX_OUTPUT_TOKENS,
            ),
        )
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        return openConnection(ctx, "https://api.anthropic.com/v1/messages", headers, body, CLOUD_TIMEOUT_MS) { reader ->
            val json = parseObject(ctx, reader.readText())
            val content = json["content"] as? List<*>
                ?: throw LlmException(ctx.getString(R.string.llm_error_decoding, ctx.getString(R.string.llm_error_missing_content)))
            // **Merge all text blocks**: when the model first outputs a thinking block, reading only content[0] would report a normal answer as “missing text”.
            val text = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
            completion(ctx, text, json["stop_reason"] == "max_tokens")
        }
    }

    private suspend fun gemini(ctx: Context, apiKey: String, model: String, systemPrompt: String, userContent: String): Completion {
        val body = writeJson(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to userContent)))),
                "generationConfig" to mapOf("maxOutputTokens" to LlmChain.MAX_OUTPUT_TOKENS),
            ),
        )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        return openConnection(ctx, url, emptyMap(), body, CLOUD_TIMEOUT_MS) { reader ->
            val json = parseObject(ctx, reader.readText())
            val candidate = (json["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val parts = (candidate?.get("content") as? Map<*, *>)?.get("parts") as? List<*>
                ?: throw LlmException(ctx.getString(R.string.llm_error_decoding, ctx.getString(R.string.llm_error_missing_candidates)))
            // Like Anthropic: concatenate the text of all parts; don’t only consider the first segment.
            val text = parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
            completion(ctx, text, candidate["finishReason"] == "MAX_TOKENS")
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────

    private suspend fun <T> openConnection(
        ctx: Context,
        endpoint: String,
        headers: Map<String, String>,
        body: String,
        timeout: Int,
        read: suspend (BufferedReader) -> T,
    ): T {
        val url = try { URL(endpoint) } catch (_: MalformedURLException) {
            throw LlmException(ctx.getString(R.string.llm_error_bad_url))
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CLOUD_TIMEOUT_MS
            readTimeout = timeout
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw LlmException(ctx.getString(R.string.llm_error_http, code, msg.take(500)))
            }
            return conn.inputStream.bufferedReader().use { read(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseObject(ctx: Context, text: String): Map<*, *> =
        runCatching { JsonParser(text).parse() as? Map<*, *> }.getOrNull()
            ?: throw LlmException(ctx.getString(R.string.llm_error_decoding, text.take(200)), streamPathFailed = true)
}
