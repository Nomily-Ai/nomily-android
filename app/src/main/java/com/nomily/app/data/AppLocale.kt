package com.nomily.app.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.config.AppLanguages
import java.io.File
import java.util.Locale

/**
 * Implementation of UI language selection.
 *
 * ## Why not use `AppCompatDelegate.setApplicationLocales`
 *
 * That is the official recommended path, but it requires `androidx.appcompat`, and this app is pure Compose +
 * `ComponentActivity` (see the dependency list in `app/build.gradle.kts`; no appcompat at all).
 * Introducing appcompat just for a single setting and switching the Activity to `AppCompatActivity`
 * would pull the entire theming system down. The framework `LocaleManager` in API 33 also doesn’t reach minSdk 24.
 * Therefore we take the **simplest approach**: override the Configuration locale in `attachBaseContext`,
 * and call `recreate()` when the language changes. One consistent behavior across all versions, no new dependencies.
 *
 * ## Where the language is read from
 *
 * Directly read `app_language` from `config.json` — **no separate SharedPreferences**.
 * Keeping a duplicate would inevitably drift: the settings page writes to config, a restart reads prefs,
 * and any write failure would cause the two sources to diverge. The cost is an extra synchronous disk read + JSON parsing in `attachBaseContext`
 * (a few milliseconds, the file is freshly written by the same process and resides in the page cache).
 */
object AppLocale {

    private const val TAG = "AppLocale"
    private const val FILE_NAME = "config.json"

    /**
     * Currently selected language tag; `null` = follow system (also the value when never set).
     *
     * Any disk‑read failure is treated as follow‑system — this runs in `attachBaseContext`,
     * **must not throw**, otherwise the app won’t start.
     */
    fun stored(context: Context): String? = try {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) null else AppConfig.parse(f.readText()).appLanguage
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the language setting, falling back to the system language: ${e.message}")
        null
    }

    /**
     * Wrap the Activity’s base context with the selected language.
     *
     * When following the system, **return as‑is**: no override, so system language, locale formats, and subsequent system language changes remain unchanged.
     * If the system language is outside the supported range, we don’t need to handle it — the resource system will fall back to `res/values/` (English),
     * which matches the requirement “use English for unsupported languages”.
     */
    fun wrap(base: Context): Context {
        val tag = stored(base) ?: run {
            // ⚠️ When following the system you can’t just “do nothing”: the previous selection of a specific language called
            // `Locale.setDefault` here, which is **process‑wide**, and Activity recreation won’t reset it.
            // So after switching back to follow‑system, the resource system’s strings revert immediately, but
            // `Locale.getDefault()`‑based date/time formats stay at the previous language — you need to kill the process to reset
            // (2026-08-12 test: the Chinese UI still showed dates as "Aug 12, 2026 16:36:44").
            // Therefore we must actively reset the default Locale to the system’s current primary language.
            // Use the **system** configuration, not the **base** one: the base Resources may have been altered by the previous
            // createConfigurationContext, whereas the system’s version is untouched.
            val system = android.content.res.Resources.getSystem()
                .configuration.locales.get(0) ?: Locale.getDefault()
            if (Locale.getDefault() != system) Locale.setDefault(system)
            return base
        }
        // The stored value may come from an older version or manual edit — first converge to the one that actually has a translation
        val locale = Locale.forLanguageTag(AppLanguages.resolve(tag))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * User selected a new language: after writing back to config, call this to update the UI immediately.
     *
     * `recreate()` rebuilds the Activity, re‑executing [wrap]. The ViewModel is attached to the Activity but
     * retained by `ViewModelStore`, so it **won’t** lose connections or ongoing transfers.
     */
    fun applyAndRecreate(activity: Activity) {
        activity.recreate()
    }
}
