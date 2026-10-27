package com.nomily.app.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveLevelsTest {

    @Test
    fun levelIsGainedRmsClippedAtOne() {
        assertEquals(0f, LiveLevels.level(ShortArray(100)))
        // Constant 4096 → RMS 0.125 → ×4 = 0.5
        assertEquals(0.5f, LiveLevels.level(ShortArray(100) { 4096 }), 1e-6f)
        assertEquals(1f, LiveLevels.level(ShortArray(100) { Short.MAX_VALUE }))
        assertEquals(0f, LiveLevels.level(ShortArray(0)))
    }

    @Test
    fun longBatchIsSqueezedToPeaks() {
        val batch = List(28) { it / 100f }
        val out = LiveLevels.push(emptyList(), batch)
        assertEquals(LiveLevels.BATCH_SLOTS, out.size)
        // Groups of two → each slot holds the larger of its pair
        assertEquals(0.01f, out.first())
        assertEquals(0.27f, out.last())
    }

    @Test
    fun stripKeepsNewestSlots() {
        var strip = emptyList<Float>()
        repeat(5) { n -> strip = LiveLevels.push(strip, List(10) { n.toFloat() }) }
        assertEquals(LiveLevels.SLOTS, strip.size)
        assertEquals(4f, strip.last())
        assertEquals(2f, strip.first())
        assertEquals(strip, LiveLevels.push(strip, emptyList()))
    }
}
