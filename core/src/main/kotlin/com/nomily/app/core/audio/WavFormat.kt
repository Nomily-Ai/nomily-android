package com.nomily.app.core.audio

/**
 * 16 kHz mono 16-bit PCM in a canonical 44-byte WAV container (the phone-microphone recording format).
 *
 * The file is opened with a header whose sizes are 0 and re-written with [header] once the PCM length is known;
 * the live-caption stream to Azure sends [header] with `pcmBytes = null` (unknown length) as its first packet.
 */
object WavFormat {

    const val HEADER_SIZE = 44
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    private const val UNKNOWN = 0xFFFF_FFFFL

    /** @param pcmBytes length of the data chunk; `null` = still streaming (both size fields 0xFFFFFFFF). */
    fun header(pcmBytes: Long?): ByteArray {
        val data = pcmBytes?.coerceIn(0L, UNKNOWN - 36) ?: UNKNOWN
        val riff = if (pcmBytes == null) UNKNOWN else 36 + data
        val out = ByteArray(HEADER_SIZE)
        var p = 0
        fun ascii(s: String) { s.forEach { out[p++] = it.code.toByte() } }
        fun le16(v: Int) { out[p++] = v.toByte(); out[p++] = (v shr 8).toByte() }
        fun le32(v: Long) { for (i in 0 until 4) out[p++] = (v shr (8 * i)).toByte() }
        ascii("RIFF"); le32(riff); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(SAMPLE_RATE.toLong()); le32(BYTES_PER_SECOND.toLong()); le16(2); le16(16)
        ascii("data"); le32(data)
        return out
    }

    /** PCM16 little-endian bytes → samples (a trailing odd byte is ignored). */
    fun samples(pcm: ByteArray, length: Int = pcm.size): ShortArray {
        val n = length / 2
        return ShortArray(n) { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() }
    }

    /** Seconds of audio in [pcmBytes] bytes. */
    fun seconds(pcmBytes: Long): Double = pcmBytes.toDouble() / BYTES_PER_SECOND
}
