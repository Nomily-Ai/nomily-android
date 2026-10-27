package com.nomily.app.core.finder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardFinderLogicTest {
    @Test fun historyIsNewestFirstAndCapped() {
        var h = emptyList<SeenRecord>()
        for (i in 1..25) h = CardFinderLogic.addSeen(h, SeenRecord(at = i.toLong()))
        assertEquals(20, h.size)
        assertEquals(25L, h.first().at)
        assertEquals(6L, h.last().at)
    }

    @Test fun patchOnlyTouchesMatchingEntry() {
        val h = listOf(SeenRecord(2), SeenRecord(1))
        val p = CardFinderLogic.patchSeen(h, 2, "Cafe", 1.0, 2.0)
        assertEquals("Cafe", p[0].place)
        assertNull(p[1].place)
    }

    @Test fun dedupWindow() {
        assertTrue(CardFinderLogic.shouldAlert(null, 0))
        assertFalse(CardFinderLogic.shouldAlert(1_000, 6_000))
        assertTrue(CardFinderLogic.shouldAlert(1_000, 6_001))
    }

    @Test fun proximityBuckets() {
        assertEquals(3, CardFinderLogic.proximityLevel(-60, 0))
        assertEquals(2, CardFinderLogic.proximityLevel(-61, 0))
        assertEquals(2, CardFinderLogic.proximityLevel(-75, 0))
        assertEquals(1, CardFinderLogic.proximityLevel(-76, 0))
        assertEquals(0, CardFinderLogic.proximityLevel(-50, 3_001))
        assertEquals(0, CardFinderLogic.proximityLevel(127, 0))
        assertEquals(0, CardFinderLogic.proximityLevel(null, 0))
    }
}
