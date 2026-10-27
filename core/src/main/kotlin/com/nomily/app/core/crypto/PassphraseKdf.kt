package com.nomily.app.core.crypto

/**
 * Derivation of a 32‑byte ChaCha20 key from a passphrase.
 *
 * **This layer belongs to us and must be machine‑verified; the Argon2 primitive itself is not ours** (RFC 9106 provides official test vectors).
 * Therefore the primitive is injected via [Argon2idProvider]:
 *
 *  - Desktop JVM injects Bouncy Castle (heap can be enlarged, handles 512 MiB)
 *  - Android injects argon2kt (NDK, off‑heap memory)
 *
 * Why this split is required: a spike on real devices on 2026‑07‑30 showed that pure‑JVM Argon2 on Android **always OOMs** — the single‑process Java heap limit is 256 MB (even `largeHeap` is only 512 MB), while the block matrix itself needs 512 MiB.
 *
 * ⚠️ **Parameters are locked in firmware and cannot be reduced to save memory** — the key stored on the device is derived from this exact set; reducing them would make the real‑device file unreadable.
 */

/** Length of the 32‑byte key. */
const val DERIVED_KEY_LENGTH = 32

/** Argon2id parameters. */
const val ARGON2_TIME_COST = 4
const val ARGON2_MEMORY_COST_KIB = 512 * 1024      // 512 MiB
const val ARGON2_PARALLELISM = 1

/**
 * Injection point for the Argon2id primitive. Implementations only perform the computation; **all parameter validation and encoding are handled in [deriveKey]**.
 */
interface Argon2idProvider {
    /** Must be Argon2**id**, version 1.3 (0x13). Returns the raw output of [hashLength] bytes. */
    fun hash(
        password: ByteArray,
        salt: ByteArray,
        tCostInIterations: Int,
        mCostInKibibyte: Int,
        parallelism: Int,
        hashLength: Int,
    ): ByteArray
}

/**
 * Derivation failed. [kind] is a stable semantic identifier (also used as `error.code` in the protocol), and the message is for human consumption — **do not parse it**.
 */
class KdfError(
    val kind: Kind,
    message: String,
) : Exception(message) {
    enum class Kind { EMPTY_PASSPHRASE, EMPTY_SN, NON_ASCII_SN }
}

/**
 * `Argon2id(passphrase_utf8, salt = sn_ascii) → 32‑byte key`.
 *
 * Encoding: **passphrase uses UTF‑8, SN uses ASCII**.
 *
 * ⚠️ **If SN is non‑ASCII, an error is thrown rather than silently replaced.** Kotlin’s `toByteArray(US_ASCII)` would replace non‑ASCII characters with `?`, producing a different key with no error — a hard‑to‑detect issue. This implementation explicitly rejects such input.
 */
fun deriveKey(passphrase: String, sn: String, argon2: Argon2idProvider): ByteArray {
    if (passphrase.isEmpty()) {
        throw KdfError(KdfError.Kind.EMPTY_PASSPHRASE, "passphrase must not be empty")
    }
    if (sn.isEmpty()) {
        throw KdfError(KdfError.Kind.EMPTY_SN, "device SN must not be empty")
    }

    val salt = ByteArray(sn.length)
    for (i in sn.indices) {
        val c = sn[i]
        if (c.code > 0x7F) {
            throw KdfError(
                KdfError.Kind.NON_ASCII_SN,
                "device SN must be ASCII (offset $i: U+%04X)".format(c.code),
            )
        }
        salt[i] = c.code.toByte()
    }

    val key = argon2.hash(
        password = passphrase.toByteArray(Charsets.UTF_8),
        salt = salt,
        tCostInIterations = ARGON2_TIME_COST,
        mCostInKibibyte = ARGON2_MEMORY_COST_KIB,
        parallelism = ARGON2_PARALLELISM,
        hashLength = DERIVED_KEY_LENGTH,
    )
    check(key.size == DERIVED_KEY_LENGTH) {
        "Argon2 provider returned ${key.size} bytes, expected $DERIVED_KEY_LENGTH"
    }
    return key
}
