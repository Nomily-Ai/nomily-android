package com.nomily.app.data

import android.content.Context
import android.util.Log
import com.nomily.app.core.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persisting `config.json`.
 *
 * Parsing/serialization is all in `:core`’s [AppConfig] (pure Kotlin, unit‑tested); this layer only does three things:
 * **File I/O, 250 ms debounce, atomic replacement**.
 *
 * ## Why the debounce is 250 ms
 *
 * Not arbitrary — 250 ms is chosen so that continuous edits like “dragging a slider” coalesce into a single write.
 *
 * ## Atomic replacement
 *
 * Here we achieve atomic replacement by **writing a temporary file + `renameTo`**.
 * Overwriting the original file directly could leave a **partial JSON** if the process is killed mid‑write, causing the next launch to fail parsing,
 * fall back to defaults, and wipe all user settings. The fallback in [AppConfig.parse] (“bad input → default”) only guarantees no crash, **does not preserve data**; the true data safety comes from this atomic replace.
 */
class ConfigStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ConfigStore"

        /** Debounce window. Changing this value alters the write cadence; don’t tweak it casually. */
        const val DEBOUNCE_MS = 250L

        private const val FILE_NAME = "config.json"

        /**
         * ISO‑8601 with milliseconds + UTC.
         *
         * The format must be consistent: [AppConfig.mostRecentDevice] compares times **lexicographically as strings**,
         * so a mismatched format will compare incorrectly.
         */
        fun nowIso(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val writeLock = Mutex()
    private var debounce: Job? = null

    /**
     * Third‑party service credentials are not stored in `config.json`.
     * The in‑memory [AppConfig] remains unchanged; they are only split out and re‑assembled at the **disk I/O boundary**.
     */
    private val secrets = SecretStore(context)

    private val _config = MutableStateFlow(loadFromDisk())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /**
     * Modify configuration. Writes are debounced and merged — multiple rapid changes result in a single write.
     *
     * In‑memory values are updated **immediately** (UI shouldn’t wait 250 ms); only the disk write is delayed.
     */
    fun update(transform: (AppConfig) -> AppConfig) {
        _config.value = transform(_config.value)
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            // After the debounce window ends, it must not be cancelled, otherwise the “last modification” may never be persisted
            withContext(NonCancellable) { saveNow() }
        }
    }

    /** Write immediately (e.g., when backgrounded or after critical changes), bypassing debounce. */
    suspend fun saveNow() {
        val snapshot = _config.value
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val text = stripSecrets(snapshot.toJson())
                    val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        // renameTo should succeed within the same directory; on failure fall back to direct write to avoid losing this change
                        file.writeText(text)
                        tmp.delete()
                        Log.w(TAG, "renameTo failed, reverted to direct overwrite")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Configuration save failed: ${e.message}")
                }
            }
        }
    }

    private fun loadFromDisk(): AppConfig {
        if (!file.exists()) return AppConfig()
        return try {
            val raw = file.readText()
            val restored = restoreSecrets(raw)
            if (restored.migrated) {
                // Plaintext credentials in old config: migrate to SecretStore and rewrite the file without credentials.
                Log.i(TAG, "Migrate plaintext credentials from config.json into SecretStore")
                runCatching { file.writeText(stripSecrets(raw)) }
                    .onFailure { Log.w(TAG, "Failed to rewrite config.json after migration: ${it.message}") }
            }
            AppConfig.parse(restored.json)
        } catch (e: Exception) {
            Log.e(TAG, "Configuration read failed, falling back to default values: ${e.message}")
            AppConfig()
        }
    }

    // ── Credential split / assemble ────────────────────────────────────────────────
    //
    // Only touch these three: `asr.azure.key`, `llm.<provider>.api_key`, `wifi_ap.psk`.
    // Do it at the JSON layer, not by modifying :core’s AppConfig — “which fields are
    // persisted” is decided by the platform storage, not part of the config format.

    private class Restored(val json: String, val migrated: Boolean)

    private fun stripSecrets(json: String): String {
        val root = org.json.JSONObject(json)
        forEachSecretSlot(root) { holder, field, name ->
            val value = holder.optString(field, "")
            if (value.isNotEmpty()) {
                secrets.put(name, value)
                holder.remove(field)
            }
        }
        return root.toString()
    }

    private fun restoreSecrets(json: String): Restored {
        val root = org.json.JSONObject(json)
        var migrated = false
        forEachSecretSlot(root) { holder, field, name ->
            val plaintext = holder.optString(field, "")
            if (plaintext.isNotEmpty()) {
                // The file still contains plaintext (from older versions) → migrate to SecretStore, then rewrite the file
                secrets.put(name, plaintext)
                migrated = true
            } else {
                // Normal path: the field is absent in the file, so load it from SecretStore into memory
                secrets.get(name)?.let { holder.put(field, it) }
            }
        }
        return Restored(root.toString(), migrated)
    }

    /**
     * Iterate over all credential slots: `asr.azure.key`, `llm.<provider>.api_key`, `wifi_ap.psk`.
     *
     * Perform this at the JSON layer, not by modifying `:core`’s [AppConfig] — “which fields
     * are persisted” is decided by the platform storage, not part of the configuration format.
     */
    private fun forEachSecretSlot(
        root: org.json.JSONObject,
        action: (holder: org.json.JSONObject, field: String, name: String) -> Unit,
    ) {
        root.optJSONObject("asr")?.optJSONObject("azure")?.let { action(it, "key", "asr.azure.key") }
        root.optJSONObject("llm")?.let { llm ->
            for (provider in llm.keys().asSequence().toList()) {
                llm.optJSONObject(provider)?.let { action(it, "api_key", "llm.$provider.api_key") }
            }
        }
        root.optJSONObject("wifi_ap")?.let { action(it, "psk", "wifi_ap.psk") }
    }

    // ── Several semantic entry points ────────────────────────────────────────

    /** On each connection, overwrite `last_connected`. */
    fun rememberDevice(id: String, name: String, deviceSid: String?) =
        update { it.rememberDevice(id, name, deviceSid, nowIso()) }

    fun setBondId(id: String, name: String, bondId: String?) =
        update { it.setBondId(id, name, bondId) }

    fun setAutoReconnect(enabled: Boolean) =
        update { it.copy(autoReconnectEnabled = enabled) }
}
