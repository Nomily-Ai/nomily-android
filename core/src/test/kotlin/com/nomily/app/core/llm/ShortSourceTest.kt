package com.nomily.app.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ShortSourceTest {
    @Test
    fun weightCountsCjkCharsAndLatinWords() {
        assertEquals(4 + 3, ShortSource.contentWeight("明天开会 call me tomorrow"))
        assertEquals(0, ShortSource.contentWeight("12:30 — [1]"))
    }

    @Test
    fun tiersPickPromptBySize() {
        val template = "TEMPLATE"
        fun adapt(source: String) = ShortSource.adaptedPrompt(template, source, "RULES", "TINY")
        assertEquals(ShortSource.Adapted("${ShortSource.SHORT_PROMPT}\n\nTINY", true), adapt("hello there"))
        val mid = "word ".repeat(100)
        assertEquals(ShortSource.Adapted("TEMPLATE\n\n${ShortSource.COMPACT_NOTE}\n\nRULES", false), adapt(mid))
        assertEquals(ShortSource.Adapted("TEMPLATE\n\nRULES", false), adapt("word ".repeat(500)))
    }

    @Test
    fun sectionKeyDependsOnPromptAndChunk() {
        val a = LlmChain.sectionCacheKey("p", "c")
        assertEquals(64, a.length)
        assertNotEquals(a, LlmChain.sectionCacheKey("p2", "c"))
        assertNotEquals(LlmChain.sectionCacheKey("pc", ""), LlmChain.sectionCacheKey("p", "c"))
    }

    @Test
    fun continuationAppendsOnNewLineAndStopsOnDone() {
        assertEquals("a\nb", LlmChain.appendContinuation("a", " b "))
        assertEquals("a\nb", LlmChain.appendContinuation("a\n", "b"))
        assertEquals(null, LlmChain.appendContinuation("a", " done "))
        assertEquals(null, LlmChain.appendContinuation("a", ""))
        assertEquals(1_500 + 35, LlmChain.continuationInput("x".repeat(3_000)).length)
    }
}
