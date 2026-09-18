package com.nomily.app.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Parsing Azure / local ASR responses — pure function, can be pinned.
 *
 * Each assertion here is written against the shape documented by Azure Fast Transcription,
 * not "I think it should be this".
 */
class AsrTypesTest {

    @Test
    fun `phrases take priority, each segment with speaker and second-level timestamp`() {
        val body = """
            {
              "durationMilliseconds": 5000,
              "phrases": [
                {"offsetMilliseconds": 200, "durationMilliseconds": 1300, "text": "hello", "speaker": 1},
                {"offsetMilliseconds": 1600, "durationMilliseconds": 900, "text": "world", "speaker": 2}
              ],
              "combinedPhrases": [{"text": "hello world"}]
            }
        """.trimIndent()

        val r = parseAzureResponse(body, "en-US")

        assertEquals(2, r.segments.size)
        assertEquals("azure", r.provider)
        assertEquals("en-US", r.locale)
        // milliseconds → seconds
        assertEquals(0.2, r.segments[0].start)
        assertEquals(1.5, r.segments[0].end)
        assertEquals(1.3, r.segments[0].duration)
        assertEquals("1", r.segments[0].speaker)
        // concatenate full text with spaces
        assertEquals("hello world", r.text)
    }

    @Test
    fun `phrases fallback to combinedPhrases only when empty, treat whole segment duration as one segment`() {
        val body = """
            {"durationMilliseconds": 4200, "phrases": [], "combinedPhrases": [{"text": "only combined"}]}
        """.trimIndent()

        val r = parseAzureResponse(body, "auto")

        assertEquals(1, r.segments.size)
        assertEquals("only combined", r.segments[0].text)
        assertEquals(0.0, r.segments[0].start)
        assertEquals(4.2, r.segments[0].end)
        assertNull(r.segments[0].speaker)
    }

    @Test
    fun `both empty must throw error, cannot return empty result`() {
        // Empty results will be written as "transcription succeeded but no speech" into txt/json, disguising failures as successes
        assertFailsWith<AsrEmptyException> {
            parseAzureResponse("""{"phrases": [], "combinedPhrases": []}""", "auto")
        }
    }

    @Test
    fun `segment formatting characters follow convention`() {
        val seg = AsrSegment(speaker = "2", text = "hi", start = 1.24, end = 3.5, duration = 2.26)
        assertEquals("[    1.2s -     3.5s] Speaker 2: hi", seg.formatted())

        val noSpeaker = AsrSegment(speaker = null, text = "hi", start = 0.0, end = 1.0, duration = 1.0)
        assertEquals("[    0.0s -     1.0s] hi", noSpeaker.formatted())
    }

    /**
     * **Rounding must follow C's ties‑to‑even, not Java's HALF_UP.**
     *
     * These two values were the divergence observed on the development machine:
     *  - C   `printf("%.1f", 1.25)` → `1.2` (ties‑to‑even on the exact binary value)
     *  - Java `String.format("%.1f", 1.25)` → `1.3` (HALF_UP)
     * Counter‑example: reverting the implementation to `String.format` causes the test to fail immediately.
     */
    @Test
    fun `half boundary rounds to even per C ties-to-even`() {
        assertEquals(
            "[    1.2s -     2.5s] hi",
            AsrSegment(null, "hi", 1.25, 2.5, 1.25).formatted(),
        )
        // 1.35's binary representation is slightly greater than 1.35, both sides round up — not all .x5 round down
        assertEquals(
            "[    1.4s -     2.0s] hi",
            AsrSegment(null, "hi", 1.35, 2.0, 0.65).formatted(),
        )
    }

    @Test
    fun `txt body is one line per segment, use whole text if no segments`() {
        val r = AsrResult(
            text = "a b",
            segments = listOf(
                AsrSegment(null, "a", 0.0, 1.0, 1.0),
                AsrSegment(null, "b", 1.0, 2.0, 1.0),
            ),
            provider = "azure",
            locale = "auto",
        )
        assertEquals("[    0.0s -     1.0s] a\n[    1.0s -     2.0s] b", r.formatTranscript())

        val flat = AsrResult("just text", emptyList(), "local", "auto")
        assertEquals("just text", flat.formatTranscript())
    }

    @Test
    fun `asr json can be read back by itself`() {
        val r = AsrResult(
            text = "hello",
            segments = listOf(AsrSegment("1", "hello", 0.0, 1.5, 1.5)),
            provider = "local",
            locale = "zh-CN",
        )
        val back = parseLocalResponse(r.toJsonString())
        assertEquals(r.text, back.text)
        assertEquals(r.provider, back.provider)
        assertEquals(r.locale, back.locale)
        assertEquals(r.segments, back.segments)
    }
}
