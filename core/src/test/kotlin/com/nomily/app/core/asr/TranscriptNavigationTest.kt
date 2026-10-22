package com.nomily.app.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptNavigationTest {

    private fun seg(start: Double, text: String, speaker: String? = null) =
        AsrSegment(speaker = speaker, text = text, start = start, end = start + 1, duration = 1.0)

    private val segments = listOf(
        seg(0.0, "Hello there", "1"),
        seg(5.0, "General Kenobi", "2"),
        seg(9.0, "hello again", "1"),
        seg(14.0, "Bye", "2"),
        seg(20.0, "End", ""),
    )

    @Test
    fun clockLabel() {
        assertEquals("0:00", ClockLabel.mmss(-3.0))
        assertEquals("1:05", ClockLabel.mmss(64.6))
        assertEquals("1:00:01", ClockLabel.mmss(3601.0))
    }

    @Test
    fun activeSegmentFollowsThePlayhead() {
        assertNull(TranscriptNavigation.activeIndex(segments, 0.0))
        assertEquals(0, TranscriptNavigation.activeIndex(segments, 0.4))
        assertEquals(2, TranscriptNavigation.activeIndex(segments, 9.0))
        assertEquals(4, TranscriptNavigation.activeIndex(segments, 99.0))
    }

    @Test
    fun inlineWindowSlidesAndClamps() {
        assertEquals(0 until 4, TranscriptNavigation.inlineWindow(5, null))
        assertEquals(1 until 5, TranscriptNavigation.inlineWindow(5, 2))
        assertEquals(1 until 5, TranscriptNavigation.inlineWindow(5, 4))
        assertEquals(0 until 3, TranscriptNavigation.inlineWindow(3, 2))
    }

    @Test
    fun searchAndSpeakerFilter() {
        assertEquals(listOf("1", "2"), TranscriptNavigation.distinctSpeakers(segments))
        assertEquals(listOf(0, 2), TranscriptNavigation.matches(segments, "HELLO", null))
        assertEquals(listOf(1, 3), TranscriptNavigation.matches(segments, "", "2"))
        assertTrue(TranscriptNavigation.matches(segments, "hello", "2").isEmpty())
        assertEquals(listOf(0..1, 4..5), TranscriptNavigation.highlightRanges("abxxAB", "ab"))
        assertTrue(TranscriptNavigation.highlightRanges("abc", "").isEmpty())
    }

    @Test
    fun qaPromptCarriesSummaryAndTimestampedTranscript() {
        val ctx = ClipQa.context("## Sum", "ignored when segments exist", segments.take(2))
        assertEquals("SUMMARY\n## Sum\n\nTRANSCRIPT\n[0:00] 1: Hello there\n[0:05] 2: General Kenobi", ctx)
        assertEquals("TRANSCRIPT\nplain", ClipQa.context(null, "plain", emptyList()))
        assertNull(ClipQa.context(null, null, emptyList()))
        val user = ClipQa.userContent("M", listOf(ClipQa.Message(true, "Q?"), ClipQa.Message(false, "A."), ClipQa.Message(true, "Q2?")))
        assertEquals("RECORDING MATERIAL\nM\n\nCONVERSATION\nUser: Q?\nAssistant: A.\nUser: Q2?", user)
    }
}
