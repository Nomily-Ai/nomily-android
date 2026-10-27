package com.nomily.app.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the shape of the built-in library that the store merges by (category, name). Mirrors iOS `SummarizeTemplateStore.defaults`. */
class SummarizeTemplatesTest {

    @Test
    fun defaultsArePlaudThenOriginalsThenVobot() {
        assertEquals(4, PLAUD_SUMMARIZE_TEMPLATES.size)
        assertEquals(16, BUILTIN_SUMMARIZE_TEMPLATES.size)
        assertEquals(49, VOBOT_SUMMARIZE_TEMPLATES.size)
        assertEquals(PLAUD_SUMMARIZE_TEMPLATES + BUILTIN_SUMMARIZE_TEMPLATES + VOBOT_SUMMARIZE_TEMPLATES, DEFAULT_SUMMARIZE_TEMPLATES)
        // The automatic summary takes the first default.
        assertEquals(AUTO_SUMMARY_TEMPLATE_NAME, DEFAULT_SUMMARIZE_TEMPLATES.first().name)
    }

    @Test
    fun autoPickerFollowsCuesAndFallsBackToQuickSummary() {
        val all = DEFAULT_SUMMARIZE_TEMPLATES
        assertEquals("Interview", AutoTemplatePicker.pick("今天的面试，候选人的简历很好", all)?.category)
        assertEquals("Meeting Minutes", AutoTemplatePicker.pick("Agenda for the weekly meeting", all)?.name)
        // One weak cue is not enough.
        assertEquals(AUTO_SUMMARY_TEMPLATE_NAME, AutoTemplatePicker.pick("we had an idea", all)?.name)
        assertEquals(AUTO_SUMMARY_TEMPLATE_NAME, AutoTemplatePicker.pick("", all)?.name)
        assertEquals(null, AutoTemplatePicker.pick("meeting", emptyList()))
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
