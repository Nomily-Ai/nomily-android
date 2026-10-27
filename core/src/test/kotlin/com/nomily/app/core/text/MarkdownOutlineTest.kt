package com.nomily.app.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownOutlineTest {

    private fun titles(n: OutlineNode): List<String> = n.flattened().map { "  ".repeat(it.depth) + it.title }

    @Test
    fun flatProseHasNoOutline() {
        assertNull(MarkdownOutline.parse(""))
        assertNull(MarkdownOutline.parse("Just one paragraph\nwrapped over two lines."))
    }

    @Test
    fun headingSkipsAreNormalisedAndListsNest() {
        val md = """
            # Weekly sync
            ## Decisions
            #### Ship **v1.2**
            - Freeze on *Friday*
              - QA owns [checklist](http://x)
            ## Tasks
            1. [ ] Draft notes:
            | a | b |
            |---|---|
            > quoted
        """.trimIndent()
        val root = assertNotNull(MarkdownOutline.parse(md))
        assertEquals(
            listOf(
                "Weekly sync",
                "  Decisions",
                "    Ship v1.2",
                "      Freeze on Friday",
                "        QA owns checklist",
                "  Tasks",
                "    Draft notes",
                "    quoted",
            ),
            titles(root),
        )
        val tasks = root.children[1]
        assertEquals(OutlineNode.Kind.TASK, tasks.children[0].kind)
        assertEquals(OutlineNode.Kind.QUOTE, tasks.children[1].kind)
        assertEquals(4, root.maxDepth)
        assertEquals(7, root.descendantCount)
        assertEquals((0..7).toList(), root.flattened().map { it.id })
    }

    @Test
    fun severalTopSectionsGetAnUntitledRoot() {
        val root = assertNotNull(MarkdownOutline.parse("## A\n- a1\n## B\n- b1"))
        assertEquals("", root.title)
        assertEquals(listOf("A", "B"), root.children.map { it.title })
    }

    @Test
    fun cjkLinesJoinWithoutSpaceAndGlyphsAreStripped() {
        val root = assertNotNull(MarkdownOutline.parse("# 结论\n▲ 第一行\n第二行\n\nLatin line\ncontinues"))
        assertEquals(listOf("第一行第二行", "Latin line continues"), root.children.map { it.title })
    }

    @Test
    fun emphasisInsideWordsIsKept() {
        assertEquals("snake_case_name and bold", MarkdownOutline.inline("snake_case_name and __bold__"))
        assertEquals("a*b*c", MarkdownOutline.inline("a*b*c"))
        assertEquals("强调", MarkdownOutline.inline("*强调*"))
    }

    @Test
    fun layoutFoldsAndPlacesColumns() {
        val root = assertNotNull(MarkdownOutline.parse("# R\n## A\n- a1\n- a2\n## B\n- b1"))
        val measure = MindMapLayout.Measure { title, _, maxWidth, _ -> minOf(maxWidth, title.length * 8f) to 18f }

        val folded = MindMapLayout.foldPoints(root, 1)
        assertEquals(setOf(1, 4), folded)
        val shallow = MindMapLayout.build(root, folded, measure)
        assertEquals(listOf(0, 1, 4), shallow.items.map { it.id })
        assertTrue(shallow.items.drop(1).all { it.childCount > 0 && !it.isExpanded })

        val full = MindMapLayout.build(root, emptySet(), measure)
        assertEquals(6, full.items.size)
        assertEquals(5, full.edges.size)
        val byId = full.items.associateBy { it.id }
        // Children sit one column to the right and the parent is centred on their span.
        val a = byId.getValue(1)
        val a1 = byId.getValue(2)
        val a2 = byId.getValue(3)
        assertTrue(a1.x > a.x + a.width)
        assertEquals((a1.y + a2.y + a2.height) / 2, a.y + a.height / 2, 0.01f)
        assertEquals(listOf(0, 1, 1, 1, 2, 2), full.items.map { it.branchIndex })
        // Sections never borrow the root's near-black, and the dark theme moves the hue the right way.
        assertTrue((1..8).none { MindMapLayout.branchFill(it, false) == MindMapLayout.branchFill(0, false) })
        assertEquals(0xFF4A7C59.toInt(), MindMapLayout.branchFill(1, false))
        assertTrue((MindMapLayout.branchInk(1, true) and 0xFF) > 0x59)
        assertTrue((MindMapLayout.branchFill(1, true) and 0xFF) < 0x59)
        assertTrue(full.items.all { it.x + it.width <= full.width && it.y + it.height <= full.height })
    }
}
