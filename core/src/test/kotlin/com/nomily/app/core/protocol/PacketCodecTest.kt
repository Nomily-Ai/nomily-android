package com.nomily.app.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vector tests for frame encoding/decoding.
 *
 * The vectors are fixed literals, not derived from this implementation: the ack frame, the
 * request frame shape, and the 0x70 fragment layout come from real device frame regression logs.
 */
class PacketCodecTest {

    @Test
    fun `ack frame matches the expected literal`() {
        assertContentEquals(
            byteArrayOf(0xA0.toByte(), 0x55, 0x01, 0x01),
            PacketCodec.ackRecStopped(),
        )
    }

    @Test
    fun `encode with empty payload is header only`() {
        assertContentEquals(
            byteArrayOf(0xA0.toByte(), 0x80.toByte(), 0x00),
            PacketCodec.encode(DnoteProtocol.Cmd.DEVICE_INFO),
        )
    }

    @Test
    fun `encode writes PID, cmd and payload length`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = PacketCodec.encode(DnoteProtocol.Cmd.MIC_GAIN, payload)
        assertContentEquals(
            byteArrayOf(0xA0.toByte(), 0x8C.toByte(), 0x03, 0x01, 0x02, 0x03),
            frame,
        )
    }

    @Test
    fun `encode rejects a payload that cannot fit the length byte`() {
        assertFailsWith<IllegalArgumentException> {
            PacketCodec.encode(DnoteProtocol.Cmd.BT_NAME_LONG, ByteArray(0x100))
        }
        // Boundary values themselves should be passed
        PacketCodec.encode(DnoteProtocol.Cmd.BT_NAME_LONG, ByteArray(0xFF))
    }

    @Test
    fun `encode then decode round-trips`() {
        val payload = byteArrayOf(0x01) + ByteArray(DnoteProtocol.BOND_ID_LENGTH) { it.toByte() }
        val frame = assertNotNull(PacketCodec.decode(PacketCodec.encode(DnoteProtocol.Cmd.BIND, payload)))
        assertEquals(DnoteProtocol.Cmd.BIND, frame.cmd)
        assertEquals(payload.size, frame.length)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `decode drops frames with the wrong PID or too short`() {
        assertNull(PacketCodec.decode(byteArrayOf(0xA1.toByte(), 0x80.toByte(), 0x00)))
        assertNull(PacketCodec.decode(byteArrayOf(0xA0.toByte(), 0x80.toByte())))
        assertNull(PacketCodec.decode(ByteArray(0)))
    }

    @Test
    fun `isAck keys off the first payload byte`() {
        assertTrue(assertNotNull(PacketCodec.decode(byteArrayOf(0xA0.toByte(), 0x51, 0x01, 0x01))).isAck)
        assertTrue(!assertNotNull(PacketCodec.decode(byteArrayOf(0xA0.toByte(), 0x51, 0x01, 0x00))).isAck)
        // An empty payload is not an ack and should not throw
        assertTrue(!assertNotNull(PacketCodec.decode(byteArrayOf(0xA0.toByte(), 0x51, 0x00))).isAck)
    }

    @Test
    fun `xfer chunk splits status, seq and a big-endian offset`() {
        // START = 0x13 → status IN_PROGRESS(0x10), sequence number 3; OFFSET = 0x00000200 big-endian
        val payload = byteArrayOf(0x13, 0x00, 0x00, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte())
        val chunk = assertNotNull(PacketCodec.parseXferChunk(payload))
        assertEquals(DnoteProtocol.Xfer.IN_PROGRESS, chunk.status)
        assertEquals(3, chunk.seq)
        assertEquals(512L, chunk.offset)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), chunk.data)
    }

    @Test
    fun `xfer offset uses the full unsigned 32-bit range`() {
        val payload = byteArrayOf(0x20, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val chunk = assertNotNull(PacketCodec.parseXferChunk(payload))
        assertEquals(DnoteProtocol.Xfer.EOF, chunk.status)
        assertEquals(4_294_967_295L, chunk.offset)   // Don't become -1 just because it's an Int
        assertEquals(0, chunk.data.size)
    }

    @Test
    fun `xfer chunk needs start plus offset`() {
        assertNull(PacketCodec.parseXferChunk(byteArrayOf(0x13, 0x00, 0x02, 0x00)))
    }

    @Test
    fun `stream status is the upper nibble`() {
        assertEquals(DnoteProtocol.Stream.STOP, PacketCodec.streamStatus(byteArrayOf(0x25)))
        assertNull(PacketCodec.streamStatus(ByteArray(0)))
    }

    @Test
    fun `switch command map matches the protocol`() {
        assertEquals(0x84, DnoteProtocol.SWITCH_CMDS["motor"])
        assertEquals(0x85, DnoteProtocol.SWITCH_CMDS["nc"])
    }
}
