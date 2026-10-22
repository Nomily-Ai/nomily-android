package com.nomily.app.core.config

import com.nomily.app.core.asr.aliyun
import com.nomily.app.core.llm.LlmProviderSpec
import com.nomily.app.core.llm.LlmStorage
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
    /** Transcription providers (Azure / local server / custom / remote live), corresponding to `asr_providers` in the JSON. */
    val asrProviders: AsrProviders = AsrProviders(),
    /** LLM providers for summarization/translation, corresponding to `llm_providers` in the JSON. */
    val llmProviders: LlmProviders = LlmProviders(),
    /** Default ASR chain: primary + fallbacks. */
    val defaults: Defaults = Defaults(),
    val autoDeleteAfterTransfer: Boolean = true,
    val autoTranscribeAfterDownload: Boolean = true,
    /**
     * Queue a summary as soon as a transcript lands. "On" means "when an AI service is configured" — the caller checks
     * that first, and never replaces an existing summary.
     */
    val autoSummarizeAfterTranscription: Boolean = true,
    /** The default summary template, keyed by [com.nomily.app.core.llm.storeKey] (`category\u001Fname`); `null` = none. */
    val summaryTemplateKey: String? = null,
    val localVadEnabled: Boolean = true,
    /** Keep background tasks (transcription, summaries…) running under a foreground-service notification when the app is not in front. */
    val backgroundKeepAliveEnabled: Boolean = true,
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
     * Colour theme tag ([AppTheme.tag]: `crimson` / `graphite` / `ocean` / …), same `app_theme` key as iOS.
     * `null` = never chosen, rendered as [AppTheme.DEFAULT]; unknown tags resolve the same way (see [AppTheme.resolve]).
     */
    val appTheme: String? = null,
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
            if (asrProviders.aliyun?.isComplete == true) return true
            return llmProviders.primary in setOf("openai", "claude", "gemini", "openRouter", "qwen", "deepseek", "doubao")
        }

    data class AsrProviders(
        val azure: Azure? = null,
        val local: Local? = null,
        /** User-defined OpenAI-compatible transcription server (`custom`); file transcription only. */
        val custom: Custom? = null,
        /** Remote secure live server (`remote_live`): WSS + API key, live transcription only. */
        val remoteLive: RemoteLive? = null,
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

        /**
         * @property endpoint Base URL, e.g. `http://192.168.1.10:8000` or `https://api.groq.com/openai`.
         * @property language Optional ISO-639-1 hint; empty = server-side detection.
         */
        data class Custom(
            val endpoint: String,
            val apiKey: String? = null,
            val model: String? = null,
            val language: String? = null,
        )

        /** @property endpoint `wss://host[:port][/v1/listen]`; see [com.nomily.app.core.asr.RemoteLiveEndpoint]. */
        data class RemoteLive(val endpoint: String, val apiKey: String? = null)

        /**
         * At least one **file** transcription provider is configured. `remoteLive` is deliberately not counted: it
         * serves live transcription only, and every caller of this flag gates file transcription.
         */
        val hasConfiguredProvider: Boolean
            get() = (aliyun?.isComplete == true) ||
                (azure?.let { it.key.isNotEmpty() && it.region.isNotEmpty() } ?: false) ||
                (local?.let { it.host.isNotEmpty() && it.port > 0 } ?: false) ||
                (custom?.let { it.endpoint.isNotBlank() } ?: false)

        /**
         * Keys the "active provider" picker offers, in display order: only services that are filled in **and** whose
         * credentials were not rejected by verification — anything else would produce no transcription.
         * `remote` serves the live chain only; the file chain skips it.
         */
        val selectable: List<String>
            get() = buildList {
                if (aliyun?.isComplete == true) add("aliyun")
                if (azure?.let { it.key.isNotEmpty() && it.region.isNotEmpty() && it.verification != false } == true) add("azure")
                if (local?.host?.isNotEmpty() == true) add("local")
                if (custom?.endpoint?.isNotBlank() == true) add("custom")
                if (remoteLive?.let {
                        !it.apiKey.isNullOrEmpty() && com.nomily.app.core.asr.RemoteLiveEndpoint.listenUrl(it.endpoint) != null
                    } == true
                ) add("remote")
            }
    }

    data class LlmProviders(
        val primary: String? = null,
        val openai: Keyed? = null,
        val openRouter: Endpoint? = null,
        val claude: Keyed? = null,
        val gemini: Keyed? = null,
        val ollama: Endpoint? = null,
        val custom: Endpoint? = null,
        /** One-tap providers (`qwen` / `deepseek` / `doubao`) keyed by [LlmProviderSpec.id]. */
        val presets: Map<String, Endpoint> = emptyMap(),
        val unknown: Map<String, Any?> = emptyMap(),
    ) {
        data class Keyed(val apiKey: String, val model: String? = null)
        data class Endpoint(val apiKey: String? = null, val endpoint: String, val model: String? = null)

        /** Providers that can send a request, in [LlmProviderSpec.ALL] order (key → display name). */
        val configuredProviders: List<Pair<String, String>>
            get() = LlmProviderSpec.ALL.mapNotNull { spec ->
                val ready = if (spec.keyRequired) !apiKeyValue(spec).isNullOrEmpty() else !storedEndpoint(spec).isNullOrEmpty()
                if (ready) spec.id to spec.name else null
            }

        private fun storedEndpoint(spec: LlmProviderSpec): String? = when (spec.storage) {
            LlmStorage.KEYED -> null
            LlmStorage.ENDPOINT -> endpointSlot(spec.id)?.endpoint
            LlmStorage.PRESET -> presets[spec.id]?.endpoint
        }

        private fun keyedSlot(id: String): Keyed? = when (id) {
            "openai" -> openai
            "claude" -> claude
            "gemini" -> gemini
            else -> null
        }

        private fun endpointSlot(id: String): Endpoint? = when (id) {
            "openRouter" -> openRouter
            "ollama" -> ollama
            "custom" -> custom
            else -> null
        }

        // One uniform view over the three storage shapes, so a single editor can drive every provider.

        /** The stored address, or the vendor's fixed one for [LlmStorage.KEYED]. */
        fun endpointValue(spec: LlmProviderSpec): String? =
            if (spec.storage == LlmStorage.KEYED) spec.defaultEndpoint else storedEndpoint(spec)

        fun apiKeyValue(spec: LlmProviderSpec): String? = when (spec.storage) {
            LlmStorage.KEYED -> keyedSlot(spec.id)?.apiKey
            LlmStorage.ENDPOINT -> endpointSlot(spec.id)?.apiKey
            LlmStorage.PRESET -> presets[spec.id]?.apiKey
        }

        fun modelValue(spec: LlmProviderSpec): String? = when (spec.storage) {
            LlmStorage.KEYED -> keyedSlot(spec.id)?.model
            LlmStorage.ENDPOINT -> endpointSlot(spec.id)?.model
            LlmStorage.PRESET -> presets[spec.id]?.model
        }

        /** Something is stored for [spec] (not merely its default address). */
        fun hasAnyConfig(spec: LlmProviderSpec): Boolean = apiKeyValue(spec) != null || modelValue(spec) != null

        fun withApiKey(spec: LlmProviderSpec, value: String?): LlmProviders {
            val v = value.orEmpty().trim()
            return when (spec.storage) {
                LlmStorage.KEYED -> {
                    val slot = (keyedSlot(spec.id) ?: Keyed("")).copy(apiKey = v)
                    // No key and no model: never configured, keep it out of the list.
                    withKeyed(spec.id, if (v.isEmpty() && slot.model.isNullOrEmpty()) null else slot)
                }
                LlmStorage.ENDPOINT -> {
                    val old = endpointSlot(spec.id) ?: Endpoint(endpoint = spec.defaultEndpoint)
                    withEndpoint(spec.id, old.copy(apiKey = v.ifEmpty { null }, endpoint = old.endpoint.ifEmpty { spec.defaultEndpoint }))
                }
                LlmStorage.PRESET -> withPreset(spec.id, (presets[spec.id] ?: Endpoint(endpoint = spec.defaultEndpoint)).copy(apiKey = v.ifEmpty { null }))
            }
        }

        fun withModel(spec: LlmProviderSpec, value: String?): LlmProviders {
            val v = value.orEmpty().trim()
            return when (spec.storage) {
                LlmStorage.KEYED -> {
                    val slot = (keyedSlot(spec.id) ?: Keyed("")).copy(model = v.ifEmpty { null })
                    withKeyed(spec.id, if (slot.apiKey.isEmpty() && v.isEmpty()) null else slot)
                }
                LlmStorage.ENDPOINT -> {
                    val old = endpointSlot(spec.id) ?: Endpoint(endpoint = spec.defaultEndpoint)
                    withEndpoint(spec.id, old.copy(model = v.ifEmpty { null }, endpoint = old.endpoint.ifEmpty { spec.defaultEndpoint }))
                }
                LlmStorage.PRESET -> withPreset(spec.id, (presets[spec.id] ?: Endpoint(endpoint = spec.defaultEndpoint)).copy(model = v.ifEmpty { null }))
            }
        }

        /** Fixed-address providers ignore this. */
        fun withEndpointUrl(spec: LlmProviderSpec, value: String): LlmProviders {
            val v = value.trim()
            return when (spec.storage) {
                LlmStorage.KEYED -> this
                LlmStorage.ENDPOINT -> withEndpoint(spec.id, (endpointSlot(spec.id) ?: Endpoint(endpoint = "")).copy(endpoint = v))
                LlmStorage.PRESET -> withPreset(spec.id, (presets[spec.id] ?: Endpoint(endpoint = "")).copy(endpoint = v))
            }
        }

        fun removed(spec: LlmProviderSpec): LlmProviders {
            val r = when (spec.storage) {
                LlmStorage.KEYED -> withKeyed(spec.id, null)
                LlmStorage.ENDPOINT -> withEndpoint(spec.id, null)
                LlmStorage.PRESET -> withPreset(spec.id, null)
            }
            return if (primary == spec.id) r.copy(primary = null) else r
        }

        private fun withKeyed(id: String, v: Keyed?) = when (id) {
            "openai" -> copy(openai = v)
            "claude" -> copy(claude = v)
            "gemini" -> copy(gemini = v)
            else -> this
        }

        private fun withEndpoint(id: String, v: Endpoint?) = when (id) {
            "openRouter" -> copy(openRouter = v)
            "ollama" -> copy(ollama = v)
            "custom" -> copy(custom = v)
            else -> this
        }

        private fun withPreset(id: String, v: Endpoint?) =
            copy(presets = if (v == null) presets - id else presets + (id to v))

        /**
         * The retired Ark preset and Doubao are one service (same host, same key). Fold `ark` into `doubao` field by
         * field, only where `doubao` is empty, and repoint a primary of `ark`. No `ark` entry: no-op.
         */
        fun mergeArkIntoDoubao(): LlmProviders {
            val ark = presets["ark"] ?: return this
            val base = presets["doubao"] ?: ark
            val merged = base.copy(
                apiKey = base.apiKey?.takeIf { it.isNotEmpty() } ?: ark.apiKey,
                endpoint = base.endpoint.ifEmpty { ark.endpoint },
                model = base.model?.takeIf { it.isNotEmpty() } ?: ark.model,
            )
            return copy(
                presets = presets - "ark" + ("doubao" to merged),
                primary = if (primary == "ark") "doubao" else primary,
            )
        }

        /** After a provider edit: when exactly one provider is configured, it becomes the primary, so a first setup works without a second step. */
        fun autoSelectPrimaryIfFirst(): LlmProviders =
            configuredProviders.singleOrNull()?.let { copy(primary = it.first) } ?: this
    }

    /** Device hotspot credentials: two fields, ssid and psk. */
    data class WifiAp(val ssid: String, val psk: String)

    data class Defaults(
        val asrPrimary: String = "azure",
        val asrFallbacks: List<String> = listOf("local"),
    )

    /** Make [key] the primary ASR; fallbacks become every other selectable provider, in picker order. */
    fun withAsrPrimary(key: String): AppConfig =
        copy(defaults = defaults.copy(asrPrimary = key, asrFallbacks = asrProviders.selectable.filter { it != key }))

    /**
     * After a provider edit: when the stored primary is not selectable but something is, adopt the first one — the
     * picker already displays it as active, and without this the chain would never reach it.
     */
    fun adoptAsrPrimaryIfUnset(): AppConfig {
        val selectable = asrProviders.selectable
        return if (selectable.isEmpty() || defaults.asrPrimary in selectable) this else withAsrPrimary(selectable.first())
    }
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
            "auto_delete_after_transfer", "auto_transcribe_after_download", "auto_summarize_after_transcription",
            "summary_template_key",
            "local_vad_enabled", "background_keep_alive_enabled", "auto_reconnect_enabled", "cloud_egress_consented",
            "developer_mode", "min_transcribe_duration", "fast_transfer_threshold_kb",
            "last_source_lang", "last_target_lang", "last_summary_lang", "wifi_ap",
            "app_language", "app_theme",
        )
        private val KNOWN_DEVICE = setOf("name", "last_connected", "bond_id", "device_sid")
        private val KNOWN_ASR = setOf("azure", "local", "custom", "remote_live")
        private val KNOWN_LLM = setOf(
            "primary", "openai", "open_router", "claude", "gemini", "ollama", "custom", "presets",
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
            // A half-filled record (port but no host) is kept, so the settings page can keep asking for the host after a restart;
            // only an entirely empty one is dropped. It never counts as configured: that needs a host (see hasConfiguredProvider).
            val lo = (m["local"] as? Map<*, *>)?.let {
                AppConfig.AsrProviders.Local(it["host"] as? String ?: "", (it["port"] as? Number)?.toInt() ?: 0)
            }?.takeIf { it.host.isNotEmpty() || it.port > 0 }
            val custom = (m["custom"] as? Map<*, *>)?.let {
                AppConfig.AsrProviders.Custom(
                    it["endpoint"] as? String ?: "",
                    it["api_key"] as? String,
                    it["model"] as? String,
                    it["language"] as? String,
                )
            }
            // `apiKey`, not `api_key`: iOS encodes this record with its default (camelCase) keys.
            val remote = (m["remote_live"] as? Map<*, *>)?.let {
                AppConfig.AsrProviders.RemoteLive(it["endpoint"] as? String ?: "", it["apiKey"] as? String)
            }
            return AppConfig.AsrProviders(az, lo, custom, remote, m.unknownKeys(KNOWN_ASR))
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
            presets = (m["presets"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                (k as? String)?.let { id -> endpoint(v)?.let { id to it } }
            }.toMap(),
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
                autoTranscribeAfterDownload = bool("auto_transcribe_after_download", true),
                autoSummarizeAfterTranscription = bool("auto_summarize_after_transcription", true),
                summaryTemplateKey = str("summary_template_key"),
                localVadEnabled = bool("local_vad_enabled", true),
                backgroundKeepAliveEnabled = bool("background_keep_alive_enabled", true),
                autoReconnectEnabled = bool("auto_reconnect_enabled", true),
                cloudEgressConsented = bool("cloud_egress_consented", false),
                developerMode = bool("developer_mode", false),
                minTranscribeDuration = int("min_transcribe_duration", 10),
                fastTransferThresholdKb = int("fast_transfer_threshold_kb", 512),
                lastSourceLang = str("last_source_lang"),
                lastTargetLang = str("last_target_lang"),
                lastSummaryLang = str("last_summary_lang"),
                appLanguage = str("app_language"),
                appTheme = str("app_theme"),
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
            asr.custom?.let {
                a["custom"] = sortedMapOf<String, Any?>("endpoint" to it.endpoint).apply {
                    it.apiKey?.let { k -> put("api_key", k) }
                    it.model?.let { v -> put("model", v) }
                    it.language?.let { v -> put("language", v) }
                }
            }
            asr.remoteLive?.let {
                a["remote_live"] = sortedMapOf<String, Any?>("endpoint" to it.endpoint).apply {
                    it.apiKey?.let { k -> put("apiKey", k) }
                }
            }
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
            if (llm.presets.isNotEmpty()) l["presets"] = llm.presets.mapValues { endpoint(it.value) }.toSortedMap()
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
        m["auto_summarize_after_transcription"] = autoSummarizeAfterTranscription
        summaryTemplateKey?.let { m["summary_template_key"] = it }
        m["local_vad_enabled"] = localVadEnabled
        m["background_keep_alive_enabled"] = backgroundKeepAliveEnabled
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
        appTheme?.let { m["app_theme"] = it }
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
