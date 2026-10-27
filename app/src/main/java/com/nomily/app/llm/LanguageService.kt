package com.nomily.app.llm

import android.content.Context
import android.util.Log
import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional **target‑language** list: fetched from Microsoft Translator’s public language list (**no key required**) and cached locally.
 *
 * Both the summary output language and on‑demand translation target languages share this list.
 */
class LanguageService(context: Context) {

    data class Language(val code: String, val name: String, val nativeName: String)


    private val cache = File(context.filesDir, CACHE_NAME)

    /** Return the cached list first (may be empty); if the network request succeeds, overwrite it. If unavailable, return empty — the UI then shows “follow source”. */
    fun targetLanguages(): List<Language> = runCatching { fetch() }
        .onFailure { Log.w(TAG, "Could not fetch the language list, using the cache: ${it.message}") }
        .getOrNull() ?: loadCache()

    /**
     * English name for a target [code] without touching the network: the cached Translator list first, then the
     * platform's locale name. Live translation needs it synchronously when it opens the ASR link.
     */
    fun cachedName(code: String): String? =
        loadCache().firstOrNull { it.code == code }?.name
            ?: java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.ENGLISH).takeIf { it.isNotEmpty() }

    private fun fetch(): List<Language> {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val text = try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val root = JsonParser(text).parse() as? Map<*, *> ?: return emptyList()
        val translation = root["translation"] as? Map<*, *> ?: return emptyList()
        val list = translation.mapNotNull { (code, info) ->
            val c = code as? String ?: return@mapNotNull null
            val m = info as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: c
            Language(c, name, m["nativeName"] as? String ?: name)
        }.sortedBy { it.name }
        saveCache(list)
        return list
    }

    private fun loadCache(): List<Language> = runCatching {
        if (!cache.isFile) return@runCatching emptyList()
        (JsonParser(cache.readText()).parse() as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Language(
                m["code"] as? String ?: return@mapNotNull null,
                m["name"] as? String ?: return@mapNotNull null,
                m["nativeName"] as? String ?: return@mapNotNull null,
            )
        }
    }.getOrDefault(emptyList())

    private fun saveCache(list: List<Language>) {
        runCatching {
            cache.writeText(
                writeJson(
                    list.map { sortedMapOf("code" to it.code, "name" to it.name, "nativeName" to it.nativeName) },
                ),
            )
        }.onFailure { Log.w(TAG, "Could not cache the language list: ${it.message}") }
    }

    companion object {

        /**
         * The 15 locales supported by Azure’s auto‑detect model (names copied verbatim from Azure’s official naming; do not translate them yourself).
         * When transcribing, **omitting** locales lets Azure automatically choose among these 15; only specify them if the user wants to restrict the range.
         */
        val AZURE_MULTI_LANGUAGE_LOCALES: List<Language> = listOf(
            Language("zh-CN", "Chinese (Mandarin)", "中文 (普通话)"),
            Language("en-US", "English (US)", "English (US)"),
            Language("en-GB", "English (UK)", "English (UK)"),
            Language("en-AU", "English (Australia)", "English (Australia)"),
            Language("en-CA", "English (Canada)", "English (Canada)"),
            Language("en-IN", "English (India)", "English (India)"),
            Language("ja-JP", "Japanese", "日本語"),
            Language("ko-KR", "Korean", "한국어"),
            Language("fr-FR", "French", "Français"),
            Language("fr-CA", "French (Canada)", "Français (Canada)"),
            Language("de-DE", "German", "Deutsch"),
            Language("es-ES", "Spanish (Spain)", "Español (España)"),
            Language("es-MX", "Spanish (Mexico)", "Español (México)"),
            Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)"),
            Language("it-IT", "Italian", "Italiano"),
        ).sortedBy { it.name }

        /** Live source languages when Azure transcribes (30 speech locales). */
        val AZURE_SPEECH_LOCALES: List<Language> = listOf(
            Language("zh-CN", "Chinese (Mandarin)", "中文 (普通话)"),
            Language("zh-TW", "Chinese (Taiwanese)", "中文 (台灣)"),
            Language("zh-HK", "Chinese (Cantonese)", "中文 (粵語)"),
            Language("en-US", "English (US)", "English (US)"),
            Language("en-GB", "English (UK)", "English (UK)"),
            Language("en-AU", "English (Australia)", "English (Australia)"),
            Language("ja-JP", "Japanese", "日本語"),
            Language("ko-KR", "Korean", "한국어"),
            Language("fr-FR", "French", "Français"),
            Language("de-DE", "German", "Deutsch"),
            Language("es-ES", "Spanish (Spain)", "Español (España)"),
            Language("es-MX", "Spanish (Mexico)", "Español (México)"),
            Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)"),
            Language("pt-PT", "Portuguese (Portugal)", "Português (Portugal)"),
            Language("it-IT", "Italian", "Italiano"),
            Language("ru-RU", "Russian", "Русский"),
            Language("ar-SA", "Arabic (Saudi)", "العربية"),
            Language("hi-IN", "Hindi", "हिन्दी"),
            Language("th-TH", "Thai", "ไทย"),
            Language("vi-VN", "Vietnamese", "Tiếng Việt"),
            Language("id-ID", "Indonesian", "Bahasa Indonesia"),
            Language("ms-MY", "Malay", "Bahasa Melayu"),
            Language("nl-NL", "Dutch", "Nederlands"),
            Language("pl-PL", "Polish", "Polski"),
            Language("tr-TR", "Turkish", "Türkçe"),
            Language("uk-UA", "Ukrainian", "Українська"),
            Language("sv-SE", "Swedish", "Svenska"),
            Language("cs-CZ", "Czech", "Čeština"),
            Language("da-DK", "Danish", "Dansk"),
            Language("fi-FI", "Finnish", "Suomi"),
        )

        /** Live source languages when the local (Whisper) server transcribes: bare language codes. */
        val WHISPER_LANGUAGES: List<Language> = listOf(
            Language("zh", "Chinese", "中文"),
            Language("en", "English", "English"),
            Language("ja", "Japanese", "日本語"),
            Language("ko", "Korean", "한국어"),
            Language("fr", "French", "Français"),
            Language("de", "German", "Deutsch"),
            Language("es", "Spanish", "Español"),
            Language("pt", "Portuguese", "Português"),
            Language("it", "Italian", "Italiano"),
            Language("ru", "Russian", "Русский"),
            Language("ar", "Arabic", "العربية"),
            Language("hi", "Hindi", "हिन्दी"),
            Language("th", "Thai", "ไทย"),
            Language("vi", "Vietnamese", "Tiếng Việt"),
            Language("id", "Indonesian", "Bahasa Indonesia"),
            Language("ms", "Malay", "Bahasa Melayu"),
            Language("nl", "Dutch", "Nederlands"),
            Language("pl", "Polish", "Polski"),
            Language("tr", "Turkish", "Türkçe"),
            Language("uk", "Ukrainian", "Українська"),
            Language("sv", "Swedish", "Svenska"),
            Language("cs", "Czech", "Čeština"),
            Language("da", "Danish", "Dansk"),
            Language("fi", "Finnish", "Suomi"),
            Language("el", "Greek", "Ελληνικά"),
            Language("he", "Hebrew", "עברית"),
            Language("hu", "Hungarian", "Magyar"),
            Language("no", "Norwegian", "Norsk"),
            Language("ro", "Romanian", "Română"),
            Language("sk", "Slovak", "Slovenčina"),
            Language("bg", "Bulgarian", "Български"),
            Language("hr", "Croatian", "Hrvatski"),
            Language("sr", "Serbian", "Српски"),
            Language("sl", "Slovenian", "Slovenščina"),
            Language("et", "Estonian", "Eesti"),
            Language("lv", "Latvian", "Latviešu"),
            Language("lt", "Lithuanian", "Lietuvių"),
            Language("tl", "Tagalog", "Tagalog"),
            Language("sw", "Swahili", "Kiswahili"),
            Language("ta", "Tamil", "தமிழ்"),
            Language("te", "Telugu", "తెలుగు"),
            Language("bn", "Bengali", "বাংলা"),
            Language("ur", "Urdu", "اردو"),
            Language("fa", "Persian", "فارسی"),
            Language("ml", "Malayalam", "മലയാളം"),
            Language("kn", "Kannada", "ಕನ್ನಡ"),
            Language("my", "Myanmar", "မြန်မာ"),
            Language("ka", "Georgian", "ქართული"),
            Language("az", "Azerbaijani", "Azərbaycan"),
            Language("af", "Afrikaans", "Afrikaans"),
        )

        /** Source-language list for the live page, by the primary ASR provider. */
        fun sourceLanguages(provider: String?): List<Language> =
            if (provider.equals("local", ignoreCase = true)) WHISPER_LANGUAGES else AZURE_SPEECH_LOCALES

        /**
         * Fit a saved source language to [list]: kept if listed, otherwise the closest code with the same language prefix
         * (`en-US` ↔ `en`), so switching between Azure and the local server keeps the user's language.
         */
        fun adaptSourceLang(saved: String, list: List<Language>): String {
            if (list.any { it.code == saved }) return saved
            val prefix = saved.substringBefore('-')
            return (
                list.firstOrNull { it.code == prefix }
                    ?: list.firstOrNull { it.code.startsWith("$prefix-") }
                    ?: list.firstOrNull { it.code.startsWith(prefix) }
                )?.code ?: saved
        }

        /** The source language a live session uses: the saved choice (default `en-US`) fitted to the provider's list. */
        fun liveSourceLang(cfg: com.nomily.app.core.config.AppConfig): String =
            adaptSourceLang(cfg.lastSourceLang ?: "en-US", sourceLanguages(cfg.defaults.asrPrimary))

        /** Selection limit (Azure returns up to 10, but 4 displayed in the UI is sufficient and easy to choose). */
        const val AZURE_MAX_SELECTIONS = 4

        private const val TAG = "LanguageService"
        private const val CACHE_NAME = "target-languages.json"
        private const val ENDPOINT =
            "https://api.cognitive.microsofttranslator.com/languages?api-version=3.0&scope=translation"
    }
}
