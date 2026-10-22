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
        assertEquals(ShortSource.SHORT_PROMPT, ShortSource.adaptedPrompt(template, "hello there"))
        val mid = "word ".repeat(100)
        assertEquals("TEMPLATE\n\n${ShortSource.COMPACT_NOTE}", ShortSource.adaptedPrompt(template, mid))
        assertEquals(template, ShortSource.adaptedPrompt(template, "word ".repeat(500)))
    }

    @Test
    fun sectionKeyDependsOnPromptAndChunk() {
        val a = LlmChain.sectionCacheKey("p", "c")
        assertEquals(64, a.length)
        assertNotEquals(a, LlmChain.sectionCacheKey("p2", "c"))
        assertNotEquals(LlmChain.sectionCacheKey("pc", ""), LlmChain.sectionCacheKey("p", "c"))
    }
}
