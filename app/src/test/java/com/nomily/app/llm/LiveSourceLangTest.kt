package com.nomily.app.llm

import com.nomily.app.llm.LanguageService.Companion.AZURE_SPEECH_LOCALES
import com.nomily.app.llm.LanguageService.Companion.WHISPER_LANGUAGES
import com.nomily.app.llm.LanguageService.Companion.adaptSourceLang
import com.nomily.app.llm.LanguageService.Companion.sourceLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveSourceLangTest {
    @Test fun listFollowsProvider() {
        assertSame(WHISPER_LANGUAGES, sourceLanguages("local"))
        assertSame(AZURE_SPEECH_LOCALES, sourceLanguages("azure"))
        assertSame(AZURE_SPEECH_LOCALES, sourceLanguages(null))
    }

    @Test fun keptWhenListed() = assertEquals("zh-TW", adaptSourceLang("zh-TW", AZURE_SPEECH_LOCALES))

    @Test fun localeToWhisperCode() = assertEquals("en", adaptSourceLang("en-US", WHISPER_LANGUAGES))

    @Test fun whisperCodeToFirstLocale() = assertEquals("zh-CN", adaptSourceLang("zh", AZURE_SPEECH_LOCALES))

    @Test fun unknownKept() = assertEquals("xx-YY", adaptSourceLang("xx-YY", AZURE_SPEECH_LOCALES))
}
