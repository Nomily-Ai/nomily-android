package com.nomily.app.core.clips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptionBannersTest {

    @Test
    fun `completions present one at a time in order`() {
        val b = TranscriptionBanners()
        b.enqueue("a", "A", now = 0)
        b.enqueue("b", "B", now = 1)
        assertEquals("a", b.current?.clip)
        b.dismissCurrent()
        assertEquals("b", b.current?.clip)
        b.dismissCurrent()
        assertNull(b.current)
    }

    @Test
    fun `stale toasts are dropped, fresh ones kept`() {
        val b = TranscriptionBanners(staleAfterMs = 10_000)
        b.enqueue("old", "Old", now = 0)
        b.enqueue("older", "Older", now = 1_000)
        b.enqueue("fresh", "Fresh", now = 15_000)
        b.dropStale(now = 20_000)
        assertEquals("fresh", b.current?.clip)
        b.dismissCurrent()
        assertNull(b.current)
    }
}
