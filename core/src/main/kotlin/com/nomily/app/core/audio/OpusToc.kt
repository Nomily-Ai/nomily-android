package com.nomily.app.core.audio

/**
 * Opus TOC byte decoding — **RFC 6716 §3.1 Table 2**.
 *
 * ## Why this file exists
 *
 * [OPUS_FRAME_DURATION_MS] `= 20` started as an assumption. The whole duration chain (the
 * `0:05` line in the database, Ogg granule) is built on it, with no external support.
 *
 * But the frame length can actually **be read**: it is encoded in the first byte (TOC) of each Opus packet,
 * and the encoding rules are defined by RFC 6716 §3.1 Table 2. Therefore we copy that table here,
 * turning 20ms into a **result read from device data** rather than a copied constant.
 *
 * In short: **the same number is upgraded from a “assumption” to a “authoritatively verified” value.**
 *
 * ## Original Table 2 (checked against rfc-editor.org on 2026-07-30, not from memory)
 *
 * | config | Mode | Bandwidth | Frame Sizes |
 * |---|---|---|---|
 * | 0…3   | SILK-only | NB  | 10, 20, 40, 60 ms |
 * | 4…7   | SILK-only | MB  | 10, 20, 40, 60 ms |
 * | 8…11  | SILK-only | WB  | 10, 20, 40, 60 ms |
 * | 12…13 | Hybrid    | SWB | 10, 20 ms |
 * | 14…15 | Hybrid    | FB  | 10, 20 ms |
 * | 16…19 | CELT-only | NB  | 2.5, 5, 10, 20 ms |
 * | 20…23 | CELT-only | WB  | 2.5, 5, 10, 20 ms |
 * | 24…27 | CELT-only | SWB | 2.5, 5, 10, 20 ms |
 * | 28…31 | CELT-only | FB  | 2.5, 5, 10, 20 ms |
 *
 * TOC bit order (RFC 6716 Figure 1, **bit 0 is the most‑significant bit**): `config`(bit 0-4) + `s`(bit 5) + `c`(bit 6-7).
 *
 * `0x4b >> 3 = 9`, and config 9 falls in **8…11 → WB (16 kHz)** — not MB (12 kHz,
 * config 4…7). Bandwidth does not affect frame length: both sections share the same
 * frame‑size table.
 */

/** Mode — as named in the Mode column of Table 2. */
enum class OpusMode { SILK_ONLY, HYBRID, CELT_ONLY }

/** Bandwidth — as named in the Bandwidth column of Table 2. */
enum class OpusBandwidth(val sampleRateHz: Int) {
    NB(8_000), MB(12_000), WB(16_000), SWB(24_000), FB(48_000)
}

/**
 * The data extracted from a TOC byte.
 *
 * Duration is expressed as **microseconds** and as **sample count at 48 kHz**, not in milliseconds — because CELT has a 2.5 ms slot, which cannot be represented as an integer number of milliseconds. The 48 kHz sample count matches the unit used by Ogg granules.
 */
data class OpusTocInfo(
    val config: Int,
    val mode: OpusMode,
    val bandwidth: OpusBandwidth,
    /** `s` bit: true = stereo. */
    val stereo: Boolean,
    /** `c` bits: 0…3, see RFC 6716 §3.2. */
    val frameCountCode: Int,
    val frameDurationUs: Int,
) {
    /** Sample count of a single frame at 48 kHz — the step size of an Ogg granule. */
    val frameSamples48k: Int get() = frameDurationUs * 48 / 1000
}

/** Frame‑length options (in microseconds) for the SILK / Hybrid sections. */
private val SILK_HYBRID_US = intArrayOf(10_000, 20_000, 40_000, 60_000)

/** Frame‑length options (in microseconds) for the CELT sections — starting at 2.5 ms. */
private val CELT_US = intArrayOf(2_500, 5_000, 10_000, 20_000)

/**
 * Decode a TOC byte.
 *
 * @param toc Only the low 8 bits are considered (when passing an element extracted from a `ByteArray`, remember to use `.toInt() and 0xff`).
 */
fun parseOpusToc(toc: Int): OpusTocInfo {
    val b = toc and 0xff
    val config = b ushr 3
    val stereo = (b ushr 2) and 1 == 1
    val code = b and 0x03

    val (mode, bandwidth) = when (config) {
        in 0..3 -> OpusMode.SILK_ONLY to OpusBandwidth.NB
        in 4..7 -> OpusMode.SILK_ONLY to OpusBandwidth.MB
        in 8..11 -> OpusMode.SILK_ONLY to OpusBandwidth.WB
        in 12..13 -> OpusMode.HYBRID to OpusBandwidth.SWB
        in 14..15 -> OpusMode.HYBRID to OpusBandwidth.FB
        in 16..19 -> OpusMode.CELT_ONLY to OpusBandwidth.NB
        in 20..23 -> OpusMode.CELT_ONLY to OpusBandwidth.WB
        in 24..27 -> OpusMode.CELT_ONLY to OpusBandwidth.SWB
        else -> OpusMode.CELT_ONLY to OpusBandwidth.FB   // 28…31
    }

    val durationUs = when (mode) {
        // SILK segment groups configs in sets of 4; Hybrid has only 10/20 ms options (config 12/14 = 10 ms, 13/15 = 20 ms)
        OpusMode.SILK_ONLY -> SILK_HYBRID_US[config % 4]
        OpusMode.HYBRID -> SILK_HYBRID_US[config % 2]
        OpusMode.CELT_ONLY -> CELT_US[config % 4]
    }

    return OpusTocInfo(config, mode, bandwidth, stereo, code, durationUs)
}

/** [opusPacketFrameCount] throws this when it cannot determine the frame count. */
class OpusPacketError(val kind: Kind, message: String) : IllegalArgumentException(message) {
    enum class Kind { EMPTY, MISSING_FRAME_COUNT_BYTE, ZERO_FRAME_COUNT }
}

/**
 * How many frames are in an Opus packet — RFC 6716 §3.2.
 *
 * - code 0 → 1 frame
 * - code 1 / 2 → 2 frames
 * - code 3 → the frame count is given by the `M` field in the byte following the TOC (§3.2.5, **bit 0 = v (VBR), bit 1 = p (padding), bit 2‑7 = M**, i.e., M occupies the low 6 bits). The RFC explicitly states: **"M MUST NOT be zero"**, so M = 0 is treated as an illegal packet.
 */
fun opusPacketFrameCount(packet: ByteArray): Int {
    if (packet.isEmpty()) {
        throw OpusPacketError(OpusPacketError.Kind.EMPTY, "Empty packet has no TOC byte")
    }
    return when (packet[0].toInt() and 0x03) {
        0 -> 1
        1, 2 -> 2
        else -> {
            if (packet.size < 2) {
                throw OpusPacketError(
                    OpusPacketError.Kind.MISSING_FRAME_COUNT_BYTE,
                    "a code 3 packet must carry a second byte holding the frame count",
                )
            }
            val m = packet[1].toInt() and 0x3f
            if (m == 0) {
                throw OpusPacketError(
                    OpusPacketError.Kind.ZERO_FRAME_COUNT,
                    "M must not be 0 for code 3 (RFC 6716 §3.2.5: \"M MUST NOT be zero\")",
                )
            }
            m
        }
    }
}
