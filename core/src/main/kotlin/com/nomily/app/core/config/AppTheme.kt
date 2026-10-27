package com.nomily.app.core.config

/**
 * App-wide colour themes — palettes and tags shared with iOS `AppTheme` (Components/AppTheme.swift).
 *
 * [AppConfig.appTheme] stores the bare [tag] under `app_theme`, same key and values as iOS, so a
 * `config.json` moved between platforms keeps its theme. Colours are `0xRRGGBB` here so this stays
 * pure Kotlin; the UI layer turns them into Compose colours.
 *
 * ⚠️ Only **decorative** colour follows the theme (controls, links, selection). Semantic colours —
 * recording red, destructive actions, success green, warning orange — stay fixed: a theme must not
 * be able to turn "recording" into "not recording". The splash keeps brand red, which is why
 * [CRIMSON] is the default.
 */
enum class AppTheme(
    val tag: String,
    val accentLight: Int,
    val accentDark: Int,
    val deepLight: Int,
    val deepDark: Int,
) {
    /** Brand red; matches the splash and the previous fixed accent. */
    CRIMSON("crimson", 0xE41D25, 0xFF6669, 0x9C0F16, 0xC41E27),
    /** Near-monochrome graphite — no hue at all. */
    GRAPHITE("graphite", 0x2C2C2E, 0xE5E5EA, 0x000000, 0x8E8E93),
    OCEAN("ocean", 0x0A63C9, 0x58A6FF, 0x063E80, 0x0A63C9),
    FOREST("forest", 0x14794C, 0x45D08A, 0x0A4E31, 0x14794C),
    VIOLET("violet", 0x6437D6, 0xB49BFF, 0x3F219B, 0x6437D6),
    AMBER("amber", 0xA85A05, 0xFFB454, 0x6E3A02, 0xA85A05);

    fun accent(dark: Boolean): Int = if (dark) accentDark else accentLight
    fun deep(dark: Boolean): Int = if (dark) deepDark else deepLight

    /**
     * Whether content drawn on a filled accent surface (selected chip, filled button) must be black
     * instead of white. The light dark-mode accents (graphite, forest, amber) would otherwise put
     * white text on a near-white or bright fill.
     */
    fun onAccentIsDark(dark: Boolean): Boolean = relativeLuminance(accent(dark)) > ON_ACCENT_LUMINANCE

    companion object {
        val DEFAULT = CRIMSON

        /**
         * Luminance above which black reads better than white on the accent. Chosen so the existing
         * crimson / ocean / violet fills keep white text (all below 0.41) and only clearly light fills flip.
         */
        private const val ON_ACCENT_LUMINANCE = 0.45

        /** Unknown or missing tags resolve to the default, so a newer or hand-edited config never leaves the app untinted. */
        fun resolve(tag: String?): AppTheme = entries.firstOrNull { it.tag == tag } ?: DEFAULT

        /** WCAG relative luminance of `0xRRGGBB`. */
        fun relativeLuminance(rgb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((rgb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
