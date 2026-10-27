package com.nomily.app.core.asr

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipChaptersTest {
    @Test
    fun `parse accepts fences, wrappers, preambles and clock formats`() {
        val fenced = "```json\n[{\"t\":\"01:20\",\"title\":\" B \"},{\"start\":5,\"title\":\"A\"},{\"t\":\"00:05\",\"title\":\"dup\"}]\n```"
        assertEquals(listOf(ClipChapter(5.0, "A"), ClipChapter(80.0, "B")), ClipChapters.parse(fenced))

        val wrapped = """{"outline":[{"time":"1:00:03","chapter":"Late"},{"timestamp":"[00:12]","heading":"Early"}]}"""
        assertEquals(listOf(ClipChapter(12.0, "Early"), ClipChapter(3603.0, "Late")), ClipChapters.parse(wrapped))

        assertEquals(listOf(ClipChapter(7.5, "X")), ClipChapters.parse("Here is the outline: [{\"from\":\"7.5s\",\"name\":\"X\"},{\"t\":\"?\",\"title\":\"no time\"}] hope it helps"))
        assertTrue(ClipChapters.parse("no json here").isEmpty())
    }

    @Test
    fun `timeline clips lines and thins every other line until it fits`() {
        val short = ClipChapters.timeline(listOf(AsrSegment(text = " hi ", start = 65.0, end = 66.0, duration = 1.0), AsrSegment(text = "", start = 70.0, end = 71.0, duration = 1.0)))
        assertEquals("[01:05] hi", short)
        assertEquals("1:00:01", ClipChapters.clock(3601.0))

        val segs = (0 until 400).map { AsrSegment(text = "x".repeat(200), start = it * 10.0, end = 0.0, duration = 0.0) }
        val lines = ClipChapters.timeline(segs).split("\n")
        assertEquals(50, lines.size)
        assertEquals("[00:00] ${"x".repeat(160)}…", lines[0])
        assertEquals("[01:20] ", lines[1].take(8))
        assertTrue(lines.joinToString("\n").length <= ClipChapters.TIMELINE_BUDGET)
    }

    @Test
    fun `write then read round trips sorted`() {
        val dir = Files.createTempDirectory("chapters").toFile()
        ClipChapters.write(dir, "c", listOf(ClipChapter(30.0, "B"), ClipChapter(0.0, "A")))
        assertEquals(listOf(ClipChapter(0.0, "A"), ClipChapter(30.0, "B")), ClipChapters.read(dir, "c"))
        ClipChapters.delete(dir, "c")
        assertTrue(ClipChapters.read(dir, "c").isEmpty())
    }
}
