package com.nomily.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nomily.app.R

/**
 * Text inside the capsule — 16sp. M3's `labelLarge` is only 14sp, which would make the capsule appear undersized.
 * The battery line is already 12sp (matching `labelMedium`), leave unchanged.
 */
private val PillText: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 21.sp)

/** Known devices (stored in configuration), used for the dropdown on the right side of the capsule. */
data class KnownDevice(val address: String, val name: String)

/** Test anchor for the recording red dot — used by UI tests and device dumps for locating; do not modify. */
const val PILL_RECORDING_DOT_TAG = "pill-recording-dot"

/**
 * Persistent connection capsule.
 *
 * **It is a global chrome, not a component of a single screen**: it must be present in every tab so that
 * "which device is connected, battery level, and whether it is recording" are visible on any screen. Previously Android placed this information on a device card in the main screen; after splitting into multiple tabs it was lost — this is the first issue this rewrite addresses.
 *
 * Three states:
 *  1. No known devices → `+ Add Device` guide button
 *  2. Known devices exist → **split capsule**: left half (name + battery + recording red dot) opens the device panel, right half `⌄` drops down to switch devices
 *  3. Bluetooth unavailable → orange warning (Android side currently lacks adapter status, see TODO below)
 */
@Composable
fun ConnectionPill(
    connectedName: String?,
    battery: Int?,
    isRecording: Boolean,
    isReconnecting: Boolean,
    knownDevices: List<KnownDevice>,
    enabled: Boolean = true,
    onTapDevice: () -> Unit,
    onAddDevice: () -> Unit,
    onSelectDevice: (String) -> Unit,
) {
    if (knownDevices.isEmpty()) {
        PillChrome(enabled = enabled, onClick = onAddDevice) {
            Text("+", style = PillText)
            Text(
                stringResource(R.string.pill_add_device),
                style = PillText,
                fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val activeName = connectedName ?: knownDevices.first().name
    val shape = CircleShape

    Row(
        Modifier
            // Left margin adjusted to 16dp: M3's TopAppBar provides 4dp, so add an extra 12dp here.
            .padding(start = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Left half: open device panel ──
        Row(
            Modifier
                .clickable(enabled = enabled, onClick = onTapDevice)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isReconnecting) {
                CircularProgressIndicator(
                    Modifier.size(12.dp).padding(end = 2.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Text(
                activeName,
                style = PillText,
                fontWeight = FontWeight.SemiBold,
                // When not connected, render in a secondary color.
                color = if (connectedName != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            battery?.let {
                Text(
                    "  $it%",
                    style = MaterialTheme.typography.labelMedium,
                    // Monospaced numbers: the capsule width stays stable when the battery level changes.
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRecording) {
                // Accessibility label: a solid colored dot is invisible to screen readers; also makes it detectable by uiautomator.
                val recordingLabel = stringResource(R.string.common_recording)
                // Solid red dot with a 40% red stroke halo; the halo prevents the dot from disappearing on a light‑colored capsule.
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(14.dp)
                        .background(Color.Red.copy(alpha = 0.4f), CircleShape)
                        .testTag(PILL_RECORDING_DOT_TAG)
                        .semantics { contentDescription = recordingLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                }
            }
        }

        // ── Divider ──
        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        // ── Right half: device dropdown ──
        Box {
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = enabled) { menuOpen = true }
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                    .size(16.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Current device gets a solid check circle, others an outline; "Add Device" appears below the divider with a plus sign.
                knownDevices.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.name) },
                        leadingIcon = {
                            Icon(
                                if (d.name == connectedName) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Outlined.Circle
                                },
                                contentDescription = null,
                                tint = if (d.name == connectedName) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        },
                        onClick = { menuOpen = false; onSelectDevice(d.address) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pill_add_device)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { menuOpen = false; onAddDevice() },
                )
            }
        }
    }
}

@Composable
private fun PillChrome(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            // Left margin adjusted to 16dp: M3's TopAppBar provides 4dp, so add an extra 12dp here.
            .padding(start = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
