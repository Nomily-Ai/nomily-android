package com.nomily.app.core.wifi

/**
 * Line format for fast transfer (TCP) — based on the device's TCP file-transfer protocol,
 * field‑aligned.
 *
 * ⚠️ **v1.47+ uses TCP, not UDP.** Older documentation (including this repo's previous migration status table) described `UDP 192.168.88.1:6718`, which was the approach for v1.39; devices now run v1.50 and follow this specification.
 *
 * Each frame: `[0xAA][type(1)][msg(1)][len_hi(1)][len_lo(1)][payload…]`
 * — length is **big‑endian 2 bytes**, while integers inside the payload are **little‑endian**. The inconsistency is defined by the protocol itself, not a typo: copy verbatim, do not "conveniently unify".
 *
 * This layer is a pure function: TCP is a byte stream, a frame may be split across multiple recv calls, or several frames may be received at once, so framing must handle "partial frames left for the next call". Placing it in `:core` allows fixing it with a constant byte sequence.
 */
object WifiProtocol {

    const val HEADER_MAGIC = 0xAA.toByte()
    const val HEADER_LEN = 5

    /** Response types. In v1.47, even a full download from offset 0 uses `pull`(4); `get`(1) comes from older firmware. */
    object Type {
        const val LIST = 0
        const val GET = 1
        const val DELETE = 3
        const val PULL = 4
        const val QUIT = 5
    }

    data class Frame(val type: Int, val msg: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && msg == other.msg && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (type * 31 + msg) * 31 + payload.contentHashCode()
    }

    /** Framing result: extracted complete frames + trailing incomplete bytes (to be continued next time). */
    data class Split(val frames: List<Frame>, val rest: ByteArray)

    class DesyncException : Exception("wifi stream desync: magic byte mismatch")

    /**
     * Extract as many complete frames as possible from the buffer.
     *
     * If the first byte does not match `0xAA`, throw [DesyncException] — **no 'skip a byte and retry' self‑healing**:
     * that would silently shift the protocol for all subsequent frames, pushing the issue further downstream before it crashes.
     */
    fun splitFrames(buffer: ByteArray): Split {
        val frames = mutableListOf<Frame>()
        var pos = 0
        while (buffer.size - pos >= HEADER_LEN) {
            if (buffer[pos] != HEADER_MAGIC) throw DesyncException()
            val type = buffer[pos + 1].toInt() and 0xFF
            val msg = buffer[pos + 2].toInt() and 0xFF
            // Length is big‑endian
            val len = ((buffer[pos + 3].toInt() and 0xFF) shl 8) or (buffer[pos + 4].toInt() and 0xFF)
            val needed = HEADER_LEN + len
            if (buffer.size - pos < needed) break
            frames += Frame(type, msg, buffer.copyOfRange(pos + HEADER_LEN, pos + needed))
            pos += needed
        }
        return Split(frames, buffer.copyOfRange(pos, buffer.size))
    }

    /** An entry in a `list` response. */
    data class RemoteFile(val index: Int, val total: Int, val size: Int, val name: String)

    /**
     * Parse the payload of a `list` frame: `[index(4,LE)][total(4,LE)][size(4,LE)][name…\0]`.
     *
     * Firmware uses **name == "0"** as a list‑termination sentinel; some firmware omit a separate sentinel
     * and instead set `index == total` on the final real entry. Both must be recognized.
     * Returning `null` indicates "this is a sentinel, end of list".
     */
    fun parseListEntry(payload: ByteArray): RemoteFile? {
        require(payload.size >= 12) { "list frame malformed (len=${payload.size})" }
        val index = readU32LE(payload, 0)
        val total = readU32LE(payload, 4)
        val size = readU32LE(payload, 8)
        val nameBytes = payload.copyOfRange(12, payload.size).takeWhile { it != 0.toByte() }.toByteArray()
        val name = String(nameBytes, Charsets.UTF_8)
        if (name == "0" || name.isEmpty()) return null
        return RemoteFile(index, total, size, name)
    }

    /**
     * `pull` fragment: `[total(4,LE)][offset(4,LE)][size(4,LE)][checksum(2,BE)][data…]`.
     *
     * [checksum] == 0 indicates **firmware did not populate it** (old/partial firmware), not "checksum equals zero" —
     * callers encountering 0 should skip verification, rely on length checks as a fallback, and must not reject the whole transfer because of it.
     */
    data class Chunk(val total: Int, val offset: Int, val checksum: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Chunk && total == other.total && offset == other.offset &&
                checksum == other.checksum && body.contentEquals(other.body)

        override fun hashCode(): Int = ((total * 31 + offset) * 31 + checksum) * 31 + body.contentHashCode()
    }

    /**
     * Parse a download fragment. Data begins after the 14‑byte header (12 bytes of three integers + 2‑byte checksum,
     * checksum is **big‑endian**).
     */
    fun parseChunk(payload: ByteArray): Chunk {
        require(payload.size >= 14) { "pull frame malformed (len=${payload.size})" }
        val total = readU32LE(payload, 0)
        val offset = readU32LE(payload, 4)
        val size = readU32LE(payload, 8)
        val checksum = ((payload[12].toInt() and 0xFF) shl 8) or (payload[13].toInt() and 0xFF)
        require(size <= payload.size - 14) { "pull frame truncated (size=$size, have=${payload.size - 14})" }
        return Chunk(total, offset, checksum, payload.copyOfRange(14, 14 + size))
    }

    /**
     * 16‑bit one's‑complement sum (big‑endian word), as carried in the chunk header.
     *
     * Previously neither side validated this checksum, so **corrupted fragments were accepted**, and the "delete after transfer"
     * feature immediately removed the original file from the device upon opening — leaving users with no usable recordings on either side.
     */
    fun internetChecksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
