package com.nomily.app.core.crypto

/**
 * Raw ChaCha20 keystream (RFC 8439 §2.3–2.4) — pure Kotlin, zero `android.*`, zero third-party dependencies.
 *
 * Why implement it ourselves instead of using `javax.crypto`: the JCE algorithm name `ChaCha20` requires **Android API 28+**,
 * whereas `:app`'s minSdk is 24; moreover, JCE's `ChaCha20` only exposes AEAD/counter-wrapped variants,
 * which do not align with the "raw ChaCha20 + hand-written verify block, no Poly1305" path used here.
 * This implementation is pure arithmetic, with every bit validated against the RFC 8439 §2.4.2 standard vectors.
 *
 * The counter is explicitly exposed as a parameter rather than folded into the nonce.
 */

private const val KEY_LENGTH = 32
private const val NONCE_LENGTH = 12
private const val BLOCK_LENGTH = 64

// "expand 32-byte k"
private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

/**
 * Performs ChaCha20 encryption/decryption on [data] (stream cipher, same operation for both directions).
 *
 * @param key     32 bytes
 * @param nonce   12 bytes
 * @param counter Starting block counter (increments per block, 32-bit natural wraparound)
 */
fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray {
    require(key.size == KEY_LENGTH) { "key must be $KEY_LENGTH bytes, got ${key.size}" }
    require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes, got ${nonce.size}" }

    val out = ByteArray(data.size)
    val keystream = ByteArray(BLOCK_LENGTH)
    var blockCounter = counter
    var offset = 0

    while (offset < data.size) {
        chacha20Block(key, nonce, blockCounter, keystream)
        val n = minOf(BLOCK_LENGTH, data.size - offset)
        for (i in 0 until n) {
            out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
        }
        offset += n
        blockCounter++
    }
    return out
}

/** Generate a 64-byte keystream block and write it to [dest]. */
private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int, dest: ByteArray) {
    val state = IntArray(16)
    SIGMA.copyInto(state, 0)
    for (i in 0 until 8) state[4 + i] = key.leU32(i * 4)
    state[12] = counter
    for (i in 0 until 3) state[13 + i] = nonce.leU32(i * 4)

    val work = state.copyOf()
    repeat(10) {                       // 20 rounds = 10 double rounds
        quarterRound(work, 0, 4, 8, 12)
        quarterRound(work, 1, 5, 9, 13)
        quarterRound(work, 2, 6, 10, 14)
        quarterRound(work, 3, 7, 11, 15)
        quarterRound(work, 0, 5, 10, 15)
        quarterRound(work, 1, 6, 11, 12)
        quarterRound(work, 2, 7, 8, 13)
        quarterRound(work, 3, 4, 9, 14)
    }

    for (i in 0 until 16) {
        val v = work[i] + state[i]
        dest.putLeU32(i * 4, v)
    }
}

private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
    x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
    x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
    x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
}

private fun ByteArray.leU32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.putLeU32(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}
