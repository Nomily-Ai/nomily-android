package com.nomily.app.core.text

/**
 * One drawable block of the summary poster body, mirroring iOS `PosterBlock`. Markdown structure is
 * flattened: headings keep their section role, list items become bullets, and a summary without any
 * structure falls back to plain paragraphs.
 */
sealed interface PosterBlock {
    val text: String

    data class Heading(override val text: String) : PosterBlock
    data class Bullet(override val text: String) : PosterBlock
    data class Paragraph(override val text: String) : PosterBlock
}

/** Markdown summary → poster blocks, mirroring iOS `SummaryPosterContent`. */
object SummaryPosterContent {

    // Caps keep a sprawling summary from turning into a 12-screen poster — the card is a teaser, not a transcript.
    const val MAX_SECTIONS = 9
    const val MAX_BULLETS_PER_SECTION = 4
    const val MAX_BULLETS_TOTAL = 24
    const val MAX_PARAGRAPHS = 8
    const val MAX_PARAGRAPH_CHARACTERS = 1_400
    const val MAX_BULLET_CHARACTERS = 140

    fun blocks(markdown: String): List<PosterBlock> {
        MarkdownOutline.parse(markdown)?.let { root ->
            val out = mutableListOf<PosterBlock>()
            var bulletsTotal = 0
            for (section in root.children.take(MAX_SECTIONS)) {
                out += if (section.kind == OutlineNode.Kind.HEADING) PosterBlock.Heading(section.title) else PosterBlock.Paragraph(section.title)
                var bulletsInSection = 0
                for (child in section.children) {
                    if (bulletsTotal >= MAX_BULLETS_TOTAL) break
                    if (child.kind == OutlineNode.Kind.HEADING) continue
                    val text = truncate(child.title, MAX_BULLET_CHARACTERS)
                    out += PosterBlock.Bullet(if (child.kind == OutlineNode.Kind.TASK) "☐ $text" else text)
                    bulletsTotal++
                    bulletsInSection++
                    if (bulletsInSection >= MAX_BULLETS_PER_SECTION) break
                }
                if (bulletsTotal >= MAX_BULLETS_TOTAL) break
            }
            if (out.isNotEmpty()) return out
        }
        // Flat prose fallback.
        val paragraphs = mutableListOf<PosterBlock>()
        var budget = MAX_PARAGRAPH_CHARACTERS
        for (line in markdown.split("\n")) {
            if (line.isEmpty()) continue
            val text = MarkdownOutline.inline(line).trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            val piece = if (text.length > budget) text.take(maxOf(budget, 0)) else text
            if (piece.isEmpty()) break
            paragraphs += PosterBlock.Paragraph(piece)
            budget -= piece.length
            if (paragraphs.size >= MAX_PARAGRAPHS || budget <= 0) break
        }
        return paragraphs
    }

    private fun truncate(text: String, limit: Int): String = if (text.length > limit) text.take(limit) + "…" else text
}
