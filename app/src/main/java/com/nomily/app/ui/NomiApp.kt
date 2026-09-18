package com.nomily.app.ui

import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nomily.app.R
import com.nomily.app.ui.components.EmptyState as SharedEmptyState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.ble.DeviceScanner
import com.nomily.app.ble.DnoteBleClient
import com.nomily.app.ble.RecordingName
import com.nomily.app.NomiViewModel.LocalClip
import java.util.Locale

/**
 * P0 UI — completes the main flow on a single screen: Scan → Connect → Recording list → Retrieve → Play.
 *
 * The structure is divided into three parts: Scanner, Device, and Recordings, but **pixel-perfect consistency is not required**:
 * The implementation guide §5.3 states clearly — the goal is “the same functionality, the same flow, the same wording”,
 * implemented using Material design language, without rigidly copying.
 *
 * Use English placeholders for copy initially; then fill the key set for 10 languages according to the guide §5.1,
 * that step can automatically detect “missing features or missing states”.
 */
/** Test anchor for the passphrase input field — UI tests use it for locating; do not modify. */
const val PASSPHRASE_FIELD_TAG = "passphraseField"
const val PASSPHRASE_CONFIRM_TAG = "passphraseConfirmField"

/**
 * A single recording entry in the library — P0 UI tests use it to open the detail page.
 *
 * The row lacks stable text to serve as an anchor: the title is **the recording time formatted according to the locale**, and the subtitle is "Size · Duration", both of which change with the data; the `.ogg` filename only appears on the detail page. Therefore, we tag the row itself.
 */
const val CLIP_ROW_TAG = "clipRow"

/**
 * A **push page** that sits on top of the tab content — the back arrow, title, and top‑right actions are all provided by the outer navigation bar.
 *
 * When pushing, **replace the entire navigation bar**: the top‑left becomes a back arrow, the title is centered, and the original capsule and top‑right actions give way.
 * Leaving the outer navigation bar in place and letting the child page draw its own
 * back arrow and left‑aligned title puts **two title bars** on screen with an
 * off‑centre title — the most noticeable visual flaw this layout can produce.
 *
 * The child page reports itself via `onPush`; on exit it reports `null`; the system back button also follows the same `onBack`.
 */
class PushedPage(
    val title: String,
    val actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    val onBack: () -> Unit,
)

/** Top three tabs (Recordings / Live / Settings). */
private enum class Tab(val titleRes: Int, val icon: ImageVector) {
    Recordings(R.string.tab_recordings, Icons.Filled.GraphicEq),
    Live(R.string.tab_live, Icons.Filled.Mic),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

/**
 * App shell —— **3 tabs + a ConnectionPill permanently pinned to the top-left of the navigation bar**.
 *
 * Built with Material 3's `NavigationBar` / `TopAppBar`; how many tabs, which is default,
 * where the pill goes, and no switching away mid-transfer are all fixed conventions.
 * See `android/UI-PORT-PLAN.md` for the canonical spec.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NomiApp(vm: NomiViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.Recordings) }
    var showScanner by remember { mutableStateOf(false) }
    var showDevicePanel by remember { mutableStateOf(false) }
    // Current pushed page (subpages report themselves) — see `PushedPage`
    var pushed by remember { mutableStateOf<PushedPage?>(null) }
    // The recording tab's sections (Device / Library) — **the action button at the top right of the navigation bar changes with it** (the Device section is 「Refresh」、
    //     // the Library section is 「Import Audio」), so the state must be placed where the navigation bar can see it.
    var segment by remember { mutableStateOf(Segment.Device) }
    // The recording tab is segmented (Device / Library) — **the action button at the top right of the navigation bar changes accordingly** (the Device segment is “Refresh”, 
    // the Library segment is “Import Audio”), so the state must be placed where it’s visible in the navigation bar. 
    // Switching tabs during a transfer will prompt 「Cancel transfer and switch?」, it won’t switch silently nor abort silently.
    var pendingTab by remember { mutableStateOf<Tab?>(null) }
    /** Device selected in the capsule while a transfer is in progress: only actually connect to it after confirming the transfer cancellation. */
    var pendingDeviceSwitch by remember { mutableStateOf<String?>(null) }
    val transferring = ui.progress != null

    val knownDevices = remember(cfg) {
        cfg.devices.entries
            .sortedByDescending { it.value.lastConnected ?: "" }
            .map { KnownDevice(it.key, it.value.name) }
    }

    // When a page is pushed, the system back button goes back one level instead of exiting the app
    androidx.activity.compose.BackHandler(enabled = pushed != null) { pushed?.onBack?.invoke() }

