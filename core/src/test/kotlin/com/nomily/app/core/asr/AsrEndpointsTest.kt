package com.nomily.app.core.asr

import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.json.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrEndpointsTest {

    @Test
    fun customEndpointShapesLandOnTranscriptions() {
        val t = CustomAsrEndpoint::transcriptionsUrl
        assertEquals("http://h:8000/v1/audio/transcriptions", t("http://h:8000"))
        assertEquals("http://h:8000/v1/audio/transcriptions", t(" http://h:8000/ "))
        assertEquals("http://h:8000/v1/audio/transcriptions", t("http://h:8000/v1"))
        assertEquals("http://h:8000/v1/audio/transcriptions", t("http://h:8000/v1/audio/transcriptions"))
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", t("https://api.groq.com/openai/v1"))
        assertEquals("http://192.168.1.10:8000/v1/audio/transcriptions", t("192.168.1.10:8000"))
        assertNull(t("  "))
        assertEquals("https://api.groq.com/openai/v1/models", CustomAsrEndpoint.modelsUrl("https://api.groq.com/openai/v1"))
        assertEquals("http://h:8000/v1/models", CustomAsrEndpoint.modelsUrl("http://h:8000"))
    }

    @Test
    fun modelDropdownKeepsTranscriptionModelsOrFallsBackToAll() {
        val ids = CustomAsrEndpoint.parseModelIds(
            """{"data":[{"id":"whisper-large-v3"},{"id":"llama-3.1-8b"},{"id":"gpt-4o-audio-preview"},
               {"id":"distil-whisper-large-v3-en"},{"id":"tts-1"}]}""",
        )
        assertEquals(listOf("distil-whisper-large-v3-en", "whisper-large-v3"), CustomAsrEndpoint.dropdownModels(ids))
        assertEquals(listOf("a", "b"), CustomAsrEndpoint.dropdownModels(listOf("b", "a")))
        assertTrue(CustomAsrEndpoint.isTranscriptionCapable("FunAudioLLM/SenseVoiceSmall"))
        assertFalse(CustomAsrEndpoint.isTranscriptionCapable("text-embedding-3-small"))
        assertEquals(emptyList(), CustomAsrEndpoint.parseModelIds("not json"))
    }

    @Test
    fun verboseJsonAndMinimalBodiesParse() {
        val r = CustomAsrEndpoint.parseTranscription(
            """{"text":"hi there","language":"en","segments":[{"text":"hi","start":0.0,"end":1.5},{"text":"there","start":1.5}]}""",
            "custom", "auto",
        )
        assertEquals("en", r.locale)
        assertEquals(2, r.segments.size)
        assertEquals(1.5, r.segments[1].end)
        assertEquals(0.0, r.segments[1].duration)
        val minimal = CustomAsrEndpoint.parseTranscription("""{"text":"ok"}""", "custom", "zh")
        assertEquals("ok", minimal.text)
        assertEquals("zh", minimal.locale)
        assertFailsWith<AsrEmptyException> { CustomAsrEndpoint.parseTranscription("""{"text":""}""", "custom", "auto") }
        assertEquals("zh", CustomAsrEndpoint.shortCode("zh-CN"))
    }

    @Test
    fun remoteListenIsWssOnly() {
        val l = RemoteLiveEndpoint::listenUrl
        assertEquals("wss://h/v1/listen", l("wss://h"))
        assertEquals("wss://h/v1/listen", l("wss://h/"))
        assertEquals("wss://h:8443/v1/listen", l("wss://h:8443/v1/listen"))
        assertEquals("wss://h/v1/listen", l("https://h"))
        assertEquals("wss://asr.example.com/v1/listen", l("asr.example.com"))
        assertNull(l("ws://h"))
        assertNull(l(""))
        assertEquals("https://h:8443/health", RemoteLiveEndpoint.healthUrl("wss://h:8443/v1/listen"))
        assertEquals("https://h/health", RemoteLiveEndpoint.healthUrl("h"))
        assertNull(RemoteLiveEndpoint.healthUrl("ws://h"))
    }

    @Test
    fun configRoundTripsAndPickerListsOnlyCompleteProviders() {
        val cfg = AppConfig.parse(
            """{"asr_providers":{
                 "custom":{"endpoint":"http://h:8000","api_key":"K","model":"whisper-large-v3","language":"zh"},
                 "remote_live":{"endpoint":"wss://r"},
                 "azure":{"key":"k","region":"eastus","verified_fingerprint":"x","verified_ok":false}}}""",
        )
        assertEquals("whisper-large-v3", cfg.asrProviders.custom?.model)
        assertTrue(cfg.asrProviders.hasConfiguredProvider)
        // Azure fingerprint does not match → unverified, so it stays selectable; remote has no key → not selectable.
        assertEquals(listOf("azure", "custom"), cfg.asrProviders.selectable)

        val back = JsonParser(cfg.toJson()).parse() as Map<*, *>
        val asr = back["asr_providers"] as Map<*, *>
        assertEquals("zh", (asr["custom"] as Map<*, *>)["language"])
        assertEquals("wss://r", (asr["remote_live"] as Map<*, *>)["endpoint"])

        val withKey = cfg.copy(asrProviders = cfg.asrProviders.copy(remoteLive = AppConfig.AsrProviders.RemoteLive("wss://r", "S")))
        assertEquals(listOf("azure", "custom", "remote"), withKey.asrProviders.selectable)
        val picked = withKey.withAsrPrimary("remote")
        assertEquals("remote", picked.defaults.asrPrimary)
        assertEquals(listOf("azure", "custom"), picked.defaults.asrFallbacks)

        val onlyCustom = AppConfig(asrProviders = AppConfig.AsrProviders(custom = AppConfig.AsrProviders.Custom("http://h")))
        assertEquals("custom", onlyCustom.adoptAsrPrimaryIfUnset().defaults.asrPrimary)
        assertEquals("azure", AppConfig().adoptAsrPrimaryIfUnset().defaults.asrPrimary)
    }
}
