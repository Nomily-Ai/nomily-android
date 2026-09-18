package com.nomily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.ui.components.ActionRow
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.InfoRow
import com.nomily.app.ui.components.NomiDimens
import com.nomily.app.ui.components.RowDivider
import com.nomily.app.ui.theme.appSwitchColors
import com.nomily.app.core.protocol.DnoteProtocol

/**
 * Device control panel (`all-features-list.md` P1 "Device Control Panel").
 *
 * Layout **aligned to the reference screenshot captured on 2026‑08‑05 with WDA from a real device**:
 * navigation bar (refresh / device name / done) + a column of white cards on a gray background, each card preceded by a small gray subtitle,
 * rows inside cards have equal height, with inset dividers between rows. Section order: hero card → device info → audio → actions → dangerous operations → connection.
 *
 * Previously it was "divider + bold title + full‑width row + five outlined buttons"; functionality is the same but it didn't look like the same app at a glance.
 *
 * After each control is changed, the ViewModel reads back 0x81 — showing the **value accepted by the device**, not a locally optimistic value (firmware may clamp or reject).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(ui: NomiViewModel.Ui, vm: NomiViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners.
        // Android's full‑screen Dialog occupies the entire screen from top to bottom; side‑by‑side they appear as two different layers.
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Title bar **does not enter the scroll area**: it contains refresh and done; when scrolling down those two buttons get pushed up by the slider — on a real device this caused accidental gain changes (the device value changed while the user thought they tapped "Close").
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(ui.connectedName ?: stringResource(R.string.device_panel))
                    },
                    navigationIcon = {
                        IconButton(onClick = vm::refreshDeviceState) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.device_refresh),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = NomiDimens.rowInset),
                    ) {
                        val controlsEnabled = ui.info?.isRecording != true
                        HeroCard(ui)
                        // During recording the entire screen is gray; the plain gray makes users think the panel is broken (hzhmmgff).
                        // The same message also appears when the command layer blocks the action (device_unavailable_while_recording).
                        if (!controlsEnabled) {
                            Text(
                                stringResource(R.string.device_unavailable_while_recording),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 6.dp),
                            )
                        }
                        // If not paired, show a tappable banner: when automatic pairing
                        // fails, an "Unpaired" line alone leaves no way to act on it.
                        if (ui.bound == false) PairingBanner(ui, vm, controlsEnabled)
                        IdentitySection(ui, vm, controlsEnabled)
                        ui.switches?.let { sw ->
                            AudioSection(sw, vm, controlsEnabled)
                            BehaviorSection(sw, vm, controlsEnabled)
                        }
                        DangerZone(vm, controlsEnabled)
                        ConnectionSection(vm, onDismiss, controlsEnabled)
                        Spacer(Modifier.height(24.dp))
                    }
                    // Busy indicator: **a capsule floating at the bottom**, not a small line of text at the top
                    ui.busy?.let { msg ->
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Layout components
// ──────────────────────────────────────────────────────────────

/** Capacity unit: switch to GB once over 1 GB (`7677 MB` is unreadable in the UI). */
private fun formatMb(mb: Int): String =
    if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) else "$mb MB"

// ──────────────────────────────────────────────────────────────