    // The navigation bar and tab bar background colors follow the page's background color — verified by screenshot color sampling:
    //   Settings root page / Summary template / Recording & transmission: both bars are group gray (240,242,245)
    //   Recording tab / Live tab:              both bars are white
    // On Android these two bars were originally always white, so when viewing the settings screens side by side you can see the "gray‑white inconsistency"
    // Here we use the current tab to select the corresponding background color.
    // The top and bottom bars **must be sampled separately**: on the live page (connected) the "navigation bar + language bar = group gray,
    // content area and tab bar = white", so the two bars have different colors. Verified by screenshot sampling:
    //   Settings: top gray (240,242,245) / bottom gray      Recording: top white / bottom white
    //   Live (connected): top gray / bottom white (the language bar is also gray; only the separator line below turns white)
    // Rule: **Both bars always follow the page's background color** (the recording page also follows the settings page uniformly).
    // Therefore the top bar is always group gray; the bottom bar is white only on the live page,
    // because the entire content area below the separator on the live page is white.
    val topBarColor = MaterialTheme.colorScheme.background
    val bottomBarColor = if (tab == Tab.Live) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.background
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // Root pages and child pages have **different** title positions; don't force centering across the board:
            //   Root page: left capsule, title pushed to the far right, **not centered**
            //   Child page: regular inline title → centered
            // In the previous iteration both used CenterAlignedTopAppBar, so the root page didn't align correctly.
            val page = pushed
                        // The **content height** of both top bars is compressed to 44dp (M3 default is 64dp). The extra 20dp
            // falls entirely between the title and the segmented control, which is the main cause of the "tab too far from the toolbar" issue.
            //
            // **Must add the status bar height back**: M3's TopAppBar includes the status bar inset in its own
            // height, so using height(44.dp) directly leaves only 18dp for content, and the capsule and title will be clipped.
            val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val barHeight = Modifier.height(44.dp + statusBar)
            if (page == null) {
                TopAppBar(
                    modifier = barHeight,
                    navigationIcon = {
                        ConnectionPill(
                            connectedName = ui.connectedName,
                            battery = ui.info?.battery,
                            isRecording = ui.info?.isRecording == true,
                            isReconnecting = ui.reconnecting,
                            knownDevices = knownDevices,
                            onTapDevice = {
                                // When not connected, **directly reconnect to the most recent one**, without showing the scan page. The scan page is only used when adding a device via the plus button/pull-down.
                                if (ui.connectedName != null) {
                                    showDevicePanel = true
                                } else {
                                    knownDevices.firstOrNull()?.let { vm.connectKnown(it.address) }
                                }
                            },
                            onAddDevice = { showScanner = true },
                            onSelectDevice = { address ->
                                // Switching tabs and splitting segments already have a guard that changes “transferring → cancel transfer and switch”,
                                //                                 // Switching devices was originally missed: the connection must be queued after the transfer command lock, clicking
                                //                                 // the UI does nothing, you have to wait for the download to finish before switching.
                                if (transferring) pendingDeviceSwitch = address else vm.connectKnown(address)
                            },
                        )
                    },
                    title = {
                        // Right margin set to 16dp
                        Row(
                            Modifier.fillMaxWidth().padding(end = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(stringResource(tab.titleRes), style = navTitleStyle())
                        }
                    },
                    actions = {
                        if (tab == Tab.Recordings) {
                            // Device segment = refresh (disabled during transmission to avoid idle after BLE command lock);
                            // Database segment = import audio.
                            //
                            // Refresh **only appears when connected**: when not connected, the top‑right corner is empty
                            // On Android it was previously persistent, but `loadFiles()` is not
                            // client directly returns —— equivalent to a button that does nothing when clicked.
                            if (segment == Segment.Device) {
                                if (ui.connectedName != null) RefreshAction(ui, vm)
                            } else {
                                ImportAction(vm)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarColor),
                )
            } else {
                CenterAlignedTopAppBar(
                    modifier = barHeight,
                    navigationIcon = {
                        IconButton(onClick = page.onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBackIos,
                                contentDescription = stringResource(R.string.common_close),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    title = { Text(page.title, style = navTitleStyle()) },
                    actions = { page.actions?.invoke(this) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = topBarColor),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = bottomBarColor) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (transferring && t != Tab.Recordings) pendingTab = t else tab = t
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.titleRes)) },
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                // **Don't add horizontal margins here**: some screens are full-width (the language bar on the Live page and the group cards on the Settings page bring their own),
                // adding them uniformly would stack with the in-screen 16dp into 32dp — that's how the Template page originally ended up at 32+16.
                .padding(inner)
                .imePadding(),
        ) {
            when (tab) {
                Tab.Recordings -> RecordingsTab(
                    ui, vm,
                    segment = segment,
                    onSegment = { segment = it },
                    onPush = { pushed = it },
                    onOpenSettings = { tab = Tab.Settings },
                )
                Tab.Live -> LiveTab(
                    ui,
                    vm,
                    onOpenSettings = { tab = Tab.Settings },
                    onViewInLibrary = { tab = Tab.Recordings },
                )
                Tab.Settings -> SettingsScreen(vm, onPush = { pushed = it })
            }
        }
    }

    // After configuring cloud ASR/LLM, **first** pop up a message 「Data will be sent to third parties」, and once accepted, don't ask again.
    // Place it here instead of at the request moment: ask as soon as the configuration changes, so the user won't be interrupted by a popup while waiting for transcription results.
    if (cfg.usesCloudService && !cfg.cloudEgressConsented) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.privacy_cloud_title)) },
            text = { Text(stringResource(R.string.privacy_cloud_message)) },
            confirmButton = {
                TextButton(onClick = { vm.consentCloudEgress() }) {
                    Text(stringResource(R.string.privacy_cloud_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = { tab = Tab.Settings }) {
                    Text(stringResource(R.string.privacy_cloud_go_local))
                }
            },
        )
    }

    if (ui.fastTransfer != null) FastTransferSheet(ui, vm)
    if (showScanner) ScannerSheet(ui, vm) { showScanner = false }
    if (showDevicePanel) DeviceSheet(ui, vm) { showDevicePanel = false }
    if (ui.needPassphrase) {
        PassphraseDialog(
            ui,
            vm,
            rotate = ui.passphraseMode == NomiViewModel.PassphraseMode.ROTATE,
            // Device encryption active → Before this save, a probe validation will run; footnotes and buttons must be clearly explained
            willVerify = ui.encryptionOn == true && ui.passphraseMode != NomiViewModel.PassphraseMode.ROTATE,
        )
    }
    if (ui.passphraseMismatch) {
        AlertDialog(
            onDismissRequest = { vm.clearPassphraseMismatch(retry = false) },
            title = { Text(stringResource(R.string.encryption_mismatch_title)) },
            text = { Text(stringResource(R.string.encryption_mismatch_message)) },
            confirmButton = {
                TextButton(onClick = { vm.clearPassphraseMismatch(retry = true) }) {
                    Text(stringResource(R.string.encryption_mismatch_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearPassphraseMismatch(retry = false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Validation failed, but the device has no prior recording: writing the user-entered password is lossless; just ask a question to proceed
    if (ui.passphraseAdoptOffer != null) {
        AlertDialog(
            onDismissRequest = { vm.resolveAdoptOffer(accept = false) },
            title = { Text(stringResource(R.string.encryption_adopt_title)) },
            text = { Text(stringResource(R.string.encryption_adopt_message)) },
            confirmButton = {
                TextButton(onClick = { vm.resolveAdoptOffer(accept = true) }) {
                    Text(stringResource(R.string.encryption_adopt_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveAdoptOffer(accept = false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingDeviceSwitch?.let { address ->
        AlertDialog(
            onDismissRequest = { pendingDeviceSwitch = null },
            title = { Text(stringResource(R.string.device_files_transfer_in_progress)) },
            text = { Text(stringResource(R.string.device_files_cancel_transfer_to_switch)) },
            confirmButton = {
                TextButton(onClick = { vm.cancelFetch(); vm.connectKnown(address); pendingDeviceSwitch = null }) {
                    Text(stringResource(R.string.device_files_cancel_and_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeviceSwitch = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingTab?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTab = null },
            title = { Text(stringResource(R.string.device_files_transfer_in_progress)) },
            text = { Text(stringResource(R.string.device_files_cancel_transfer_to_switch)) },
            confirmButton = {
                TextButton(onClick = { vm.cancelFetch(); tab = target; pendingTab = null }) {
                    Text(stringResource(R.string.device_files_cancel_and_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTab = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Recordings tab — top `[ Device | Library ]` segmented control plus a record start/stop button on the right; below, select one of the two sections.
 *
 * The device info card has been removed: device name/firmware/SN/switches are all in DeviceSheet (tap the pill to enter),
 * Auto-reconnect is in Settings; Android previously duplicated it on this screen, according to the guideline
 * “no platform differences unless there’s a special case”.
 */
@Composable
private fun RecordingsTab(
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    segment: Segment,
    onSegment: (Segment) -> Unit,
    onPush: (PushedPage?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // The detail page is a **pushed page**: it covers the entire content area (even the segmented control is hidden).
    // So this state lives at the tab level, rather than being stuffed into the library list.
    var openBase by remember { mutableStateOf<String?>(null) }
    ui.clips.firstOrNull { it.base == openBase }?.let { c ->
        ClipDetail(c, ui, vm, onOpenSettings, onPush) { openBase = null }
        return
    }
    val transferring = ui.progress != null
    var pendingSegment by remember { mutableStateOf<Segment?>(null) }

    // Entering the Device section will automatically refresh the file list once, without adding a manual refresh button.
    LaunchedEffect(segment, ui.connectedName) {
        if (segment == Segment.Device && ui.connectedName != null) vm.loadFiles()
    }

    // The segmented control row needs a **full-width white background**, extending all the way to the left and right edges of the screen. Therefore, the 16dp horizontal padding should not be added to the outermost Column,
    // otherwise the white background will only cover the middle part, exposing gray edges on both sides.
    Column(Modifier.fillMaxSize()) {
        Row(
            // This follows the navigation bar — after the top bar is unified to a grouped gray, leaving whitespace here would place it below the gray top bar.
            // Insert a white strip. The entire screen then shares the same structure as the settings page: gray background + white cards.
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // Vertical 4dp: the segmented control itself includes 6dp of touch target expansion, so
                // 4+6=10, matching the target spacing from the bottom of the nav bar to the top of the segmented control. Originally 10dp; actual measurement showed a 6dp discrepancy.
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Height reduced to 32dp: M3's default height is 40dp (including padding measured at 44dp here), and side‑by‑side Android appears noticeably thicker by one stroke
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).height(32.dp)) {
                Segment.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = segment == s,
                        onClick = {
                            // When cutting a segment during transmission, just like cutting a tab, you must prompt; do not silently abort
                            if (transferring && s != segment) pendingSegment = s else onSegment(s)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, Segment.entries.size),
                        // Material by default inserts a ✓ before the selected item; here we only want the text, with selection indicated by background color.
                        icon = {},
                        // The background color for selected/unselected states should be **gray slot + white selected block**, which is the opposite of Material's default white background + gray selected block. When viewed side by side, it looks like the selection is reversed.
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.surface,
                            activeContentColor = MaterialTheme.colorScheme.onSurface,
                            inactiveContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        // Font size reduced to 13sp (M3's labelLarge is 14sp).
                        //
                        // **includeFontPadding must be turned off and line height squeezed to 16sp**: once the
                        // control is compressed to 32dp, the default line box (font top/bottom padding + 20sp line height)
                        // is taller than the available height, and the last horizontal stroke of tall CJK glyphs gets clipped.
                        // The line box is centered (measured with uiautomator: the text node's midline sits exactly on the control's midline),
                        // but the ink of Chinese characters sits low within the line box — computing the ink centroid row by row from screenshots gives 3.8px below the control center.
                        // In a 32dp slot this 1dp-plus offset is visible at a glance,
                        // so lift it another 1.5dp to push the ink back onto the midline (after lifting, measured centroid 274.7, control center 274.5).
                        Box(
                            Modifier.fillMaxHeight().offset(y = (-1.5).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                        Text(
                            stringResource(s.titleRes),
                            maxLines = 1,
                            style = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                            ),
                        )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            RecordControl(ui, vm)
        }

        // The content below the segmented control consumes this 16dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        ui.error?.let { msg ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::clearError) { Text(stringResource(R.string.error_dismiss)) }
                }
            }
        }

        when (segment) {
            Segment.Device -> {
                if (ui.connectedName == null) {
                    EmptyState(R.string.recordings_no_device, R.string.recordings_no_device_message)
                } else {
                    // "Please keep the app in the foreground" only appears when there is an actual transfer, and is shown at the top of the content area
                    if (transferring) {
                        Text(stringResource(R.string.common_keep_foreground),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    // Device is encrypted, yet this phone has no password: clarify the issue first, don't let the user click download only to encounter failure
                    // (second‑hand device / phone‑swap scenarios)
                    if (ui.encryptionOn == true && !ui.hasLocalKey) {
                        // Device rejected during recording with 0x90, `ui.files` must be empty — that's not “no recordings on the device”,
                        // It's “cannot be counted right now”. Passing null lets the banner speak in the worst-case scenario: otherwise it would reassure the user “no loss will occur” when encrypted recordings are present on the device,
                        // even though encrypted recordings exist, comforting the user “there will be no loss”, and the user sets the wrong passphrase based on that,
                        // those recordings will truly become inaccessible (verified 2026-08-12).
                        val atRisk = if (ui.info?.isRecording == true) null else ui.files.size
                        EncryptionBanner(recordingsAtRisk = atRisk) { vm.needPassphrase(true) }
                    }
                    // While recording, the device rejects with 0x90, so the list is necessarily empty — push up a "device is recording"
                    // overlay (with the device's true elapsed time and a stop button) instead of leaving the user staring at an empty list.
                    if (ui.info?.isRecording == true && ui.files.isEmpty()) {
                        RecordingOverlay(ui, vm)
                    } else {
                        // The list consumes the remaining space, and the quick transfer button is **pinned to the bottom**——
                        // Originally, the list used fillMaxSize, which pushed the quick transfer button entirely off-screen, effectively removing the entry point for this feature.
                        DeviceFilesList(ui, vm, Modifier.weight(1f))
                        FastTransferBar(ui, vm)
                    }
                }
            }
            Segment.Library -> LibrarySection(ui, vm) { base -> openBase = base }
        }
        }
    }

    pendingSegment?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSegment = null },
            title = { Text(stringResource(R.string.device_files_transfer_in_progress)) },
            text = { Text(stringResource(R.string.device_files_cancel_transfer_to_switch)) },
            confirmButton = {
                TextButton(onClick = { vm.cancelFetch(); onSegment(target); pendingSegment = null }) {
                    Text(stringResource(R.string.device_files_cancel_and_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSegment = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private enum class Segment(val titleRes: Int) {
    Device(R.string.recordings_device),
    Library(R.string.recordings_library),
}

@Composable
private fun EmptyState(titleRes: Int, messageRes: Int, icon: ImageVector = Icons.Filled.GraphicEq) {
    // Just decode the resource ID into a string — the drawing method is in ui/components, shared with the settings page and other places.
    SharedEmptyState(stringResource(titleRes), stringResource(messageRes), icon)
}

/**
 * Recording start/stop button — when not recording it's a hollow red circle, when recording it's a solid square stop button, and when no device is connected it is grayed out. The text is only used as an accessibility label.
 */
@Composable
private fun RecordControl(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    val connected = ui.connectedName != null
    val recording = ui.info?.isRecording == true
    // Recording can't **start** until the passphrase is fully set (an in-progress recording must still be stoppable). The command layer
    // `DnoteBleClient.startRecording` is the real gate; here we just keep the button from looking clickable.
    val startBlocked = !recording && ui.encryptionOn == true && !ui.hasLocalKey
    IconButton(onClick = vm::toggleRecording, enabled = connected && !startBlocked) {
        Icon(
            imageVector = if (recording) Icons.Filled.StopCircle else Icons.Filled.RadioButtonChecked,
            contentDescription = stringResource(
                if (recording) R.string.device_files_stop_recording else R.string.common_recording,
            ),
            tint = if (connected && !startBlocked) Color.Red else MaterialTheme.colorScheme.outline,
            // Red dot compressed to 32dp: Material icons default to 24dp,
            //            // Originally only 22dp here — appears slightly smaller when placed side‑by‑side.
            //            // The outer IconButton remains a 48dp touch area, only the graphic is enlarged.
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * "Device is recording" overlay — red dot icon + title + **device-reported elapsed time** + description + stop button.
 *
 * Duration taken from `ui.recordingElapsedMs` (the `recd` of 0x56), not the app-side stopwatch —
 * Recordings started via a physical key are unknown to the app.
 */
@Composable
private fun RecordingOverlay(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.RadioButtonChecked,
            contentDescription = null,
            tint = Color.Red,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(R.string.device_files_device_is_recording),
            style = MaterialTheme.typography.titleMedium)
        ui.recordingElapsedMs?.let { ms ->
            Text(
                formatElapsed(ms),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = Color.Red,
            )
        }
        Text(stringResource(R.string.device_files_recording_unavailable_message),
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::toggleRecording) {
            Text(stringResource(R.string.device_files_stop_recording))
        }
    }
}

/** `H:MM:SS` / `MM:SS` — Hours are omitted when the duration is less than 1 hour. */
internal fun formatElapsed(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Real-time transcription — top status line (provider + five-state text), middle transcription stream (finalized paragraphs + current tentative line), bottom start/stop button.
 *
 * The tentative line is **on its own line, in gray**: it can be replaced by the next entry at any time and is not the same thing as the already finalized paragraphs.
 */
@Composable
private fun LiveTab(
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    onOpenSettings: () -> Unit,
    onViewInLibrary: () -> Unit,
) {
    val live = ui.live
    val connected = ui.connectedName != null
    if (!connected) {
        // When no device is connected, **check for existing results first**: paragraphs already transcribed in this round, or clips just saved to the library,
        // must continue to be displayed — if the transcription results were cleared the moment the device disconnects,
        // users would think this segment was recorded for nothing. Only when there are none should we show the centered empty state.
        if (live.finals.isEmpty() && live.savedClip == null) {
            EmptyState(R.string.live_no_device, R.string.live_no_device_message, Icons.Filled.Sensors)
            return
        }
        Column(Modifier.fillMaxSize()) {
            // When disconnected but with results, this list fills the content area (the empty state branch has already returned above)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(live.finals.size) { i ->
                    val line = live.finals[i]
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        if (line.translation.isNotEmpty()) {
                            Text(
                                line.translation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            live.savedClip?.let { name ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.live_saved_to_library, name),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onViewInLibrary) {
                        Text(stringResource(R.string.live_view_in_library))
                    }
                }
            }
        }
        return
    }
    // The content area of the real-time page has a **white background** (only the navigation bar and language bar are gray).
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Target language: empty = transliteration only; selected = real-time translation (`[source] → [target]` language bar)
        var showTargetPicker by remember { mutableStateOf(false) }
        var targetLanguages by remember { mutableStateOf<List<com.nomily.app.llm.LanguageService.Language>>(emptyList()) }
        val cfg by vm.config.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { targetLanguages = vm.targetLanguages() }
        val targetName = targetLanguages.firstOrNull { it.code == cfg.lastTargetLang }?.name
        var showSourcePicker by remember { mutableStateOf(false) }
        // **Source language uses the ASR locale catalog** (things like `zh-CN` / `en-US`), not the translation target-language table —
        // using the wrong table has two consequences: the UI can only show the raw code (when it should show a name like "English (US)"),
        // and what gets picked from the table is a translation code like `en`, which is wrong to feed to ASR as a locale.
        val sourceLanguages = com.nomily.app.llm.LanguageService.AZURE_MULTI_LANGUAGE_LOCALES
        // If not set, it defaults to en-US. The **default value must also go through the directory** to get the display name,
        // otherwise the UI will show the raw code (it should display "English (US)").
        val sourceCode = cfg.lastSourceLang ?: "en-US"
        val sourceName = sourceLanguages.firstOrNull { it.code == sourceCode }?.name ?: sourceCode
        // Switching languages during an active session requires restarting the ASR connection. Ask first.
        var pendingLang by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        val applyLang: (Boolean, String) -> Unit = { isSource, code ->
            val value = code.takeIf { c -> c.isNotEmpty() }
            vm.updateConfig { if (isSource) it.copy(lastSourceLang = value) else it.copy(lastTargetLang = value) }
        }
        if (showSourcePicker) {
            LanguagePickerDialog(
                title = stringResource(R.string.live_source_language),
                languages = sourceLanguages,
                defaultOption = null,
                onPick = { code ->
                    showSourcePicker = false
                    if (live.running) pendingLang = true to code else applyLang(true, code)
                },
                onDismiss = { showSourcePicker = false },
            )
        }
        pendingLang?.let { (isSource, code) ->
            AlertDialog(
                onDismissRequest = { pendingLang = null },
                title = { Text(stringResource(R.string.live_switch_language)) },
                text = {
                    // Main text: Without a target language, it's "transcribe only X";
                    // with one, it's "X → Y", letting users see the result after cutting.
                    val srcCode = if (isSource) code else sourceCode
                    val dstCode = if (isSource) (cfg.lastTargetLang ?: "") else code
                    val nameOf = { c: String ->
                        (sourceLanguages + targetLanguages).firstOrNull { it.code == c }?.name ?: c
                    }
                    Text(
                        if (dstCode.isEmpty()) {
                            stringResource(R.string.live_transcribe_only, nameOf(srcCode))
                        } else {
                            stringResource(R.string.live_translate_pair, nameOf(srcCode), nameOf(dstCode))
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { applyLang(isSource, code); pendingLang = null }) {
                        Text(stringResource(R.string.live_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingLang = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
        // Language header: **full-width left-aligned** "Source Language ⌄ → Target Language ⌄",
        // Background color is one shade darker than the body text; the original Android used two full-width "Label …… Value" rows, which looked quite different.
        // When Azure is not configured, the target language cell is not a button but a lock icon + "Go to Azure Settings" (tertiary color, non-clickable).
        val hasAsr = cfg.asrProviders.azure?.key?.isNotEmpty() == true ||
            cfg.asrProviders.local?.host?.isNotEmpty() == true
        Row(
            Modifier.fillMaxWidth()
                // The language bar and navigation bar share the same grouped gray background;
// only the separator line below transitions to the white content area.
// Previously, surfaceVariant (white) was used, which inverted the overall color scheme.
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.clickable(enabled = !live.running) { showSourcePicker = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sourceName, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            if (hasAsr) {
                Row(
                    Modifier.clickable(enabled = !live.running) { showTargetPicker = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        targetName ?: stringResource(R.string.live_same_as_source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (targetName == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.live_setup_azure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        // The line between the language bar and the white content area
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (showTargetPicker) {
            LanguagePickerDialog(
                title = stringResource(R.string.live_target_language),
                languages = targetLanguages,
                defaultOption = stringResource(R.string.live_same_as_source),
                onPick = { code ->
                    showTargetPicker = false
                    if (live.running) pendingLang = false to code else applyLang(false, code)
                },
                onDismiss = { showTargetPicker = false },
            )
        }


        live.error?.let { msg ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    // Error without provider configured: directly falls to the speech recognition service page
                    if (!hasAsr) {
                        TextButton(onClick = {
                            vm.openSettingsAt(NomiViewModel.SettingsTarget.ASR)
                            onOpenSettings()
                        }) {
                            Text(stringResource(R.string.asr_open_provider_settings))
                        }
                    }
                }
            }
        }

        // Replace result banner (success / complete recording still on device)
        if (live.repairState != NomiViewModel.RepairState.IDLE) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (live.repairState == NomiViewModel.RepairState.SUCCEEDED) {
                                R.string.live_repair_succeeded
                            } else {
                                R.string.live_repair_manual_transfer
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::dismissRepairState) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }

        val liveEmpty = live.finals.isEmpty() && live.partial.isEmpty() && !live.running
        if (liveEmpty) {
            // Connected device, not yet started: a large gray microphone + the prompt text centered in the content area,
            // not a small line of text at the top (real device reference image 2026-08-06).
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    stringResource(R.string.live_tap_mic_to_start),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // The empty state and this list are **mutually exclusive**: if both specify weight(1f), the empty state will only occupy the upper half of the screen,
        // leaving the hint text hanging at one-third of the screen height instead of being centered in the entire content area. An empty list naturally takes no height.
        LazyColumn((if (liveEmpty) Modifier else Modifier.weight(1f)).padding(horizontal = 16.dp)) {
            items(live.finals.size) { i ->
                val line = live.finals[i]
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    // Translation indented by one level: clearly distinguish which line is the original at a glance (double-line layout)
                    if (line.translation.isNotEmpty()) {
                        Text(
                            line.translation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (live.partial.isNotEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            live.partial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (live.partialTranslation.isNotEmpty()) {
                            Text(
                                live.partialTranslation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        live.savedClip?.let { name ->
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.live_saved_to_library, name),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                // Provide a direct entry to the database after saving,
                // so users don't have to manually switch tabs and sections to find it.
                TextButton(onClick = onViewInLibrary) {
                    Text(stringResource(R.string.live_view_in_library))
                }
            }
        }

        // Control bar: left provider capsule (green if connected, orange if not, 12% background color),
        // right bold status, far right is a **circular icon button** — originally two full-width text buttons ("Start" not yet translated).
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live.provider.isNotEmpty()) {
                val wsUp = live.status == NomiViewModel.LiveStatus.READY ||
                    live.status == NomiViewModel.LiveStatus.STREAMING
                val tint = if (wsUp) Color(0xFF34C759) else Color(0xFFFF9500)
                Text(
                    live.provider.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Text(
                    // Pause priority: Pause → Listening → Ready
                    stringResource(if (live.paused) R.string.live_paused else live.status.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                // Duration and size are both calculated from the received byte count: 40 bytes/frame, 20ms/frame
                val secs = live.bytes / com.nomily.app.core.audio.OPUS_FRAME_SIZE / 50
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatClipBytes(live.bytes.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (live.running) {
                LiveCircleButton(
                    icon = if (live.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    // Pause only ASR; the device continues recording. Resume by reconnecting.
                    onClick = { if (live.paused) vm.resumeLive() else vm.pauseLive() },
                )
                Spacer(Modifier.width(8.dp))
                LiveCircleButton(
                    icon = Icons.Filled.Stop,
                    destructive = true,
                    onClick = vm::stopLive,
                )
            } else {
                LiveCircleButton(icon = Icons.Filled.Mic, enabled = connected, onClick = vm::startLive)
            }
        }
    }

    // Unable to determine if the device is currently recording → let the user decide whether to proceed blindly.
    // Sending 0x51 directly would interrupt the recording in progress via physical buttons, so we must pause and ask first.
    if (live.pendingStartConfirm) {
        AlertDialog(
            onDismissRequest = { vm.resolveLiveStartConfirm(false) },
            title = { Text(stringResource(R.string.live_start_unknown_title)) },
            text = { Text(stringResource(R.string.live_start_unknown_message)) },
            confirmButton = {
                TextButton(onClick = { vm.resolveLiveStartConfirm(true) }) {
                    Text(stringResource(R.string.live_start_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveLiveStartConfirm(false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Paused (or attached to the segment already recorded on the device) → This phone copy lacks audio; ask whether to replace it with the complete version from the device.
    live.pendingRepair?.let { repair ->
        AlertDialog(
            onDismissRequest = { vm.declineLiveRepair(repair) },
            title = { Text(stringResource(R.string.live_repair_title)) },
            text = {
                Text(
                    stringResource(
                        // The user in the attached scenario has never paused. Don't use the "your recording was interrupted" excuse to brush them off.
                        if (repair.wasAttached) R.string.live_repair_message_attached
                        else R.string.live_repair_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.repairLiveClip(repair) }) {
                    Text(stringResource(R.string.live_repair_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.declineLiveRepair(repair) }) {
                    Text(stringResource(R.string.live_repair_keep))
                }
            },
        )
    }
}

/** Named text for the five states: "Connecting/Ready/Sending/Failed/Reconnecting". */
private fun NomiViewModel.LiveStatus.labelRes(): Int = when (this) {
    // If not running, it is "ready"
    NomiViewModel.LiveStatus.IDLE -> R.string.live_ready
    NomiViewModel.LiveStatus.CONNECTING -> R.string.live_status_connecting
    NomiViewModel.LiveStatus.READY -> R.string.live_ready
    NomiViewModel.LiveStatus.STREAMING -> R.string.live_listening
    NomiViewModel.LiveStatus.FAILED -> R.string.live_status_failed
    NomiViewModel.LiveStatus.RECONNECTING -> R.string.live_status_reconnecting
}

/**
 * Scan — modal launched from pill.
 *
 * **Full-screen sheet**: left "Rescan" (spinner while scanning), center title, right "Done";
 * content is either centered empty state or a gray background with an embedded white card,
 * with the entire row tappable.
 * The original Android UI had a small card in the middle of the screen + three buttons (Scan/Stop/Close) + a "Connect" button per row,
 * which didn't match the look and feel or interaction flow. This has been completely redesigned:
 * - Scanning starts automatically on entry and stops on exit, so there's no need for the user to tap "Scan" first;
 *   the "Stop" button is removed — keeping it would mislead users into thinking scanning continues indefinitely unless stopped.
 * - Tapping a row initiates connection; while connecting, that row shows a spinner on the right and other rows are disabled.
 *   The sheet closes only after a successful connection (previously it closed immediately after tapping,
 *   leaving users unaware of connection failures on the main screen).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScannerSheet(ui: NomiViewModel.Ui, vm: NomiViewModel, onDismiss: () -> Unit) {
    val close = { vm.stopScan(); onDismiss() }
    val btOn = run {
        val mgr = LocalContext.current
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        mgr?.adapter?.isEnabled == true
    }
    var connecting by remember { mutableStateOf<String?>(null) }
    // Devices that have already been added are identified by their peripheral address (same key as `rememberDevice(d.address, …)`).
    val knownAddresses = vm.config.collectAsStateWithLifecycle().value.devices.keys
    LaunchedEffect(btOn) { if (btOn) vm.startScan() }
    // Disconnect immediately upon connection; when connection fails, `busy` is cleared.
    // At this point, we must clear `connecting`, otherwise the list will remain stuck in a disabled + loading state.
    // **Only recognize the connection initiated by the user** (connecting != null): background auto-reconnection also populates `connectedName`.
    // Previously, this check was missing, causing the "Add Device" screen to close immediately due to auto-reconnection, making it appear as if the click had no effect.
    LaunchedEffect(ui.connectedName, ui.busy) {
        if (connecting == null) return@LaunchedEffect
        if (ui.connectedName != null) onDismiss() else if (ui.busy == null) connecting = null
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // This popup is **card-style**: the top portion leaves the status bar area exposed to the parent view, with rounded top corners.
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.scanner_scan)) },
                    navigationIcon = {
                        if (ui.scanning) {
                            CircularProgressIndicator(
                                Modifier.padding(start = 16.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            TextButton(onClick = vm::startScan, enabled = btOn) {
                                Text(stringResource(R.string.scanner_scan_again))
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = close) { Text(stringResource(R.string.common_done)) }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                when {
                    !btOn -> EmptyState(
                        R.string.scanner_bluetooth_unavailable,
                        R.string.scanner_bluetooth_off_message,
                        Icons.Filled.BluetoothDisabled,
                    )
                    ui.discovered.isEmpty() -> EmptyState(
                        R.string.scanner_looking_for_devices,
                        R.string.scanner_looking_message,
                        Icons.Filled.Sensors,
                    )
                    else -> {
                        // Group already-added devices in a separate section with a label so users can distinguish new devices.
                        // Do not hide the entire section: when auto-reconnection fails, the scan list is the only entry point for reconnection.
                        // Tapping it won't add duplicates — rememberDevice overwrites the existing record by address.
                        val (added, available) = ui.discovered.partition { it.address in knownAddresses }
                        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            if (available.isNotEmpty()) {
                                item {
                                    ScanSection(R.string.scanner_section_available, available, connecting, false) {
                                        connecting = it.address; vm.connect(it)
                                    }
                                }
                            }
                            if (added.isNotEmpty()) {
                                item {
                                    ScanSection(R.string.scanner_section_added, added, connecting, true) {
                                        connecting = it.address; vm.connect(it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A segment of the scan result: segment title + an embedded card. */
@Composable
private fun ScanSection(
    titleRes: Int,
    items: List<DeviceScanner.Discovered>,
    connecting: String?,
    isAdded: Boolean,
    onPick: (DeviceScanner.Discovered) -> Unit,
) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        items.forEachIndexed { index, d ->
            DiscoveredRow(
                d,
                isConnecting = connecting == d.address,
                enabled = connecting == null,
                isAdded = isAdded,
            ) { onPick(d) }
            if (index < items.lastIndex) {
                HorizontalDivider(
                    Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Display fields: Name / Product Name / SID · Firmware · Battery / RSSI. */
@Composable
private fun DiscoveredRow(
    d: DeviceScanner.Discovered,
    isConnecting: Boolean,
    enabled: Boolean,
    isAdded: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                if (isAdded) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.scanner_added_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                d.adv.model ?: "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "SID ${d.adv.deviceSidHex} · ${d.adv.firmwareStr} · ${d.adv.battery}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isConnecting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                // Signal strength: 4 bars + dBm value
                RssiBars(d.rssi)
                Text(
                    "${d.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/** A line of password input: no border, gray placeholder, masked text. */
@Composable
private fun SecureRow(
    value: String,
    placeholder: String,
    keyboard: KeyboardOptions,
    enabled: Boolean,
    tag: String,
    isError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = keyboard,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}


/** Circular button on the real-time page control bar: 48dp circular background (15% of theme color) + 20dp icon, red for stop. */
@Composable
private fun LiveCircleButton(
    icon: ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
            .background(tint.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Banner for "Device is encrypted, please set a passcode".
 *
 * The severity should follow the **actual risk**: Firmware v1.50 enables encryption
 * automatically during pairing, so a brand-new V05 will hit this screen immediately
 * upon connection, with no recordings on the device. Displaying a red alert at this
 * stage makes the user think "My new device has a problem," when nothing is wrong.
 * When there are no recordings on the device, this is merely a setup prompt; it
 * should only turn into an alert color if there are indeed recordings on the device
 * that cannot be opened locally (or whose count cannot be determined at this moment).
 */
@Composable
private fun EncryptionBanner(recordingsAtRisk: Int?, onSetPassphrase: () -> Unit) {
    // null = Cannot determine the count at this moment (device is recording, 0x90 was rejected). Handle with caution:
    // Overstating the risk may only prompt the user to take a closer look, while understating it could encourage them to lock in the existing recording.
    val warn = recordingsAtRisk == null || recordingsAtRisk > 0
    val container = if (warn) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (warn) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    if (warn) R.string.encryption_banner_title
                    else R.string.encryption_banner_setup_title
                ),
                style = MaterialTheme.typography.titleSmall,
                color = onContainer,
            )
            Text(
                stringResource(
                    if (warn) R.string.encryption_banner_message
                    else R.string.encryption_banner_setup_message
                ),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
            TextButton(onClick = onSetPassphrase) {
                Text(stringResource(R.string.encryption_banner_cta))
            }
        }
    }
}

/**
 * Device file list — title and size on the left, an icon button that changes shape based on state on the right; during transfer, append a progress bar and received/total bytes inline.
 * Swipe left to delete, with a secondary confirmation.
 */
@Composable
private fun DeviceFilesList(ui: NomiViewModel.Ui, vm: NomiViewModel, modifier: Modifier = Modifier) {
    var pendingDelete by remember { mutableStateOf<DnoteBleClient.DeviceFile?>(null) }
    val transferring = ui.progress != null
    if (ui.files.isEmpty()) {
        // What to draw when the list is empty, in order:
        // Reading → Read failed → No recording on the device. (The "Device is recording" case is handled at a higher level.)
        when {
            ui.filesLoading -> Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.device_files_reading_file_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // On read failure, render an empty state (warning triangle + reason), not the error card at the top that requires manual dismissal
            ui.filesError != null -> SharedEmptyState(
                stringResource(R.string.device_files_couldnt_read_device),
                ui.filesError,
                Icons.Outlined.WarningAmber,
            )
            else -> Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.device_files_no_recordings),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.device_files_no_recordings_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    // The row is placed inside an embedded white card on a gray background, originally spanning the full width
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                ui.files.forEachIndexed { i, f ->
                    DeviceFileRow(
                f = f,
                // Progress only belongs to the currently transferring item: the global progress is claimed by `downloadingName`.
                // Other rows do not show a progress bar or a stop button—the stop button is bound to the same
                // `cancelFetch`, which looks like "stop this item" but actually stops the one currently transferring.
                progress = if (f.name == ui.downloadingName) ui.progress else null,
                downloaded = ui.clips.any { c -> c.base == f.name.substringBeforeLast(".") },
                enabled = !transferring,
                onFetch = { vm.fetch(f) },
                onCancel = vm::cancelFetch,
                onDelete = { pendingDelete = f },
                    )
                    if (i < ui.files.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { target ->
        // Deleting files on the device is irreversible — always confirm first
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.device_files_delete_recording)) },
            text = { Text(stringResource(R.string.device_files_delete_recording_message, target.name)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteDeviceFile(target); pendingDelete = null }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DeviceFileRow(
    f: DnoteBleClient.DeviceFile,
    progress: Pair<Int, Int>?,
    downloaded: Boolean,
    enabled: Boolean,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        // Only trigger the confirmation dialog via swipe gesture, without actually removing the row — return false to snap the row back.
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, stringResource(R.string.common_delete), tint = Color.Red)
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // The background color should follow the card (`surface`), not use `background` — that's the page's gray background,
                // applying it on a white card would flatten the card's appearance.
                .background(MaterialTheme.colorScheme.surface)
                // Left edge of card text = card edge + 16, aligned with the inset of the divider line
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // The title uses displayTitle (timestamp filenames are rendered as dates), not the raw filename
                    Text(RecordingName.displayTitle(f.name), style = MaterialTheme.typography.bodyMedium)
                    Text(formatClipBytes(f.size.toLong()), style = MaterialTheme.typography.labelSmall)
                }
                if (progress != null) {
                    // P0 #4 "Stop button". Cancellation is not just local — the client sends a 0x70 cmd=0
                    // to tell the device to stop pushing, then drains residual packets; otherwise,
                    // fragments will collide with the next command.
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.StopCircle, stringResource(R.string.common_cancel),
                            tint = Color.Red)
                    }
                } else {
                    IconButton(onClick = onFetch, enabled = enabled) {
                        Icon(
                            if (downloaded) Icons.Filled.CheckCircle else Icons.Filled.ArrowCircleDown,
                            stringResource(R.string.clip_fetch),
                            tint = if (downloaded) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { if (progress.second > 0) progress.first.toFloat() / progress.second else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text("${formatClipBytes(progress.first.toLong())} / ${formatClipBytes(progress.second.toLong())}",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Wi-Fi Quick Transfer entry point —— located at the bottom of the content area:
 * **Only appears when the pending transfer size exceeds the threshold**, not a persistent button. The threshold is `fast_transfer_threshold_kb` (configurable in settings; 0 means always show).
 *
 * "Pending transfer" refers to files on the device that have not yet been downloaded to the local library. Files already retrieved are excluded to avoid prompting the user to re-download just-completed transfers.
 */
@Composable
private fun FastTransferBar(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val pending = ui.files.filter { f -> ui.clips.none { c -> c.base == f.name.substringBeforeLast(".") } }
    val backlog = pending.sumOf { it.size.toLong() }
    val thresholdBytes = cfg.fastTransferThresholdKb.toLong() * 1000
    val transferring = ui.progress != null
    if (pending.isEmpty() || backlog <= thresholdBytes) return

    Button(
        onClick = { vm.fastTransfer(pending) },
        enabled = !transferring,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column {
            Text(stringResource(R.string.device_files_fast_transfer_wifi))
            Text(
                stringResource(R.string.device_files_pending, formatClipBytes(backlog)),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Library section - row fields: title + badge / date · size · duration. */
@Composable
private fun LibrarySection(ui: NomiViewModel.Ui, vm: NomiViewModel, onOpenClip: (String) -> Unit) {
    if (ui.clips.isEmpty()) {
        EmptyState(R.string.recordings_no_clips, R.string.recordings_no_clips_message)
        return
    }
    var pendingMerge by remember { mutableStateOf<Pair<LocalClip, LocalClip>?>(null) }
    // The database is a **white card embedded on a gray background**,
    // The fragment count and total size are gray footnotes **outside** the card, not full-width tiled rows.
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                ui.clips.forEachIndexed { index, c ->
                    ClipRow(
                        c,
                        // The list is ordered from new to old, so "the adjacent older item" is the next row
                        mergeWith = adjacentOlder(ui.clips, index)?.let { older -> { pendingMerge = older to c } },
                    ) { onOpenClip(c.base) }
                    if (index < ui.clips.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            Text(
                stringResource(
                    if (ui.clips.size == 1) R.string.recordings_clip_count_one
                    else R.string.recordings_clip_count_other,
                    ui.clips.size,
                    formatClipBytes(ui.clips.sumOf { it.sizeBytes }),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
            )
        }
    }

    pendingMerge?.let { (older, newer) ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text(stringResource(R.string.library_merge_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.library_merge_confirm_message,
                        RecordingName.displayTitle(older.name),
                        RecordingName.displayTitle(newer.name),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.mergeClips(older, newer); pendingMerge = null }) {
                    Text(stringResource(R.string.library_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMerge = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Entry point for "Import audio" — toolbar import button + confirmation dialog + file picker.
 *
 * The picker uses SAF's `OpenMultipleDocuments`;
 * the MIME passed to the picker is only a **hint** — the real whitelist is enforced by extension in `AudioImporter`.
 */
/**
 * Font size for the top bar title — 17sp semibold, used by both the root page and subpages.
 *
 * M3's TopAppBar defaults to `titleLarge` (22sp regular); side by side the two differ by a full 5sp and also in weight —
 * "the font size difference is too large".
 */
@Composable
private fun navTitleStyle() = MaterialTheme.typography.titleMedium.copy(
    fontSize = 17.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

/**
 * Device segment "refresh" — shows a spinner while loading; **disabled during transmission**,
 * because a single BLE transmission occupies the device's command lock, causing subsequent refreshes to spin indefinitely ("stuck on refresh").
 */
@Composable
private fun RefreshAction(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    // The spinner only recognizes "reading file list" and ignores "busy with other tasks".
    // Previously, using ui.busy caused the spinner to also rotate during pairing/connection.
    val loading = ui.filesLoading
    val transferring = ui.progress != null
    IconButton(onClick = vm::loadFiles, enabled = !loading && !transferring) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.device_refresh),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ImportAction(vm: NomiViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.importAudio(uris) }

    // Import is placed in the icon at the top-right corner of the navigation bar, not as a link above the list
    IconButton(onClick = { confirm = true }) {
        Icon(
            Icons.Filled.FileDownload,
            contentDescription = stringResource(R.string.library_import_audio),
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.library_import_confirm_title)) },
            text = { Text(stringResource(R.string.library_import_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    picker.launch(arrayOf("audio/*"))
                }) { Text(stringResource(R.string.library_import_choose_file)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Adjacency check: the gap between **the end of the previous recording** and **the start of this one**
 * counts as adjacent if it falls within −2s ~ 10min.
 *
 * The −2s negative tolerance isn't arbitrary: timestamps in filenames are only precise to the second, so two recordings
 * that truly play back-to-back can appear to overlap slightly due to rounding.
 */
private fun adjacentOlder(clips: List<LocalClip>, index: Int): LocalClip? {
    val newer = clips.getOrNull(index) ?: return null
    val older = clips.getOrNull(index + 1) ?: return null
    if (!newer.hasAudio || !older.hasAudio) return null
    val newerStart = newer.recordedAt?.time ?: return null
    val olderStart = older.recordedAt?.time ?: return null
    val olderEnd = olderStart + (older.durationMs ?: return null)
    val gapSec = (newerStart - olderEnd) / 1000.0
    return if (gapSec >= -2 && gapSec <= 10 * 60) older else null
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClipRow(
    c: LocalClip,
    mergeWith: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    // "Merge adjacent" is revealed via **swipe left**, not a button that permanently lives in the row.
    // It was originally an inline TextButton, which added an extra button to every row in the Android library.
    val dismissState = rememberSwipeToDismissBoxState(
        // Same-device file row: only borrow the gesture to trigger the confirmation dialog; returning false bounces the row back without actually swiping it away.
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) mergeWith?.invoke()
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = mergeWith != null,
        backgroundContent = {
            if (mergeWith != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.library_merge),
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color(0xFF5856D6),
                    )
                }
            }
        },
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)   // The entire row is clickable to view details
            .testTag(CLIP_ROW_TAG)
            // Left edge of card text = Card edge + 16
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title ?: RecordingName.displayTitle(c.name),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                // P0 #7: Size / duration. **The date no longer appears again on the second line** — the title itself is the recording time
                // (the date is only added to the second line when a custom title is used). Duration is read from the last Ogg page granule, without decoding.
                Text(
                    listOfNotNull(
                        // Only pad the second line with the date when a custom title is used — otherwise the title itself is the recording time
                        c.title?.let { RecordingName.displayTitle(c.name) },
                        if (c.hasAudio) formatClipBytes(c.sizeBytes) else null,
                        c.durationMs?.let { formatDuration(it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Badge: no audio (waveform slash) / has transcription (green bubble); playback control is in the detail page, not in the feed
            if (!c.hasAudio) {
                Icon(Icons.Filled.VoiceOverOff, stringResource(R.string.clip_detail_text_only),
                    tint = MaterialTheme.colorScheme.outline)
            }
            if (c.transcript != null) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = stringResource(R.string.clip_detail_transcript),
                    tint = androidx.compose.ui.graphics.Color(0xFF34C759),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            // Arrow icon pressed to 16dp and faded: Material's default 24dp arrow looks much thicker when placed side by side.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                // Use secondaryLabel instead of separator: Material's arrow stroke is thin,
            // so using the same color makes it appear lighter; a darker shade provides equivalent visual weight.
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
        }
    }
    }
}

private fun formatDuration(ms: Int): String = formatClipDuration(ms)

/**
 * Human-readable byte count — decimal 1000-based, KB/MB/GB. Previously, raw byte values (e.g., `361494 B`) were used directly in various places, causing platform inconsistencies.
 */
internal fun formatClipBytes(n: Long): String = when {
    // Display "0 KB" instead of "0 B" for 0 bytes (this is the only place in the real-time page footer)
    n <= 0L -> "0 KB"
    n < 1_000L -> "$n B"
    n < 1_000_000L -> "%.0f KB".format(n / 1_000.0)
    n < 1_000_000_000L -> "%.1f MB".format(n / 1_000_000.0)
    else -> "%.2f GB".format(n / 1_000_000_000.0)
}

/**
 * Password input. The description specifies Argon2id + SN as the salt,
 * and notes that the password cannot be recovered if lost.
 */
/**
 * Set / rotate the encryption password.
 *
 * Layout: **full-screen sheet** (navigation bar title + form card + action card), not an AlertDialog.
 * Key aspect is the **busy state**: Argon2id with 512 MiB / t=4 takes several seconds by itself,
 * and the `set` path adds another ~3 seconds for device recording + BLE transmission.
 * Use a **full-screen overlay + large spinner + current step text**, and make it non-dismissable while busy.
 * Android originally used a dialog with a persistent footer saying "Deriving key..." (regardless of whether it was actually busy),
 * leaving users unsure of what was happening and prone to tapping repeatedly, thinking it was frozen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseDialog(ui: NomiViewModel.Ui, vm: NomiViewModel, rotate: Boolean, willVerify: Boolean) {
    var text by remember { mutableStateOf("") }
    // Secondary confirmation -- If you mistype the password by even one character, all audio recorded on the device afterward will be irretrievable.
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != text
    // **Only recognize this commit**: `ui.busy` is a global device busy flag (refreshing file lists also occupies it), 
    // If you use it as a mask condition, any background operation will lock this table.
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(ui.busy) { if (ui.busy == null) submitted = false }
    val busy = submitted
    // Do not auto‑capitalize or auto‑correct the passphrase field
    val secureKeyboard = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
    )

    androidx.compose.ui.window.Dialog(
        // When busy, tapping outside or pressing back does not close
        onDismissRequest = { if (!busy) vm.needPassphrase(false) },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ),
    ) {
        // This popup is **card-style**: the top portion leaves the status bar area exposed to the parent view, with rounded top corners.
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    CenterAlignedTopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Text(
                                stringResource(
                                    when {
                                        rotate -> R.string.encryption_sheet_title_rotate
                                        willVerify -> R.string.encryption_sheet_title_verify
                                        else -> R.string.encryption_sheet_title_set
                                    },
                                ),
                            )
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        // After successful pairing, the immediate popup should add: encryption is enforced by firmware,
                        //                         // The passphrase cannot be recovered, and recording won't start until it's set.
                        if (ui.passphraseFirstBind) {
                            Text(
                                stringResource(R.string.encryption_first_bind_note),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        Card(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            // Borderless full‑width row with thin separators between rows, not two outlined boxes
                            SecureRow(
                                value = text,
                                placeholder = stringResource(R.string.encryption_field_passphrase),
                                keyboard = secureKeyboard,
                                enabled = !busy,
                                // Tests must target the input field precisely: the label node and the editable node are separate;
                                // performing text input on the label has no effect.
                                tag = PASSPHRASE_FIELD_TAG,
                                onValueChange = { text = it },
                            )
                            HorizontalDivider(
                                Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                            SecureRow(
                                value = confirm,
                                placeholder = stringResource(R.string.encryption_field_confirm),
                                keyboard = secureKeyboard,
                                enabled = !busy,
                                isError = mismatch,
                                tag = PASSPHRASE_CONFIRM_TAG,
                                onValueChange = { confirm = it },
                            )
                        }
                        // Reuse existing copy keys instead of creating new ones
                        Text(
                            stringResource(
                                when {
                                    rotate -> R.string.encryption_footer_rotate
                                    // Device is encrypted: indicates that a probe will be recorded to verify the passphrase (the probe will be deleted automatically).
                                    willVerify -> R.string.encryption_footer_set_encrypted
                                    else -> R.string.encryption_footer_set_unencrypted
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                        // Local validation errors appear as **small red text**, not as a dialog.
                        if (mismatch) {
                            Text(
                                stringResource(R.string.encryption_mismatch_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                        Card(
                            Modifier.fillMaxWidth().padding(top = 18.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            val canSubmit = !busy && text.isNotEmpty() && confirm == text
                            Text(
                                stringResource(
                                    when {
                                        rotate -> R.string.encryption_action_rotate
                                        willVerify -> R.string.encryption_action_verify_save
                                        else -> R.string.encryption_action_save
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (canSubmit) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = canSubmit) { submitted = true; vm.submitPassphrase(text) }
                                    .padding(16.dp),
                            )
                            HorizontalDivider(
                                Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                stringResource(R.string.encryption_action_cancel),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (busy) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !busy) { vm.needPassphrase(false) }
                                    .padding(16.dp),
                            )
                        }
                    }
                }

                // Busy overlay: full‑screen cover with a spinner and current step. Without it, users think the app is frozen.
                if (busy) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            Modifier.background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(12.dp),
                            ).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                            Text(
                                ui.busy ?: stringResource(R.string.encryption_progress_working),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            if (willVerify) {
                                Text(
                                    stringResource(R.string.encryption_progress_probe_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
