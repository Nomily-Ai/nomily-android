package com.nomily.app.core.protocol

/**
 * Frame codec with a fixed frame shape:
 *
 * ```
 * Write RX_CHAR / Receive TX_CHAR notification: [PID][CMD][LEN][PAYLOAD…]
 * ```
 *
 * The status nibble of streaming commands (0x68 / 0x70) resides in `payload[0]`.
 *
 * Pure function, no `android.*`, no third‑party dependencies.
 * JSON payload **is not handled at this layer**: key order is not enforced, left to the
 * upper layer to assemble as needed before [encode].
 */
object PacketCodec {

    private val EMPTY = ByteArray(0)

    /** Fixed header length of a frame: PID + CMD + LEN. */
    const val HEADER_LENGTH = 3

    /** Maximum payload size of a single frame (LEN is only one byte). */
    const val MAX_PAYLOAD = 0xFF

    /** Received notification frame. */
    class Frame(
        val cmd: Int,
        val length: Int,
        val payload: ByteArray,
    ) {
        /** Device indicates OK with `payload[0] == 0x01`. */
        val isAck: Boolean get() = payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x01

        override fun toString(): String =
            "Frame(cmd=0x%02X, len=%d, payload=%d bytes)".format(cmd, length, payload.size)
    }

    /**
     * Construct a request frame.
     */
    fun encode(cmd: Int, payload: ByteArray = EMPTY): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
 "payload cannot fit in one frame: ${payload.size} > $MAX_PAYLOAD"
        }
        val out = ByteArray(HEADER_LENGTH + payload.size)
        out[0] = DnoteProtocol.PID.toByte()
        out[1] = cmd.toByte()
        out[2] = payload.size.toByte()
        payload.copyInto(out, HEADER_LENGTH)
        return out
    }

    /**
     * Parse a notification frame. Length < 3 or mismatched PID → null, **not an exception**.
     */
    fun decode(data: ByteArray): Frame? {
        if (data.size < HEADER_LENGTH) return null
        if ((data[0].toInt() and 0xFF) != DnoteProtocol.PID) return null
        return Frame(
            cmd = data[1].toInt() and 0xFF,
            length = data[2].toInt() and 0xFF,
            payload = data.copyOfRange(HEADER_LENGTH, data.size),
        )
    }

    /**
     * ACK to send when the device sends 0x55 (recording stopped).
     */
    fun ackRecStopped(): ByteArray = encode(DnoteProtocol.Cmd.REC_STOPPED, byteArrayOf(0x01))

    /**
     * ACK to send when the device sends 0x54 (recording started, only on physical‑button press).
     *
     * **Firmware documentation does not specify whether this requires an ack** — mimic 0x55 and reply
     * with a 1‑byte success packet of the same shape; devices that don't need an ack will ignore it.
     */
    fun ackRecStarted(): ByteArray = encode(DnoteProtocol.Cmd.REC_STARTED, byteArrayOf(0x01))

    /**
     * Payload layout for file‑transfer fragment (cmd 0x70):
     * ```
     * [START(1)][OFFSET(4, big‑endian)][DATA…]
     * ```
     * START byte: high nibble = status (see [DnoteProtocol.Xfer]), low nibble = sequence number.
     *
     * OFFSET is **big‑endian**; a real frame `00 00 00 C8` means 200. Reading it as
     * little‑endian would mistake the second block for offset 3355443200.
     */
    class XferChunk(
        val status: Int,
        val seq: Int,
        val offset: Long,
        val data: ByteArray,
    )

    /** payload shorter than 5 bytes (START + OFFSET) → null. */
    fun parseXferChunk(payload: ByteArray): XferChunk? {
        if (payload.size < 5) return null
        val start = payload[0].toInt() and 0xFF
        return XferChunk(
            status = start and 0xF0,
            seq = start and 0x0F,
            offset = beU32(payload, 1),
            data = payload.copyOfRange(5, payload.size),
        )
    }

    /**
     * The START byte of real‑time stream fragment (cmd 0x68) is also "high‑nibble status / low‑nibble sequence number".
     */
    fun streamStatus(payload: ByteArray): Int? =
        if (payload.isEmpty()) null else (payload[0].toInt() and 0xF0)

    private fun beU32(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)
}
