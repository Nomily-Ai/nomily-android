package com.nomily.app.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Blob parsing with a CID prefix.
 *
 * The input is an example payload,
 * prepended with the online CID `A3 22` — both paths must yield
 * identical results; otherwise, it indicates that "stripping the
 * CID via API" and "manually comparing the CID" are not equivalent.
 */
class DnoteAdvBlobTest {

    /** Example payload. */
    private val payload = "686800000102030052016d6f05".hexToBytes()

    @Test
    fun `blob with the little-endian CID parses the same as the stripped payload`() {
        val blob = byteArrayOf(0xA3.toByte(), 0x22) + payload
        val fromBlob = assertNotNull(parseDnoteAdvBlob(blob))
        val fromPayload = assertNotNull(parseDnoteAdvPayload(payload))
        assertEquals(fromPayload, fromBlob)
        assertEquals("D·NOTE Standard", fromBlob.model)
        assertEquals("v1.2", fromBlob.firmwareStr)
        assertEquals("6D6F05", fromBlob.deviceSidHex)
    }

    @Test
    fun `big-endian CID is rejected`() {
        // On the wire the CID is little-endian, so the big-endian spelling must not parse.
        assertNull(parseDnoteAdvBlob(byteArrayOf(0x22, 0xA3.toByte()) + payload))
    }

    @Test
    fun `blob shorter than CID plus 13 bytes is rejected`() {
        assertNull(parseDnoteAdvBlob(byteArrayOf(0xA3.toByte(), 0x22) + payload.copyOfRange(0, 12)))
        assertNull(parseDnoteAdvBlob(null))
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
