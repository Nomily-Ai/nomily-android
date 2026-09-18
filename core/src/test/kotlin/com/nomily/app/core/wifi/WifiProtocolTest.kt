package com.nomily.app.core.wifi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Frame splitting and payload parsing for the KuaChuan line format.
 *
 * **These are the most error-prone areas on the TCP byte stream**—
 * frames being split, multiple frames arriving at once, mixed endianness; during joint debugging, all of these manifest as "garbled data," making it difficult to locate the issue on-site.
 */
class WifiProtocolTest {

    private fun frame(type: Int, msg: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            WifiProtocol.HEADER_MAGIC,
            type.toByte(),
            msg.toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),   // Length is big‑endian
            (payload.size and 0xFF).toByte(),
        ) + payload

    private fun u32le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    @Test
    fun `split multiple frames received in one go`() {
        val buf = frame(0, 0, byteArrayOf(1, 2)) + frame(4, 0, byteArrayOf(3))
        val split = WifiProtocol.splitFrames(buf)
        assertEquals(2, split.frames.size)
        assertEquals(0, split.frames[0].type)
        assertContentEquals(byteArrayOf(1, 2), split.frames[0].payload)
        assertEquals(4, split.frames[1].type)
        assertEquals(0, split.rest.size)
    }

    @Test
    fun `half frame stays in rest to be combined next time`() {
        val full = frame(1, 0, byteArrayOf(9, 9, 9))
        // Only feed the first 6 bytes: header is complete but payload is not
        val first = WifiProtocol.splitFrames(full.copyOfRange(0, 6))
        assertEquals(0, first.frames.size)
        assertEquals(6, first.rest.size)

        // Fill in the remaining parts to complete a full frame
        val second = WifiProtocol.splitFrames(first.rest + full.copyOfRange(6, full.size))
        assertEquals(1, second.frames.size)
        assertContentEquals(byteArrayOf(9, 9, 9), second.frames[0].payload)
        assertEquals(0, second.rest.size)
    }

    @Test
    fun `header byte mismatch must throw error immediately, no byte skipping recovery`() {
        // Self-healing silently turns one misalignment into subsequent misalignments, pushing the problem further before it explodes
        assertFailsWith<WifiProtocol.DesyncException> {
            WifiProtocol.splitFrames(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        }
    }

    @Test
    fun `list entries read little endian, name ends at NUL`() {
        val payload = u32le(2) + u32le(7) + u32le(348060) +
            "20260803110018.opus".toByteArray() + byteArrayOf(0, 0, 0)
        val f = WifiProtocol.parseListEntry(payload)!!
        assertEquals(2, f.index)
        assertEquals(7, f.total)
        assertEquals(348060, f.size)
        assertEquals("20260803110018.opus", f.name)
    }

    @Test
    fun `name 0 is list end sentinel`() {
        assertNull(WifiProtocol.parseListEntry(u32le(7) + u32le(7) + u32le(0) + "0".toByteArray()))
        // An empty name is also treated as a sentinel
        assertNull(WifiProtocol.parseListEntry(u32le(7) + u32le(7) + u32le(0) + byteArrayOf(0)))
    }

    @Test
    fun `download chunk skip 14 byte header (12 integers + 2 checksum)`() {
        val body = ByteArray(5) { (it + 1).toByte() }
        val payload = u32le(1000) + u32le(200) + u32le(body.size) + byteArrayOf(0x12, 0x34) + body
        val c = WifiProtocol.parseChunk(payload)
        assertEquals(1000, c.total)
        assertEquals(200, c.offset)
        assertContentEquals(body, c.body)
    }

    @Test
    fun `checksum in chunk header is big endian and decoded`() {
        val body = ByteArray(5) { (it + 1).toByte() }
        val payload = u32le(1000) + u32le(200) + u32le(body.size) + byteArrayOf(0x12, 0x34) + body
        assertEquals(0x1234, WifiProtocol.parseChunk(payload).checksum)
    }

    @Test
    fun `checksum is 16-bit ones complement sum (big endian word)`() {
        assertEquals(0xF6F9, WifiProtocol.internetChecksum(byteArrayOf(1, 2, 3, 4, 5)))
        // Odd length: the last byte is padded to the **high** position. If incorrectly padded to the low position, the result will be a different number.
        // A checksum error would cause valid fragments to be marked as corrupted, wasting the entire transmission.
        assertEquals(
            WifiProtocol.internetChecksum(byteArrayOf(1, 0)),
            WifiProtocol.internetChecksum(byteArrayOf(1)),
        )
    }

    @Test
    fun `chunk claimed length exceeds actual payload must throw error`() {
        // If truncated frames are not intercepted, subsequent frames will be concatenated using misaligned offsets, resulting in corrupted audio on disk.
        val payload = u32le(1000) + u32le(0) + u32le(999) + byteArrayOf(0, 0) + byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalArgumentException> { WifiProtocol.parseChunk(payload) }
    }
}
