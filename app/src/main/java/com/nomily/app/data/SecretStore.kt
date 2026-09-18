package com.nomily.app.data

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
 * Storage location for service credentials.
 *
 * It stores **third‑party credentials entered by the user in the settings page**: Azure Speech key, various LLM `api_key`s, device hotspot password.
 * These used to lie in plain text alongside `config.json` in the app’s private directory — the private directory blocks other apps but not `adb backup`, rooted devices, or any debugging operation that extracts the config file.
 *
 * Do not confuse with [KeyVault]; differences:
 * - [KeyVault] stores **recording decryption keys**, one alias per SN, unreadable when locked (R1); lose the key and recordings become inaccessible;
 * - Here we store **credentials that can be re‑entered**, so we do not set `setUnlockedDeviceRequired` — adding it would prevent the app from reading the key while the device is locked (e.g., background automatic transcription).
 *
 * Persistence also uses an AES‑GCM wrapper in the Keystore plus storing the ciphertext in SharedPreferences, and the file is excluded in `backup_rules.xml` / `data_extraction_rules.xml` to avoid cloud backup.
 */
class SecretStore(context: Context) {

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dnote-secrets"
        const val PREFS = "dnote-secrets"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Same as [KeyVault]: lazy‑loaded to avoid crashes in JVM test environments that lack AndroidKeyStore. */
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    /** Retrieve a credential; returns null if never stored or if decryption fails (key cleared). */
    fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        val secret = existingSecret() ?: run {
            Log.w(TAG, "$name has ciphertext but the Keystore alias is missing (App data was cleared or the key is invalid)")
            return null
        }
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            require(bytes.size > GCM_IV_BYTES) { "Ciphertext is too short" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
            )
            String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "$name could not be read: ${e::class.simpleName}")
            null
        }
    }

    /** Store a credential; an empty string equals deletion (clearing the field in settings means “no longer use this provider”). */
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingSecret() ?: createSecret())
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (e: java.security.InvalidKeyException) {
            // Wrapping key invalidated (device change / factory reset leaves alias): recreate once before writing
            Log.w(TAG, "Wrapped key is unavailable (${e::class.simpleName}), retry after rebuilding")
            runCatching { keyStore.deleteEntry(ALIAS) }
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, createSecret())
                val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            }.onFailure { Log.e(TAG, "$name failed to save: ${it.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "$name failed to save: ${e.message}")
        }
    }

    private fun existingSecret(): SecretKey? =
        runCatching { keyStore.getKey(ALIAS, null) as? SecretKey }.getOrNull()

    private fun createSecret(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
