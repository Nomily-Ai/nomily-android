package com.nomily.app.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.nomily.app.core.crypto.Argon2idProvider

/**
 * Argon2id primitive on Android — **this is the production implementation**.
 *
 * Using the NDK is forced, not preferred: on a real device (2026-07-30) pure JVM (Bouncy Castle) with
 * 512 MiB parameters **always OOMs** (single‑process Java heap limit 256 MB, `largeHeap` only 512 MB,
 * and the block matrix itself requires 512 MiB). argon2kt runs off‑heap and completes in 1636 ms.
 *
 * ⚠️ This path has no automated consistency gate — **if you modify this class or change the argon2kt version,
 * you must manually verify against the RFC 9106 official vectors byte‑by‑byte.**
 *
 * argon2kt 1.6.0: minSdk 21 (below this project's 24), ABI coverage
 * arm64-v8a / armeabi-v7a / x86 / x86_64.
 */
class Argon2KtProvider(
    private val argon2Kt: Argon2Kt = Argon2Kt(),
) : Argon2idProvider {

    override fun hash(
        password: ByteArray,
        salt: ByteArray,
        tCostInIterations: Int,
        mCostInKibibyte: Int,
        parallelism: Int,
        hashLength: Int,
    ): ByteArray = argon2Kt.hash(
        mode = Argon2Mode.ARGON2_ID,
        password = password,
        salt = salt,
        tCostInIterations = tCostInIterations,
        mCostInKibibyte = mCostInKibibyte,
        parallelism = parallelism,
        hashLengthInBytes = hashLength,
    ).rawHashAsByteArray()
}
