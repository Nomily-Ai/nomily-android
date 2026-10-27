package com.nomily.app.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupAssistantTest {

    @Test
    fun `chinese unless an explicit non-chinese language is set`() {
        assertEquals(SetupAssistant.ZH_PROMPT, SetupAssistant.prompt(null))
        assertEquals(SetupAssistant.ZH_PROMPT, SetupAssistant.prompt("zh-Hans"))
        assertEquals(SetupAssistant.EN_PROMPT, SetupAssistant.prompt("en"))
        assertEquals(SetupAssistant.EN_PROMPT, SetupAssistant.prompt("pt-BR"))
    }

    @Test
    fun `prompt carries the ports the app speaks`() {
        for (p in listOf(SetupAssistant.ZH_PROMPT, SetupAssistant.EN_PROMPT)) {
            assertTrue("/v1/transcribe" in p && "/v1/listen" in p && "12300" in p && "12301" in p && "11434" in p)
        }
    }
}
