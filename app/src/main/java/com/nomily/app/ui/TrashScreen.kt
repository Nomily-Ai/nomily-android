package com.nomily.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.core.clips.ClipTrash
import com.nomily.app.ui.components.Footer
import com.nomily.app.ui.components.RowDivider
import java.text.DateFormat
import java.util.Date

/**
 * Recently Deleted: clips and single artefacts kept for 30 days. Each row can be recovered or deleted for good;
 * "Select" switches to multi-select with recover / delete for the ticked rows.
 */
@Composable
fun TrashPage(vm: NomiViewModel, onPush: (PushedPage?) -> Unit, onDismiss: () -> Unit) {
    val items by vm.library.trashItems.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingPurge by remember { mutableStateOf<ClipTrash.Item?>(null) }
    var confirmPurgeAll by remember { mutableStateOf(false) }
    var confirmPurgeSelected by remember { mutableStateOf(false) }
    var restoreFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.purgeExpiredTrash() }
    // Rows purged elsewhere (expiry, another entry point) drop out of the selection too.
    LaunchedEffect(items) {
        selected = selected intersect items.map { it.id }.toSet()
        if (items.isEmpty()) selecting = false
    }
    val chosen = items.filter { it.id in selected }
    val allSelected = items.isNotEmpty() && selected.size == items.size

    val title = stringResource(R.string.trash_title)
    val selectLabel = stringResource(R.string.trash_select)
    val doneLabel = stringResource(R.string.common_done)
    val selectAllLabel = stringResource(R.string.trash_select_all)
    val deselectAllLabel = stringResource(R.string.trash_deselect_all)
    val emptyAllLabel = stringResource(R.string.trash_empty_all)
    LaunchedEffect(title, selecting, allSelected, items.isEmpty()) {
        onPush(
            PushedPage(
                title = title,
                actions = {
                    if (items.isNotEmpty()) {
                        if (selecting) {
                            TextButton(onClick = {
                                selected = if (allSelected) emptySet() else items.map { it.id }.toSet()
                            }) { Text(if (allSelected) deselectAllLabel else selectAllLabel) }
                            TextButton(onClick = { selecting = false; selected = emptySet() }) { Text(doneLabel) }
                        } else {
                            TextButton(onClick = { selecting = true; selected = emptySet() }) { Text(selectLabel) }
                            TextButton(onClick = { confirmPurgeAll = true }) {
                                Text(emptyAllLabel, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                onBack = onDismiss,
            ),
        )
    }
    DisposableEffect(Unit) { onDispose { onPush(null) } }
    BackHandler { if (selecting) { selecting = false; selected = emptySet() } else onDismiss() }

    fun restore(list: List<ClipTrash.Item>) {
        if (!vm.restoreFromTrash(list)) restoreFailed = true
        selected = selected - list.map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
                Text(
                    stringResource(R.string.trash_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.trash_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.padding(top = 8.dp)) }
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    items.forEachIndexed { i, item ->
                        TrashRow(
                            item = item,
                            selecting = selecting,
                            isSelected = item.id in selected,
                            onToggle = {
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            },
                            onRestore = { restore(listOf(item)) },
                            onPurge = { pendingPurge = item },
                        )
                        if (i < items.lastIndex) RowDivider()
                    }
                }
                Footer(stringResource(R.string.trash_retention_footer, ClipTrash.RETENTION_DAYS.toInt()))
            }
        }
        if (selecting && chosen.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { restore(chosen) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = GREEN, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.trash_restore_selected, chosen.size), color = GREEN, modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { confirmPurgeSelected = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.trash_delete_selected, chosen.size),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }

    pendingPurge?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever_title),
            message = "${item.title}\n\n${stringResource(R.string.trash_delete_forever_message)}",
            confirm = stringResource(R.string.trash_delete_forever),
            onConfirm = { vm.purgeTrash(listOf(item)); selected = selected - item.id },
            onDismiss = { pendingPurge = null },
        )
    }
    if (confirmPurgeAll) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_all_title),
            message = stringResource(R.string.trash_empty_all_message, items.size),
            confirm = stringResource(R.string.trash_empty_all),
            onConfirm = { vm.purgeAllTrash() },
            onDismiss = { confirmPurgeAll = false },
        )
    }
    if (confirmPurgeSelected) {
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_selected_title),
            message = stringResource(R.string.trash_delete_selected_message, chosen.size),
            confirm = stringResource(R.string.trash_delete_selected, chosen.size),
            onConfirm = { vm.purgeTrash(chosen); selected = emptySet() },
            onDismiss = { confirmPurgeSelected = false },
        )
    }
    if (restoreFailed) {
        AlertDialog(
            onDismissRequest = { restoreFailed = false },
            title = { Text(stringResource(R.string.trash_restore_failed_title)) },
            text = { Text(stringResource(R.string.trash_restore_failed_message)) },
            confirmButton = { TextButton(onClick = { restoreFailed = false }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

private val GREEN = Color(0xFF34C759)

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TrashRow(
    item: ClipTrash.Item,
    selecting: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val daysLeft = item.daysLeft(now)
    Row(
        Modifier.fillMaxWidth()
            .then(if (selecting) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selecting) {
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 10.dp).size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            item.source?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.hasAudio) Chip(stringResource(R.string.trash_artefact_audio))
                if (item.hasTranscript) Chip(stringResource(R.string.trash_artefact_transcript))
                if (item.hasSummary) Chip(stringResource(R.string.trash_artefact_summary))
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        "${stringResource(R.string.trash_deleted_on)} " +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.deletedAt)),
                        if (item.bytes > 0) formatClipBytes(item.bytes) else null,
                    ).joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.trash_days_left, daysLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (daysLeft <= 3) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selecting) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(stringResource(R.string.trash_restore), Icons.AutoMirrored.Filled.Undo, GREEN, onRestore)
                    SmallButton(stringResource(R.string.trash_delete_forever), Icons.Filled.Delete, MaterialTheme.colorScheme.error, onPurge)
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 30.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
