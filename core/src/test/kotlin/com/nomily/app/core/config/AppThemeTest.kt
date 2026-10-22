package com.nomily.app.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppThemeTest {

    @Test
    fun `tags match iOS and unknown resolves to crimson`() {
        assertEquals(
            listOf("crimson", "graphite", "ocean", "forest", "violet", "amber"),
            AppTheme.entries.map { it.tag },
        )
        assertEquals(AppTheme.OCEAN, AppTheme.resolve("ocean"))
        assertEquals(AppTheme.CRIMSON, AppTheme.resolve(null))
        assertEquals(AppTheme.CRIMSON, AppTheme.resolve("neon"))
    }

    @Test
    fun `app_theme round-trips and is omitted when unset`() {
        assertNull(AppConfig.parse("{}").appTheme)
        assertFalse(AppConfig().toJson().contains("app_theme"))
        val cfg = AppConfig.parse("""{"app_theme":"forest"}""")
        assertEquals("forest", cfg.appTheme)
        assertEquals("forest", AppConfig.parse(cfg.toJson()).appTheme)
    }

    @Test
    fun `only light fills take dark content`() {
        // Brand red keeps white text in both modes, as before themes existed.
        assertFalse(AppTheme.CRIMSON.onAccentIsDark(dark = false))
        assertFalse(AppTheme.CRIMSON.onAccentIsDark(dark = true))
        assertFalse(AppTheme.OCEAN.onAccentIsDark(dark = true))
        assertFalse(AppTheme.VIOLET.onAccentIsDark(dark = true))
        assertTrue(AppTheme.GRAPHITE.onAccentIsDark(dark = true))
        assertTrue(AppTheme.AMBER.onAccentIsDark(dark = true))
        assertTrue(AppTheme.FOREST.onAccentIsDark(dark = true))
        AppTheme.entries.forEach { assertFalse(it.onAccentIsDark(dark = false), it.tag) }
    }
}
