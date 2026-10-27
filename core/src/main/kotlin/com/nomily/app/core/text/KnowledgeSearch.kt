package com.nomily.app.core.text

/** Full-text search over a clip's transcript and summary: find the query and cut a one-line snippet around it. */
object KnowledgeSearch {

    /**
     * Case- and diacritic-insensitive form for matching ("Café" finds "cafe"). Folds char by char so indices in the
     * folded text still point at the same characters in the original.
     */
    fun fold(s: String): String = buildString(s.length) {
        for (c in s) {
            val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                .firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: c
            append(base.lowercaseChar())
        }
    }

    /** Whether [haystack] contains [needle] under [fold]. */
    fun matches(haystack: String, needle: String): Boolean = fold(haystack).contains(fold(needle.trim()))

    /** Sidecars searched, in order. JSON sidecars are machine formats and stay out. */
    val SUFFIXES = listOf(".txt", ".translated.txt", ".summary.md", ".summary.translated.md")

    /**
     * Snippet around the first case-insensitive match of [query] in [text], or null when it doesn't occur.
     * Whitespace runs collapse to one space; an ellipsis marks each cut end.
     */
    fun snippet(text: String, query: String, before: Int = 36, after: Int = 56): String? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val at = fold(text).indexOf(fold(needle))
        if (at < 0) return null
        val start = maxOf(0, at - before)
        val end = minOf(text.length, at + needle.length + after)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }
}
