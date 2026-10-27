package com.nomily.app.core.audio

/**
 * Raw OPUS frames → Ogg‑Opus container.
 *
 * Recordings stored on the device are **continuously concatenated raw OPUS frames without a container**: each frame is a fixed 40 bytes,
 * and they must be wrapped in Ogg to be playable by standard players.
 *
 * In a real‑device 250‑frame clip, the first byte has only two possible values (both are config 9 = **SILK‑only / WB 16kHz / 20ms / mono**, see [parseOpusToc]):
 *
 * | First byte | Count | `c` | Meaning |
 * |---|---|---|---|
 * | `0x4b` | 197 frames | 3 | Next byte `0x01` → M=1, one frame |
 * | `0x48` | 53 frames | 0 | code 0, one frame |
 *
 * Both correspond to **one 20 ms frame**, so 40 bytes = 20 ms holds.
 *
 * > ⚠️ `0x4b >> 3 = 9` falls in 8…11 = **WB (16kHz)**, not MB (12kHz): an earlier
 * > bandwidth label in this file was wrong; the frame length is unchanged.
 *
 * ## Why `serial` is an explicit parameter
 *
 * According to RFC 3533, an Ogg `serial` is only used to distinguish **multiple logical streams multiplexed within the same physical stream**.
 * Device recordings are independent single‑stream files and never multiplexed → **uniqueness has zero value for us; any value will play correctly**.
 *
 * Thus `serial` here is **entropy**, so it is made a parameter rather than an internal constant: the caller pins a fixed value,
 * and the same clip must be converted twice to obtain byte‑identical files.
 *
 * > ⚠️ Deriving the serial from the file name would make the same clip convert to
 * > **byte‑different files** across runs, breaking checksums, deduplication and resumable
 * > uploads. The serial is supplied by the caller instead.
 */

/** Bytes per frame (fixed). */
const val OPUS_FRAME_SIZE = 40

/**
 * Frame duration (milliseconds).
 *
 * This 20 is **no longer a copy‑paste of a copied constant** — it equals the frame length derived from the device data's TOC byte according to RFC 6716 §3.1 Table 2
 * (config 9 → 20 ms), with [parseOpusToc] providing the authoritative source and `OpusTocTest` continuously guarding it.
 */
const val OPUS_FRAME_DURATION_MS = 20

/** Ogg‑Opus always declares 48 kHz. */
const val OPUS_SAMPLE_RATE = 48_000

const val OPUS_CHANNELS = 1

/** Standard Opus encoder preskip. */
const val OPUS_PRESKIP = 312

/** Number of frames per page (= 1 second). */
private const val FRAMES_PER_PAGE = 50

/**
 * 20 ms @ 48 kHz.
 *
 * ⚠️ **Intentionally written as a constant, not computed per packet TOC.** Computing on the fly would be “more correct”, but the correctness criterion here is
 * **byte‑identical output across clients**: 960 is fixed, and computing it from the TOC
 * would make any non‑20 ms input produce a different file on each side. The 20 ms premise
 * is independently verified by [parseOpusToc].
 */
private const val SAMPLES_PER_FRAME = 960

/** Default serial, ASCII `"DNOT"`. */
const val DNOTE_OGG_SERIAL = 0x444E4F54

/**
 * Wrap concatenated raw OPUS frames into Ogg‑Opus bytes.
 *
 * A trailing partial frame is discarded; if not even one full frame is present, an empty
 * array is returned.
 */
fun rawOpusToOggBytes(raw: ByteArray, serial: Int = DNOTE_OGG_SERIAL): ByteArray {
    val numFrames = raw.size / OPUS_FRAME_SIZE
    if (numFrames == 0) return ByteArray(0)

    val out = java.io.ByteArrayOutputStream()
    var pageSeq = 0

    // OpusHead page (BOS)
    val opusHead = ByteArray(19).also { h ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[8] = 1                                    // version
        h[9] = OPUS_CHANNELS.toByte()
        h.putLeU16(10, OPUS_PRESKIP)
        h.putLeU32(12, OPUS_SAMPLE_RATE)
        h.putLeU16(16, 0)                           // output gain
        h[18] = 0                                   // channel mapping family
    }
    out.write(makeOggPage(serial, pageSeq++, 0L, listOf(opusHead), bos = true))

    // OpusTags page
    val vendor = "dnote".toByteArray(Charsets.US_ASCII)
    val opusTags = ByteArray(8 + 4 + vendor.size + 4).also { t ->
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(t, 0)
        t.putLeU32(8, vendor.size)
        vendor.copyInto(t, 12)
        t.putLeU32(12 + vendor.size, 0)             // User comment count = 0
    }
    out.write(makeOggPage(serial, pageSeq++, 0L, listOf(opusTags)))

    // Audio page
    var granule = OPUS_PRESKIP.toLong()
    var i = 0
    while (i < numFrames) {
        val end = minOf(i + FRAMES_PER_PAGE, numFrames)
        val batch = ArrayList<ByteArray>(end - i)
        for (j in i until end) {
            batch.add(raw.copyOfRange(j * OPUS_FRAME_SIZE, (j + 1) * OPUS_FRAME_SIZE))
            granule += SAMPLES_PER_FRAME
        }
        val isLast = end >= numFrames
        out.write(makeOggPage(serial, pageSeq++, granule, batch, eos = isLast))
        i = end
    }
    return out.toByteArray()
}

