package com.nomily.app.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLanguagesTest {

    @Test
    fun `full tag hit`() {
        assertEquals("pt-BR", AppLanguages.resolve("pt-BR"))
        assertEquals("zh-Hans", AppLanguages.resolve("zh-Hans"))
        assertEquals("ja", AppLanguages.resolve("ja"))
    }

    /** System language often includes region (e.g., `de-AT`, `zh-Hant-TW`) — translations within the same language family are better than directly falling back to English. */
    @Test
    fun `fallback by main language sub-tag`() {
        assertEquals("de", AppLanguages.resolve("de-AT"))
        assertEquals("pt-BR", AppLanguages.resolve("pt-PT"))
        assertEquals("zh-Hans", AppLanguages.resolve("zh-Hant-TW"))
    }

    /** Hardcoded fallback requirement: English for all unsupported ranges. */
    @Test
    fun `unsupported language fallback to english`() {
        assertEquals("en", AppLanguages.resolve("th"))
        assertEquals("en", AppLanguages.resolve("sw-KE"))
        assertEquals("en", AppLanguages.resolve(null))
        assertEquals("en", AppLanguages.resolve("  "))
    }

    /** The page's checkmark only recognizes natively supported tags. Do not mark de-AT just because it can be matched through fallback. */
    @Test
    fun `isSupported does not fallback to language family`() {
        assertTrue(AppLanguages.isSupported("de"))
        assertFalse(AppLanguages.isSupported("de-AT"))
        assertFalse(AppLanguages.isSupported(null))
    }

    @Test
    fun `each language has native name and tags are unique`() {
        val tags = AppLanguages.SUPPORTED.map { it.tag.lowercase() }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(AppLanguages.SUPPORTED.all { it.autonym.isNotBlank() })
        assertTrue(AppLanguages.SUPPORTED.any { it.tag == AppLanguages.FALLBACK })
    }

    // ── Implementation in config.json ────────────────────────────────────────

    /** Not set = follow system. The default value must be null, not "en". */
    @Test
    fun `follow system by default`() {
        assertNull(AppConfig().appLanguage)
        assertNull(AppConfig.parse("{}").appLanguage)
    }

    @Test
    fun `app language read write round trip`() {
        val cfg = AppConfig.parse("""{"app_language":"zh-Hans"}""")
        assertEquals("zh-Hans", cfg.appLanguage)
        // Compare the results of **re-parsing**, not the JSON text — indentation/whitespace is at the discretion of writeJson
        assertEquals("zh-Hans", AppConfig.parse(cfg.toJson()).appLanguage)
    }

    /** Follow the system time **do not write this key** (instead of writing null). */
    @Test
    fun `do not write key when following system time`() {
        assertFalse(AppConfig().toJson().contains("app_language"))
    }
}
