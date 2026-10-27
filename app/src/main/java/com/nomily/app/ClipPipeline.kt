package com.nomily.app

import android.content.Context
import android.util.Log
import com.nomily.app.ble.DnoteBleClient
import com.nomily.app.core.audio.rawOpusDurationMs
import com.nomily.app.core.audio.rawOpusToOggBytes
import com.nomily.app.core.crypto.DecryptError
import com.nomily.app.core.crypto.decryptBytes
import com.nomily.app.core.crypto.deriveKey
import com.nomily.app.core.crypto.isEncrypted
import com.nomily.app.crypto.Argon2KtProvider
import java.io.File

/**
 * Post‑download processing: **BLE download → decrypt (if needed) → wrap Ogg → write to storage**.
 *
 * The middle two steps are fully handled in `:core` and have unit tests; this layer only orchestrates and performs file I/O.
 *
 * Whether the device encrypts is answered by `0xA3`, **no guessing**:
 * if the device reports no encryption, do not try to decrypt with an old key,
 * treating plaintext as ciphertext yields garbage. Here we inspect the magic first before deciding.
 */
class ClipPipeline(private val context: Context) {

    companion object {
        private const val TAG = "ClipPipeline"
    }

    class Result(
        val oggFile: File,
        val wasEncrypted: Boolean,
        val rawBytes: Int,
        val durationMs: Int,
    )

    sealed class Stage(val label: String) {
        data object Downloading : Stage("Downloading…")
        data object Deriving : Stage("Deriving key…")
        data object Decrypting : Stage("Decrypting…")
        data object Packaging : Stage("Packaging Ogg…")
    }

    /** Derive once and cache — 512 MiB / 1.6 s, cannot recompute for every file. */
    private val keyCache = HashMap<String, ByteArray>()

    fun cachedKeyFor(sn: String): ByteArray? = keyCache[sn]

    /** Forget the cached key in memory (used by the settings page "Clear local key"; the Keystore entry is cleared by the caller). */
    fun forgetCachedKey(sn: String) {
        keyCache.remove(sn)?.fill(0)
    }

    /**
     * Insert an already‑available key into the cache (used when retrieving from Keystore), **skipping the 1.6 s Argon2**.
     *
     * This is a separate entry point rather than letting callers write directly to [keyCache]: the passphrase should never leave [deriveAndCache],
     * and this path has no passphrase at all — the two concerns should not share a single entry point.
     */
    fun seedCachedKey(sn: String, key: ByteArray) {
    require(key.size == 32) { "key must be 32 bytes" }
        keyCache[sn] = key
    }

    /** Passphrase → key. Takes about 1.6 s; callers should run it in the background and show progress messages like "Deriving key…". */
    fun deriveAndCache(passphrase: String, sn: String): ByteArray =
        deriveKey(passphrase, sn, Argon2KtProvider()).also { keyCache[sn] = it }

    /**
     * Retrieve a recording and write it as a playable `.ogg`.
     *
     * @param key Must be provided when the device encrypts; ignored if the file is not an encrypted envelope.
     */
    suspend fun fetch(
        client: DnoteBleClient,
        file: DnoteBleClient.DeviceFile,
        sn: String,
        key: ByteArray?,
        onStage: (Stage, Int, Int) -> Unit = { _, _, _ -> },
    ): Result {
        onStage(Stage.Downloading, 0, file.size)
        val blob = client.downloadFile(file.name, file.size) { got, total ->
            onStage(Stage.Downloading, got, total)
        }
    Log.i(TAG, "Download completed ${file.name} ${blob.size}B")
        return ingestRaw(blob, file.name, key, onStage)
    }

    /**
     * Store already‑obtained bytes — decrypt → wrap Ogg → write to storage.
     *
     * Extracted because fast transfer (Wi‑Fi TCP) and BLE download differ only in "how the bytes are obtained",
     * while the subsequent three steps must be **identical**: files produced by both paths must be interchangeable,
     * otherwise the same recording transferred via different channels would yield different results, causing mismatches later.
     */
    fun ingestRaw(
        blob: ByteArray,
        deviceFileName: String,
        key: ByteArray?,
        onStage: (Stage, Int, Int) -> Unit = { _, _, _ -> },
    ): Result {
        val encrypted = isEncrypted(blob)
        val raw = if (!encrypted) {
 Log.i(TAG, "$deviceFileName is not an encrypted envelope (no encrypt magic), processing as plaintext")
            blob
        } else {
            requireNotNull(key) { "File is encrypted but no key – set passphrase first" }
            onStage(Stage.Decrypting, 0, 0)
            try {
                decryptBytes(blob, key)
            } catch (e: DecryptError) {
                // kind is a stable identifier; do not parse message
                Log.w(TAG, "Decryption failed kind=${e.kind}: ${e.message}")
                throw e
            }
        }

        onStage(Stage.Packaging, 0, 0)
        val ogg = rawOpusToOggBytes(raw)
        val outDir = File(context.filesDir, "clips").apply { mkdirs() }
        val out = File(outDir, deviceFileName.removeSuffix(".opus") + ".ogg")
        out.writeBytes(ogg)
        Log.i(TAG, "Saved to disk ${out.absolutePath} (${ogg.size}B, raw frames ${raw.size}B)")

        return Result(
            oggFile = out,
            wasEncrypted = encrypted,
            rawBytes = raw.size,
            durationMs = rawOpusDurationMs(raw),
        )
    }
}
