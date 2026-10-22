package com.nomily.app.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCaptionStateTest {

    @Test
    fun finalReplacesPartialAndDropsRepeats() {
        var s = LiveCaptionState().withPartial("hel")
        assertTrue(s.hasText)
        s = s.withFinal("hello", 1.0).withFinal("hello", 2.0).withFinal("  ", 3.0)
        assertEquals(listOf(CaptionLine("hello", 1.0)), s.lines)
        assertEquals("", s.partial)
    }

    @Test
    fun linesSurviveAnEngineSwitch() {
        // Azure drops mid-take: the next engine keeps feeding the same state, so nothing on screen disappears.
        var s = LiveCaptionState().withFinal("one", 1.0).withPartial("tw")
        s = s.withPartial("").withFinal("two", 4.0)
        assertEquals("one\ntwo", s.joinedText())
    }

    @Test
    fun committedTurnsPartialIntoLine() {
        val s = LiveCaptionState().withFinal("a", 1.0).withPartial("b").committed(2.5)
        assertEquals(listOf(CaptionLine("a", 1.0), CaptionLine("b", 2.5)), s.lines)
        assertFalse(LiveCaptionState().hasText)
    }

    @Test
    fun asrResultKeepsFinalizeTimes() {
        val r = LiveCaptionState().withFinal("a", 1.0).withPartial("b").toAsrResult("live-captions", "en-US")
        assertEquals("a\nb", r.text)
        assertEquals(1, r.segments.size)
        assertEquals(1.0, r.segments[0].start)
        assertEquals(1.0, r.segments[0].end)
    }
}
