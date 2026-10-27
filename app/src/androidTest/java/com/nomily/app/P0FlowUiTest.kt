package com.nomily.app

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nomily.app.ui.CLIP_ROW_TAG
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **P0 main flow end-to-end UI test — requires a real device + a broadcasting D·NOTE device.**
 *
 * Why use an instrumented test instead of `adb shell input tap`: this debugging device (Xiaomi HyperOS)
 * **disables adb event injection** (`input tap` throws SecurityException, similar to `pm grant` restrictions).
 * instrumentation injects events as the app itself, bypassing this restriction — and the assertions can later be used for regression testing.
 *
 * The exercised path is the full P0 chain:
 * ```
 * Scan → Connect → (record a sample if the device has no recording) → Retrieve (download/decrypt/encapsulate Ogg) → Store in library → Play
 * ```
 *
 * ⚠️ **It does not assess UI aesthetics** — visual fidelity and feel must be evaluated by a human.
 * Here we only guarantee that the functionality and flow work.
 *
 * ## All assertions should use `R.string`, never hard‑coded English literals
 *
 * A literal like `"Scanning…"` stops matching the moment the wording in `strings.xml`
 * changes, and every assertion after it fails — the failure reads as if the UI never
 * appeared, which sends you looking in the wrong place.
 *
 * A test that is always red and one that is always green both lack informational value:
 * **the former no longer reports the state of the subject under test, only that it is outdated.**
 *
 * With `R.string`, neither a wording change nor a language switch breaks the test.
 */
