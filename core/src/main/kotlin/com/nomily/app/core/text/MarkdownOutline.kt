package com.nomily.app.core.text

/**
 * A node in the outline derived from a Markdown summary.
 *
 * `###` headings are the sections and the lists beneath them are the points belonging to each
 * section; this turns that structure into a tree so the mind map can draw it and an outline can
 * indent it.
 *
 * @property depth Resolved tree depth — root is 0, every level below is exactly `parent.depth + 1`,
 *   so a `##` → `####` jump doesn't leave a phantom empty level.
 */
data class OutlineNode(
    val id: Int,
    val title: String,
    val depth: Int,
    val kind: Kind,
    val children: List<OutlineNode>,
) {
    enum class Kind {
        /** A `#`…`######` heading. */
        HEADING,

        /** A `-` / `*` / `+` / `1.` list item. */
        BULLET,

        /** A `- [ ]` / `- [x]` task item. */
        TASK,

        /** A `>` blockquote. */
        QUOTE,

        /** Free-standing paragraph text (continuation lines are merged). */
        TEXT,
    }

    val isLeaf: Boolean get() = children.isEmpty()

    /** Total number of nodes below this one, at any depth. */
    val descendantCount: Int get() = children.fold(children.size) { acc, c -> acc + c.descendantCount }

    /** The deepest node in the tree — drives the "expand level" upper bound. */
    val maxDepth: Int get() = children.fold(depth) { acc, c -> maxOf(acc, c.maxDepth) }

    /** Depth-first walk that stops descending once [maxDepth] (inclusive) is reached. */
    fun flattened(maxDepth: Int = Int.MAX_VALUE): List<OutlineNode> {
        val out = mutableListOf(this)
        if (depth >= maxDepth) return out
        children.forEach { out += it.flattened(maxDepth) }
        return out
    }
}

/**
 * Markdown → outline tree, mirroring iOS `MarkdownOutline`.
 *
 * Deliberately forgiving because the input is LLM output: heading levels can skip, list indentation
 * can be 2 or 4 spaces, bullets can be `-`, `*`, `+` or `1.`. Anything unrecognised becomes a plain
 * child node rather than being dropped; a document with no structure parses to null so callers can
 * fall back to plain Markdown rendering.
 */
object MarkdownOutline {

    /** Parses [markdown] into a tree, or null when there is nothing worth drawing as a mind map. */
    fun parse(markdown: String): OutlineNode? {
        val top = build(markdown)
        if (top.isEmpty()) return null
        val root = if (top.size == 1) {
            top[0]
        } else {
            // Several top-level sections and no single wrapper heading. An empty title signals the
            // caller to supply a localised label of its own.
            Builder("", 0, OutlineNode.Kind.HEADING).also { it.children += top }
        }
        // A single childless paragraph is flat prose — no structure to draw.
        if (root.kind == OutlineNode.Kind.TEXT && root.children.isEmpty()) return null
        var nextId = 0
        fun convert(node: Builder, depth: Int): OutlineNode {
            val me = nextId++
            val kids = node.children.map { convert(it, depth + 1) }
            return OutlineNode(me, node.title, depth, node.kind, kids)
        }
        return convert(root, 0)
    }

    /** Strips inline Markdown so a title can be shown as plain text. */
    fun inline(raw: String): String = cleanInline(raw)

    private class Builder(val title: String, val depth: Int, val kind: OutlineNode.Kind) {
        val children = mutableListOf<Builder>()
    }

    private class Bullet(val indent: Int, val text: String, val task: Boolean)

