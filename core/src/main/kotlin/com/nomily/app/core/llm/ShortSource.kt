package com.nomily.app.core.llm

/**
 * Keeps a template from inflating a short transcript: a sentence or two gets a two-sentence reply, a short paragraph
 * keeps the template but may drop empty sections. Weight = CJK characters + Latin words, one each.
 */
object ShortSource {
    const val SHORT_GATE_WEIGHT = 60
    const val COMPACT_THRESHOLD = 500

    const val SHORT_PROMPT =
        "The transcript below is very short — most likely a quick voice note. " +
            "Reply in the user's language with at most two short sentences: what was said, " +
            "plus any task, date or decision it contains. " +
            "Do NOT use headings, tables or template sections, and never write more text than the transcript itself."

    const val COMPACT_NOTE =
        "The source text is short. Keep the output shorter than the source: drop every " +
            "section that has nothing real to say instead of filling the template with placeholders."

    /** [text] is the system prompt to send; [replacedTemplate] is true only when the short-source prompt stood in for the template. */
    data class Adapted(val text: String, val replacedTemplate: Boolean)

    /**
     * The template adapted to the size of [source]. [rules] (the output discipline) is appended to every template
     * prompt; a one-sentence source gets [SHORT_PROMPT] plus the one-line [tinyNote] instead of the template.
     */
    fun adaptedPrompt(templatePrompt: String, source: String, rules: String, tinyNote: String): Adapted {
        val weight = contentWeight(source)
        return when {
            weight < SHORT_GATE_WEIGHT -> Adapted("$SHORT_PROMPT\n\n$tinyNote", replacedTemplate = true)
            weight < COMPACT_THRESHOLD -> Adapted("$templatePrompt\n\n$COMPACT_NOTE\n\n$rules", replacedTemplate = false)
            else -> Adapted("$templatePrompt\n\n$rules", replacedTemplate = false)
        }
    }

    fun contentWeight(text: String): Int {
        var cjk = 0
        var words = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isCjk(cp)) {
                cjk++
                inWord = false
            } else if (Character.isLetter(cp)) {
                if (!inWord) { words++; inWord = true }
            } else {
                inWord = false
            }
        }
        return cjk + words
    }

    private fun isCjk(cp: Int) =
        cp in 0x2E80..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF
}
