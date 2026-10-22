package com.nomily.app.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nomily.app.NomiViewModel
import com.nomily.app.NomiViewModel.LocalClip
import com.nomily.app.R
import com.nomily.app.ble.RecordingName
import com.nomily.app.core.clips.ClipManifest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private enum class SearchScope(val titleRes: Int) {
    KNOWLEDGE(R.string.library_search_scope_knowledge),
    ALL(R.string.library_search_scope_all),
}

/** Time bucket; [rank] orders Today → Yesterday → This week → This month → months newest first. */
private data class Bucket(val rank: Int, val month: Int = 0) {
    val sortKey: Long get() = rank * 1_000_000L - month
}

private class Entry(val clip: LocalClip, val index: Int)

/** One (time × device) section; the bucket title shows only on the first section of its bucket. */
private class Section(
    val id: String,
    val showsBucketTitle: Boolean,
    val bucketTitle: String,
    val deviceName: String,
    val bucketCount: Int,
    val bucketBytes: Long,
    val unreadCount: Int,
    val entries: List<Entry>,
)

/**
 * The library: knowledge search, device filter chips, grouping by time then device, unread dots, source badges,
 * and multi-select with batch export (into a folder the user picks) and batch delete (into Recently Deleted).
 *
 * [selecting] is hoisted so the navigation bar can show Select / Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryList(
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    selecting: Boolean,
    onSelectingChange: (Boolean) -> Unit,
    onOpenClip: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf(SearchScope.KNOWLEDGE) }
    var snippets by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var deviceFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingMerge by remember { mutableStateOf<Pair<LocalClip, LocalClip>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val clips = ui.clips
    val searchActive = query.isNotBlank()
    LaunchedEffect(searchActive) { vm.setLibrarySearching(searchActive) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.setLibrarySearching(false) } }

    // Knowledge search: re-run when the query, scope or library changes; typing fast cancels the previous run.
    LaunchedEffect(query, clips) {
        if (!searchActive) { snippets = emptyMap(); searching = false; return@LaunchedEffect }
        searching = true
        delay(150)
        snippets = vm.searchKnowledge(query, clips)
        searching = false
    }
    LaunchedEffect(selecting) { if (!selecting) selected = emptySet() }
    BackHandler(enabled = selecting) { onSelectingChange(false) }

    // Devices that appear in the library, most clips first. The filter falls back to "All" once its device is gone.
    val devices = remember(clips) {
        clips.groupBy { it.source.groupKey }
            .map { (key, list) -> Triple(key, vm.library.displayName(list.first().source), list.size) }
            .sortedByDescending { it.third }
    }
    val effectiveFilter = deviceFilter?.takeIf { f -> devices.any { it.first == f } }

    val todayTitle = stringResource(R.string.library_bucket_today)
    val yesterdayTitle = stringResource(R.string.library_bucket_yesterday)
    val weekTitle = stringResource(R.string.library_bucket_this_week)
    val monthTitle = stringResource(R.string.library_bucket_this_month)
    val sections = remember(clips, effectiveFilter, snippets, searchScope, query, searchActive) {
        buildSections(
            clips, effectiveFilter, searchActive, query, searchScope, snippets,
            vm.library::displayName, listOf(todayTitle, yesterdayTitle, weekTitle, monthTitle),
        )
    }
    val visible = sections.flatMap { it.entries }
    val chosen = clips.filter { it.base in selected }

    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val items = chosen
        scope.launch {
            busyMessage = context.getString(R.string.library_export_preparing)
            val failures = vm.exportClips(items, tree)
            busyMessage = null
            if (failures.isNotEmpty()) {
                errorTitle = context.getString(R.string.library_export_failed)
                errorMessage = failures.joinToString("\n")
            } else {
                onSelectingChange(false)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (clips.isEmpty()) {
            EmptyState(R.string.recordings_no_clips, R.string.recordings_no_clips_message)
            return@Column
        }
        SearchField(query, onQuery = { query = it }, enabled = !selecting)
        if (searchActive) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp).height(32.dp)) {
                SearchScope.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = searchScope == s,
                        onClick = { searchScope = s },
                        shape = SegmentedButtonDefaults.itemShape(i, SearchScope.entries.size),
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.surface,
                            inactiveContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
                        ),
                    ) { Text(stringResource(s.titleRes), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        // Only worth a row when there's more than one device to choose between.
        if (devices.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)
                    .semantics { contentDescription = context.getString(R.string.library_filter_devices) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(stringResource(R.string.library_filter_all_devices), clips.size, effectiveFilter == null) {
                    deviceFilter = null
                }
                devices.forEach { (key, name, count) ->
                    FilterChip(name, count, effectiveFilter == key) { deviceFilter = key }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                searchActive && sections.isEmpty() && searching ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
                sections.isEmpty() -> EmptyState(R.string.library_search_empty_title, R.string.library_search_empty_message, Icons.Filled.Search)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "export-ready") { ExportReadyBanner(vm) }
                    items(sections, key = { it.id }) { section ->
                        SectionHeader(section)
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            section.entries.forEachIndexed { i, e ->
                                val c = e.clip
                                // Merging needs the whole library's time order, so not while searching or selecting.
                                val older = if (!selecting && !searchActive) adjacentOlder(clips, e.index) else null
                                LibraryRow(
                                    clip = c,
                                    sourceName = vm.library.displayName(c.source),
                                    snippet = snippets[c.base],
                                    selecting = selecting,
                                    isSelected = c.base in selected,
                                    mergeWith = older?.let { o -> { pendingMerge = o to c } },
                                    onLongPress = {
                                        if (!selecting && !searchActive) {
                                            selected = setOf(c.base)
                                            onSelectingChange(true)
                                        }
                                    },
                                ) {
                                    if (selecting) {
                                        selected = if (c.base in selected) selected - c.base else selected + c.base
                                    } else {
                                        onOpenClip(c.base)
                                    }
                                }
                                if (i < section.entries.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        if (selecting) {
            val allSelected = visible.isNotEmpty() && visible.all { it.clip.base in selected }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { selected = if (allSelected) emptySet() else visible.map { it.clip.base }.toSet() },
                        enabled = visible.isNotEmpty(),
                    ) {
                        Text(
                            if (allSelected) stringResource(R.string.library_deselect_all)
                            else stringResource(R.string.library_select_all_count, visible.size),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.library_selected_count, selected.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { exportPicker.launch(null) }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.IosShare, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.library_export), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }

    busyMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(msg, Modifier.padding(start = 16.dp))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete_selected_title)) },
            text = { Text(stringResource(R.string.library_delete_selected_message, chosen.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val items = chosen
                    scope.launch {
                        busyMessage = context.getString(R.string.library_deleting)
                        val failures = vm.trashClips(items)
                        busyMessage = null
                        onSelectingChange(false)
                        if (failures.isNotEmpty()) {
                            errorTitle = context.getString(R.string.library_delete_failed)
                            errorMessage = failures.joinToString("\n")
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(errorTitle.orEmpty()) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text(stringResource(R.string.common_ok)) } },
        )
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
                    // Destructive styling: the two originals leave the library (to Recently Deleted).
                    Text(stringResource(R.string.library_merge), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMerge = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun buildSections(
    clips: List<LocalClip>,
    filter: String?,
    searching: Boolean,
    query: String,
    scope: SearchScope,
    snippets: Map<String, String>,
    deviceName: (ClipManifest.Source) -> String,
    fixedTitles: List<String>,
): List<Section> {
    val cal = Calendar.getInstance()
    fun startOf(field: Int): Long = (cal.clone() as Calendar).apply {
        when (field) {
            Calendar.WEEK_OF_YEAR -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            Calendar.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
        }
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val today = startOf(Calendar.DAY_OF_YEAR)
    val yesterday = today - 24 * 3600 * 1000L
    val week = minOf(startOf(Calendar.WEEK_OF_YEAR), today)
    val month = startOf(Calendar.MONTH)
    fun bucketOf(t: Long): Bucket = when {
        // A card whose clock isn't set can report a future time; it joins Today rather than topping the list.
        t >= today -> Bucket(0)
        t >= yesterday -> Bucket(1)
        t >= week -> Bucket(2)
        t >= month -> Bucket(3)
        else -> Calendar.getInstance().apply { timeInMillis = t }.let { Bucket(4, it.get(Calendar.YEAR) * 100 + it.get(Calendar.MONTH) + 1) }
    }
    val monthFormat = SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMM"), Locale.getDefault(),
    )
    fun title(b: Bucket): String = if (b.rank < 4) fixedTitles[b.rank] else {
        monthFormat.format(Calendar.getInstance().apply { clear(); set(b.month / 100, b.month % 100 - 1, 1) }.time)
    }

    // Clips are newest first, so appending in order keeps buckets and device groups newest first too.
    val perBucket = linkedMapOf<Bucket, LinkedHashMap<String, MutableList<Entry>>>()
    val names = HashMap<String, String>()
    clips.forEachIndexed { index, c ->
        val key = c.source.groupKey
        if (filter != null && key != filter) return@forEachIndexed
        if (searching) {
            val inBody = c.base in snippets
            val keep = when (scope) {
                SearchScope.KNOWLEDGE -> inBody
                // "All" also matches titles of clips that have no text yet.
                // "All" also matches by name — the file name or the displayed title — since untranscribed clips have no text.
                SearchScope.ALL -> inBody ||
                    com.nomily.app.core.text.KnowledgeSearch.matches(c.name, query) ||
                    com.nomily.app.core.text.KnowledgeSearch.matches(c.title ?: RecordingName.displayTitle(c.name), query)
            }
            if (!keep) return@forEachIndexed
        }
        names.getOrPut(key) { deviceName(c.source) }
        perBucket.getOrPut(bucketOf(c.recordedAt?.time ?: 0L)) { linkedMapOf() }
            .getOrPut(key) { mutableListOf() } += Entry(c, index)
    }
    return perBucket.entries.sortedBy { it.key.sortKey }.flatMap { (bucket, groups) ->
        val all = groups.values.flatten()
        val bytes = all.sumOf { it.clip.sizeBytes }
        groups.entries.mapIndexed { i, (key, entries) ->
            Section(
                id = "${bucket.rank}-${bucket.month}|$key",
                showsBucketTitle = i == 0,
                bucketTitle = title(bucket),
                deviceName = names[key] ?: key,
                bucketCount = all.size,
                bucketBytes = bytes,
                unreadCount = entries.count { it.clip.unread },
                entries = entries,
            )
        }
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

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, enabled: Boolean) {
    TextField(
        value = query,
        onValueChange = onQuery,
        enabled = enabled,
        placeholder = { Text(stringResource(R.string.library_search_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
            unfocusedContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
            disabledContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** One filter pill: solid accent when selected, light grey otherwise; the count is a small tag inside. */