    private fun build(markdown: String): List<Builder> {
        val paragraphs = mutableListOf<String>()
        val root = Builder("", -1, OutlineNode.Kind.HEADING)
        val headings = mutableListOf<Pair<Int, Builder>>()
        val bullets = mutableListOf<Pair<Int, Builder>>()

        fun parent() = headings.lastOrNull()?.second ?: root

        fun flushParagraph() {
            if (paragraphs.isEmpty()) return
            val text = cleanInline(joinLines(paragraphs))
            paragraphs.clear()
            if (text.isEmpty()) return
            val host = parent()
            host.children += Builder(text, host.depth + 1, OutlineNode.Kind.TEXT)
        }

        for (line in markdown.split("\n")) {
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty() || isThematicBreak(trimmed)) {
                flushParagraph()
                continue
            }
            // Tables read as noise in a mind map. Skip them whole.
            if (trimmed.startsWith("|")) {
                flushParagraph()
                continue
            }
            val heading = parseHeading(trimmed)
            if (heading != null) {
                flushParagraph()
                bullets.clear()
                while (headings.isNotEmpty() && headings.last().first >= heading.first) headings.removeAt(headings.lastIndex)
                val node = Builder(cleanInline(heading.second), heading.first, OutlineNode.Kind.HEADING)
                if (node.title.isEmpty()) continue
                parent().children += node
                headings += heading.first to node
                continue
            }
            val bullet = parseBullet(line)
            if (bullet != null) {
                flushParagraph()
                while (bullets.isNotEmpty() && bullets.last().first >= bullet.indent) bullets.removeAt(bullets.lastIndex)
                val host = bullets.lastOrNull()?.second ?: parent()
                val node = Builder(
                    cleanInline(bullet.text),
                    host.depth + 1,
                    if (bullet.task) OutlineNode.Kind.TASK else OutlineNode.Kind.BULLET,
                )
                host.children += node
                bullets += bullet.indent to node
                continue
            }
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val text = cleanInline(trimmed.drop(1).trim(' ', '\t'))
                if (text.isEmpty()) continue
                val host = parent()
                host.children += Builder(text, host.depth + 1, OutlineNode.Kind.QUOTE)
                continue
            }
            paragraphs += trimmed
        }
        flushParagraph()
        return root.children
    }

    /** CJK has no inter-word space, so wrapped CJK lines are glued without one; Latin lines need it. */
    private fun joinLines(lines: List<String>): String {
        val out = StringBuilder(lines.first())
        for (next in lines.drop(1)) {
            val glue = if ((out.isNotEmpty() && isCjk(out.last())) || (next.isNotEmpty() && isCjk(next.first()))) "" else " "
            out.append(glue).append(next)
        }
        return out.toString()
    }

    /** A bare `###` with nothing after it is not a heading (the LLM sometimes emits a stray one). */
    private fun parseHeading(line: String): Pair<Int, String>? {
        val level = line.takeWhile { it == '#' }.length
        if (level !in 1..6) return null
        val rest = line.drop(level).trim(' ', '\t').trimEnd('#').trim(' ', '\t')
        if (rest.isEmpty()) return null
        return level to rest
    }

    /**
     * `-` / `*` / `+` / `1.` / `1)` markers, leading whitespace measured as indentation (tab = 4). The
     * marker must be followed by a space, which keeps a line starting with `*emphasis*` from being a bullet.
     */
    private fun parseBullet(line: String): Bullet? {
        var indent = 0
        var start = 0
        for (ch in line) {
            when (ch) {
                ' ' -> indent += 1
                '\t' -> indent += 4
                else -> break
            }
            start++
        }
        if (start >= line.length) return null
        var rest = line.substring(start)
        val markerLength = if (rest[0] == '-' || rest[0] == '*' || rest[0] == '+') {
            1
        } else {
            val digits = rest.takeWhile { it.isDigit() }.length
            if (digits == 0 || digits > 3) return null
            val delim = rest.getOrNull(digits) ?: return null
            if (delim != '.' && delim != ')') return null
            digits + 1
        }
        rest = rest.substring(markerLength)
        if (!rest.startsWith(" ")) return null
        var text = rest.trim(' ', '\t')
        var task = false
        for (box in listOf("[ ] ", "[x] ", "[X] ")) {
            if (text.startsWith(box)) {
                task = true
                text = text.substring(box.length)
                break
            }
        }
        if (!task && (text == "[ ]" || text == "[x]" || text == "[X]")) {
            task = true
            text = ""
        }
        if (text.isEmpty()) return null
        return Bullet(indent, text, task)
    }

    private fun isThematicBreak(line: String): Boolean {
        val s = line.filterNot { it.isWhitespace() }
        if (s.length < 3) return false
        return s.all { it == '-' } || s.all { it == '*' } || s.all { it == '_' }
    }

    private val imageOrLink = Regex("""!?\[([^\]\[]*)\]\([^)]*\)""")
    private val referenceLink = Regex("""\[([^\]\[]*)\]\[[^\]\[]*\]""")
    private val emphasisStar = Regex("""(?<![\w*])\*(?!\s)([^*]*?)(?<!\s)\*(?![\w*])""")
    private val emphasisUnderscore = Regex("""(?<![\w_])_(?!\s)([^_]*?)(?<!\s)_(?![\w_])""")
    private val lineBreak = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")
    private const val LEAD_GLYPHS = "▲▶◀▼●○•·※★☆"

    private fun cleanInline(raw: String): String {
        var s = raw
        s = imageOrLink.replace(s, "$1")
        s = referenceLink.replace(s, "$1")
        // `**`/`__` first, so the single-marker pass can't eat one half of a pair.
        s = s.replace("**", "").replace("__", "").replace("~~", "")
        s = emphasisStar.replace(s, "$1")
        s = emphasisUnderscore.replace(s, "$1")
        s = s.replace("`", "")
        s = lineBreak.replace(s, " ")
        s = htmlTag.replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        s = s.trim()
        // Lead-in glyphs are decoration in prose but noise in a tree node.
        while (s.isNotEmpty() && s.first() in LEAD_GLYPHS) s = s.drop(1).trim(' ', '\t')
        // A trailing colon only introduced the text that followed it; standing alone it dangles.
        while (s.isNotEmpty() && (s.last() == ':' || s.last() == '：')) s = s.dropLast(1).trim(' ', '\t')
        return s
    }

    private fun isCjk(ch: Char): Boolean = when (ch.code) {
        in 0x1100..0x11FF, in 0x2E80..0x2FFF, in 0x3000..0x303F, in 0x3040..0x30FF,
        in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xA960..0xA97F, in 0xAC00..0xD7AF,
        in 0xF900..0xFAFF, in 0xFF00..0xFFEF,
        -> true
        else -> false
    }
}
