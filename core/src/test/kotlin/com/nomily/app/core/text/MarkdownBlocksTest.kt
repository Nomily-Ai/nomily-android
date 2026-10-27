package com.nomily.app.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownBlocksTest {

    @Test
    fun parsesTheShapesTemplatesEmit() {
        val md = """
            # Title
            First line
            continues here

            - one
            * two
            2) second
            > quoted
            ---
            ###Tight
        """.trimIndent()
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(
            listOf(
                MarkdownBlock(MarkdownBlock.Kind.Heading(1), "Title"),
                MarkdownBlock(MarkdownBlock.Kind.Paragraph, "First line continues here"),
                MarkdownBlock(MarkdownBlock.Kind.Bullet, "one"),
                MarkdownBlock(MarkdownBlock.Kind.Bullet, "two"),
                MarkdownBlock(MarkdownBlock.Kind.Ordered(2), "second"),
                MarkdownBlock(MarkdownBlock.Kind.Quote, "quoted"),
                MarkdownBlock(MarkdownBlock.Kind.Rule, ""),
                MarkdownBlock(MarkdownBlock.Kind.Heading(3), "Tight"),
            ),
            blocks,
        )
        assertEquals(2, MarkdownBlocks.parse(md, limit = 2).size)
    }

    @Test
    fun todosAreTheBlocksUnderAnActionHeading() {
        val md = """
            ## Overview
            - not a todo
            ## Action Items
            - Alice: ship the build
            1. Bob: write notes
            ### Risks
            - not a todo either
            ## 待办事项
            - 张三：周五前提交
        """.trimIndent()
        assertEquals(
            listOf("Alice: ship the build", "Bob: write notes", "张三：周五前提交"),
            TodoExtract.fromSummary(md).map { it.text },
        )
        assertEquals("todo", TodoExtract.keywords.last())
    }

    @Test
    fun noActionHeadingMeansNoTodos() {
        assertTrue(TodoExtract.fromSummary("## Overview\n- something\n\nPlain prose.").isEmpty())
        assertTrue(TodoExtract.fromSummary("").isEmpty())
    }

    @Test
    fun posterKeepsEveryLevelAndItem() {
        val md = buildString {
            appendLine("## Decisions")
            repeat(6) { appendLine("- point $it") }
            appendLine("### Details")
            appendLine("- deep")
            appendLine("## Tasks")
            appendLine("- [ ] follow up")
        }
        val blocks = SummaryPosterContent.blocks(md)
        assertEquals(PosterBlock.Heading("Decisions"), blocks.first())
        assertEquals(6, blocks.drop(1).takeWhile { it is PosterBlock.Bullet }.size)
        assertTrue(PosterBlock.Heading("Details", level = 1) in blocks)
        assertTrue(PosterBlock.Bullet("deep", level = 2) in blocks)
        assertEquals(PosterBlock.Bullet("☐ follow up", level = 1), blocks.last())
    }

    @Test
    fun posterKeepsProseUnderHeadingAsParagraph() {
        val blocks = SummaryPosterContent.blocks("# 会议纪要\n## 概要\n本次会议确定了发布计划。\n## 决定\n- 周五上线")
        assertEquals(
            listOf(
                PosterBlock.Heading("概要"),
                PosterBlock.Paragraph("本次会议确定了发布计划。"),
                PosterBlock.Heading("决定"),
                PosterBlock.Bullet("周五上线", level = 1),
            ),
            blocks,
        )
    }

    @Test
    fun posterFallsBackToProse() {
        val blocks = SummaryPosterContent.blocks("Just one **paragraph** of prose.")
        assertEquals(listOf<PosterBlock>(PosterBlock.Paragraph("Just one paragraph of prose.")), blocks)
        assertTrue(SummaryPosterContent.blocks("").isEmpty())
    }
}
