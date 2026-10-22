package com.nomily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R

/**
 * Phone-microphone recording with live captions (iOS `PhoneRecordScreen`): clock, level strip, one record button,
 * and the caption transcript with the in-flight hypothesis in gray italics at the bottom.
 * The recording lives in the ViewModel, so leaving the tab does not stop it.
 */
@Composable
internal fun PhoneRecordScreen(vm: NomiViewModel, onViewInLibrary: () -> Unit) {
    val phone by vm.phone.collectAsStateWithLifecycle()
    val captions by vm.phoneCaption.collectAsStateWithLifecycle()
    KeepScreenOn(phone.running)
    val lines = captions.caption.lines
    val partial = captions.caption.partial

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                clock(phone.seconds),
                fontSize = 44.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(8.dp).background(
                        if (phone.running) Color.Red else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        CircleShape,
                    ),
                )
                Text(
                    stringResource(if (phone.running) R.string.live_streaming else R.string.phone_idle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (captions.provider.isNotEmpty()) {
                    Text(
                        stringResource(if (captions.provider == "azure") R.string.phone_provider_azure else R.string.phone_provider_ondevice),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier
                            .background(Color(0xFF2E7D32).copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            LiveLevelBars(phone.levels, paused = false)
            if (phone.running) {
                captions.notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
        phone.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFF57C00), modifier = Modifier.size(18.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }

        if (lines.isEmpty() && partial.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (phone.running) captions.notice ?: stringResource(R.string.phone_listening)
                    else stringResource(R.string.phone_idle_caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val listState = rememberLazyListState()
            val rows = lines.size + if (partial.isNotEmpty()) 1 else 0
            LaunchedEffect(rows, partial) { if (rows > 0) listState.animateScrollToItem(rows - 1) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(lines) { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            clock(line.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (partial.isNotEmpty()) {
                    item {
                        Text(
                            partial,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = { if (phone.running) vm.stopPhoneRecording() else vm.startPhoneRecording() },
                modifier = Modifier.size(72.dp).background(
                    if (phone.running) Color.Red else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
            ) {
                Icon(
                    if (phone.running) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = stringResource(if (phone.running) R.string.phone_stop else R.string.phone_record),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            phone.savedClip?.takeIf { !phone.running }?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.live_saved_to_library, name),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    androidx.compose.material3.TextButton(onClick = onViewInLibrary) {
                        Text(stringResource(R.string.live_view_in_library))
                    }
                }
            }
        }
    }
}

private fun clock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
