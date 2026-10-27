package com.nomily.app.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BluetoothNameLimitTest {

    @Test
    fun `names within the limit are untouched`() {
        val name = "D·NOTE"
        assertEquals(name, DnoteProtocol.clampBluetoothName(name))
    }

    @Test
    fun `ascii names are cut at the byte ceiling`() {
        val clamped = DnoteProtocol.clampBluetoothName("A".repeat(40))
        assertEquals(DnoteProtocol.BT_NAME_MAX_BYTES, clamped.length)
    }

    @Test
    fun `multi-byte characters are never split`() {
        // 9 Chinese characters = 27 UTF-8 bytes; only 8 of them fit.
        val clamped = DnoteProtocol.clampBluetoothName("录音卡片测试名称九")
        assertEquals(8, clamped.length)
        assertTrue(clamped.toByteArray(Charsets.UTF_8).size <= DnoteProtocol.BT_NAME_MAX_BYTES)
    }
}