/** Top hero card: device name + battery, connection status and MTU, storage usage bar. */
@Composable
private fun HeroCard(ui: NomiViewModel.Ui) {
    val info = ui.info
    Group {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info?.bluetoothName ?: ui.connectedName ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                info?.battery?.let { bat ->
                    // Battery icon uses a **horizontal** style with accent‑colored stroke; Material only provides a vertical battery icon,
                    // rotating 90° achieves the desired look. Previously it also used `onSurface` (pure black), which gave the wrong tone.
                    Icon(
                        Icons.Filled.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp).rotate(90f),
                    )
                    Text(
                        "$bat%",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            ui.mtu?.let {
                Text(
                    stringResource(R.string.device_connected_mtu, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val free = info?.freeMb
            val total = info?.totalMb
            if (free != null && total != null && total > 0) {
                Text(
                    stringResource(R.string.device_storage_used, formatMb(total - free), formatMb(total)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                LinearProgressIndicator(
                    progress = { ((total - free).toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    // M3 defaults to a "stop point" at the end of the track; removed here to make a bare, uninterrupted track.
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

/** Remediation when unpaired — a "Pair" button plus an explanatory note outside the card. */
@Composable
private fun PairingBanner(ui: NomiViewModel.Ui, vm: NomiViewModel, enabled: Boolean) {
    Group(footer = stringResource(R.string.pairing_message, ui.connectedName ?: "")) {
        ActionRow(stringResource(R.string.pairing_pair), enabled = enabled, destructive = false, icon = Icons.Filled.AddLink, divider = false) {
            vm.pairDevice()
        }
    }
}

@Composable
private fun IdentitySection(ui: NomiViewModel.Ui, vm: NomiViewModel, enabled: Boolean) {
    val info = ui.info
    var renaming by remember { mutableStateOf(false) }

    Group(stringResource(R.string.device_identity)) {
        // The Bluetooth name is the **only editable field** in this section, highlighted with accent color and a pencil icon.
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { renaming = true }
                .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.device_bluetooth_name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // Editable values and the pencil use a **light version of the accent color**, not gray text with a black pencil.
            val editable = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            Text(
                info?.bluetoothName ?: ui.connectedName ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                color = editable,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = editable,
                modifier = Modifier.padding(start = 8.dp).size(16.dp),
            )
        }
        RowDivider()
        InfoRow(stringResource(R.string.device_serial), info?.serial ?: "—")
        RowDivider()
        InfoRow(stringResource(R.string.device_firmware), info?.firmware ?: "—")
        RowDivider()
        // Display format is `AA:BB:CC:DD:EE:FF` (uppercase, colon‑separated); the device returns `aabbccddeeff`.
    InfoRow(stringResource(R.string.device_bt_mac), formatBtMac(info?.bluetoothMac))
        RowDivider()
        InfoRow(stringResource(R.string.device_device_time), info?.deviceTime ?: "—")
        RowDivider()
        InfoRow(
            stringResource(R.string.pairing_status),
            stringResource(if (ui.bound == true) R.string.pairing_paired else R.string.pairing_not_paired),
        )
    }

    if (renaming) {
        // Length limit is enforced client‑side (≤16 B uses 0x86 JSON, longer uses 0x8E raw UTF‑8; the firmware stops acknowledging above DnoteProtocol.BT_NAME_MAX_BYTES).
        var draft by remember { mutableStateOf(info?.bluetoothName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.device_bluetooth_name)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = DnoteProtocol.clampBluetoothName(it) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= DnoteProtocol.BT_NAME_MAX_BYTES,
                    onClick = { vm.renameDevice(draft.trim()); renaming = false },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SwitchRow(label: String, hint: String? = null, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        // Hint does not occupy a full line: it follows the label as a `?`; tapping expands it.
        hint?.let { InfoTip(it) }
        Spacer(Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled, colors = appSwitchColors())
    }
}

/**
 * Slider for values 1…9. **Command is sent only on release** (`onValueChangeFinished`) — sending on every step during drag would flood the command queue, and the device couldn't ack in time.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GainSlider(label: String, hint: String? = null, value: Int, enabled: Boolean, onCommit: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.coerceIn(1, 9).toFloat()) }
    Column(Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { InfoTip(it) }
            Spacer(Modifier.weight(1f))
            // Display "4 / 9" — showing only "4" hides the upper bound.
            Text(
                "${draft.toInt()} / 9",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Slider should be a "thin track + circular thumb", without tick marks and with a continuous track; M3 defaults to drawing 7 tick marks,
        // a rectangular thumb and gaps on either side, which look poor, so we disable all three.
        val interaction = remember { MutableInteractionSource() }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft.roundToInt()) },
            valueRange = 1f..9f,
            enabled = enabled,
            steps = 0,
            interactionSource = interaction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    thumbSize = DpSize(22.dp, 22.dp),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                )
            },
        )
    }
}

@Composable
private fun AudioSection(sw: com.nomily.app.ble.SwitchInfo, vm: NomiViewModel, enabled: Boolean) {
    // Each item includes a hint: the name alone doesn't convey "what happens when enabled, or the cost of increasing/decreasing",
    // "Noise reduction" and "Noise suppression" are especially ambiguous from the name alone.
    Group(stringResource(R.string.device_audio)) {
        SwitchRow(
            stringResource(R.string.device_noise_cancel),
            hint = stringResource(R.string.device_noise_cancel_hint),
            on = sw.noiseCancel, enabled = enabled,
        ) { vm.setSwitch("nc", it) }
        RowDivider()
        SwitchRow(
            stringResource(R.string.device_save_raw_wav),
            hint = stringResource(R.string.device_save_raw_wav_hint),
            on = sw.saveWav, enabled = enabled,
        ) { vm.setSwitch("wav", it) }
        RowDivider()
        SwitchRow(
            stringResource(R.string.device_vad),
            hint = stringResource(R.string.device_vad_hint),
            on = sw.vad, enabled = enabled,
        ) { vm.setSwitch("vad", it) }
        RowDivider()
        GainSlider(
            stringResource(R.string.device_mic_gain),
            stringResource(R.string.device_mic_gain_hint),
            sw.micGain, enabled, vm::setMicGain,
        )
        RowDivider()
        GainSlider(
            stringResource(R.string.device_noise_reduction),
            stringResource(R.string.device_noise_reduction_hint),
            sw.noiseReduction, enabled, vm::setNrLevel,
        )
    }
}

@Composable
private fun BehaviorSection(sw: com.nomily.app.ble.SwitchInfo, vm: NomiViewModel, enabled: Boolean) {
    Group(stringResource(R.string.device_behavior)) {
        SwitchRow(stringResource(R.string.device_led_indicator), on = sw.led, enabled = enabled) { vm.setSwitch("led", it) }
        RowDivider()
        SwitchRow(stringResource(R.string.device_vibration), on = sw.motor, enabled = enabled) { vm.setSwitch("motor", it) }
        RowDivider()
        SwitchRow(
            stringResource(R.string.device_usb_drive_mode),
            hint = stringResource(R.string.device_usb_drive_mode_hint),
            on = sw.massStorage,
            enabled = enabled,
        ) { vm.setSwitch("ms", it) }
        RowDivider()
        IdleOffRow(sw, vm, enabled)
    }
}

/**
 * Auto shutdown — a dropdown menu on the right side of a line, not a row of chips.
 * A row of chips will collapse into two lines on narrow screens and consume the entire line width.
 */
@Composable
private fun IdleOffRow(sw: com.nomily.app.ble.SwitchInfo, vm: NomiViewModel, enabled: Boolean) {
    val presets = listOf(
        "30 s" to 30,
        "1 m" to 60,
        "5 m" to 5 * 60,
        "15 m" to 15 * 60,
        "1 h" to 60 * 60,
        stringResource(R.string.device_never) to DnoteProtocol.IDLE_OFF_NEVER,
    )
    // The firmware reported a value we have no preset for — display it as-is, don't silently change the user's choice
    val current = presets.firstOrNull { it.second == sw.idleOff }?.first
        ?: stringResource(R.string.device_custom_idle_off, sw.idleOff)
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.device_auto_power_off),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            current,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp).size(18.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            presets.forEach { (label, seconds) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { vm.setIdleOff(seconds); open = false },
                )
            }
        }
    }
}

@Composable
private fun DangerZone(vm: NomiViewModel, enabled: Boolean) {
    var pending by remember { mutableStateOf<Danger?>(null) }

    Group(
        stringResource(R.string.device_danger_zone),
        footer = stringResource(R.string.device_danger_zone_footer),
    ) {
        ActionRow(stringResource(R.string.device_format_storage), icon = Icons.Filled.SdStorage, enabled = enabled, destructive = true, divider = false) { pending = Danger.FORMAT }
        RowDivider()
        ActionRow(stringResource(R.string.device_factory_reset), icon = Icons.Filled.SettingsBackupRestore, enabled = enabled, destructive = true, divider = false) {
            pending = Danger.FACTORY
        }
        RowDivider()
        ActionRow(stringResource(R.string.device_shut_down), icon = Icons.Filled.PowerSettingsNew, enabled = enabled, destructive = true, divider = false) {
            pending = Danger.SHUTDOWN
        }
        RowDivider()
        ActionRow(stringResource(R.string.pairing_unpair), icon = Icons.Filled.Cancel, enabled = enabled, destructive = true, divider = false) { pending = Danger.UNPAIR }
        RowDivider()
        ActionRow(stringResource(R.string.device_unbind_and_remove), icon = Icons.Filled.DeleteForever, enabled = enabled, destructive = true, divider = false) {
            pending = Danger.UNBIND_REMOVE
        }
    }

    pending?.let { d ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(d.title)) },
            text = { Text(stringResource(d.message)) },
            confirmButton = {
                TextButton(onClick = {
                    when (d) {
                        Danger.FORMAT -> vm.formatDisk()
                        Danger.FACTORY -> vm.factoryReset()
                        Danger.SHUTDOWN -> vm.shutdown()
                        Danger.UNPAIR -> vm.unbindDevice()
                        Danger.UNBIND_REMOVE -> vm.unbindAndRemove()
                    }
                    pending = null
                }) { Text(stringResource(d.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Disconnect (`device.connection` section).
 *
 * 2026-08-03 Moved from the Recordings screen: this button only appears in the device panel,
 * Not present on the main list page. Placed **after** dangerous operations.
 */
@Composable
private fun ConnectionSection(vm: NomiViewModel, onDismiss: () -> Unit, enabled: Boolean) {
    Group(stringResource(R.string.device_connection)) {
        ActionRow(stringResource(R.string.device_disconnect), icon = Icons.Filled.LinkOff, enabled = enabled, destructive = true, divider = false) {
            vm.disconnect(); onDismiss()
        }
    }
}

/** Titles/Descriptions/Confirmations for the five irreversible actions — clearly state what will happen. */
private enum class Danger(val title: Int, val message: Int, val confirm: Int) {
    FORMAT(R.string.device_erase_all_recordings, R.string.device_erase_message, R.string.device_format_storage),
    FACTORY(R.string.device_restore_factory, R.string.device_restore_message, R.string.device_factory_reset),
    SHUTDOWN(R.string.device_power_off, R.string.device_power_off_message, R.string.device_shut_down),
    UNPAIR(R.string.pairing_unpair, R.string.pairing_unpair_message, R.string.pairing_unpair),
    UNBIND_REMOVE(
        R.string.device_unbind_and_remove,
        R.string.device_unbind_and_remove_message,
        R.string.device_unbind_and_remove,
    ),
}


/** `aabbccddeeff` -> `AA:BB:CC:DD:EE:FF`. Return unchanged if it already has separators or the length is incorrect. */
private fun formatBtMac(raw: String?): String {
    val hex = raw?.trim().orEmpty()
    if (hex.isEmpty()) return "—"
    if (hex.contains(":") || hex.length != 12) return hex.uppercase()
    return hex.uppercase().chunked(2).joinToString(":")
}
