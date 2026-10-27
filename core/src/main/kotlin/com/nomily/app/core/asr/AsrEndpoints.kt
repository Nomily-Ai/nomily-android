package com.nomily.app.core.asr

import com.nomily.app.core.json.JsonParser

/**
 * URL shaping and response parsing for the user-defined **OpenAI-compatible** transcription server
 * (`POST /v1/audio/transcriptions`: faster-whisper-server, whisper.cpp, speaches, Groq, SiliconFlow…), mirroring
 * iOS `CustomASR`. File transcription only — live transcription has no standard.
 */
object CustomAsrEndpoint {

    /** Default `model` form field: every compatible server accepts or ignores it. */
    const val DEFAULT_MODEL = "whisper-1"

    /** Trim, drop trailing slashes, default the scheme to `http://`; the path is kept (`…/openai/v1` stays one base). */
    fun normalizedBase(raw: String): String? {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) s else "http://$s"
    }

    /**
     * Every shape a user is likely to paste lands on the transcriptions path:
     * `http://h:8000` / `http://h:8000/v1` / `http://h:8000/v1/audio/transcriptions` / `http://h/openai/v1`
     * → `…/v1/audio/transcriptions`. `null` when there is no host.
     */
    fun transcriptionsUrl(raw: String): String? {
        val base = normalizedBase(raw) ?: return null
        val lower = base.lowercase()
        val url = when {
            lower.endsWith("/audio/transcriptions") -> base
            lower.endsWith("/v1") -> "$base/audio/transcriptions"
            else -> "$base/v1/audio/transcriptions"
        }
        return url.takeIf { hasHost(it) }
    }

    /** `GET …/v1/models` — the listing beside the transcriptions path; also the connection probe target. */
    fun modelsUrl(raw: String): String? =
        transcriptionsUrl(raw)?.let { it.dropLast("/audio/transcriptions".length) + "/models" }

    /**
     * Heuristic allowlist over model ids, tuned to what the transcription ecosystem actually serves.
     * Chat, embedding, image and TTS models are excluded even when they mention "audio"
     * (`gpt-4o-audio-preview` cannot serve `/audio/transcriptions`).
     */
    fun isTranscriptionCapable(id: String): Boolean {
        val lower = id.lowercase()
        if (BLOCKED.any { it in lower }) return false
        return ALLOWED.any { it in lower }
    }

    /**
     * Sorted ids for the model dropdown: the transcription-capable ones, or **all** of them when the filter would
     * empty the list — an exotic naming scheme still gets a dropdown rather than a dead end.
     */
    fun dropdownModels(ids: List<String>): List<String> {
        val all = ids.filter { it.isNotEmpty() }.distinct().sorted()
        val capable = all.filter(::isTranscriptionCapable)
        return capable.ifEmpty { all }
    }

    /** Ids from an OpenAI-style `{"data": [{"id": …}]}` listing; empty when the body has no such list. */
    fun parseModelIds(body: String): List<String> {
        val root = runCatching { JsonParser(body).parse() as? Map<*, *> }.getOrNull() ?: return emptyList()
        return (root["data"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: emptyList()
    }

    /** `zh-CN` → `zh`: the API takes ISO-639-1, and strict servers reject a full locale with 400. */
    fun shortCode(locale: String): String? =
        locale.trim().substringBefore('-').takeIf { it.isNotEmpty() }

    /**
     * `verbose_json` from the OpenAI-compatible family. Every field is optional: minimal servers return only
     * `{"text": "…"}`. Throws [AsrEmptyException] when there is neither text nor a segment.
     */
    fun parseTranscription(body: String, provider: String, fallbackLocale: String): AsrResult {
        val root = JsonParser(body).parse() as? Map<*, *> ?: throw AsrEmptyException()
        val segments = (root["segments"] as? List<*>)?.mapNotNull { item ->
            val s = item as? Map<*, *> ?: return@mapNotNull null
            val start = (s["start"] as? Number)?.toDouble() ?: 0.0
            val end = (s["end"] as? Number)?.toDouble() ?: start
            AsrSegment(text = s["text"] as? String ?: "", start = start, end = end, duration = maxOf(0.0, end - start))
        } ?: emptyList()
        val text = root["text"] as? String ?: segments.joinToString(" ") { it.text }
        if (segments.isEmpty() && text.isEmpty()) throw AsrEmptyException()
        val detected = (root["language"] as? String)?.takeIf { it.isNotEmpty() }
        return AsrResult(text, segments, provider, detected ?: fallbackLocale)
    }

    private val BLOCKED = listOf(
        "tts", "embedding", "dall-e", "image", "moderation", "moderator", "audio-preview", "realtime", "guard",
    )
    private val ALLOWED = listOf(
        "whisper", "transcribe", "transcription", "asr", "speech", "sensevoice", "paraformer", "funasr",
        "canary", "parakeet", "moonshine", "voxtral", "fireredasr", "audio",
    )
}

/**
 * The **remote secure** live service: the same `/v1/listen` + raw-Opus protocol as the LAN server, but over WSS
 * with API-key auth (iOS `RemoteLiveASR`). The field takes `wss://` only — plaintext `ws://` is the LAN shape.
 */
object RemoteLiveEndpoint {

    /**
     * | pasted | produced |
     * |---|---|
     * | `wss://h` / `wss://h/` | `wss://h/v1/listen` |
     * | `wss://h/v1/listen` | unchanged |
     * | `https://h` | `wss://h/v1/listen` (upgraded, never downgraded) |
     * | `h` | `wss://h/v1/listen` |
     * | `ws://h` | `null` |
     */
    fun listenUrl(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        s = when {
            lower.startsWith("ws://") || lower.startsWith("http://") -> return null
            lower.startsWith("wss://") -> s
            lower.startsWith("https://") -> "wss://" + s.drop("https://".length)
            else -> "wss://$s"
        }
        if (!s.lowercase().endsWith("/listen")) s += "/v1/listen"
        return s.takeIf { hasHost(it) }
    }

    /** `wss://h:8443/v1/listen` → `https://h:8443/health`: the diagnostic and settings-probe target. */
    fun healthUrl(raw: String): String? {
        val uri = listenUrl(raw)?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return null
        return runCatching { java.net.URI("https", null, uri.host, uri.port, "/health", null, null).toString() }.getOrNull()
    }
}

private fun hasHost(url: String): Boolean =
    runCatching { java.net.URI(url).host }.getOrNull().isNullOrEmpty().not()
