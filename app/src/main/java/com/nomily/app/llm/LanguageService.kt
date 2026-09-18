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

        /** Selection limit (Azure returns up to 10, but 4 displayed in the UI is sufficient and easy to choose). */
        const val AZURE_MAX_SELECTIONS = 4

        private const val TAG = "LanguageService"
        private const val CACHE_NAME = "target-languages.json"
        private const val ENDPOINT =
            "https://api.cognitive.microsofttranslator.com/languages?api-version=3.0&scope=translation"
    }
}