@RunWith(AndroidJUnit4::class)
class P0FlowUiTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * **Before the Activity starts**, grant the BLE runtime permission.
     *
     * Why this must be a rule and cannot rely on `adb shell pm grant`:
     * **Every time the APK is installed, any granted runtime permissions are cleared**, and running instrumented tests reinstalls the app.
     * Manually using `pm grant` is only effective *between this install and the next* — it inevitably expires.
     *
     * The symptom of missing permission is **highly misleading**: after MainActivity starts, the system permission dialog appears immediately,
     * the Activity is PAUSED, Compose tests cannot obtain the UI tree, and report
     * *"No compose hierarchies found"* — it looks as if the UI never started,
     * whereas the real cause is a permission dialog covering it.
     *
     * Do not use `GrantPermissionRule`: its permission list is static, while **which permissions to grant depends on the runtime SDK**
     * (API 31+ uses `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`; API 30 and below use `ACCESS_FINE_LOCATION`,
     * see `maxSdkVersion=30` in AndroidManifest). A static list on a different SDK would attempt to grant
     * a permission not declared in the manifest, resulting in a SecurityException.
     */
    private val grantBlePermissions = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val inst = InstrumentationRegistry.getInstrumentation()
                val pkg = inst.targetContext.packageName
                val needed = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    listOf(
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                    )
                } else {
                    listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                needed.forEach { perm ->
                    runCatching { inst.uiAutomation.grantRuntimePermission(pkg, perm) }
    .onFailure { android.util.Log.w("P0FlowUiTest", "Grant $perm failed: ${it.message}") }
                }
                base.evaluate()
            }
        }
    }

    /** Authorization must happen **before** the Activity starts — so grant is outside, compose is inside. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(grantBlePermissions).around(compose)

    /** Read copy from resources — don't hardcode English in assertions (see class comment). */
    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private companion object {
        const val PASSPHRASE = "testpass"

        /** Scan the fixed prefix of the "SID xxxxxx · vx.y · nn%" segment in a line — used as a line anchor. */
        const val SID_PREFIX = "SID "
        const val SCAN_TIMEOUT = 45_000L
        const val CONNECT_TIMEOUT = 40_000L
        const val FETCH_TIMEOUT = 120_000L
    }

    /**
     * Wait until a certain `contentDescription` appears.
     *
     * Icon buttons (play/pause/stop) **do not have text nodes**, the label is only attached to `contentDescription`,
     * so [awaitText] will never catch them — stop using [awaitText] to wait for icons.
     */
    private fun awaitContentDescription(desc: String, timeoutMs: Long) {
        try {
            compose.waitUntil(timeoutMs) {
                compose.onAllNodesWithContentDescription(desc)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            runCatching { compose.onAllNodes(isRoot()).onFirst().printToLog("P0FlowUiTest") }
    throw AssertionError("Waiting for accessibility label \"$desc\" timed out (${timeoutMs}ms)", e)
        }
    }

    /** Wait until a certain text appears; if timeout, print all text on the current screen to facilitate debugging. */
    private fun awaitText(text: String, timeoutMs: Long, substring: Boolean = true) {
        try {
            compose.waitUntil(timeoutMs) {
                compose.onAllNodesWithText(text, substring = substring)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // onRoot() matches two roots when a dialog is present and throws its own exception, obscuring the real cause — don't let diagnostic code cause confusion
            runCatching { compose.onAllNodes(isRoot()).onFirst().printToLog("P0FlowUiTest") }
    throw AssertionError("Waiting for \"$text\" timed out (${timeoutMs}ms)", e)
        }
    }

    @Test
    fun p0_flow_scan_connect_record_fetch_play() {
        // ① Scan — pull up the scan page from the pill; **scanning starts automatically on page entry**, and there is no longer a "Scan" button to tap
        compose.onNodeWithText(str(R.string.pill_add_device)).performClick()
        awaitText(str(R.string.scanner_looking_for_devices), 5_000)
        // At least one device in the directory is scanned. The row no longer has a "Connect" button (the whole row is clickable),
        //         so we use the fixed "SID …" segment in the row as an anchor.
        awaitText(SID_PREFIX, SCAN_TIMEOUT)

        // ② Connect (click the first one) — clicking the SID text within the line is equivalent to clicking the line
        compose.onAllNodesWithText(SID_PREFIX, substring = true).onFirst().performClick()
        awaitText(str(R.string.device_refresh), CONNECT_TIMEOUT)   // Device area appears = Connected
        awaitText(str(R.string.device_encryption_state_row), 5_000)

        // ③ Precondition: The device must have a recording
        //
        // The product UI **does not** have a recording entry (it shouldn't have one — see i18n rule 4,
        //         // “Record 6s” has been removed as a debug entry). To generate data, run the debug variant's collection activity first:
        //   adb shell am start -n com.nomily.app/.CaptureClipActivity
        // Here we only assert preconditions, not trigger a recording in the product UI.
        val hasFiles = compose.onAllNodesWithText(str(R.string.clip_fetch)).fetchSemanticsNodes().isNotEmpty()
        assertTrue(
    "Precondition not met: no recording on device. Run CaptureClipActivity to create one before this test.",
            hasFiles,
        )

        // ④ Retrieve one: download → decrypt (if encrypted) → encapsulate Ogg
        compose.onAllNodesWithText(str(R.string.clip_fetch)).onFirst().performClick()

        // The encryption device will first require a password
        val needsPassphrase = runCatching {
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText(str(R.string.encryption_sheet_title_set))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (needsPassphrase) {
            compose.onNodeWithTag(com.nomily.app.ui.PASSPHRASE_FIELD_TAG).performTextInput(PASSPHRASE)
            compose.onNodeWithText(str(R.string.encryption_action_save)).performClick()
            // 512MiB Argon2 ~1.6s; after derivation, click Fetch again
            awaitText(str(R.string.clip_fetch), 30_000)
            compose.onAllNodesWithText(str(R.string.clip_fetch)).onFirst().performClick()
        }

        // ⑤ Switch to the "Database" segment, and wait for the retrieved entry to be added to the list
        //
        // Originally we were waiting for `"${'$'}{str(...)}: 1"` — the escaping was wrong, and at runtime it was waiting for the **literal**
        // `${'$'}{str(R.string.recordings_library)}: 1` never appears on the UI; moreover
        // `recordings_library` is only a **section title** (“Library”), not the footnote on the “N items” line.
        // Now changed to: tap the section to enter the library, then wait for the line itself to appear.
        compose.onAllNodesWithText(str(R.string.recordings_library)).onFirst().performClick()
        compose.waitUntil(FETCH_TIMEOUT) {
            compose.onAllNodesWithTag(CLIP_ROW_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // ⑥ Open the details page and then play (if it starts playing, it's considered working; the audio quality should be acceptable to human listeners)
        //
        // **The playback control is on the detail page, not in the library row**, and it's an icon button ——
        //         // （The text is attached to `contentDescription`, `onNodeWithText` cannot match (this is why
        //         // the test kept failing before: it tried to click a non-existent "Play" text button.
        compose.onAllNodesWithTag(CLIP_ROW_TAG).onFirst().performClick()
        awaitContentDescription(str(R.string.clip_play), 10_000)
        compose.onNodeWithContentDescription(str(R.string.clip_play)).performClick()
        // Enter playback state = the same button flips to "Pause" (Stop is a separate button and cannot be used as evidence of starting playback)
        awaitContentDescription(str(R.string.live_pause), 10_000)

        assertTrue(
    "Database should contain at least one clip",
            compose.onAllNodesWithText(".ogg", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
