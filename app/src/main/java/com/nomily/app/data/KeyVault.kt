package com.nomily.app.data

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistence of the derived 32‑byte ChaCha20 key.
 *
 * ## Satisfying PLAN §3.5's R1 / R2 / R3
 *
 * The three requirements are implemented via three distinct mechanisms:
 *
 * | | How Android does it | What guarantees it |
 * |---|---|---|
 * | **R1** Unreadable when the device is locked | `setUnlockedDeviceRequired(true)` (API 28+, requires a lock screen) | System Keystore |
 * | **R2** Separate per SN | **One independent Keystore alias per SN** `dnote-key-<SN>` | Different keys → a key on one device cannot decrypt another device’s data |
 * | **R3** Excluded from cloud backup | Exclude `dnote-keys.xml` in `backup_rules.xml` / `data_extraction_rules.xml` (excludes both cloud backup and direct transfer) | **Build‑time property, not a runtime dependency** |
 *
 * R3 **does not** use `allowBackup=false`; that would disable backup for config, recordings, etc., which is stricter than excluding a single file.
 * Precise exclusion is the correct granularity.
 *
 * ### Why R2 is not “one key encrypts one table”
 *
 * That would be simpler, but the original R2 requirement is “**a key on one device must not decrypt a file from another device**”.
 * When sharing a wrapping key, this guarantee relies solely on “our code picking the correct entry” — a single index error could let device A’s key decrypt device B’s file.
 * With one alias per SN, picking the wrong alias results in **decryption failure** (GCM tag mismatch) rather than incorrect decryption.
 * **Turn errors into failures, not silent successes.**
 *
 * ### R1 has two prerequisites; missing either makes it impossible — state it explicitly rather than pretend
 *
 * 1. **API >= 28**: `setUnlockedDeviceRequired` exists only from API 28, minSdk is 24
 * 2. **A secure lock screen is set** (PIN / pattern / password / biometrics)
 *
 * The second condition was discovered on real devices, not from the docs: on a Pixel 3 / API 31 without a lock screen, a key with `setUnlockedDeviceRequired(true)` cannot even be initialized for encryption — `UserNotAuthenticatedException: User not authenticated`.
 * Thus, on devices without a lock screen this flag does not merely weaken protection; the **entire key becomes unusable**.
 *
 * If left unhandled, the consequence is that users without a lock screen must re‑enter the passphrase and wait ~1.6 s for Argon2 on every app launch, while the log only shows a single `Cipher.init` failure.
 *
 * When no secure lock screen is set, we choose **graceful degradation** — the key is still persisted, but we expose that “R1 is currently not satisfied” via [r1Satisfied] instead of pretending all three requirements are met after degradation.
 *
 * ### We **do not** use `setUserAuthenticationRequired`
 *
 * That would be stricter (requiring fingerprint/password on each key access). Whether to add it is a product decision, not an implementation detail to add casually.
 */
