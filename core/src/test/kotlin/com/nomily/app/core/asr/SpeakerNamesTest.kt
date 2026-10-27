package com.nomily.app.core.asr

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakerNamesTest {
    @Test
    fun `parse is tolerant and drops blanks`() {
        assertEquals(mapOf("1" to "张伟"), SpeakerNames.parse("""{"1":" 张伟 ","2":"  ","3":5}"""))
        assertEquals(emptyMap(), SpeakerNames.parse("[1]"))
        assertEquals(emptyMap(), SpeakerNames.parse("garbage"))
        assertEquals(emptyMap(), SpeakerNames.parse(null))
    }

    @Test
    fun `blank name removes the entry and serialize round trips`() {
        val m = SpeakerNames.with(SpeakerNames.with(emptyMap(), "1", " Ann "), "2", "Bo")
        assertEquals(mapOf("1" to "Ann", "2" to "Bo"), SpeakerNames.parse(SpeakerNames.serialize(m)))
        assertEquals(mapOf("2" to "Bo"), SpeakerNames.with(m, "1", "  "))
    }

    @Test
    fun `names reach formatted text and keep the old format without one`() {
        val seg = AsrSegment("1", "hi", 1.0, 2.0, 1.0)
        assertEquals("[    1.0s -     2.0s] Speaker 1: hi", seg.formatted())
        assertEquals("[    1.0s -     2.0s] Ann: hi", seg.formatted(mapOf("1" to "Ann")))
        val r = AsrResult("hi", listOf(seg), "azure", "en")
        assertTrue(r.textForSummarization(mapOf("1" to "Ann")).contains("Ann: hi"))
        assertTrue(ClipQa.context(null, "hi", listOf(seg), mapOf("1" to "Ann"))!!.contains("Ann: hi"))
    }

    @Test
    fun `empty map deletes the file`() {
        val dir = Files.createTempDirectory("spk").toFile()
        SpeakerNames.write(dir, "c", mapOf("1" to "Ann"))
        assertEquals(mapOf("1" to "Ann"), SpeakerNames.read(dir, "c"))
        SpeakerNames.write(dir, "c", emptyMap())
        assertFalse(java.io.File(dir, "c.speakers.json").exists())
        dir.deleteRecursively()
    }
}
