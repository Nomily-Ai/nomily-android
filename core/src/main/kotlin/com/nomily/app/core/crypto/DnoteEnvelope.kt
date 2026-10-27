package com.nomily.app.core.crypto

/**
 * Envelope parsing for encrypted recording files (firmware v1.50+).
 *
 * The key is derived on the host side via Argon2id(passphrase, salt=SN) — **outside the scope of this module**;
 * this code only consumes an existing 32‑byte key. (Feasibility of Argon2 512 MiB on target devices is a separate spike.)
 *
 * The decrypted plaintext consists of **raw OPUS frames** (no Ogg container); they must be re‑encapsulated before playback.
 */

/** magic: ASCII `encryt` (note: six letters, not `encrypt`). */
val ENVELOPE_MAGIC: ByteArray = byteArrayOf(0x65, 0x6E, 0x63, 0x72, 0x79, 0x74)

const val ENVELOPE_HEADER_LENGTH = 60   // magic(6) + sn(18) + nonce(12) + verify(24)
const val ENVELOPE_SN_OFFSET = 6
const val ENVELOPE_SN_LENGTH = 18
const val ENVELOPE_NONCE_OFFSET = 24
const val ENVELOPE_NONCE_LENGTH = 12
const val ENVELOPE_VERIFY_OFFSET = 36
const val ENVELOPE_VERIFY_LENGTH = 24

/**
 * Encrypted segment cannot be decrypted. **[kind] is the unique stable semantic identifier**; callers should branch on it and not parse the message — the text is for humans and may change.
 *
 * [Kind.name] is a stable semantic identifier and can be used directly as an error code.
 */
class DecryptError(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind { TOO_SHORT, BAD_MAGIC, VERIFY_MISMATCH }
}

/** Cheap check that looks only at the magic; no key required. */
fun isEncrypted(head: ByteArray): Boolean {
    if (head.size < ENVELOPE_MAGIC.size) return false
    for (i in ENVELOPE_MAGIC.indices) if (head[i] != ENVELOPE_MAGIC[i]) return false
    return true
}

/**
 * Decrypt a segment of encrypted recording and return raw OPUS plaintext.
 *
 * Incorrect magic, insufficient length, or mismatched verify block (i.e., wrong key / device key changed) → [DecryptError].
 */
fun decryptBytes(buf: ByteArray, key: ByteArray): ByteArray {
    if (buf.size < ENVELOPE_HEADER_LENGTH) {
        throw DecryptError(
            DecryptError.Kind.TOO_SHORT,
            "too short (${buf.size} bytes, need ≥ $ENVELOPE_HEADER_LENGTH)",
        )
    }
    if (!isEncrypted(buf)) {
        throw DecryptError(
            DecryptError.Kind.BAD_MAGIC,
            "bad magic: 0x${buf.copyOfRange(0, ENVELOPE_MAGIC.size).toHexUpper()}",
        )
    }

    val header = buf.copyOfRange(0, ENVELOPE_NONCE_OFFSET)      // magic + sn
    val nonce = buf.copyOfRange(ENVELOPE_NONCE_OFFSET, ENVELOPE_NONCE_OFFSET + ENVELOPE_NONCE_LENGTH)
    val body = buf.copyOfRange(ENVELOPE_VERIFY_OFFSET, buf.size)  // verify block + audio, continuous

    val plain = chacha20(key, nonce, counter = 0, data = body)

    if (!plain.copyOfRange(0, ENVELOPE_NONCE_OFFSET).contentEquals(header)) {
        throw DecryptError(
            DecryptError.Kind.VERIFY_MISMATCH,
            "verify-block mismatch (wrong key, or device was re-keyed)",
        )
    }
    return plain.copyOfRange(ENVELOPE_NONCE_OFFSET, plain.size)
}

private const val HEX_UPPER = "0123456789ABCDEF"

private fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_UPPER[v ushr 4]).append(HEX_UPPER[v and 0x0F])
    }
    return sb.toString()
}
