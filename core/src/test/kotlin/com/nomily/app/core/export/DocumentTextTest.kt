package com.nomily.app.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTextTest {
    private val json = """{"text":"hi","segments":[{"speaker":"1","text":"hi","start":1.0,"end":2.0}],"provider":"azure","language":"en"}"""

    @Test
    fun `transcript prefers speaker aware json then falls back to txt`() {
        val named = DocumentText.transcript(json, "plain", mapOf("1" to "Ann"))
        assertTrue(named != null && named.contains("Ann: hi"), named)
        assertEquals("plain", DocumentText.transcript("garbage", "plain", emptyMap()))
        assertEquals("plain", DocumentText.transcript(null, "plain", emptyMap()))
        assertNull(DocumentText.transcript(null, null, emptyMap()))
    }

    @Test
    fun `markdown has title summary and transcript`() {
        assertEquals("# a\n\n## S\n\nsum\n\n## T\n\nbody\n", DocumentText.markdown("a", "sum", "body", "S", "T"))
        assertEquals("# a\n\n## T\n\nbody\n", DocumentText.markdown("a", null, "body", "S", "T"))
    }

    @Test
    fun `stem and safe stem`() {
        assertEquals("Phone-1", DocumentText.stem("Phone-1.wav"))
        assertEquals("a_b", DocumentText.safeFileStem("a/b"))
    }
}
