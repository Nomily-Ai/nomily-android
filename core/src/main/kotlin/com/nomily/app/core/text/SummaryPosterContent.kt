package com.nomily.app.core.text

/**
 * One drawable block of the summary poster body, mirroring iOS `PosterBlock`. Markdown structure is
 * flattened: headings keep their section role and [level] (indentation), list items become bullets, prose
 * stays a paragraph, and a summary without any structure falls back to plain paragraphs.
 */
sealed interface PosterBlock {
    val text: String

    data class Heading(override val text: String, val level: Int = 0) : PosterBlock
    data class Bullet(override val text: String, val level: Int = 0) : PosterBlock
    data class Paragraph(override val text: String) : PosterBlock
}

/** Markdown summary → poster blocks, mirroring iOS `SummaryPosterContent`. The poster carries the whole summary. */
object SummaryPosterContent {

    /** Only pathological bullets are cut; there is no section, item or total cap. */
    const val MAX_BULLET_CHARACTERS = 400

    fun blocks(markdown: String): List<PosterBlock> {
        MarkdownOutline.parse(markdown)?.let { root ->
            val out = mutableListOf<PosterBlock>()
            // The root is the document's own `#` title; the poster header already prints the clip title.
            root.children.forEach { append(it, out) }
            if (out.isEmpty()) append(root, out)
            if (out.isNotEmpty()) return out
        }
        // Flat prose fallback.
        return markdown.split("\n")
            .map { MarkdownOutline.inline(it).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { PosterBlock.Paragraph(it) }
    }

    /** Depth-first over every level; the outline depth (`##` = 1) is the indentation drawn. */
    private fun append(node: OutlineNode, out: MutableList<PosterBlock>) {
        val level = maxOf(0, node.depth - 1)
        out += when (node.kind) {
            OutlineNode.Kind.HEADING -> PosterBlock.Heading(node.title, level)
            OutlineNode.Kind.BULLET -> PosterBlock.Bullet(truncate(node.title, MAX_BULLET_CHARACTERS), level)
            OutlineNode.Kind.TASK -> PosterBlock.Bullet("☐ " + truncate(node.title, MAX_BULLET_CHARACTERS), level)
            OutlineNode.Kind.QUOTE, OutlineNode.Kind.TEXT -> PosterBlock.Paragraph(node.title)
        }
        node.children.forEach { append(it, out) }
    }

    private fun truncate(text: String, limit: Int): String = if (text.length > limit) text.take(limit) + "…" else text
}
