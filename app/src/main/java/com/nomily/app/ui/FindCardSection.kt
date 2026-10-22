package com.nomily.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.ble.CardFinder
import com.nomily.app.ble.DeviceScanner
import com.nomily.app.core.finder.CardFinderLogic
import com.nomily.app.ui.components.ActionRow
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.InfoRow
import com.nomily.app.ui.components.NomiDimens
import com.nomily.app.ui.components.RowDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import java.text.DateFormat
import java.util.Date

/**
 * "Find card": last seen (time, place, history), a proximity radar and the drop-alert switch.
 *
 * The radar can only measure while the card is not connected (a connected peripheral stops advertising),
 * so a connected card shows "connected" instead of a reading. The scan lives and dies with this composable.
 */
@Composable
fun FindCardSection(vm: NomiViewModel, connected: Boolean) {
    val context = LocalContext.current
    val st by CardFinder.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }

    // Grants change in system settings; re-read them whenever the page comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) CardFinder.refreshPermissions() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val title = stringResource(R.string.find_header)
    Group(
        st.name?.let { stringResource(R.string.find_header_for, it) } ?: title,
        footer = stringResource(R.string.find_hint),
    ) {
        val seenAt = st.lastSeenAt
        val newest = st.history.firstOrNull()
        if (seenAt != null) {
            InfoRow(stringResource(R.string.find_last_seen_header), formatTime(seenAt))
            RowDivider()
            val placeText = newest?.place ?: stringResource(R.string.find_place_unknown)
            val canOpen = newest?.latitude != null
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = canOpen) { newest?.let { CardFinder.openInMaps(context, it) } }
                    .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.find_radar_header), style = MaterialTheme.typography.bodyLarge)
                Text(
                    placeText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (canOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            RowDivider()
        } else {
            Text(
                stringResource(R.string.find_last_seen_never),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
            )
            RowDivider()
        }

        if (st.history.isNotEmpty()) {
            ActionRow(
                stringResource(R.string.find_history_header, st.history.size),
                icon = Icons.Filled.History,
                divider = !historyOpen,
            ) { historyOpen = !historyOpen }
            if (historyOpen) {
                st.history.take(CardFinderLogic.HISTORY_SHOWN).forEach { rec ->
                    val canOpen = rec.latitude != null
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = canOpen) { CardFinder.openInMaps(context, rec) }
                            .padding(horizontal = NomiDimens.rowInset, vertical = 6.dp),
                    ) {
                        Text(formatTime(rec.at), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            rec.place ?: stringResource(R.string.find_place_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (canOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (st.history.size > CardFinderLogic.HISTORY_SHOWN) {
                    Text(
                        stringResource(R.string.find_history_more, st.history.size - CardFinderLogic.HISTORY_SHOWN),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 4.dp),
                    )
                }
                ActionRow(stringResource(R.string.find_clear_history), icon = Icons.Filled.DeleteSweep, destructive = true) {
                    CardFinder.clearHistory()
                }
            }
        }

        if (!st.locationGranted) {
            Text(
                stringResource(R.string.find_place_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 8.dp),
            )
            ActionRow(stringResource(R.string.find_allow_location), icon = Icons.Filled.LocationOn) {
                vm.requestFinderLocation()
            }
        }

        Radar(address = st.address, connected = connected)

        SwitchRow(
            stringResource(R.string.find_notify_toggle),
            on = st.notifyOnDisconnect, enabled = true,
        ) { vm.setFinderNotify(it) }
        RowDivider()
        if (st.notifyOnDisconnect && st.notificationsBlocked) {
            Text(
                stringResource(R.string.find_notify_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 8.dp),
            )
            ActionRow(stringResource(R.string.find_open_settings), icon = Icons.Filled.Notifications, divider = false) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else if (st.notifyOnDisconnect) {
            ActionRow(stringResource(R.string.find_test_alert), icon = Icons.Filled.Notifications, divider = false) {
                CardFinder.sendTestAlert()
            }
        }
    }
}

/** Proximity rows. The BLE scan runs only while [scanning] and this composable is in the tree. */
@Composable
private fun Radar(address: String?, connected: Boolean) {
    if (connected) {
        Text(
            stringResource(R.string.find_connected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
        )
        RowDivider()
        return
    }
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var rssi by remember { mutableStateOf<Int?>(null) }
    var lastAdvAt by remember { mutableStateOf(0L) }
    var level by remember { mutableStateOf(0) }

    // Matching is by the recorded device address: an alias never reaches the radio, an address does.
    LaunchedEffect(scanning, address) {
        if (!scanning || address == null) return@LaunchedEffect
        var seen: DeviceScanner.Discovered? = null
        DeviceScanner(context).scan()
            .catch { scanning = false } // adapter off / no scan permission: stop quietly
            .collect { list ->
                val d = list.firstOrNull { it.address == address }
                // The scanner replaces the entry on every advert, so a new object is a new advert.
                if (d != null && d !== seen) {
                    seen = d
                    rssi = d.rssi
                    lastAdvAt = System.currentTimeMillis()
                    level = CardFinderLogic.proximityLevel(rssi, 0)
                }
            }
    }
    LaunchedEffect(scanning) {
        if (!scanning) { rssi = null; level = 0; return@LaunchedEffect }
        while (true) {
            delay(500)
            level = CardFinderLogic.proximityLevel(rssi, System.currentTimeMillis() - lastAdvAt)
            if (level == 0) rssi = null
        }
    }

    Row(
        Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProximityBars(level)
        Text(
            stringResource(
                when (level) {
                    3 -> R.string.find_tier_very_near
                    2 -> R.string.find_tier_near
                    1 -> R.string.find_tier_far
                    else -> R.string.find_tier_unknown
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        rssi?.let {
            Text("$it dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    RowDivider()
    if (scanning && level == 0) {
        Text(
            stringResource(R.string.find_scanning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 6.dp),
        )
    }
    ActionRow(
        stringResource(if (scanning) R.string.find_stop else R.string.find_start),
        icon = if (scanning) Icons.Filled.StopCircle else Icons.Filled.Radar,
        enabled = address != null,
        destructive = scanning,
    ) { scanning = !scanning }
}

/** Three bars that fill as the card gets closer; a number would only invite "why 71 then 68". */
@Composable
private fun ProximityBars(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(21.dp)) {
        for (i in 1..3) {
            Column(
                Modifier.width(5.dp).height((6 + i * 5).dp).clip(RoundedCornerShape(2.dp)).background(
                    if (i <= level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {}
        }
    }
}

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
