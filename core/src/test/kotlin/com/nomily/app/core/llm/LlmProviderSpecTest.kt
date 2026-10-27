package com.nomily.app.core.llm

import com.nomily.app.core.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmProviderSpecTest {

    @Test
    fun `order and labels match the provider list`() {
        assertEquals(
            listOf("openai", "claude", "gemini", "qwen", "deepseek", "doubao", "openRouter", "ollama", "custom"),
            LlmProviderSpec.ALL.map { it.id },
        )
        assertEquals("ChatGPT", LlmChain.providerLabel("openai"))
        assertEquals("unknown-x", LlmChain.providerLabel("unknown-x"))
    }

    @Test
    fun `chat filter drops embedding and speech models but keeps vision previews`() {
        assertFalse(LlmModels.isChatCapable("text-embedding-3-small"))
        assertFalse(LlmModels.isChatCapable("Whisper-1"))
        assertFalse(LlmModels.isChatCapable("gpt-4o-realtime-preview"))
        assertFalse(LlmModels.isChatCapable("BAAI/bge-m3"))
        assertTrue(LlmModels.isChatCapable("gpt-4-vision-preview"))
        assertTrue(LlmModels.isChatCapable("qwen-max"))
        assertEquals(listOf("gpt-4o"), LlmModels.chatCapable(listOf("gpt-4o", "tts-1", "dall-e-3")))
        assertEquals(3, LlmModels.latencyBand(5000))
    }

    @Test
    fun `preset round trips through json and joins the chain after built-ins`() {
        val json = """{"llm_providers":{"primary":"qwen","custom":{"endpoint":"http://x","model":"m"},
            "presets":{"qwen":{"api_key":"K","endpoint":"https://q/v1","model":"qwen-max"},"deepseek":{"endpoint":"https://d/v1"}}}}"""
        val cfg = AppConfig.parse(json)
        assertEquals("qwen-max", cfg.llmProviders.presets["qwen"]?.model)
        assertEquals(listOf("qwen", "custom"), LlmChain.candidateChain(cfg))
        assertEquals(LlmChain.Gap.NoApiKey("DeepSeek"), LlmChain.configGap("deepseek", cfg))
        assertNull(LlmChain.configGap("qwen", cfg))
        assertTrue(cfg.usesCloudService)
        val back = AppConfig.parse(cfg.toJson())
        assertEquals(cfg.llmProviders.presets, back.llmProviders.presets)
    }

    @Test
    fun `ark folds into doubao without overwriting`() {
        val cfg = AppConfig.parse(
            """{"llm_providers":{"primary":"ark","presets":{
            "ark":{"api_key":"A","endpoint":"https://ark","model":"ep-1"},
            "doubao":{"endpoint":"https://ark","model":"doubao-x"}}}}""",
        ).llmProviders.mergeArkIntoDoubao()
        assertNull(cfg.presets["ark"])
        assertEquals("doubao", cfg.primary)
        assertEquals("A", cfg.presets["doubao"]?.apiKey)
        assertEquals("doubao-x", cfg.presets["doubao"]?.model)
    }

    @Test
    fun `uniform accessors create and remove slots`() {
        val q = LlmProviderSpec.QWEN
        var p = AppConfig.LlmProviders().withApiKey(q, " k ").withModel(q, "qwen-plus")
        assertEquals("k", p.apiKeyValue(q))
        assertEquals(q.defaultEndpoint, p.endpointValue(q))
        p = p.copy(primary = "qwen").removed(q)
        assertTrue(p.presets.isEmpty())
        assertNull(p.primary)
        val o = LlmProviderSpec.CHATGPT
        assertNull(AppConfig.LlmProviders().withApiKey(o, "").openai)
    }
}
