package com.nomily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.llm.LanguageService
import com.nomily.app.ui.theme.NomilyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baseline — captures "how it looks now" so CI can immediately flag regressions.
 *
 * Without it, changing a single padding or a component's default value can silently
 * regress a screen and only surface when someone compares screenshots by hand.
 *
 * How to run (JDK 21 on `JAVA_HOME`):
 *
 *   ./gradlew :app:recordRoborazziDebug   # Record baseline: run only after changing the UI and visually confirming the new appearance
 *   ./gradlew :app:verifyRoborazziDebug   # Regression: compares against baseline; diff images are saved to build/outputs/roborazzi
 *
 * Baseline PNGs are stored in `app/src/test/screenshots/`. Humans only need to review diffs that appear in PRs.
 *
 * ## Two critical pitfalls
 *
 * 1. **`mainClock.autoAdvance = false`**: Screens with input fields (e.g., blinking cursor in a search box) trigger a
 *    never-ending animation. Compose never reaches idle state, so `captureRoboImage` waits for 60 seconds and then throws
 *    `AppNotIdleException`. Disabling the virtual clock freezes the frame at the first frame.
 * 2. **First run requires internet**: Robolectric needs to download `android-all-instrumented` (≈200 MB) to
 *    `~/.m2/repository`, and Roborazzi needs `nativeruntime-dist-compat` (≈160 MB). Gradle's built-in HTTP client
 *    may drop these large downloads mid-transfer on this machine. The easiest workaround is to `curl` them directly
 *    into the corresponding cache directories before running.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotBaselineTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        // See class comment pitfall 1: Infinite animations like cursor blinking will prevent Compose from ever becoming idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NomilyTheme(darkTheme = dark) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /**
     * Real VM, not fake. After changing the `KeyVault` Keystore handle to lazy loading, constructing the VM no longer opens
     * AndroidKeyStore (Robolectric does not have this provider), allowing the empty-state UI to render.
     * Paths that use keys will still hit the Keystore — those are not in the screenshot range.
     */
    private fun viewModel(): NomiViewModel =
        NomiViewModel(ApplicationProvider.getApplicationContext<Application>())

    // MARK: - Screen that does not depend on device state

    @Test
    fun azureSetupGuide() = capture("azure-setup-guide") {
        AzureSetupGuide(onDismiss = {})
    }

    @Test
    fun azureSetupGuideDark() = capture("azure-setup-guide-dark", dark = true) {
        AzureSetupGuide(onDismiss = {})
    }

    // AzureLocalesPicker can't be captured for now: it uses a Material3 `AlertDialog`, and even
    // with `mainClock.autoAdvance = false` it runs the full 60 seconds without going idle (the
    // `LanguagePickerDialog`, which also has a search box, uses a fullscreen Dialog and has no
    // such problem). To capture it, first figure out which part of AlertDialog's animation keeps running; don't touch production code just for screenshots.

    @Test
    fun languagePicker() = capture("language-picker") {
        LanguagePickerDialog(
            title = "Source language",
            languages = listOf(
                LanguageService.Language("en-US", "English (United States)", "English"),
                LanguageService.Language("zh-CN", "Chinese (Simplified)", "Simplified Chinese"),
                LanguageService.Language("ja-JP", "Japanese", "Japanese"),
            ),
            defaultOption = null,
            onPick = {},
            onDismiss = {},
        )
    }

    @Test
    fun connectionPillNoDevice() = capture("connection-pill-no-device") {
        ConnectionPill(
            connectedName = null,
            battery = null,
            isRecording = false,
            isReconnecting = false,
            knownDevices = emptyList(),
            onTapDevice = {},
            onAddDevice = {},
            onSelectDevice = {},
        )
    }

    // MARK: - ViewModel for Eating Screen
    //
    // All render **empty states** (no connected devices, no local segments), which is exactly the layer we must guard:
    // Group card margins, line heights, separator start points, and group header spacing. The device panel, scan list, and real-time connection states
    // are not part of this batch — their visuals are determined by connected devices, which cannot provide stable input.

    @Test
    fun settingsRoot() = capture("settings-root") {
        SettingsScreen(vm = viewModel(), onPush = {})
    }

    @Test
    fun settingsRootDark() = capture("settings-root-dark", dark = true) {
        SettingsScreen(vm = viewModel(), onPush = {})
    }

    /**
     * The subpage being set is a private composable inside `SettingsScreen` with no independent entry — click the root page line
     * Navigating into it is cleaner than changing them to internal just for test visibility.
     *
     * The order of toggling the clock matters: **the virtual clock must run automatically while clicking**, otherwise event injection and the subsequent
     * recomposition will not occur, and the captured screenshot will still be the root page (the first version quietly recorded four identical images);
     * and **the clock must be stopped before taking a screenshot**, because the subpage contains an input field; a blinking cursor prevents Compose from ever becoming idle.
     */
    private fun captureSettingsSubPage(name: String, titleRes: Int) {
        val title = ApplicationProvider
            .getApplicationContext<Application>()
            .getString(titleRes)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            NomilyTheme(darkTheme = false) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { SettingsScreen(vm = viewModel(), onPush = {}) }
            }
        }
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun settingsAsr() = captureSettingsSubPage("settings-asr", R.string.settings_asr_providers)

    @Test
    fun settingsLlm() = captureSettingsSubPage("settings-llm", R.string.settings_llm_providers)

    @Test
    fun settingsRecordings() =
        captureSettingsSubPage("settings-recordings", R.string.settings_recordings)

    @Test
    fun settingsDevicesLibrary() =
        captureSettingsSubPage("settings-devices-library", R.string.settings_devices_library)

    @Test
    fun templateManager() = capture("template-manager") {
        TemplateManagerPage(vm = viewModel(), onPush = {}, onDismiss = {})
    }
}