class KeyVault(
    private val context: Context,
    private val prefs: android.content.SharedPreferences,
) {

    companion object {
        private const val TAG = "KeyVault"
        private const val KEYSTORE = "AndroidKeyStore"

        /** Keystore alias prefix — one alias per SN (R2). */
        private const val ALIAS_PREFIX = "dnote-key-"

        /** ChaCha20 key length. */
        const val KEY_LENGTH = 32

        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12

        /** Available from API 28 to express “unreadable when locked”. See the R1 section in the class comment. */
        val unlockedDeviceRequiredSupported: Boolean
            get() = android.os.Build.VERSION.SDK_INT >= 28
    }

    /**
     * Whether this device currently truly satisfies **R1 (key unreadable when locked)**.
     *
     * Both conditions must hold: API >= 28 **and** a secure lock screen is set.
     * Callers should display or log this value — it is a **security posture**, not an implementation detail.
     */
    val r1Satisfied: Boolean get() = unlockedDeviceRequiredSupported && deviceSecure

    private val deviceSecure: Boolean =
        runCatching {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        }.getOrDefault(false)

    /**
     * **Lazy loading is not an optimization; it separates the construction of KeyVault from the use of Keystore.**
     *
     * Originally the property was initialized directly, causing AndroidKeyStore to be opened as soon as NomiViewModel is constructed.
     * In environments without this provider (e.g., JVM Robolectric screenshot tests), the UI cannot even render, throwing `KeyStoreException: AndroidKeyStore not found` — even though those screens never touch a key.
     * On real devices behavior is unchanged: the same Keystore is opened on the first load/save/clear, and any failure is thrown at that moment.
     */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private fun alias(sn: String) = ALIAS_PREFIX + sn
    private fun prefKey(sn: String) = "wrapped-$sn"

    /**
     * Retrieve the cached key for this device; returns null if none.
     *
     * When the screen is locked (with R1 in effect) the Keystore will refuse decryption — we **do not masquerade this as “never stored”**; instead we log the event, return null, and **retain the ciphertext**.
     * If null is returned we delete the entry; a single lock‑screen read failure would otherwise cause the user to permanently lose the key.
     */
    fun load(sn: String): ByteArray? {
        val wrapped = prefs.getString(prefKey(sn), null) ?: return null
        val secret = existingSecret(sn) ?: run {
            Log.w(TAG, "SN=$sn has ciphertext but the Keystore alias is missing (App data was cleared or the key is invalid)")
            return null
        }
        return try {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            require(blob.size > GCM_IV_BYTES) { "Ciphertext is too short" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
                .takeIf { it.size == KEY_LENGTH }
        } catch (e: Exception) {
            // The most common case: screen locked (R1). **Do not delete the ciphertext** — it must be readable after unlocking.
            Log.w(TAG, "Key for SN=$sn could not be read (possibly due to screen lock): ${e::class.simpleName}")
            null
        }
    }

    /**
     * Store the key for this device. Storing again for the same SN overwrites.
     *
     * If the user **disables the lock screen after a key has been stored**, the previously created key with `setUnlockedDeviceRequired` becomes permanently unusable.
     * We **re‑create it once and retry** — otherwise the key can never be stored again, and the symptom is merely “the passphrase was entered for nothing”.
     */
    fun save(sn: String, key: ByteArray) {
        require(key.size == KEY_LENGTH) { "key must be $KEY_LENGTH bytes, got ${key.size}" }
        try {
            encryptAndStore(sn, key, existingSecret(sn) ?: createSecret(sn))
        } catch (e: java.security.InvalidKeyException) {
            // UserNotAuthenticatedException / KeyPermanentlyInvalidatedException are both subclasses of it
            Log.w(TAG, "Packaging key for SN=$sn is unavailable (${e::class.simpleName}), retry after rebuilding")
            runCatching { keyStore.deleteEntry(alias(sn)) }
            encryptAndStore(sn, key, createSecret(sn))
        }
    }

    private fun encryptAndStore(sn: String, key: ByteArray, secret: SecretKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val blob = cipher.iv + cipher.doFinal(key)
        prefs.edit().putString(prefKey(sn), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** Delete the key for this device (used after changing the passphrase or resetting the device). Returns whether it previously existed. */
    fun clear(sn: String): Boolean {
        val had = prefs.contains(prefKey(sn))
        prefs.edit().remove(prefKey(sn)).apply()
        runCatching { keyStore.deleteEntry(alias(sn)) }
            .onFailure { Log.w(TAG, "Failed to delete Keystore alias: ${it.message}") }
        return had
    }

    /** Set of SNs that have stored keys — consulted locally without decryption. */
    fun knownSerials(): Set<String> =
        prefs.all.keys.filter { it.startsWith("wrapped-") }.map { it.removePrefix("wrapped-") }.toSet()

    private fun existingSecret(sn: String): SecretKey? =
        runCatching { keyStore.getKey(alias(sn), null) as? SecretKey }.getOrNull()

    private fun createSecret(sn: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias(sn),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                // R1: unreadable when locked. Missing either prerequisite makes this attribute unusable — adding it would render the entire key
                // unusable on devices without a lock screen (real‑device test: API 31 without lock screen → UserNotAuthenticatedException).
                if (r1Satisfied && android.os.Build.VERSION.SDK_INT >= 28) {
                    setUnlockedDeviceRequired(true)
                } else {
                    Log.w(
                        TAG,
                        "R1 (unreadable while locked) is not satisfied: API=${android.os.Build.VERSION.SDK_INT}" +
                            " (needs >=28), secure lock screen=$deviceSecure. The key is still persisted, but without lock-screen protection.",
                    )
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