/**
 * For real‑time streams: **only emit two header pages** (OpusHead + OpusTags).
 *
 * Azure's streaming interface requires the container header before it can decode, and subsequent audio pages are appended one by one via [oggAudioPage].
 * The header matches **byte‑for‑byte** what [rawOpusToOggBytes] writes (same set of constants, same default serial).
 */
fun oggHeaders(serial: Int = DNOTE_OGG_SERIAL): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val opusHead = ByteArray(19).also { h ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[8] = 1
        h[9] = OPUS_CHANNELS.toByte()
        h.putLeU16(10, OPUS_PRESKIP)
        h.putLeU32(12, OPUS_SAMPLE_RATE)
        h.putLeU16(16, 0)
        h[18] = 0
    }
    out.write(makeOggPage(serial, 0, 0L, listOf(opusHead), bos = true))
    val vendor = "dnote".toByteArray(Charsets.US_ASCII)
    val opusTags = ByteArray(8 + 4 + vendor.size + 4).also { t ->
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(t, 0)
        t.putLeU32(8, vendor.size)
        vendor.copyInto(t, 12)
        t.putLeU32(12 + vendor.size, 0)
    }
    out.write(makeOggPage(serial, 1, 0L, listOf(opusTags)))
    return out.toByteArray()
}

/**
 * For real‑time streams: wrap a number of raw OPUS frames into **a single audio page**.
 *
 * Page sequence number and granule are maintained by the caller (the stream is unbounded, so total frame count cannot be known in advance as with a file).
 */
fun oggAudioPage(serial: Int, pageSeq: Int, granule: Long, frames: List<ByteArray>): ByteArray =
    makeOggPage(serial, pageSeq, granule, frames)

/** Samples per frame (48 kHz, 20 ms) — used when maintaining granule in real‑time streams. */
const val OPUS_SAMPLES_PER_FRAME = 960

/**
 * Read duration (milliseconds) from an Ogg‑Opus container **without decoding audio** — by locating the granule position of the last page.
 *
 * Read the granule of the final page to obtain the duration,
 * used for the database duration chip and minimum‑duration gating. It is orders of magnitude faster than decoding and requires no audio library.
 *
 * The meaning of granule: **the number of encoded samples up to the end of that page**, counted at 48 kHz. When we write, we start from
 * [OPUS_PRESKIP] and add 960 per frame, so
 * `duration = (last page granule − preskip) / 48` milliseconds.
 *
 * If the file is not a valid Ogg (no `OggS` pages) → return null, do not throw an exception:
 * the database may contain arbitrary user‑uploaded files, and when duration cannot be obtained we should degrade gracefully rather than crash the screen.
 */
fun oggDurationMs(ogg: ByteArray): Int? {
    var lastGranule: Long? = null
    var i = 0
    while (i + 27 <= ogg.size) {
        if (ogg[i] != 0x4F.toByte() || ogg[i + 1] != 0x67.toByte() ||
            ogg[i + 2] != 0x67.toByte() || ogg[i + 3] != 0x53.toByte()
        ) {
            i++                                  // Not a page header, move back one byte and continue searching
            continue
        }
        val segCount = ogg[i + 26].toInt() and 0xFF
        val tableEnd = i + 27 + segCount
        if (tableEnd > ogg.size) break           // Header truncated, stop here
        var body = 0
        for (k in 0 until segCount) body += ogg[i + 27 + k].toInt() and 0xFF
        lastGranule = leI64(ogg, i + 6)
        i = tableEnd + body                      // Jump to next page
    }
    return granuleToMs(lastGranule ?: return null)
}

