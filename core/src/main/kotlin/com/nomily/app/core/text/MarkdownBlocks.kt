package com.nomily.app.core.text

/**
 * One block of a summary rendered natively inside the detail page, mirroring iOS `MarkdownBlock`.
 *
 * The full summary page renders through a WebView; inside the detail page's scrolling column a WebView
 * cannot size itself, so the summary tab and the to-dos tab use these blocks instead.
 */
data class MarkdownBlock(val kind: Kind, val text: String) {
    sealed interface Kind {
        data class Heading(val level: Int) : Kind
        data object Bullet : Kind
        data class Ordered(val number: Int) : Kind
        data object Quote : Kind
        data object Rule : Kind
        data object Paragraph : Kind
    }
}

/**
 * Line-based parser covering what the summary templates emit — headings, bullets, numbered items,
 * quotes, rules, paragraphs — mirroring iOS `MarkdownBlocks.parse`.
 */
object MarkdownBlocks {

    private val heading = Regex("""^#{1,6}\s*""")
    private val bullet = Regex("""^[-*•]\s+""")
    private val ordered = Regex("""^\d{1,2}[.)]\s+""")

    fun parse(markdown: String, limit: Int? = null): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += MarkdownBlock(MarkdownBlock.Kind.Paragraph, paragraph.joinToString(" "))
            paragraph.clear()
        }

        for (rawLine in markdown.split("\r\n", "\n", "\r")) {
            val line = rawLine.trim(' ', '\t')
            if (line.isEmpty()) {
                flushParagraph()
                continue
            }
            if (line == "---" || line == "***" || line == "___") {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlock.Kind.Rule, "")
                continue
            }
            val h = heading.find(line)
            if (h != null) {
                flushParagraph()
                val level = h.value.count { it == '#' }
                blocks += MarkdownBlock(MarkdownBlock.Kind.Heading(level), line.substring(h.range.last + 1))
                continue
            }
            val b = bullet.find(line)
            if (b != null) {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlock.Kind.Bullet, line.substring(b.range.last + 1))
                continue
            }
            val o = ordered.find(line)
            if (o != null) {
                flushParagraph()
                val number = o.value.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                blocks += MarkdownBlock(MarkdownBlock.Kind.Ordered(number), line.substring(o.range.last + 1))
                continue
            }
            if (line.startsWith("> ")) {
                flushParagraph()
                blocks += MarkdownBlock(MarkdownBlock.Kind.Quote, line.substring(2))
                continue
            }
            paragraph += line
        }
        flushParagraph()
        return if (limit != null && blocks.size > limit) blocks.take(limit) else blocks
    }
}

/**
 * Lifts the action-item section out of a summary's markdown — the to-dos tab's data source, mirroring
 * iOS `TodoExtract`.
 *
 * Heuristic by design: the summary templates emit action items under a heading containing one of
 * [keywords]. Everything between such a heading and the next heading (any level) is a to-do block. A
 * summary without such a heading has no to-dos — an honest empty state, not an error.
 */
object TodoExtract {
    val keywords = listOf("待办", "行动", "后续", "action", "todo")

    fun fromSummary(markdown: String): List<MarkdownBlock> {
        val result = mutableListOf<MarkdownBlock>()
        var collecting = false
        for (block in MarkdownBlocks.parse(markdown)) {
            if (block.kind is MarkdownBlock.Kind.Heading) {
                collecting = keywords.any { block.text.contains(it, ignoreCase = true) }
                continue
            }
            if (collecting) result += block
        }
        return result
    }
}
