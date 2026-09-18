package com.nomily.app.core.config

import com.nomily.app.core.json.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {

    /**
     * **The most important rule in this file**: keys written by other clients but not modeled in Android must be written back exactly as they are.
     *
     * This red flag means: User-configured Azure keys and LLM providers
     * are lost after Android saves them once — and the symptom is simply "settings disappear on their own".
     */
    @Test
    fun `unmapped keys preserved as is`() {
        val fromConfigFile = """
            {
              "asr_providers": {"azure": {"key": "SECRET", "region": "eastasia"}},
              "llm_providers": {"primary": "claude", "claude": {"api_key": "K", "model": "m"}},
              "defaults": {"asr": {"primary": "azure", "fallbacks": ["local"]}},
              "wifi_ap": {"ssid": "s", "psk": "p"},
              "auto_reconnect_enabled": false,
              "devices": {"UUID-1": {"name": "D·NOTE", "device_sid": "AABBCC", "some_future_key": 7}}
            }
        """.trimIndent()

        val cfg = AppConfig.parse(fromConfigFile)
        assertEquals(false, cfg.autoReconnectEnabled, "Modeled keys should be read in")

        val back = JsonParser(cfg.toJson()).parse() as Map<*, *>

        // The top-level four unmodeled subtrees are all
        @Suppress("UNCHECKED_CAST")
        val azure = ((back["asr_providers"] as Map<String, Any?>)["azure"]) as Map<*, *>
        assertEquals("SECRET", azure["key"])
        assertEquals("eastasia", azure["region"])
        assertEquals("claude", (back["llm_providers"] as Map<*, *>)["primary"])
        assertTrue(back.containsKey("defaults"))
        assertEquals("s", (back["wifi_ap"] as Map<*, *>)["ssid"])

        // Unmodeled keys in the device record are also included
        val dev = (back["devices"] as Map<*, *>)["UUID-1"] as Map<*, *>
        assertEquals(7L, dev["some_future_key"])
        assertEquals("AABBCC", dev["device_sid"])
    }

    /** Empty file / invalid JSON / not an object — all fall back to defaults, no exceptions thrown. */
    @Test
    fun `bad input falls back to default value instead of crashing`() {
        for (bad in listOf("", "   ", "{", "[1,2]", "null", "not json at all", """{"devices": 5}""")) {
            val cfg = AppConfig.parse(bad)
            assertEquals(true, cfg.autoReconnectEnabled, "Input <$bad> should fall back to default")
            assertEquals(emptyMap(), cfg.devices, "devices of input <$bad> should be empty")
        }
    }

    /** Keys are individually converted to snake_case. A single typo causes divergence across all three platforms. */
    @Test
    fun `key names match config schema`() {
        val json = AppConfig().toJson()
        val keys = (JsonParser(json).parse() as Map<*, *>).keys
        val expected = setOf(
            "devices", "asr_providers", "llm_providers", "defaults",
            "auto_delete_after_transfer", "auto_transcribe_after_download",
            "local_vad_enabled", "auto_reconnect_enabled", "cloud_egress_consented",
            "developer_mode", "min_transcribe_duration", "fast_transfer_threshold_kb",
        )
        assertEquals(expected, keys, "The set of keys output by the default config must match the config schema (omit when optional value is null)")
    }

    /** Aligns default values one-by-one with the fallbacks of `AppConfig.empty` / `decodeIfPresent`. */
    @Test
    fun `default values match config schema`() {
        val c = AppConfig()
        assertEquals(true, c.autoDeleteAfterTransfer)
        assertEquals(false, c.autoTranscribeAfterDownload)
        assertEquals(true, c.localVadEnabled)
        assertEquals(true, c.autoReconnectEnabled)
        assertEquals(false, c.cloudEgressConsented)
        assertEquals(false, c.developerMode)
        assertEquals(10, c.minTranscribeDuration)
        assertEquals(512, c.fastTransferThresholdKb)
        assertNull(c.lastSourceLang)
    }

    @Test
    fun `round trip stable`() {
        val a = AppConfig()
            .rememberDevice("MAC-1", "D·NOTE", "AABBCC", "2026-07-30T15:50:17.000Z")
            .setBondId("MAC-1", "D·NOTE", "0".repeat(32))
            .copy(lastSourceLang = "zh-CN")
        val b = AppConfig.parse(a.toJson())
        assertEquals(a, b)
        assertEquals(a.toJson(), b.toJson(), "Serialization must be idempotent (key order fixed)")
    }

    /** When `rememberDevice` is passed a null SID, it must not erase the stored value. */
    @Test
    fun `reconnection records do not erase existing device_sid and bond_id`() {
        val c = AppConfig()
            .rememberDevice("MAC-1", "D·NOTE", "AABBCC", "2026-07-30T10:00:00.000Z")
            .setBondId("MAC-1", "D·NOTE", "ff".repeat(16))
            .rememberDevice("MAC-1", "D·NOTE renamed", null, "2026-07-30T11:00:00.000Z")
        val r = c.devices["MAC-1"]!!
        assertEquals("AABBCC", r.deviceSid, "Passing null should retain original SID")
        assertEquals("ff".repeat(16), r.bondId, "bond_id should not be cleared by rememberDevice")
        assertEquals("D·NOTE renamed", r.name)
        assertEquals("2026-07-30T11:00:00.000Z", r.lastConnected)
    }

    /** Automatically reconnects to the server with the latest `last_connected` timestamp; servers without a timestamp are excluded. */
    @Test
    fun `pick recently connected device`() {
        assertNull(AppConfig().mostRecentDevice(), "returns null when no devices are present")

        val c = AppConfig()
            .rememberDevice("A", "Old", "AAAAAA", "2026-07-29T23:59:59.999Z")
            .rememberDevice("B", "New", "BBBBBB", "2026-07-30T00:00:00.001Z")
            .copy(
                devices = AppConfig()
                    .rememberDevice("A", "Old", "AAAAAA", "2026-07-29T23:59:59.999Z")
                    .rememberDevice("B", "New", "BBBBBB", "2026-07-30T00:00:00.001Z")
                    .devices + ("C" to AppConfig.DeviceRecord(name = "Never Connected")),
            )
        assertEquals("B", c.mostRecentDevice()?.first)
        assertEquals(3, c.devices.size, "Records without last_connected are retained but not considered for selection")
    }

    /** Providers are now modeled, but **unmodeled provider sub-keys** (e.g., those added by a provider in the future) must still be carried over as-is. */
    @Test
    fun `provider modeling and preserve sub-keys for unmapped providers`() {
        val json = """
            {
              "asr_providers": {
                "azure": {"key": "K", "region": "westus3"},
                "local": {"host": "10.0.0.9", "port": 12300},
                "future_asr": {"x": 1}
              },
              "llm_providers": {
                "primary": "openai",
                "openai": {"api_key": "OA", "model": "gpt"},
                "custom": {"api_key": "C", "endpoint": "https://e", "model": "m"},
                "future_llm": {"y": 2}
              }
            }
        """.trimIndent()
        val cfg = AppConfig.parse(json)
        assertEquals("K", cfg.asrProviders.azure?.key)
        assertEquals(12300, cfg.asrProviders.local?.port)
        assertEquals("openai", cfg.llmProviders.primary)
        assertEquals("gpt", cfg.llmProviders.openai?.model)
        assertTrue(cfg.usesCloudService, "primary=openai counts as cloud")
        assertEquals(listOf("openai" to "OpenAI", "custom" to "Custom"), cfg.llmProviders.configuredProviders)

        val back = JsonParser(cfg.toJson()).parse() as Map<*, *>
        val asr = back["asr_providers"] as Map<*, *>
        assertEquals(1L, (asr["future_asr"] as Map<*, *>)["x"], "Unmodeled provider subkey should be present")
        assertEquals("westus3", (asr["azure"] as Map<*, *>)["region"])
        val llm = back["llm_providers"] as Map<*, *>
        assertEquals(2L, (llm["future_llm"] as Map<*, *>)["y"])
        assertEquals("OA", (llm["openai"] as Map<*, *>)["api_key"])
    }

    @Test
    fun `azure verification only counts for the credentials it was run against`() {
        val key = "K"
        val region = "westus3"
        val verified = AppConfig.AsrProviders.Azure(
            key = key,
            region = region,
            verifiedFingerprint = AppConfig.AsrProviders.Azure.fingerprint(key, region),
            verifiedOk = true,
        )
        assertEquals(true, verified.verification)
        // Changed key/region, so the previous conclusion no longer applies — otherwise, an incorrect key would still show as "verified".
        assertEquals(null, verified.copy(key = "K2").verification)
        assertEquals(null, verified.copy(region = "eastus").verification)
        // region case does not alter the credentials themselves
        assertEquals(true, verified.copy(region = "WESTUS3").verification)
        // Not verified means null, not false: false would cause the transcription chain to skip this provider directly
        assertEquals(null, AppConfig.AsrProviders.Azure(key, region).verification)

        // Persist only the fingerprint, not the key itself (config.json is in plaintext)
        val back = JsonParser(
            AppConfig(asrProviders = AppConfig.AsrProviders(azure = verified)).toJson(),
        ).parse() as Map<*, *>
        val azure = (back["asr_providers"] as Map<*, *>)["azure"] as Map<*, *>
        assertEquals(true, azure["verified_ok"])
        assertTrue((azure["verified_fingerprint"] as String).length == 64)
        assertTrue(!(azure["verified_fingerprint"] as String).contains(key))
    }
}
