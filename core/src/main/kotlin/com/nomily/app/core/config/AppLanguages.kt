package com.nomily.app.core.config

/**
 * Languages supported by the app — **pure Kotlin, unit‑testable**.
 *
 * ## Why display names are not translated
 *
 * Each entry in the list uses its **autonym** (English / 简体中文 / Русский / …) and does not change with the current UI language.
 * Users arrive at this screen mainly because they **cannot understand the current UI**; translating “German” into Japanese according to the current UI would force them to guess their language among unreadable terms. The system settings language list works the same way.
 *
 * ## This table must stay in sync with res/values-*
 *
 * If a language appears here, a corresponding `values-xx/strings.xml` must exist;
 * conversely, adding a new translation resource directory requires adding it to this table, otherwise the new language will never appear in the settings page.
 */
object AppLanguages {

    /** Fallback language — used when the selected option is out of range or the system language is not in the table. */
    const val FALLBACK = "en"

    /** An optional language: `tag` is a BCP‑47 tag, `autonym` is the autonym. */
    data class Language(val tag: String, val autonym: String)

    /**
     * Table of supported languages. Order = display order on the settings page:
     * English first (fallback language), the rest sorted alphabetically by tag to avoid differing sort orders when adding languages later.
     */
    val SUPPORTED: List<Language> = listOf(
        Language("en", "English"),
        Language("ar", "العربية"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("pt-BR", "Português (Brasil)"),
        Language("ru", "Русский"),
        Language("zh-Hans", "简体中文"),
    )

    /** Tag → autonym; unknown tags are returned unchanged to avoid blank lines in the settings page. */
    fun autonymOf(tag: String): String =
        SUPPORTED.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.autonym ?: tag

    /**
     * Collapse **any** language tag to one that we actually have a translation for.
     *
     * Three‑tier matching, from strict to lenient:
     * 1. Exact tag match (`pt-BR` → `pt-BR`);
     * 2. Match only the primary language sub‑tag (`zh-Hant-TW` → `zh-Hans`, `pt-PT` → `pt-BR`) —
     *    better to provide a translation within the same language family than fall back to English;
     * 3. If none match, use [FALLBACK] (the requirement hard‑codes “out‑of‑range uses English”).
     *
     * @param tag `null`/empty is treated as unknown and falls back.
     */
    fun resolve(tag: String?): String {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty()) return FALLBACK
        SUPPORTED.firstOrNull { it.tag.equals(t, ignoreCase = true) }?.let { return it.tag }
        val primary = t.substringBefore('-').substringBefore('_')
        SUPPORTED.firstOrNull { it.tag.substringBefore('-').equals(primary, ignoreCase = true) }
            ?.let { return it.tag }
        return FALLBACK
    }

    /** Whether this tag is **directly** supported (no language‑family fallback). Used for checkmarks on the settings page. */
    fun isSupported(tag: String?): Boolean =
        tag != null && SUPPORTED.any { it.tag.equals(tag, ignoreCase = true) }
}
