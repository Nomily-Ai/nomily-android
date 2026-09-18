package com.nomily.app.core.config

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson

/**
 * `config.json` read/write — **pure Kotlin, no `android.*`**, so it can be unit‑tested off‑device.
 *
 * ## Why JSON instead of DataStore
 *
 * This is not a “which Android library should be used” decision. **The JSON format is shared across clients**, with fixed key names, nesting, and optionality. Switching to DataStore on Android would be a **one‑sided fork**:
 * the same device’s `bond_id` / `device_sid` would no longer match between the two sides.
 *
 * Therefore we copy that schema verbatim (aligning snake_case keys one‑by‑one) and store it as `config.json`.
 *
 * ## Unknown keys must be preserved
 *
 * Unknown keys cannot be discarded so that “files written by older App versions can be read cleanly,
 * rather than falling back to defaults and wiping user‑configured settings”. On Android we go a step further: **keys that are not modeled are kept entirely in [unknown] and merged back unchanged when writing**.
 * The rationale is that the current Android implementation only covers a tiny subset (no ASR / LLM / Wi‑Fi / VAD). If we only wrote fields we recognize, a single save would delete Azure keys, providers, etc., configured by other clients — **silent data loss, manifested as “my settings disappeared”, which is hard to trace**.
 *
 * > This principle mirrors “don’t provide i18n strings for nonexistent features”:
 * > **Don’t pretend to implement what isn’t implemented (don’t model), but also don’t break it (preserve as‑is). */
