package com.nomily.app.core.asr

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.File
import java.util.Locale

/** One chapter of a recording's outline: [start] in seconds from the start of the audio. */
data class ClipChapter(val start: Double, val title: String)

/**
 * The chapter outline of one recording, stored as `{base}.chapters.json` (`[{start, title}]`, the shape iOS writes)
 * beside `{base}.asr.json`. A separate sidecar because its timestamps belong to one ASR run: a re-transcription
 * deletes it instead of serving chapters that point at the wrong words.
 */
object ClipChapters {
    const val SUFFIX = ".chapters.json"

    /** Character budget of the timeline handed to the model (about 3.5k input tokens). */
    const val TIMELINE_BUDGET = 14_000
    private const val LINE_MAX = 160
    private const val MIN_LINES = 24

    const val PROMPT =
        "You build a navigation outline (a table of contents) for a recording, from a timestamped transcript.\n\n" +
            "Return ONLY a JSON array — no prose, no code fence:\n" +
            "[{\"t\":\"00:00\",\"title\":\"...\"}]\n\n" +
            "Rules:\n" +
            "- 5 to 12 chapters, in order. Each one starts where its topic actually changes in the transcript.\n" +
            "- \"t\" is the timestamp of the FIRST line belonging to that chapter, copied from the input's\n" +
            "  [mm:ss] markers (use h:mm:ss once past the first hour).\n" +
            "- \"title\" is a 4-12 word noun phrase naming what that stretch is about. No numbering,\n" +
            "  no \"Chapter\", no quotation marks, no trailing punctuation.\n" +
            "- Cover the whole recording: the first chapter starts at the beginning of the first line.\n" +
            "- Write the titles in the SAME LANGUAGE as the transcript."

    private val WRAPPER_KEYS = listOf("chapters", "outline", "items", "data", "result")
    private val TIME_KEYS = listOf("t", "start", "time", "timestamp", "from", "begin")
    private val TITLE_KEYS = listOf("title", "chapter", "name", "heading", "text")

    /**
     * Chapters from the model's reply, sorted, one per start second (the first one wins). Lenient on purpose:
     * code fences, a sentence before the JSON, an object wrapping the array, and numeric or clock-string times
     * all occur across providers.
     */
    fun parse(reply: String): List<ClipChapter> {
        val chapters = jsonArray(reply).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val title = TITLE_KEYS.firstNotNullOfOrNull { (m[it] as? String)?.trim()?.takeIf(String::isNotEmpty) }
                ?: return@mapNotNull null
            val start = TIME_KEYS.firstNotNullOfOrNull { k ->
                when (val v = m[k]) {
                    is Number -> v.toDouble()
                    is String -> seconds(v)
                    else -> null
                }
            } ?: return@mapNotNull null
            ClipChapter(start, title)
        }
        return chapters.sortedBy { it.start }.distinctBy { it.start }
    }

    /** `"00:01:15"` → 75, `"1:15"` → 75, `"[00:12]"` → 12, `"75.5s"` → 75.5; null when it is not a time. */
    fun seconds(text: String): Double? {
        val s = text.trim().trim('[', ']', '(', ')').removeSuffix("s")
        if (':' in s) {
            val parts = s.split(':').filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }
            return if (parts.isEmpty()) null else parts.fold(0.0) { acc, p -> acc * 60 + p }
        }
        return s.toDoubleOrNull()
    }

    /**
     * `[mm:ss] text` per non-empty segment, each clipped to 160 characters, then every other line dropped until it
     * fits [TIMELINE_BUDGET] — thinning keeps the whole recording represented, where cutting the tail would not.
     * Empty when no segment has text.
     */
    fun timeline(segments: List<AsrSegment>): String {
        var lines = segments.mapNotNull { seg ->
            val text = seg.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val clipped = if (text.length > LINE_MAX) text.take(LINE_MAX) + "…" else text
            "[${clock(seg.start)}] $clipped"
        }
        while (lines.joinedLength() > TIMELINE_BUDGET && lines.size > MIN_LINES) {
            lines = lines.filterIndexed { i, _ -> i % 2 == 0 }
        }
        return lines.joinToString("\n")
    }

    /** `mm:ss`, or `h:mm:ss` past the first hour — the shape [PROMPT] asks the model to copy back. */
    fun clock(seconds: Double): String {
        val total = maxOf(0, seconds.toInt())
        return if (total >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
        }
    }

    fun read(dir: File, base: String): List<ClipChapter> {
        val f = File(dir, base + SUFFIX)
        return if (f.isFile) parse(runCatching { f.readText() }.getOrDefault("")) else emptyList()
    }

    fun write(dir: File, base: String, chapters: List<ClipChapter>) {
        val body = writeJson(chapters.sortedBy { it.start }.map { mapOf("start" to it.start, "title" to it.title) })
        val f = File(dir, base + SUFFIX)
        val tmp = File(dir, "$base$SUFFIX.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(f)) {
            f.writeText(body)
            tmp.delete()
        }
    }

    fun delete(dir: File, base: String) {
        File(dir, base + SUFFIX).delete()
    }

    private fun List<String>.joinedLength() = sumOf { it.length } + maxOf(0, size - 1)

    /** The first JSON array in [reply]: the whole reply (fences stripped), an object's known key, or the outermost `[…]`. */
    private fun jsonArray(reply: String): List<*>? {
        val whole = runCatching { JsonParser(stripFence(reply)).parse() }.getOrNull()
        if (whole is List<*>) return whole
        if (whole is Map<*, *>) WRAPPER_KEYS.firstNotNullOfOrNull { whole[it] as? List<*> }?.let { return it }
        val open = reply.indexOf('[')
        val close = reply.lastIndexOf(']')
        if (open < 0 || close <= open) return null
        return runCatching { JsonParser(reply.substring(open, close + 1)).parse() }.getOrNull() as? List<*>
    }

    private fun stripFence(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            val nl = s.indexOf('\n')
            if (nl >= 0) s = s.substring(nl + 1)
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
        }
        return s.trim()
    }
}
