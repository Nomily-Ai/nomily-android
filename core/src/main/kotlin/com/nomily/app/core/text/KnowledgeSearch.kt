package com.nomily.app.core.text

/** Full-text search over a clip's transcript and summary: find the query and cut a one-line snippet around it. */
object KnowledgeSearch {

    /** Sidecars searched, in order. JSON sidecars are machine formats and stay out. */
    val SUFFIXES = listOf(".txt", ".translated.txt", ".summary.md", ".summary.translated.md")

    /**
     * Snippet around the first case-insensitive match of [query] in [text], or null when it doesn't occur.
     * Whitespace runs collapse to one space; an ellipsis marks each cut end.
     */
    fun snippet(text: String, query: String, before: Int = 36, after: Int = 56): String? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val at = text.indexOf(needle, ignoreCase = true)
        if (at < 0) return null
        val start = maxOf(0, at - before)
        val end = minOf(text.length, at + needle.length + after)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }
}