data class AppConfig(
    /** Known devices table, key = platform‑side device identifier (Android uses BLE MAC). */
    val devices: Map<String, DeviceRecord> = emptyMap(),
    /** Transcription providers (Azure / local ASR server), corresponding to `asr_providers` in the JSON. */
    val asrProviders: AsrProviders = AsrProviders(),
    /** LLM providers for summarization/translation, corresponding to `llm_providers` in the JSON. */
    val llmProviders: LlmProviders = LlmProviders(),
    /** Default ASR chain: primary + fallbacks. */
    val defaults: Defaults = Defaults(),
    val autoDeleteAfterTransfer: Boolean = true,
    val autoTranscribeAfterDownload: Boolean = false,
    val localVadEnabled: Boolean = true,
    /** Automatically reconnect to the most recent device after disconnection (P0 #2 switch). */
    val autoReconnectEnabled: Boolean = true,
    val cloudEgressConsented: Boolean = false,
    val developerMode: Boolean = false,
    val minTranscribeDuration: Int = 10,
    val fastTransferThresholdKb: Int = 512,
    /** Device hotspot credentials used for fast transfer. On first fast transfer, a pair is randomly generated and stored (in `wifi_ap` of the JSON). */
    val wifiAp: WifiAp? = null,
    val lastSourceLang: String? = null,
    val lastTargetLang: String? = null,
    val lastSummaryLang: String? = null,
    /**
     * UI language, BCP‑47 tag (`zh-Hans` / `pt-BR` / …).
     *
     * **`null` = follow system**, which is also the default when never set — therefore this field is optional,
     * rather than “default en”: defaulting to en would show an English UI to a Chinese user installing the app for the first time.
     * If the value is outside [AppLanguages.SUPPORTED], English is used as a fallback (see [AppLanguages.resolve]).
     */
    val appLanguage: String? = null,
    /**
     * Top‑level keys that are **not modeled** on this side (e.g., `wifi_ap`). Stored and written back unchanged.
     * The approach has been narrowed from “everything unmodeled goes into unknown” to “only truly unmodeled entries go into unknown” —
     * providers are now modeled because the settings UI needs to edit them.
     */
    val unknown: Map<String, Any?> = emptyMap(),
) {
    /**
     * Whether the current configuration will send user data to third‑party cloud services:
     * Azure transcription, or a cloud LLM (openai/claude/gemini/openRouter) used as a summarization provider.
     * Local ASR, Ollama, custom (mostly self‑hosted endpoints) are excluded. Used to gate a one‑time consent prompt.
     */
    val usesCloudService: Boolean
        get() {
            asrProviders.azure?.let { if (it.key.isNotEmpty() && it.region.isNotEmpty()) return true }
            return llmProviders.primary in setOf("openai", "claude", "gemini", "openRouter")
        }

    data class AsrProviders(
        val azure: Azure? = null,
        val local: Local? = null,
        val unknown: Map<String, Any?> = emptyMap(),
    ) {
        data class Azure(
            val key: String,
            val region: String,
            /**
             * The fingerprint of the credential set that the last “verify configuration” targeted, and its conclusion. A mismatched fingerprint indicates that the key/region was changed later, so that conclusion no longer applies.
             */
            val verifiedFingerprint: String? = null,
            val verifiedOk: Boolean? = null,
        ) {
            /** Verification result for the current key/region; `null` = not verified, or changed after verification. */
            val verification: Boolean?
                get() = if (verifiedFingerprint != null && verifiedFingerprint == fingerprint(key, region)) verifiedOk else null

            companion object {
                /** Only the fingerprint is stored, not the key itself — the config file is plaintext. */
                fun fingerprint(key: String, region: String): String =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest("$key|${region.lowercase()}".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
            }
        }
        data class Local(val host: String, val port: Int)

        /** At least one provider must be configured. */
        val hasConfiguredProvider: Boolean
            get() = (azure?.let { it.key.isNotEmpty() && it.region.isNotEmpty() } ?: false) ||
                (local?.let { it.host.isNotEmpty() && it.port > 0 } ?: false)
    }

    data class LlmProviders(
        val primary: String? = null,
        val openai: Keyed? = null,
        val openRouter: Endpoint? = null,
        val claude: Keyed? = null,
        val gemini: Keyed? = null,
        val ollama: Endpoint? = null,
        val custom: Endpoint? = null,
        val unknown: Map<String, Any?> = emptyMap(),
    ) {
        data class Keyed(val apiKey: String, val model: String? = null)
        data class Endpoint(val apiKey: String? = null, val endpoint: String, val model: String? = null)

        /** List of providers with configured credentials (key → display name). */
        val configuredProviders: List<Pair<String, String>>
            get() = buildList {
                openRouter?.let { if (!it.apiKey.isNullOrEmpty()) add("openRouter" to "Open Router") }
                openai?.let { if (it.apiKey.isNotEmpty()) add("openai" to "OpenAI") }
                claude?.let { if (it.apiKey.isNotEmpty()) add("claude" to "Claude") }
                gemini?.let { if (it.apiKey.isNotEmpty()) add("gemini" to "Gemini") }
                ollama?.let { if (it.endpoint.isNotEmpty()) add("ollama" to "Ollama") }
                custom?.let { if (it.endpoint.isNotEmpty()) add("custom" to "Custom") }
            }
    }

    /** Device hotspot credentials: two fields, ssid and psk. */
    data class WifiAp(val ssid: String, val psk: String)

    data class Defaults(
        val asrPrimary: String = "azure",
        val asrFallbacks: List<String> = listOf("local"),
    )
    /**
     * A remembered device.
     *
     * @property deviceSid 6‑character uppercase hex (3 bytes), sourced from the manufacturer’s broadcast data — **the only identifier that can recognize the same physical card across devices/re‑installs**. Android’s MAC may be randomized on some models,
     *   so automatic reconnection uses this rather than the platform identifier.
     */
    data class DeviceRecord(
        val name: String,
        val lastConnected: String? = null,
        /** 32‑character hex (16 bytes) — the bond_id previously pushed to this device via `0xA0`. */
        val bondId: String? = null,
        val deviceSid: String? = null,
        /** Same as above: a key in this record that the local side does not recognize. */
        val unknown: Map<String, Any?> = emptyMap(),
    )

    companion object {
        /** Modeled top‑level keys — everything else goes into [unknown]. */
        private val KNOWN_TOP = setOf(
            "devices", "asr_providers", "llm_providers", "defaults",
            "auto_delete_after_transfer", "auto_transcribe_after_download",
            "local_vad_enabled", "auto_reconnect_enabled", "cloud_egress_consented",
            "developer_mode", "min_transcribe_duration", "fast_transfer_threshold_kb",
            "last_source_lang", "last_target_lang", "last_summary_lang", "wifi_ap",
            "app_language",
        )
        private val KNOWN_DEVICE = setOf("name", "last_connected", "bond_id", "device_sid")
        private val KNOWN_ASR = setOf("azure", "local")
        private val KNOWN_LLM = setOf(
            "primary", "openai", "open_router", "claude", "gemini", "ollama", "custom",
        )

        private fun Map<*, *>.unknownKeys(known: Set<String>): Map<String, Any?> =
            entries.filter { it.key is String && it.key !in known }
                .associate { it.key as String to it.value }

        private fun parseAsr(m: Map<*, *>): AppConfig.AsrProviders {
            val az = (m["azure"] as? Map<*, *>)?.let {
                AppConfig.AsrProviders.Azure(
                    it["key"] as? String ?: "",
                    it["region"] as? String ?: "",
                    it["verified_fingerprint"] as? String,
                    it["verified_ok"] as? Boolean,
                )
            }
            // A local record without a host is junk: it can't connect to any service, but it causes the port field to display a number no one entered. In older versions, entering a number in an empty port field would store {host: "", port: 1}.
            val lo = (m["local"] as? Map<*, *>)?.let {
                AppConfig.AsrProviders.Local(it["host"] as? String ?: "", (it["port"] as? Number)?.toInt() ?: 0)
            }?.takeIf { it.host.isNotEmpty() }
            return AppConfig.AsrProviders(az, lo, m.unknownKeys(KNOWN_ASR))
        }

        private fun keyed(v: Any?): AppConfig.LlmProviders.Keyed? =
            (v as? Map<*, *>)?.let {
                AppConfig.LlmProviders.Keyed(it["api_key"] as? String ?: "", it["model"] as? String)
            }

        private fun endpoint(v: Any?): AppConfig.LlmProviders.Endpoint? =
            (v as? Map<*, *>)?.let {
                AppConfig.LlmProviders.Endpoint(it["api_key"] as? String, it["endpoint"] as? String ?: "", it["model"] as? String)
            }

        private fun parseLlm(m: Map<*, *>): AppConfig.LlmProviders = AppConfig.LlmProviders(
            primary = m["primary"] as? String,
            openai = keyed(m["openai"]),
            openRouter = endpoint(m["open_router"]),
            claude = keyed(m["claude"]),
            gemini = keyed(m["gemini"]),
            ollama = endpoint(m["ollama"]),
            custom = endpoint(m["custom"]),
            unknown = m.unknownKeys(KNOWN_LLM),
        )

        /**
         * Parse. **Never throws on malformed input**, instead falls back to defaults while preserving as much as possible:
         * A manually edited broken config should not prevent the app from starting.
         *
         * @return Parsing result; returns the default [AppConfig] when the whole file is not a JSON object.
         */
        fun parse(json: String): AppConfig {
            val root = try {
                JsonParser(json).parse() as? Map<*, *> ?: return AppConfig()
            } catch (_: Exception) {
                return AppConfig()
            }
            fun bool(k: String, d: Boolean) = root[k] as? Boolean ?: d
            fun int(k: String, d: Int) = (root[k] as? Number)?.toInt() ?: d
            fun str(k: String) = root[k] as? String

            val devices = (root["devices"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val id = k as? String ?: return@mapNotNull null
                val rec = v as? Map<*, *> ?: return@mapNotNull null
                id to DeviceRecord(
                    name = rec["name"] as? String ?: "",
                    lastConnected = rec["last_connected"] as? String,
                    bondId = rec["bond_id"] as? String,
                    deviceSid = rec["device_sid"] as? String,
                    unknown = rec.entries
                        .filter { it.key is String && it.key !in KNOWN_DEVICE }
                        .associate { it.key as String to it.value },
                )
            }?.toMap() ?: emptyMap()

            val defaults = (root["defaults"] as? Map<*, *>)?.let { d ->
                (d["asr"] as? Map<*, *>)?.let { a ->
                    AppConfig.Defaults(
                        asrPrimary = a["primary"] as? String ?: "azure",
                        asrFallbacks = (a["fallbacks"] as? List<*>)?.mapNotNull { it as? String } ?: listOf("local"),
                    )
                }
            } ?: AppConfig.Defaults()

            return AppConfig(
                devices = devices,
                asrProviders = (root["asr_providers"] as? Map<*, *>)?.let { parseAsr(it) } ?: AppConfig.AsrProviders(),
                llmProviders = (root["llm_providers"] as? Map<*, *>)?.let { parseLlm(it) } ?: AppConfig.LlmProviders(),
                defaults = defaults,
                autoDeleteAfterTransfer = bool("auto_delete_after_transfer", true),
                autoTranscribeAfterDownload = bool("auto_transcribe_after_download", false),
                localVadEnabled = bool("local_vad_enabled", true),
                autoReconnectEnabled = bool("auto_reconnect_enabled", true),
                cloudEgressConsented = bool("cloud_egress_consented", false),
                developerMode = bool("developer_mode", false),
                minTranscribeDuration = int("min_transcribe_duration", 10),
                fastTransferThresholdKb = int("fast_transfer_threshold_kb", 512),
                lastSourceLang = str("last_source_lang"),
                lastTargetLang = str("last_target_lang"),
                lastSummaryLang = str("last_summary_lang"),
                appLanguage = str("app_language"),
                wifiAp = (root["wifi_ap"] as? Map<*, *>)?.let { w ->
                    val ssid = w["ssid"] as? String ?: ""
                    val psk = w["psk"] as? String ?: ""
                    if (ssid.isEmpty() || psk.isEmpty()) null else AppConfig.WifiAp(ssid, psk)
                },
                unknown = root.entries
                    .filter { it.key is String && it.key !in KNOWN_TOP }
                    .associate { it.key as String to it.value },
            )
        }
    }

    /** Serialize. Keys are output in alphabetical order to keep diffs stable. */
    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>(unknown)    // Insert unmapped keys first
        m["devices"] = devices.mapValues { (_, r) ->
            val d = LinkedHashMap<String, Any?>(r.unknown)
            d["name"] = r.name
            r.lastConnected?.let { d["last_connected"] = it }
            r.bondId?.let { d["bond_id"] = it }
            r.deviceSid?.let { d["device_sid"] = it }
            d.toSortedMap()
        }
         // providers / defaults — unmapped sub‑keys are overlaid after their respective unknown base
        asrProviders.let { asr ->
            val a = LinkedHashMap<String, Any?>(asr.unknown)
            asr.azure?.let {
                a["azure"] = sortedMapOf<String, Any?>("key" to it.key, "region" to it.region).apply {
                    it.verifiedFingerprint?.let { f -> put("verified_fingerprint", f) }
                    it.verifiedOk?.let { ok -> put("verified_ok", ok) }
                }
            }
            asr.local?.let { a["local"] = sortedMapOf<String, Any?>("host" to it.host, "port" to it.port) }
            m["asr_providers"] = a.toSortedMap()    // Non‑optional field; output {} even when empty
        }
        llmProviders.let { llm ->
            val l = LinkedHashMap<String, Any?>(llm.unknown)
            llm.primary?.let { l["primary"] = it }
            fun keyed(p: LlmProviders.Keyed) = LinkedHashMap<String, Any?>().apply {
                put("api_key", p.apiKey); p.model?.let { put("model", it) }
            }.toSortedMap()
            fun endpoint(p: LlmProviders.Endpoint) = LinkedHashMap<String, Any?>().apply {
                p.apiKey?.let { put("api_key", it) }; put("endpoint", p.endpoint); p.model?.let { put("model", it) }
            }.toSortedMap()
            llm.openai?.let { l["openai"] = keyed(it) }
            llm.openRouter?.let { l["open_router"] = endpoint(it) }
            llm.claude?.let { l["claude"] = keyed(it) }
            llm.gemini?.let { l["gemini"] = keyed(it) }
            llm.ollama?.let { l["ollama"] = endpoint(it) }
            llm.custom?.let { l["custom"] = endpoint(it) }
            m["llm_providers"] = l.toSortedMap()    // Same as above; output {} when empty
        }
        m["defaults"] = sortedMapOf(
            "asr" to sortedMapOf<String, Any?>(
                "primary" to defaults.asrPrimary,
                "fallbacks" to defaults.asrFallbacks,
            ),
        )
        m["auto_delete_after_transfer"] = autoDeleteAfterTransfer
        m["auto_transcribe_after_download"] = autoTranscribeAfterDownload
        m["local_vad_enabled"] = localVadEnabled
        m["auto_reconnect_enabled"] = autoReconnectEnabled
        m["cloud_egress_consented"] = cloudEgressConsented
        m["developer_mode"] = developerMode
        m["min_transcribe_duration"] = minTranscribeDuration
        m["fast_transfer_threshold_kb"] = fastTransferThresholdKb
        lastSourceLang?.let { m["last_source_lang"] = it }
        lastTargetLang?.let { m["last_target_lang"] = it }
        lastSummaryLang?.let { m["last_summary_lang"] = it }
                // When following the system, **omit this key** (instead of writing null),
        // allowing the file to distinguish between “never set” and “explicitly chosen a language”.
        appLanguage?.let { m["app_language"] = it }
        wifiAp?.let { m["wifi_ap"] = sortedMapOf("psk" to it.psk, "ssid" to it.ssid) }
        return writeJson(m.toSortedMap())
    }

    /** Remember/update a device (each connection overwrites `last_connected`). */
    fun rememberDevice(id: String, name: String, deviceSid: String?, nowIso: String): AppConfig {
        val old = devices[id]
        return copy(
            devices = devices + (
                id to DeviceRecord(
                    name = name,
                    lastConnected = nowIso,
                    bondId = old?.bondId,
                     // Preserve the existing SID when null is passed
                    deviceSid = deviceSid ?: old?.deviceSid,
                    unknown = old?.unknown ?: emptyMap(),
                )
                ),
        )
    }

    /** Write/clear bond_id (create a stub if the record does not exist). */
    fun setBondId(id: String, name: String, bondId: String?): AppConfig {
        val old = devices[id]
        return copy(
            devices = devices + (
                id to (old ?: DeviceRecord(name = name)).copy(bondId = bondId)
                ),
        )
    }

    /**
     * Auto‑reconnect target: the device with the most recent `last_connected`.
     *
     * Timestamps are ISO‑8601 strings, **compared lexicographically** — the lexical order of ISO‑8601 produced by the same formatter (`withInternetDateTime + withFractionalSeconds`) matches chronological order.
     * Records without `last_connected` are ignored.
     */
    fun mostRecentDevice(): Pair<String, DeviceRecord>? =
        devices.entries
            .filter { it.value.lastConnected != null }
            .maxByOrNull { it.value.lastConnected!! }
            ?.let { it.key to it.value }
}
