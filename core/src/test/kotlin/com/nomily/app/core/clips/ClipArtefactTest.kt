package com.nomily.app.core.clips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The branching points for suffix rules are pinned here. There is no reference implementation to compare against.
 */
class ClipArtefactTest {

    @Test
    fun `audio by extension`() {
        assertEquals(ClipArtefact.AUDIO, ClipArtefact.of("20260730120000.ogg"))
        assertEquals(ClipArtefact.AUDIO, ClipArtefact.of("20260730120000.opus"))
        assertEquals("20260730120000", ClipArtefact.baseOf("20260730120000.ogg"))
    }

    /** Long suffix priority: Translated summaries must be classified as summaries and cannot be overridden by short suffixes like `.txt`/`.json`. */
    @Test
    fun `longest suffix wins`() {
        assertEquals(ClipArtefact.SUMMARY, ClipArtefact.of("clip.summary.translated.md"))
        assertEquals(ClipArtefact.SUMMARY, ClipArtefact.of("clip.summary.md"))
        assertEquals(ClipArtefact.TRANSCRIPT, ClipArtefact.of("clip.translated.txt"))
        assertEquals(ClipArtefact.TRANSCRIPT, ClipArtefact.of("clip.asr.json"))
        assertEquals(ClipArtefact.TRANSCRIPT, ClipArtefact.of("clip.txt"))
        assertEquals("clip", ClipArtefact.baseOf("clip.summary.translated.md"))
        assertEquals("clip", ClipArtefact.baseOf("clip.asr.json"))
    }

    /** `.title` is metadata, not one of the three deletable artifacts — `of()` must not recognize it. */
    @Test
    fun `title sidecar is not an artefact but has a base`() {
        assertNull(ClipArtefact.of("clip.title"))
        assertEquals("clip", ClipArtefact.baseOf("clip.title"))
    }

    @Test
    fun `unrelated files are not artefacts`() {
        assertNull(ClipArtefact.of("notes"))
        assertNull(ClipArtefact.baseOf("notes"))
        assertNull(ClipArtefact.of(".manifest.json"))
    }

    /** Fragments with only `.title` are ghost entries — they must be filtered out; those with content must not. */
    @Test
    fun `orphan titles are only those with nothing left`() {
        val files = listOf(
            "a.ogg", "a.title",           // Audio is also present → keep
            "b.title",                    // Nothing left → sweep
            "c.summary.md", "c.title",    // Also keep the summary → leave
        )
        assertEquals(listOf("b.title"), ClipArtefact.orphanTitles(files))
    }
}
