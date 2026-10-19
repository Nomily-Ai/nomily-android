package com.nomily.app.core.llm

import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.config.AppConfig.LlmProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmChainTest {

    private fun cfg(p: LlmProviders) = AppConfig().copy(llmProviders = p)

    /** The case seen in the field: primary is Open Router with no key, Ollama is set up. */
    private val brokenPrimary = cfg(
        LlmProviders(
            primary = "openRouter",
            openRouter = LlmProviders.Endpoint(apiKey = null, endpoint = ""),
            ollama = LlmProviders.Endpoint(endpoint = "http://10.0.0.2:11434", model = "qwen2.5:3b"),
            openai = LlmProviders.Keyed(apiKey = "sk", model = "gpt-4.1"),
        ),
    )

    @Test
    fun primaryLeadsThenLocalBeforeCloud() {
        assertEquals(listOf("openRouter", "ollama", "openai"), LlmChain.candidateChain(brokenPrimary))
    }

    @Test
    fun gapNamesWhatIsMissing() {
        assertEquals(LlmChain.Gap.NoApiKey("Open Router"), LlmChain.configGap("openRouter", brokenPrimary))
        assertNull(LlmChain.configGap("ollama", brokenPrimary))
        val noModel = cfg(LlmProviders(primary = "openai", openai = LlmProviders.Keyed("sk")))
        assertEquals(LlmChain.Gap.NoModel("OpenAI"), LlmChain.configGap("openai", noModel))
    }

    @Test
    fun suggestsFirstUsableFallback() {
        assertEquals("ollama", LlmChain.suggestedPrimary(brokenPrimary))
        val healthy = cfg(LlmProviders(primary = "openai", openai = LlmProviders.Keyed("sk", "gpt-4.1")))
        assertNull(LlmChain.suggestedPrimary(healthy))
        assertTrue(LlmChain.isConfigured(healthy))
    }

    @Test
    fun preferredMovesToFront() {
        assertEquals(listOf("ollama", "openRouter", "openai"), LlmChain.preferring(listOf("openRouter", "ollama", "openai"), "ollama"))
        assertEquals(listOf("a", "b"), LlmChain.preferring(listOf("a", "b"), "z"))
    }

    @Test
    fun splitKeepsChunksUnderCapAndLosesNothing() {
        val para = "这是一句话。".repeat(300) // 1800 chars
        val text = List(10) { para }.joinToString("\n\n")
        val chunks = LlmChain.split(text, 4000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4000 })
        assertEquals(text.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
        assertEquals(listOf("short"), LlmChain.split("short"))
    }
}
