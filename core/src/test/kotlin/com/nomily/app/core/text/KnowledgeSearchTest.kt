package com.nomily.app.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeSearchTest {

    @Test
    fun cutsAroundMatchAndFlattensWhitespace() {
        val text = "a".repeat(100) + "\n\nBudget  review\tnext" + "b".repeat(100)
        val s = KnowledgeSearch.snippet(text, "budget")!!
        assertEquals("…" + "a".repeat(34) + " Budget review next" + "b".repeat(43) + "…", s)
    }

    @Test
    fun shortTextHasNoEllipsisAndMissIsNull() {
        assertEquals("会议纪要", KnowledgeSearch.snippet("会议纪要", "纪要"))
        assertNull(KnowledgeSearch.snippet("会议纪要", "预算"))
        assertNull(KnowledgeSearch.snippet("anything", "  "))
    }
}
