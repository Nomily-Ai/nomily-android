package com.nomily.app.core.llm

import com.nomily.app.core.config.AppConfig

/**
 * Provider-chain rules shared by every LLM call: which providers to try, in what order, and what a
 * provider is missing before a request can be sent. Pure logic — the HTTP side lives in the app's `LlmClient`.
 */
object LlmChain {

    /** Why a provider can't send a request yet. The app turns it into a localized sentence. */
    sealed interface Gap {
        data object NoProvider : Gap
        data class NoApiKey(val label: String) : Gap
        data class NoModel(val label: String) : Gap
        data class NoEndpoint(val label: String) : Gap
    }

    /** Output cap for cloud providers. */
    const val MAX_OUTPUT_TOKENS = 4096

    /**
     * Output cap for local models (Ollama / self-hosted). A CPU-only 3B model writes about 5 token/s, so 4096
     * tokens take 13 minutes; 2048 is still over a thousand Chinese characters per chunk.
     */
    const val LOCAL_MAX_OUTPUT_TOKENS = 2048

    /**
     * Input cap for one request, in characters. Ollama's OpenAI-compatible endpoint can't set `num_ctx`, and going
     * over the server's context silently drops the start of the prompt — so long transcripts are chunked instead.
     * 8000 characters ≈ 5000 tokens of Chinese, plus 4096 output, fits a 16k context.
     */
    const val MAX_CHARS_PER_REQUEST = 8000

    /** Local providers come first in the fallback order: no quota, nothing leaves the user's network. */
    private val FALLBACK_ORDER = listOf("ollama", "custom", "openRouter", "openai", "claude", "gemini")

    fun providerLabel(key: String): String = when (key) {
        "openai" -> "OpenAI"
        "claude" -> "Claude"
        "gemini" -> "Gemini"
        "openRouter" -> "Open Router"
        "ollama" -> "Ollama"
        "custom" -> "Custom"
        else -> key
    }

    fun isLocal(key: String): Boolean = key == "ollama" || key == "custom"

    /**
     * Providers to try, in order. The primary always leads even when incomplete, so "no API key" is reported as a
     * concrete reason instead of being skipped silently; then every configured provider in [FALLBACK_ORDER].
     */
    fun candidateChain(config: AppConfig): List<String> {
        val p = config.llmProviders
        val chain = mutableListOf<String>()
        p.primary?.takeIf { it.isNotEmpty() }?.let { chain += it }
        val configured = p.configuredProviders.map { it.first }.toSet()
        FALLBACK_ORDER.filter { it in configured && it !in chain }.forEach { chain += it }
        return chain
    }

    /** What [key] is missing before it can send a request; null when it is complete. */
    fun configGap(key: String, config: AppConfig): Gap? {
        val p = config.llmProviders
        val label = providerLabel(key)
        fun keyed(c: AppConfig.LlmProviders.Keyed?): Gap? = when {
            c == null -> Gap.NoProvider
            c.apiKey.isEmpty() -> Gap.NoApiKey(label)
            c.model.isNullOrEmpty() -> Gap.NoModel(label)
            else -> null
        }
        fun endpoint(c: AppConfig.LlmProviders.Endpoint?): Gap? = when {
            c == null -> Gap.NoProvider
            c.endpoint.isEmpty() -> Gap.NoEndpoint(label)
            c.model.isNullOrEmpty() -> Gap.NoModel(label)
            else -> null
        }
        return when (key) {
            "openai" -> keyed(p.openai)
            "claude" -> keyed(p.claude)
            "gemini" -> keyed(p.gemini)
            "openRouter" -> p.openRouter.let { c ->
                when {
                    c == null -> Gap.NoProvider
                    c.apiKey.isNullOrEmpty() -> Gap.NoApiKey(label)
                    c.model.isNullOrEmpty() -> Gap.NoModel(label)
                    else -> null
                }
            }
            "ollama" -> endpoint(p.ollama)
            "custom" -> endpoint(p.custom)
            else -> Gap.NoProvider
        }
    }

    /** The primary provider has everything one request needs (no network check). */
    fun isConfigured(config: AppConfig): Boolean {
        val primary = config.llmProviders.primary?.takeIf { it.isNotEmpty() } ?: return false
        return configGap(primary, config) == null
    }

    /** The primary is incomplete: its gap, plus the provider key that would stand in (null when none would). */
    fun primaryGapWithFallback(config: AppConfig): Pair<Gap, String?>? {
        val primary = config.llmProviders.primary?.takeIf { it.isNotEmpty() } ?: return null
        val gap = configGap(primary, config) ?: return null
        return gap to candidateChain(config).drop(1).firstOrNull()
    }

    /** Provider key to promote to primary when the primary can't be used; null when it's fine or nothing could replace it. */
    fun suggestedPrimary(config: AppConfig): String? = primaryGapWithFallback(config)?.second

    /** [chain] with [preferred] moved to the front — the provider that just did the work goes first next time. */
    fun preferring(chain: List<String>, preferred: String?): List<String> =
        if (preferred != null && preferred in chain) listOf(preferred) + (chain - preferred) else chain

    /**
     * Split [text] into chunks of at most [max] characters, breaking at blank lines, then newlines, then sentence
     * ends, so no chunk starts mid-sentence. Text under the cap comes back as one chunk.
     */
    fun split(text: String, max: Int = MAX_CHARS_PER_REQUEST): List<String> {
        if (text.length <= max) return listOf(text)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) chunks += current.toString().trim()
            current = StringBuilder()
        }
        fun add(piece: String) {
            if (current.length + piece.length > max) flush()
            if (piece.length > max) {
                piece.chunked(max).forEach { chunks += it }
            } else {
                current.append(piece)
            }
        }
        for (paragraph in text.split("\n\n")) {
            val block = paragraph + "\n\n"
            if (block.length <= max) { add(block); continue }
            for (line in paragraph.split("\n")) {
                val l = line + "\n"
                if (l.length <= max) { add(l); continue }
                splitSentences(line).forEach(::add)
            }
        }
        flush()
        return chunks
    }

    private fun splitSentences(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in line) {
            sb.append(ch)
            if (ch in "。！？.!?；;") { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Instruction for one chunk, appended after the template prompt so its constraints still apply. */
    fun sectionSystemPrompt(original: String, index: Int, total: Int): String =
        "$original\n\n———\n" +
            "The user's content below is ONLY part ${index + 1} of $total of one longer recording. " +
            "Work on this part alone: produce just the findings for this part, in the same format the instructions above ask for. " +
            "Do NOT add an introduction, do NOT add a closing summary, and do NOT refer to \"this part\" — " +
            "your output will be merged with the other parts afterwards."

    /**
     * Cache key of one finished chunk section: SHA-256 of (instruction + NUL + chunk). Both halves matter — the same
     * transcript under a different template must not reuse the old sections.
     */
    fun sectionCacheKey(prompt: String, chunk: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((prompt + "\u0000" + chunk).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Input for the merge step; it says these are per-section findings so the model doesn't summarise a summary. */
    fun mergePrompt(sections: List<String>): String =
        "Below are the per-section findings from one long recording, in order. " +
            "Merge them into ONE final result that satisfies the original instructions exactly: " +
            "de-duplicate, group related items together, keep the requested structure, and " +
            "remove any trace of how it was split up (no \"part 1 / part 2\" headings).\n\n" +
            sections.mapIndexed { i, s -> "--- section ${i + 1} ---\n$s" }.joinToString("\n\n")
}
