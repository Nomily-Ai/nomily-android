package com.nomily.app.core.asr

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson

/**
 * Transcription result shared by all providers — the shape written into `{name}.asr.json`.
 *
 * Placed in `:core` because **parsing and formatting are pure functions**:
 * we can use a fixed response body for unit tests without network access. The networking part resides in `:app`'s `AzureAsr`/`LocalAsr`.
 */
data class AsrSegment(
    val speaker: String? = null,
    val text: String,
    val start: Double,
    val end: Double,
    val duration: Double,
) {
    /** `[    1.2s -     3.4s] Speaker 1: …`. */
    fun formatted(): String {
        val stamp = "[${fixed1(start)}s - ${fixed1(end)}s]"
        return if (!speaker.isNullOrEmpty()) "$stamp Speaker $speaker: $text" else "$stamp $text"
    }
}

data class AsrResult(
    val text: String,
    val segments: List<AsrSegment>,
    val provider: String,
    val locale: String,
) {
    /** The body written to `{name}.txt`: if there are segments, one line per segment; otherwise the whole text as a single block. */
    fun formatTranscript(): String =
        if (segments.isEmpty()) text else segments.joinToString("\n") { it.formatted() }

    /** Payload written to `{name}.asr.json`. */
    fun toJsonString(): String = writeJson(
        mapOf(
            "text" to text,
            "provider" to provider,
            "locale" to locale,
            "segments" to segments.map { s ->
                buildMap<String, Any?> {
                    if (s.speaker != null) put("speaker", s.speaker)
                    put("text", s.text)
                    put("start", s.start)
                    put("end", s.end)
                    put("duration", s.duration)
                }
            },
        ),
    )
}

/**
 * An equivalent implementation of `%7.1f`, but **rounding according to C rules**.
 *
 * This is not nitpicking: `String.format("%.1f", 1.25)` yields `1.3` in Java (HALF_UP),
 * whereas C printf applies ties-to-even on the exact binary value, yielding `1.2`.
 * Both have been verified on this development machine (C printf prints `[    1.2s -     3.5s]`, Java prints `1.3`).
 * Transcription txt is meant for human reading and may be compared as the canonical source; **the same audio segment should be byte‑identical on both sides**,
 * so we use `BigDecimal(exact) + HALF_EVEN` to reproduce C's behavior rather than accommodate Java's default.
 */
private fun fixed1(v: Double): String =
    java.math.BigDecimal(v)
        .setScale(1, java.math.RoundingMode.HALF_EVEN)
        .toPlainString()
        .padStart(7)

class AsrEmptyException : Exception("transcript is empty")

/**
 * Azure Fast Transcription response body → [AsrResult].
 *
 * Parsing rules:
 *  - Prefer `phrases[]` (includes offset/duration/speaker)
 *  - If `phrases` is empty, fall back to `combinedPhrases[]`, using the whole `durationMilliseconds` as the duration
 *  - If both are empty → throw [AsrEmptyException] (**not** return an empty result: an empty result would be written to the file as "transcription succeeded but no speech")
 *  - Concatenate all segments with spaces.
 */
fun parseAzureResponse(body: String, locale: String): AsrResult {
    val root = JsonParser(body).parse() as? Map<*, *> ?: throw AsrEmptyException()
    val segments = mutableListOf<AsrSegment>()

    (root["phrases"] as? List<*>)?.forEach { item ->
        val p = item as? Map<*, *> ?: return@forEach
        val offsetMs = num(p["offsetMilliseconds"])
        val durMs = num(p["durationMilliseconds"])
        segments += AsrSegment(
            speaker = p["speaker"]?.let { num(it).toInt().toString() },
            text = p["text"] as? String ?: "",
            start = offsetMs / 1000.0,
            end = (offsetMs + durMs) / 1000.0,
            duration = durMs / 1000.0,
        )
    }

    if (segments.isEmpty()) {
        val totalDur = num(root["durationMilliseconds"]) / 1000.0
        (root["combinedPhrases"] as? List<*>)?.forEach { item ->
            val cp = item as? Map<*, *> ?: return@forEach
            val text = cp["text"] as? String ?: return@forEach
            if (text.isEmpty()) return@forEach
            segments += AsrSegment(
                speaker = cp["speaker"]?.let { num(it).toInt().toString() },
                text = text,
                start = 0.0,
                end = totalDur,
                duration = totalDur,
            )
        }
    }

    if (segments.isEmpty()) throw AsrEmptyException()
    return AsrResult(
        text = segments.joinToString(" ") { it.text },
        segments = segments,
        provider = "azure",
        locale = locale,
    )
}

/** JsonParser numbers may be Long or Double — normalize to Double before calculation. */
private fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

/**
 * Local ASR server response body → [AsrResult].
 *
 * The server already returns data in the shape of `AsrResult`,
 * so here we simply deserialize the JSON into an object, using default values for missing fields.
 */
/**
 * Read back the `{name}.asr.json` that we wrote.
 *
 * The only difference from [parseLocalResponse] is the **tolerance policy**: this is a file we wrote ourselves;
 * if parsing fails, return null to let the caller fall back to `.txt` instead of throwing an exception.
 */
fun parseAsrArtefact(body: String): AsrResult? {
    val root = runCatching { JsonParser(body).parse() }.getOrNull() as? Map<*, *> ?: return null
    val segments = (root["segments"] as? List<*>).orEmpty().mapNotNull { item ->
        val s = item as? Map<*, *> ?: return@mapNotNull null
        AsrSegment(
            speaker = (s["speaker"] as? String)?.takeIf { it.isNotEmpty() },
            text = s["text"] as? String ?: "",
            start = num(s["start"]),
            end = num(s["end"]),
            duration = num(s["duration"]),
        )
    }
    val text = root["text"] as? String ?: ""
    if (text.isEmpty() && segments.isEmpty()) return null
    return AsrResult(
        text = text,
        segments = segments,
        provider = root["provider"] as? String ?: "",
        locale = root["locale"] as? String ?: "",
    )
}

fun parseLocalResponse(body: String): AsrResult {
    val root = JsonParser(body).parse() as? Map<*, *> ?: throw AsrEmptyException()
    val segments = mutableListOf<AsrSegment>()
    (root["segments"] as? List<*>)?.forEach { item ->
        val s = item as? Map<*, *> ?: return@forEach
        segments += AsrSegment(
            speaker = (s["speaker"] as? String)?.takeIf { it.isNotEmpty() },
            text = s["text"] as? String ?: "",
            start = num(s["start"]),
            end = num(s["end"]),
            duration = num(s["duration"]),
        )
    }
    val text = (root["text"] as? String ?: "").ifEmpty { segments.joinToString(" ") { it.text } }
    if (text.isEmpty() && segments.isEmpty()) throw AsrEmptyException()
    return AsrResult(
        text = text,
        segments = segments,
        provider = root["provider"] as? String ?: "local",
        locale = root["locale"] as? String ?: "auto",
    )
}
