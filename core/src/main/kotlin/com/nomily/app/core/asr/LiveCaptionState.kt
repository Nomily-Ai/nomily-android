package com.nomily.app.core.asr

/** One finalized caption line; [at] = seconds into the recording when it was finalized. */
data class CaptionLine(val text: String, val at: Double)

/**
 * The caption transcript of a phone recording, independent of the engine producing it: when Azure drops and the
 * on-device recogniser takes over, the lines already on screen simply stay (iOS seeds the new engine with them).
 */
data class LiveCaptionState(
    val lines: List<CaptionLine> = emptyList(),
    val partial: String = "",
) {
    val hasText: Boolean get() = lines.isNotEmpty() || partial.isNotEmpty()

    fun withPartial(text: String) = copy(partial = text)

    /** A finalized sentence; blank text only clears the partial, and an immediate repeat of the last line is dropped. */
    fun withFinal(text: String, at: Double): LiveCaptionState {
        val t = text.trim()
        if (t.isEmpty() || lines.lastOrNull()?.text == t) return copy(partial = "")
        return LiveCaptionState(lines + CaptionLine(t, at), "")
    }

    /** The in-flight hypothesis becomes a line (used when the recording stops). */
    fun committed(at: Double): LiveCaptionState = withFinal(partial, at)

    /** One line per caption, plus the trailing partial. */
    fun joinedText(): String =
        (lines.map { it.text } + listOfNotNull(partial.takeIf { it.isNotEmpty() })).joinToString("\n")

    /** The `.asr.json` payload: segments carry the finalize time only (start == end), like iOS. */
    fun toAsrResult(provider: String, locale: String): AsrResult =
        AsrResult(
            joinedText(),
            lines.map { AsrSegment(null, it.text, it.at, it.at, 0.0) },
            provider,
            locale,
        )
}
