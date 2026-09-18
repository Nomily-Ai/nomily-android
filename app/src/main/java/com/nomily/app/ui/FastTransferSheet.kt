package com.nomily.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.InfoRow
import com.nomily.app.ui.components.NomiDimens

/**
 * Quick transfer panel — tapping "Wi-Fi Quick Transfer" pushes up a sheet, and the
 * whole process lives inside it: **Status** (icon + stage name + hint + error) →
 * **AP credentials** (SSID / password) → **Files (N)** (one row per file: name +
 * size + pending/progress/saved/failed) →
 * a "keep in foreground" note while unfinished → a "Close" button when done or failed.
 *
 * Before, there was only a global progress bar on the main list + a stream of busy
 * messages: which step it was stuck at, how far each file had gotten, and what the
 * AP account/password was (needed when manually joining the hotspot) — none visible.
 *
 * Styling follows Material defaults (a consistent look is enough; not pixel-perfect).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FastTransferSheet(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    val ft = ui.fastTransfer ?: return
    val finished = ft.stage == NomiViewModel.FtStage.DONE ||
        ft.stage == NomiViewModel.FtStage.FAILED ||
        ft.stage == NomiViewModel.FtStage.JOIN_FAILED
    val canRetry = ft.stage == NomiViewModel.FtStage.JOIN_FAILED || ft.stage == NomiViewModel.FtStage.FAILED

    Dialog(
        // Do not casually close during a transfer: closing = canceling the transfer (canceling requires an explicit button)
        onDismissRequest = { if (finished) vm.dismissFastTransfer(cancel = false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = finished,
            dismissOnClickOutside = false,
        ),
    ) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.fast_transfer_title)) },
                    navigationIcon = {
                        // "Cancel" is disabled in the completed state
                        TextButton(
                            onClick = { vm.dismissFastTransfer(cancel = true) },
                            enabled = ft.stage != NomiViewModel.FtStage.DONE,
                        ) { Text(stringResource(R.string.common_cancel)) }
                    },
                    actions = {
                        // On hotspot join failure / transmission failure, retry — only retransmit the ones that haven't succeeded yet
                        if (canRetry) {
                            TextButton(onClick = vm::retryFastTransfer) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = NomiDimens.rowInset)) {
                    // ── State ──
                    item {
                        Group(stringResource(R.string.fast_transfer_status)) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                when (ft.stage) {
                                    NomiViewModel.FtStage.DONE -> Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    NomiViewModel.FtStage.FAILED,
                                    NomiViewModel.FtStage.JOIN_FAILED -> Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    else -> CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(
                                    if (ft.stage == NomiViewModel.FtStage.POLLING_AP) {
                                        stringResource(
                                            R.string.fast_transfer_stage_polling_ap,
                                            ft.pollAttempt,
                                            ft.pollTotal,
                                        )
                                    } else {
                                        stringResource(ft.stage.labelRes())
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                            // Stage hint: waiting for hotspot / join failure each has a line
                            val hint = when (ft.stage) {
                                NomiViewModel.FtStage.POLLING_AP -> R.string.fast_transfer_hint_polling
                                NomiViewModel.FtStage.JOIN_FAILED -> R.string.fast_transfer_hint_join_failed
                                else -> null
                            }
                            hint?.let {
                                Text(
                                    stringResource(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                )
                            }
                            // The hotspot wasn't turned off (or we can't verify whether it was): the next quick share most likely won't be able to join,
                            // so say it out loud — don't just leave it in the log.
                            if (ft.apStillOn && finished) {
                                HorizontalDivider(
                                    Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    stringResource(R.string.fast_transfer_ap_still_on),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFFF9500),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            ft.error?.let { msg ->
                                HorizontalDivider(
                                    Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    // Failures/degradations are always orange (red is reserved for destructive operations and "recording")
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9500),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        msg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFFF9500),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    // ── AP credentials (copy these two lines when manually connecting to hotspot) ──
                    if (ft.ssid != null) {
                        item {
                            Group(stringResource(R.string.fast_transfer_ap_credentials)) {
                                InfoRow(stringResource(R.string.rec_settings_ssid), ft.ssid, monospace = true)
                                ft.psk?.let {
                                    HorizontalDivider(
                                        Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    InfoRow(stringResource(R.string.rec_settings_password), it, monospace = true)
                                }
                            }
                        }
                    }

                    // ── Files (N) ──
                    if (ft.files.isNotEmpty()) {
                        item {
                            Group(stringResource(R.string.fast_transfer_files_count, ft.files.size)) {
                                ft.files.forEachIndexed { i, f ->
                                    FileRow(f)
                                    if (i < ft.files.lastIndex) {
                                        HorizontalDivider(
                                            Modifier.padding(start = 16.dp),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── "Keep in foreground" hint when incomplete ──
                    if (!finished) {
                        item {
                            Text(
                                stringResource(R.string.common_keep_foreground),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                            )
                        }
                    }

                    // ── "Close" after completion / failure ──
                    if (finished) {
                        item {
                            Group {
                                Text(
                                    stringResource(R.string.common_close),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { vm.dismissFastTransfer(cancel = false) }
                                        .heightIn(min = NomiDimens.rowMinHeight)
                                        .padding(16.dp),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun NomiViewModel.FtStage.labelRes(): Int = when (this) {
    NomiViewModel.FtStage.STOPPING -> R.string.fast_transfer_stage_stopping
    NomiViewModel.FtStage.STARTING_AP -> R.string.fast_transfer_stage_starting_ap
    NomiViewModel.FtStage.POLLING_AP -> R.string.fast_transfer_stage_polling_ap
    NomiViewModel.FtStage.JOINING -> R.string.fast_transfer_stage_joining_wifi
    NomiViewModel.FtStage.JOIN_FAILED -> R.string.fast_transfer_stage_join_failed
    NomiViewModel.FtStage.CLEANUP -> R.string.fast_transfer_stage_cleanup
    NomiViewModel.FtStage.CONNECTING -> R.string.fast_transfer_stage_connecting
    NomiViewModel.FtStage.DOWNLOADING -> R.string.fast_transfer_stage_downloading
    NomiViewModel.FtStage.DONE -> R.string.fast_transfer_stage_done
    NomiViewModel.FtStage.FAILED -> R.string.fast_transfer_stage_stopped
}

/** One file per line: name (fixed width) + size; below, according to status, provide “Pending / Progress / Saved / Failure reason”. */
@Composable
private fun FileRow(f: NomiViewModel.FtFile) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatClipBytes(f.size.toLong()),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (f.status) {
            NomiViewModel.FtFileStatus.PENDING -> Text(
                stringResource(R.string.fast_transfer_pending),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NomiViewModel.FtFileStatus.DOWNLOADING -> {
                LinearProgressIndicator(
                    progress = { if (f.size > 0) f.got.toFloat() / f.size else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Text(
                    "${formatClipBytes(f.got.toLong())} / ${formatClipBytes(f.size.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NomiViewModel.FtFileStatus.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.fast_transfer_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            NomiViewModel.FtFileStatus.FAILED -> Text(
                f.error ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
