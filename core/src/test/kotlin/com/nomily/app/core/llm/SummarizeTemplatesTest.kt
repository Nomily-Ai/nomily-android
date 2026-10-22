package com.nomily.app.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the shape of the built-in library that the store merges by (category, name). Mirrors iOS `SummarizeTemplateStore.defaults`. */
class SummarizeTemplatesTest {

    @Test
    fun defaultsAreOriginalsThenVobot() {
        assertEquals(16, BUILTIN_SUMMARIZE_TEMPLATES.size)
        assertEquals(49, VOBOT_SUMMARIZE_TEMPLATES.size)
        assertEquals(BUILTIN_SUMMARIZE_TEMPLATES + VOBOT_SUMMARIZE_TEMPLATES, DEFAULT_SUMMARIZE_TEMPLATES)
    }

    @Test
    fun identitiesAreUnique() {
        val ids = DEFAULT_SUMMARIZE_TEMPLATES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val keys = DEFAULT_SUMMARIZE_TEMPLATES.map { it.category to it.name }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun vobotTemplatesCarryDescriptionAndFillInHeader() {
        VOBOT_SUMMARIZE_TEMPLATES.forEach {
            assertTrue(!it.summary.isNullOrBlank(), it.name)
            assertTrue(it.prompt.trimStart().startsWith("Fill in this report from the transcript."), it.name)
        }
        assertEquals(15, VOBOT_SUMMARIZE_TEMPLATES.map { it.category }.toSet().size)
    }

    @Test
    fun storeKeyIsCategoryUnitSeparatorName() {
        assertEquals("Meeting Notes\u001FFormal Meeting Minutes", BUILTIN_SUMMARIZE_TEMPLATES.first().storeKey)
    }
}