/**
 * Same as [oggDurationMs], but only examines a **trailing segment of the file**.
 *
 * Since duration depends only on the granule of the final page, there is no need to read the entire file into memory just to obtain a duration —
 * with 1,890 entries and 25 GB in the database, “reading the whole file for each entry” accounts for the majority of the initial 14 seconds of the first screen.
 * Here we scan backward from the end of [tail] to find the last page header and read its granule.
 *
 * ⚠️ The four bytes `OggS` may also appear in audio data, so their mere presence is not sufficient. We additionally validate the Ogg page header fields per RFC 3533:
 * version (offset 4) must be 0, and header_type (offset 5) may only have the lower three bits set.
 * This is not an absolute guarantee, but combined with “searching backward from the last page” it is sufficient —
 * if uncertain, return null and let the caller fall back to the full‑file [oggDurationMs].
 *
 * **Caller responsibility**: the buffer must be large enough to contain the entire last page. An Ogg page is at most ~64 KB, so providing ≥128 KB guarantees the page header is included;
 * if the window is smaller than the last page, this function can only return null, and the caller must then fall back to the full‑file [oggDurationMs].
 * The test case `tail parse yields null when the window is
 * smaller than the last page` enforces this condition.
 *
 * @param tail Continuous bytes from the end of the file
 */
fun oggDurationMsFromTail(tail: ByteArray): Int? {
    var i = tail.size - 27
    while (i >= 0) {
        if (tail[i] == 0x4F.toByte() && tail[i + 1] == 0x67.toByte() &&
            tail[i + 2] == 0x67.toByte() && tail[i + 3] == 0x53.toByte() &&
            tail[i + 4] == 0.toByte() &&                       // stream_structure_version
            (tail[i + 5].toInt() and 0xF8) == 0                // header_type: only the lower three bits are defined
        ) {
            val segCount = tail[i + 26].toInt() and 0xFF
            if (i + 27 + segCount <= tail.size) {
                return granuleToMs(leI64(tail, i + 6))
            }
        }
        i--
    }
    return null
}

/** granule (48 kHz sample count) → milliseconds. When writing, it starts from [OPUS_PRESKIP], so subtract that first. */
private fun granuleToMs(granule: Long): Int {
    val samples = granule - OPUS_PRESKIP
    if (samples <= 0) return 0
    return (samples / (OPUS_SAMPLE_RATE / 1000)).toInt()
}

private fun leI64(b: ByteArray, offset: Int): Long {
    var v = 0L
    for (k in 7 downTo 0) v = (v shl 8) or (b[offset + k].toLong() and 0xFF)
    return v
}

/** Recording duration (milliseconds), calculated from frame count — independent of the container. */
fun rawOpusDurationMs(raw: ByteArray): Int =
    (raw.size / OPUS_FRAME_SIZE) * OPUS_FRAME_DURATION_MS

/**
 * Assemble an Ogg page.
 *
 * Header is 27 bytes: `OggS`(4) + version(1) + flags(1) + granule(8, little‑endian signed) +
 * serial(4) + pageSeq(4) + crc(4) + segCount(1), followed by the segment table and segment data.
 * CRC is computed over the entire page with the checksum field zeroed, then written back at offset 22.
 */
private fun makeOggPage(
    serial: Int,
    pageSeq: Int,
    granule: Long,
    segments: List<ByteArray>,
    bos: Boolean = false,
    eos: Boolean = false,
): ByteArray {
    require(segments.size in 1..255) { "A page can only have 1..255 segments, got ${segments.size}" }
    segments.forEach {
        // Each frame is 40 bytes; this should never trigger. If it does, it indicates the frame‑size assumption changed, and we prefer to crash rather than silently produce a corrupt container
        require(it.size <= 255) { "Segment length must be ≤255 (Ogg lacing), actual: ${it.size}" }
    }

    var flag = 0
    if (bos) flag = flag or 0x02
    if (eos) flag = flag or 0x04

    val bodySize = segments.sumOf { it.size }
    val page = ByteArray(27 + segments.size + bodySize)
    "OggS".toByteArray(Charsets.US_ASCII).copyInto(page, 0)
    page[4] = 0                                     // version
    page[5] = flag.toByte()
    page.putLeI64(6, granule)
    page.putLeU32(14, serial)
    page.putLeU32(18, pageSeq)
    page.putLeU32(22, 0)                            // CRC placeholder
    page[26] = segments.size.toByte()

    var p = 27
    for (s in segments) page[p++] = s.size.toByte()  // Segment table
    for (s in segments) { s.copyInto(page, p); p += s.size }

    page.putLeU32(22, oggCrc(page))
    return page
}

/**
 * Ogg's CRC‑32 uses polynomial `0x04C11DB7`, **without bit reflection, initial value 0, and no final XOR**
 * — it differs from the common zlib CRC‑32.
 */
internal fun oggCrc(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xFF) shl 24)
        repeat(8) {
            crc = if (crc and 0x8000_0000.toInt() != 0) {
                (crc shl 1) xor 0x04C1_1DB7
            } else {
                crc shl 1
            }
        }
    }
    return crc
}

private fun ByteArray.putLeU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putLeU32(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun ByteArray.putLeI64(offset: Int, value: Long) {
    for (k in 0 until 8) this[offset + k] = ((value ushr (8 * k)) and 0xFF).toByte()
}
