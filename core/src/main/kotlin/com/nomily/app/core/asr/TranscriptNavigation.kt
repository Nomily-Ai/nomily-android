package com.nomily.app.core.asr

/** `m:ss`, or `h:mm:ss` from one hour on — mirroring iOS `ClockLabel.mmss`. */
object ClockLabel {
    fun mmss(seconds: Double): String {
        val t = maxOf(0L, Math.round(seconds))
        return if (t >= 3600) {
            "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
        } else {
            "%d:%02d".format(t / 60, t % 60)
        }
    }
}

/**
 * The detail page's transcript list logic, mirroring iOS `ClipDetailView`: which segment is under the
 * playhead, the short window that follows playback, search / speaker filtering and in-line highlights.
 */
object TranscriptNavigation {

    /** Rows shown in the detail page when not searching. */
    const val INLINE_WINDOW = 4

    /** Lines shown at most while searching or filtering. */
    const val MAX_MATCHES = 40

    /** Index of the segment playing at [positionSeconds]; null before playback starts. */
    fun activeIndex(segments: List<AsrSegment>, positionSeconds: Double): Int? {
        if (segments.isEmpty() || positionSeconds <= 0) return null
        return segments.indexOfLast { it.start <= positionSeconds }.takeIf { it >= 0 }
    }

    /**
     * Indices of a [size]-row window that slides with the audio: one row of context above the active
     * segment, clamped to the ends.
     */
    fun inlineWindow(count: Int, active: Int?, size: Int = INLINE_WINDOW): IntRange {
        if (count <= size) return 0 until count
        val start = maxOf(0, minOf((active ?: 0) - 1, count - size))
        return start until start + size
    }

    /** Speakers in order of first appearance; blank labels are ignored. */
    fun distinctSpeakers(segments: List<AsrSegment>): List<String> =
        segments.mapNotNull { it.speaker?.takeIf(String::isNotEmpty) }.distinct()

    /** Indices of segments matching both the speaker filter (null = all) and the case-insensitive [query]. */
    fun matches(segments: List<AsrSegment>, query: String, speaker: String?): List<Int> =
        segments.indices.filter { i ->
            val s = segments[i]
            (speaker == null || s.speaker == speaker) && (query.isEmpty() || s.text.contains(query, ignoreCase = true))
        }

    /** Non-overlapping, case-insensitive occurrences of [needle] in [text]. */
    fun highlightRanges(text: String, needle: String?): List<IntRange> {
        if (needle.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            out += at until at + needle.length
            from = at + needle.length
        }
        return out
    }
}

/** Prompt construction for the detail page's AI Q&A, mirroring iOS `AIChatModel` / `ClipDetailView.aiContext`. */
object ClipQa {

    private const val SUMMARY_CAP = 6_000
    private const val TRANSCRIPT_CAP = 12_000

    data class Message(val isUser: Boolean, val text: String)

    /**
     * Everything the Q&A may read: the summary plus the timestamped transcript. Capped so a three-hour
     * recording still fits one request. Null when there is neither — the Q&A block is then hidden.
     */
    fun context(summary: String?, transcriptText: String?, segments: List<AsrSegment>): String? {
        val parts = mutableListOf<String>()
        if (!summary.isNullOrEmpty()) parts += "SUMMARY\n" + summary.take(SUMMARY_CAP)
        if (transcriptText != null) {
            val body = if (segments.isEmpty()) {
                transcriptText
            } else {
                segments.joinToString("\n") { s ->
                    "[${ClockLabel.mmss(s.start)}] " + (s.speaker?.let { "$it: " } ?: "") + s.text
                }
            }
            parts += "TRANSCRIPT\n" + body.take(TRANSCRIPT_CAP)
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /** The user turn: the material, then the whole conversation so far (the latest question last). */
    fun userContent(material: String, messages: List<Message>): String {
        val history = messages.joinToString("\n") { (if (it.isUser) "User: " else "Assistant: ") + it.text }
        return "RECORDING MATERIAL\n$material\n\nCONVERSATION\n$history"
    }
}