@Composable
private fun FilterChip(title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(start = 6.dp)
                .background(if (selected) fg.copy(alpha = 0.28f) else fg.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SectionHeader(section: Section) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        if (section.showsBucketTitle) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(section.bucketTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    (if (section.bucketCount == 1) stringResource(R.string.library_short_count_one, 1)
                    else stringResource(R.string.library_short_count_other, section.bucketCount)) +
                        " · " + formatClipBytes(section.bucketBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
            Text(
                section.deviceName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (section.unreadCount > 0) {
                Box(Modifier.size(6.dp).background(UNREAD_RED, CircleShape))
                Text(
                    stringResource(R.string.library_unread_count, section.unreadCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private val UNREAD_RED = Color(0xFFFF3B30)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryRow(
    clip: LocalClip,
    sourceName: String,
    snippet: String?,
    selecting: Boolean,
    isSelected: Boolean,
    mergeWith: (() -> Unit)?,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    // "Merge adjacent" is revealed by swiping left; the gesture only opens the confirmation and the row springs back.
    // The swipe state outlives recomposition, so read the *current* callback: a captured one would act on whichever
    // clips sat in this row when it was first composed (after a merge or delete, the wrong pair).
    val currentMergeWith by rememberUpdatedState(mergeWith)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) currentMergeWith?.invoke()
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
                    Modifier.fillMaxSize().background(Color(0xFF5856D6)).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.library_merge), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .testTag(CLIP_ROW_TAG)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (clip.unread) {
                        val unread = stringResource(R.string.library_unread)
                        Box(
                            Modifier.padding(end = 6.dp).size(8.dp).background(UNREAD_RED, CircleShape)
                                .semantics { contentDescription = unread },
                        )
                    }
                    Text(
                        clip.title ?: RecordingName.displayTitle(clip.name),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    if (!clip.hasAudio) {
                        Icon(Icons.Filled.VoiceOverOff, stringResource(R.string.clip_detail_text_only), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                    if (clip.transcript != null) {
                        Icon(
                            Icons.AutoMirrored.Filled.Message, stringResource(R.string.library_has_transcript),
                            tint = Color(0xFF34C759), modifier = Modifier.padding(start = 6.dp).size(18.dp),
                        )
                    }
                    if (clip.summary != null) {
                        Icon(
                            Icons.Filled.Description, stringResource(R.string.library_has_summary),
                            tint = Color(0xFFFF9500), modifier = Modifier.padding(start = 6.dp).size(18.dp),
                        )
                    }
                }
                snippet?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The source goes first: in a pile of near-identical timestamps, "who recorded this" picks the clip.
                    val a11y = stringResource(R.string.library_source_accessibility, sourceName)
                    Row(Modifier.semantics { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(sourceIcon(clip.source.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                        Text(sourceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
                    }
                    val meta = listOfNotNull(
                        // The date only joins this line when a custom title replaced the timestamp title.
                        clip.title?.let { RecordingName.displayTitle(clip.name) },
                        if (clip.hasAudio) formatClipBytes(clip.sizeBytes) else null,
                        clip.durationMs?.let { formatClipDuration(it) },
                    )
                    Text(
                        meta.joinToString("  ·  ", prefix = if (meta.isEmpty()) "" else "  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!selecting) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
    }
}

private fun sourceIcon(kind: ClipManifest.Source.Kind): ImageVector = when (kind) {
    ClipManifest.Source.Kind.CARD -> Icons.Filled.CreditCard
    ClipManifest.Source.Kind.WATCH -> Icons.Filled.Watch
    ClipManifest.Source.Kind.PHONE -> Icons.Filled.PhoneAndroid
    ClipManifest.Source.Kind.IMPORTED -> Icons.Filled.FileDownload
}

/**
 * "Your last export is ready": a long export can finish while the user is elsewhere; without a trace here the only
 * idea left is to export again. Tapping the row shares the staged file; the trash icon frees the staged copy only.
 */
@Composable
private fun ExportReadyBanner(vm: NomiViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ready by vm.exports.ready.collectAsStateWithLifecycle()
    val r = ready?.takeIf { vm.exports.fileOf(it).isFile } ?: return
    Column(Modifier.padding(top = 12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        shareFile(context, vm.exports.fileOf(r), if (r.file.endsWith(".wav")) "audio/wav" else "audio/mp4")
                    }
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(R.string.library_export_ready), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${exportTitle(vm, r)} · ${formatClipBytes(r.bytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.exports.discard() }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.library_clear_export_staging), tint = Color.Red)
                }
            }
        }
        Text(
            stringResource(R.string.library_export_ready_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** The exported clip's current title (it may have been renamed since), else the staged name rendered as a date. */
private fun exportTitle(vm: NomiViewModel, r: com.nomily.app.audio.ExportCenter.Ready): String =
    vm.ui.value.clips.firstOrNull { it.base == r.origin }?.title ?: RecordingName.displayTitle(r.title)
