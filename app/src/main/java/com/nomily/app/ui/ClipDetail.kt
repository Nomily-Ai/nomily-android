package com.nomily.app.ui

import androidx.compose.material.icons.filled.Block
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.NomiViewModel.LocalClip
import com.nomily.app.R
import com.nomily.app.jobs.JobKind
import com.nomily.app.jobs.WorkJob
import com.nomily.app.ble.RecordingName
import com.nomily.app.core.asr.AsrSegment
import com.nomily.app.core.asr.parseAsrArtefact
import com.nomily.app.core.clips.ClipArtefact
import com.nomily.app.core.text.MarkdownOutline
import com.nomily.app.ui.components.ActionRow
import com.nomily.app.ui.components.Footer
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.NomiDimens
import com.nomily.app.ui.components.RowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import com.nomily.app.core.asr.ClipQa
import com.nomily.app.core.asr.ClockLabel
import com.nomily.app.core.asr.TranscriptNavigation
import com.nomily.app.core.text.MarkdownBlock
import com.nomily.app.core.text.MarkdownBlocks
import com.nomily.app.core.text.TodoExtract

/**
 * Clip detail page, mirroring iOS `ClipDetailView`: the inline player card, then three tabs —
 * Transcript (segment list with search / speaker filter, translation), AI Minutes (summary, mind map,
 * translated summary) and To-dos (action items lifted from the summary) — and the AI Q&A, whose input bar
 * is pinned below the scrolling content. Export and the independent deletes live in the top-right menu;
 * only a running / failed / ready export keeps an inline row.
 */
@Composable
fun ClipDetail(
    clip: LocalClip,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    onOpenSettings: () -> Unit,
    onPush: (PushedPage?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The title bar is not drawn here — it is reported to the outer navigation bar (see `PushedPage`), otherwise two title bars would appear on screen.
    val cfg by vm.config.collectAsStateWithLifecycle()
    // Opening the clip clears its unread dot in the list.
    LaunchedEffect(clip.base) { vm.markSeen(clip) }
    var showVad by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DetailTab.TRANSCRIPT) }
    var transcriptQuery by remember { mutableStateOf("") }
    var speakerFilter by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ClipArtefact?>(null) }
    var chooseExport by remember { mutableStateOf(false) }
    // Share automatically when the export the user just started finishes while they're still here.
    var shareWhenReady by remember { mutableStateOf(false) }
    val title = clip.title ?: RecordingName.displayTitle(clip.name)
    // VAD preview available only in developer mode (waveform icon in the detail page top bar)
    val showVadAction = cfg.developerMode && clip.hasAudio
    // The menu is composed by the outer top bar; reading the clip through this state keeps it current.
    val currentClip = rememberUpdatedState(clip)
    LaunchedEffect(title, showVadAction) {
        onPush(
            PushedPage(
                title = title,
                actions = {
                    if (showVadAction) TextButton(onClick = { showVad = true }) { Text("VAD") }
                    DetailMenu(
                        currentClip.value,
                        vm,
                        onExport = { chooseExport = true },
                        onDelete = { pendingDelete = it },
                    )
                },
                // As on iOS the detail page drops the tab bar; the Q&A bar then sits on the bottom edge.
                hidesTabBar = true,
                onBack = onDismiss,
            )
        )
    }
    // Leaving the page stops this clip's playback and drops the page's speed, as on iOS (one player per page).
    DisposableEffect(Unit) {
        onDispose {
            onPush(null)
            if (vm.ui.value.playing == clip.audio?.name) vm.stopPlayback()
            vm.setPlaybackRate(1f)
        }
    }
    if (showVad) VadPreviewDialog(clip) { showVad = false }

    val doc = remember(clip.transcriptJson, clip.transcript) { loadTranscript(clip) }
    val summary = remember(clip.summary?.path, clip.summary?.lastModified()) { readText(clip.summary) }
    val chat = remember(clip.base) { ClipChat() }
    val qaContext = remember(summary, doc) { ClipQa.context(summary, doc?.text, doc?.segments.orEmpty()) }
    val scroll = rememberScrollState()
    // A new question or answer lands at the end of the page; bring it into view.
    LaunchedEffect(chat.messages.size, chat.busy) {
        if (chat.messages.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp),
            ) {
                if (clip.hasAudio) HeroCard(clip, ui, vm)
                DetailTabs(tab) { tab = it }
                when (tab) {
                    DetailTab.TRANSCRIPT -> TranscriptSections(
                        clip, doc, ui, vm, onOpenSettings,
                        query = transcriptQuery,
                        onQuery = { transcriptQuery = it },
                        speaker = speakerFilter,
                        onSpeaker = { speakerFilter = it },
                    )
                    DetailTab.SUMMARY -> SummarySections(clip, summary, ui, vm, title, onOpenSettings)
                    DetailTab.TODOS -> TodosSection(summary, hasTranscript = doc != null)
                }
                // AI Q&A closes the page: the questions a user has after reading are about this clip.
                if (qaContext != null) AiChatSection(chat)
                if (clip.hasAudio) ExportStatusSection(clip, vm, shareWhenReady) { shareWhenReady = false }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
            // Answers scroll, the question box does not. With the tab bar hidden this page owns the bottom
            // edge, so the last row clears the navigation bar itself (the shell's IME padding consumes it while typing).
            if (qaContext != null) AiChatInputBar(chat, vm, qaContext) else Spacer(Modifier.navigationBarsPadding())
        }

        // The detail page covers the error card on the main screen — if it were not shown here too,
        // tapping "Transcribe" would appear to do nothing.
        ui.error?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::clearError,
                title = { Text(stringResource(R.string.device_files_operation_failed)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = vm::clearError) { Text(stringResource(R.string.common_ok)) }
                },
            )
        }
    }

    if (chooseExport) {
        ExportFormatDialog(
            onPick = { format ->
                chooseExport = false
                shareWhenReady = true
                vm.enqueueExport(clip, format.ext)
            },
            onDismiss = { chooseExport = false },
        )
    }
    pendingDelete?.let { what -> DeleteConfirmDialog(clip, what, vm, onDone = { pendingDelete = null }, onDeletedEverything = onDismiss) }
}

/** iOS `DetailTab`: Transcript / AI Minutes / To-dos. */
private enum class DetailTab(val titleRes: Int) {
    TRANSCRIPT(R.string.clip_detail_tab_transcript),
    SUMMARY(R.string.clip_detail_tab_summary),
    TODOS(R.string.clip_detail_tab_todos),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTabs(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 32.dp)) {
        DetailTab.entries.forEachIndexed { i, t ->
            SegmentedButton(
                selected = selected == t,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(i, DetailTab.entries.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContainerColor = com.nomily.app.ui.theme.AppFillTertiary,
                ),
            ) { Text(stringResource(t.titleRes), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * Top-right overflow menu (iOS `ellipsis.circle`): export audio, share the finished export, and the three
 * independent deletes — each entry only while it applies.
 */
@Composable
private fun DetailMenu(clip: LocalClip, vm: NomiViewModel, onExport: () -> Unit, onDelete: (ClipArtefact) -> Unit) {
    val context = LocalContext.current
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val readyAny by vm.exports.ready.collectAsStateWithLifecycle()
    val exporting = ClipJobs(allJobs, clip.base, JobKind.EXPORT).active != null
    val ready = readyAny?.takeIf { !exporting }?.let { vm.exports.readyFor(clip.base) }
    val deletes = listOfNotNull(
        ClipArtefact.AUDIO.takeIf { clip.hasAudio },
        ClipArtefact.TRANSCRIPT.takeIf { clip.transcript != null },
        ClipArtefact.SUMMARY.takeIf { clip.summary != null },
    )
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.clip_detail_more_actions), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (clip.hasAudio) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_export_audio)) },
                    leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                    enabled = !exporting,
                    onClick = { open = false; onExport() },
                )
            }
            if (ready != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_export_ready)) },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                    onClick = {
                        open = false
                        shareFile(context, vm.exports.fileOf(ready), if (ready.file.endsWith(".wav")) "audio/wav" else "audio/mp4")
                    },
                )
            }
            if (deletes.isNotEmpty()) HorizontalDivider()
            deletes.forEach { what ->
                val label = when (what) {
                    ClipArtefact.AUDIO -> R.string.clip_detail_delete_audio
                    ClipArtefact.TRANSCRIPT -> R.string.clip_detail_delete_transcript
                    ClipArtefact.SUMMARY -> R.string.clip_detail_delete_summary
                }
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; onDelete(what) },
                )
            }
        }
    }
}

// MARK: - Inline player

private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** `1×`, `1.25×` — no trailing `.0`. */
private fun speedLabel(v: Float): String = if (v % 1f == 0f) "${v.toInt()}×" else "$v×"

/**
 * In-page transport, mirroring iOS `InlineAudioPlayer`: scrubber, −15 s / play / +15 s and a 0.75–2× speed
 * menu. The clip is loaded paused when the page opens so the bar has a real range before the first tap.
 */
@Composable
private fun InlineAudioPlayer(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel) {
    val audio = clip.audio ?: return
    LaunchedEffect(audio.name) { vm.load(audio) }
    val loaded = ui.playing == audio.name
    val playing = loaded && !ui.playPaused
    ui.playError?.takeIf { it.first == audio.name }?.let { (_, reason) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.clip_detail_playback_failed, reason),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9500),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        return
    }

    @Composable
    fun Controls(shownMs: Int) {
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatClipDuration(shownMs),
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                modifier = Modifier.width(52.dp),
            )
            Spacer(Modifier.weight(1f))
            SkipButton(back = true) { vm.skipBy(audio, -15_000) }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { vm.togglePlay(audio) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.live_pause else R.string.clip_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            SkipButton(back = false) { vm.skipBy(audio, 15_000) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                var speedMenu by remember { mutableStateOf(false) }
                Text(
                    speedLabel(ui.playRate),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { speedMenu = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        DropdownMenuItem(
                            text = { Text(speedLabel(speed)) },
                            trailingIcon = { if (kotlin.math.abs(ui.playRate - speed) < 0.01f) Icon(Icons.Filled.Check, null) },
                            onClick = { speedMenu = false; vm.setPlaybackRate(speed) },
                        )
                    }
                }
            }
        }
        Text(
            formatClipDuration(clip.durationMs ?: ui.playDurationMs.takeIf { loaded } ?: 0),
            style = MaterialTheme.typography.labelSmall,
            color = secondary.copy(alpha = 0.7f),
        )
    }

    if (loaded && ui.playDurationMs > 0) {
        PlaybackScrubber(ui.playPositionMs, ui.playDurationMs, vm, totalMs = clip.durationMs, labels = { Controls(it) })
    } else {
        // Not loaded (another clip is playing): the bar sits at the start; the first tap loads this one.
        Slider(value = 0f, onValueChange = {}, enabled = false)
        Controls(0)
    }
}

/** ±15 s: the circular-arrow glyph with "15" inside, like iOS `gobackward.15` / `goforward.15`. */
@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (back) R.string.clip_detail_skip_back else R.string.clip_detail_skip_forward)
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp).then(if (back) Modifier else Modifier.scale(scaleX = -1f, scaleY = 1f)),
            )
            Text("15", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

// MARK: - Transcript rows

/**
 * One transcript line, mirroring iOS `TranscriptSegmentRow`: the time pill and the whole row jump the audio
 * there; the playing line is tinted; [highlight] marks search hits in place.
 */
@Composable
private fun TranscriptSegmentRow(seg: AsrSegment, active: Boolean, highlight: String? = null, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            ClockLabel.mmss(seg.start),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = if (active) 0.20f else 0.08f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            seg.speaker?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.clip_detail_speaker, it),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(highlighted(seg.text, highlight), style = MaterialTheme.typography.bodyLarge, lineHeight = READER_LINE_HEIGHT)
        }
    }
}

/** Case-insensitive needle highlight over an otherwise plain line. */
private fun highlighted(text: String, needle: String?): AnnotatedString = buildAnnotatedString {
    append(text)
    TranscriptNavigation.highlightRanges(text, needle).forEach {
        addStyle(SpanStyle(background = Color(0xFFFFCC00).copy(alpha = 0.45f)), it.first, it.last + 1)
    }
}

/** Seeks a line 0.3 s early so its first word isn't clipped, and keeps playing. */
private fun seekToSegment(vm: NomiViewModel, clip: LocalClip, seg: AsrSegment) {
    val audio = clip.audio ?: return
    vm.seekAndPlay(audio, (maxOf(0.0, seg.start - 0.3) * 1000).toInt())
}

private fun positionSeconds(ui: NomiViewModel.Ui, clip: LocalClip): Double =
    if (clip.audio != null && ui.playing == clip.audio.name) ui.playPositionMs / 1000.0 else 0.0

/** Search field + speaker chips (iOS `transcriptSearchBar`); hidden for single-line transcripts. */
@Composable
private fun TranscriptSearchBar(doc: TranscriptDocument, query: String, onQuery: (String) -> Unit, speaker: String?, onSpeaker: (String?) -> Unit) {
    if (doc.segments.size <= 1) return
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = secondary, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.clip_detail_search_transcript), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Cancel, null, tint = secondary, modifier = Modifier.size(16.dp).clickable { onQuery("") })
        }
    }
    val speakers = remember(doc) { TranscriptNavigation.distinctSpeakers(doc.segments) }
    if (speakers.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = NomiDimens.rowInset, end = NomiDimens.rowInset, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeakerChip(stringResource(R.string.clip_detail_speaker_all), speaker == null) { onSpeaker(null) }
            speakers.forEach { sp ->
                SpeakerChip(stringResource(R.string.clip_detail_speaker, sp), speaker == sp) { onSpeaker(sp) }
            }
        }
    }
    RowDivider()
}

@Composable
private fun SpeakerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.18f) else com.nomily.app.ui.theme.AppFillTertiary.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * While searching or filtering: every matching line (highlighted, capped) with tap-to-seek; otherwise a
 * four-line window that follows playback.
 */
@Composable
private fun TranscriptRows(clip: LocalClip, doc: TranscriptDocument, ui: NomiViewModel.Ui, vm: NomiViewModel, query: String, speaker: String?) {
    val active = TranscriptNavigation.activeIndex(doc.segments, positionSeconds(ui, clip))
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (query.isNotEmpty() || speaker != null) {
            val matches = remember(doc, query, speaker) { TranscriptNavigation.matches(doc.segments, query, speaker) }
            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.clip_detail_no_transcript_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                matches.take(TranscriptNavigation.MAX_MATCHES).forEach { i ->
                    val seg = doc.segments[i]
                    TranscriptSegmentRow(seg, active == i, highlight = query.ifEmpty { null }) { seekToSegment(vm, clip, seg) }
                }
                if (matches.size > TranscriptNavigation.MAX_MATCHES) {
                    Text(
                        "+${matches.size - TranscriptNavigation.MAX_MATCHES}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        } else {
            TranscriptNavigation.inlineWindow(doc.segments.size, active).forEach { i ->
                val seg = doc.segments[i]
                TranscriptSegmentRow(seg, active == i) { seekToSegment(vm, clip, seg) }
            }
        }
    }
    RowDivider()
}

// MARK: - Native Markdown blocks

/** The summary / to-do blocks rendered natively (iOS `MarkdownBlocksView`). */
@Composable
private fun MarkdownBlocksView(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (val kind = block.kind) {
                    is MarkdownBlock.Kind.Heading -> Text(
                        inlineMarkdown(block.text),
                        style = when {
                            kind.level <= 1 -> MaterialTheme.typography.titleMedium
                            kind.level == 2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.labelLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    MarkdownBlock.Kind.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = secondary)
                        Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                    }
                    is MarkdownBlock.Kind.Ordered -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${kind.number}.", style = MaterialTheme.typography.bodyMedium, color = secondary)
                        Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
                    }
                    MarkdownBlock.Kind.Quote -> Row(Modifier.heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(3.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(1.5.dp)).background(secondary.copy(alpha = 0.35f)))
                        Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyMedium, color = secondary)
                    }
                    MarkdownBlock.Kind.Rule -> HorizontalDivider()
                    MarkdownBlock.Kind.Paragraph -> Text(
                        inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}

private val INLINE_MARKUP = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*([^*\s][^*]*?)\*""")

/** `**bold**` / `*italic*` / `` `code` `` without a Markdown engine; anything else stays literal. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE_MARKUP.findAll(text)) {
        append(text, at, m.range.first)
        val (bold, code, italic) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        at = m.range.last + 1
    }
    append(text, at, text.length)
}

// MARK: - To-dos tab

/** Action items lifted from the summary; an honest empty state when there are none — never invented content. */
@Composable
private fun TodosSection(summary: String?, hasTranscript: Boolean) {
    val todos = remember(summary) { summary?.let(TodoExtract::fromSummary).orEmpty() }
    Group {
        when {
            todos.isNotEmpty() -> MarkdownBlocksView(todos, Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp))
            summary != null -> EmptyRow(stringResource(R.string.clip_detail_todos_empty))
            hasTranscript -> EmptyRow(stringResource(R.string.clip_detail_todos_need_summary))
            else -> EmptyRow(stringResource(R.string.clip_detail_transcribe_first))
        }
    }
}

// MARK: - AI Q&A

/**
 * Q&A state for one clip, mirroring iOS `AIChatModel`. Held by the page (not persisted, as on iOS) so the
 * pinned input bar and the scrolling answers share it.
 */
private class ClipChat {
    val messages = mutableStateListOf<ClipQa.Message>()
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val canSend: Boolean get() = !busy && draft.isNotBlank()
}

/** The answers, scrolling with the page (iOS `AIChatSection`). */
@Composable
private fun AiChatSection(chat: ClipChat) {
    Group(
        title = stringResource(R.string.clip_detail_ai_ask_title),
        trailing = if (chat.messages.isNotEmpty()) {
            {
                TextButton(onClick = { chat.messages.clear(); chat.error = null }) {
                    Text(stringResource(R.string.clip_detail_ai_ask_clear), style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            null
        },
        footer = if (chat.messages.isEmpty()) stringResource(R.string.clip_detail_ai_ask_footer) else null,
    ) {
        chat.messages.forEachIndexed { i, message ->
            Column(Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (message.isUser) R.string.clip_detail_ai_ask_you else R.string.clip_detail_ai_ask_assistant),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp) }
            }
            if (i < chat.messages.lastIndex || chat.busy || chat.error != null) RowDivider()
        }
        if (chat.busy) ProgressRow(stringResource(R.string.clip_detail_ai_ask_thinking))
        chat.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 10.dp),
            )
        }
    }
}

/** The pinned question bar (iOS `AIChatInputBar`): lives outside the scroll so it never scrolls away. */
@Composable
private fun AiChatInputBar(chat: ClipChat, vm: NomiViewModel, material: String) {
    val scope = rememberCoroutineScope()
    val send = send@{
        if (!chat.canSend) return@send
        val question = chat.draft.trim()
        chat.draft = ""
        chat.error = null
        chat.messages += ClipQa.Message(isUser = true, text = question)
        chat.busy = true
        val history = chat.messages.toList()
        scope.launch {
            try {
                val answer = vm.askAboutClip(material, history)
                chat.messages += ClipQa.Message(isUser = false, text = answer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                chat.error = e.message ?: e.javaClass.simpleName
            } finally {
                chat.busy = false
            }
        }
        Unit
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(com.nomily.app.ui.theme.AppFillTertiary.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (chat.draft.isEmpty()) {
                    Text(stringResource(R.string.clip_detail_ai_ask_placeholder), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = chat.draft,
                    onValueChange = { chat.draft = it },
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = { send() }, enabled = chat.canSend) {
                Icon(
                    Icons.Filled.ArrowCircleUp,
                    contentDescription = stringResource(R.string.clip_detail_ai_ask_title),
                    tint = if (chat.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/** Small header icon button (translate / redo) beside a section title. */
@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HeaderIcons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** A row that opens a full-screen page: label + trailing chevron, like an iOS `NavigationLink`. */
@Composable
private fun NavRow(label: String, divider: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = NomiDimens.rowMinHeight)
            .padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (divider) RowDivider()
}

/** Gray 3-line preview of a long text inside a card. */
@Composable
private fun PreviewRow(text: String) {
    Text(
        text.take(200),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
    )
    RowDivider()
}

/** Spinner + label (+ optional trailing action) inside a card. */
@Composable
private fun ProgressRow(label: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NomiDimens.rowMinHeight)
            .padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    RowDivider()
}

/** This clip's jobs of one kind (and translate target), from the background queue. */
private class ClipJobs(all: List<WorkJob>, clip: String, kind: JobKind, target: String? = null) {
    private val mine = all.filter { it.clip == clip && it.kind == kind && (target == null || it.translateTarget == target) }
    val active: WorkJob? = mine.firstOrNull { it.finishedAt == null }
    val failed: WorkJob? = mine.firstOrNull { it.finishedAt != null && it.lastError != null }
    /** The latest success, whose notes (fallback provider, chunking, truncation) still describe the output on disk. */
    val lastDone: WorkJob? = mine.filter { it.finishedAt != null && it.lastError == null }.maxByOrNull { it.finishedAt ?: 0L }
}

@Composable
private fun JobProgressRow(job: WorkJob, label: String) {
    val part = if (job.progressDone != null && job.progressTotal != null) {
        stringResource(R.string.jobs_progress, job.progressDone, job.progressTotal)
    } else null
    ProgressRow(listOfNotNull(label, part).joinToString("  ·  "))
}

/** The job gave up after its retries: the reason and a retry button. */
@Composable
private fun JobFailedRow(job: WorkJob, vm: NomiViewModel) {
    val orange = androidx.compose.ui.graphics.Color(0xFFFF9500)
    Column(Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = orange, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.jobs_status_gave_up), color = orange, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 6.dp))
        }
        job.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        OutlinedButton(onClick = { vm.jobs.retry(job.id) }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.jobs_retry)) }
    }
    RowDivider()
}

@Composable
private fun JobNotes(job: WorkJob?) {
    job ?: return
    val notes = job.notes.filterNot { job.wasTruncated && it == stringResource(R.string.summarize_truncated_warning) }
    if (!job.wasTruncated && notes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 8.dp)) {
        if (job.wasTruncated) {
            Text(stringResource(R.string.summarize_truncated_warning), style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFFF9500))
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun readText(f: java.io.File?): String? = f?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }

/**
 * Transcript tab: the segment list (search, speaker filter, playback-following window) + translation. The
 * header carries translate / re-transcribe once a transcript exists; a clip without one offers a single
 * Transcribe row (auto-detect or pick languages).
 */
@Composable
private fun TranscriptSections(
    clip: LocalClip,
    doc: TranscriptDocument?,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    onOpenSettings: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    speaker: String?,
    onSpeaker: (String?) -> Unit,
) {
    val cfgNow by vm.config.collectAsStateWithLifecycle()
    val transcribing = clip.base in ui.transcribing
    val translating = "${clip.base}:${ClipArtefact.TRANSCRIPT.name}" in ui.translating
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val transcribeJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSCRIBE)
    val translateJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSLATE, "transcript")
    var full by remember { mutableStateOf(false) }
    var fullTranslation by remember { mutableStateOf(false) }
    var translatePicker by remember { mutableStateOf(false) }
    var noLlm by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    // Transcription mode: automatic detection / specified language; the specified `locales` apply only to this run.
    var chooseMode by remember { mutableStateOf(false) }
    var showLocales by remember { mutableStateOf(false) }
    var noAsr by remember { mutableStateOf(false) }
    val hasAsr = cfgNow.asrProviders.hasConfiguredProvider
    val hasLlm = cfgNow.llmProviders.configuredProviders.isNotEmpty()

    Group(
        title = stringResource(R.string.clip_detail_transcript),
        trailing = if (doc != null && !transcribing) {
            {
                HeaderIcons {
                    HeaderIcon(Icons.Filled.Translate, stringResource(R.string.clip_detail_translate), enabled = !translating) {
                        if (hasLlm) translatePicker = true else noLlm = true
                    }
                    if (clip.hasAudio) {
                        HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.clip_detail_retranscribe)) { confirmRetranscribe = true }
                    }
                }
            }
        } else {
            null
        },
    ) {
        if (transcribing) {
            ProgressRow(stringResource(R.string.clip_detail_transcribing)) {
                OutlinedButton(onClick = { vm.cancelTranscribe(clip) }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        if (!transcribing) transcribeJobs.failed?.let { JobFailedRow(it, vm) }
        translateJobs.active?.let { JobProgressRow(it, stringResource(R.string.clip_detail_translating)) }
            ?: translateJobs.failed?.let { JobFailedRow(it, vm) }
        when {
            doc != null -> {
                if (doc.segments.isEmpty()) {
                    PreviewRow(transcriptPreview(doc))
                } else {
                    TranscriptSearchBar(doc, query, onQuery, speaker, onSpeaker)
                    TranscriptRows(clip, doc, ui, vm, query, speaker)
                }
                NavRow(stringResource(R.string.clip_detail_view_full_transcript)) { full = true }
            }
            clip.hasAudio && !transcribing -> ActionRow(
                stringResource(R.string.clip_detail_transcribe),
                icon = Icons.AutoMirrored.Filled.Message,
                divider = false,
            ) { if (hasAsr) chooseMode = true else noAsr = true }
            !transcribing -> EmptyRow(stringResource(R.string.clip_detail_transcribe_first))
        }
    }

    val translation = readText(clip.transcriptTranslation)
    if (translation != null) {
        Group(title = stringResource(R.string.clip_detail_translation)) {
            PreviewRow(translation)
            NavRow(stringResource(R.string.clip_detail_view_full_translation)) { fullTranslation = true }
        }
    }

    if (full && doc != null) TranscriptFullDialog(clip, doc, vm) { full = false }
    if (fullTranslation && translation != null) {
        FullTextDialog(stringResource(R.string.clip_detail_translation), translation, markdown = false) { fullTranslation = false }
    }
    if (translatePicker) {
        TranslatePicker(vm) { lang ->
            translatePicker = false
            if (lang != null) vm.translateArtefact(clip, ClipArtefact.TRANSCRIPT, lang.name, lang.code)
        }
    }
    if (noLlm) NoLlmDialog(vm, onOpenSettings) { noLlm = false }
    if (chooseMode) {
        AlertDialog(
            onDismissRequest = { chooseMode = false },
            title = { Text(stringResource(R.string.clip_detail_transcribe)) },
            text = { Text(stringResource(R.string.asr_languages_footer)) },
            confirmButton = {
                TextButton(onClick = { chooseMode = false; vm.transcribe(clip) }) {
                    Text(stringResource(R.string.asr_transcribe_auto))
                }
            },
            dismissButton = {
                TextButton(onClick = { chooseMode = false; showLocales = true }) {
                    Text(stringResource(R.string.asr_specify_languages))
                }
            },
        )
    }
    if (showLocales) {
        AzureLocalesPicker(emptyList()) { codes ->
            showLocales = false
            if (codes != null) vm.transcribe(clip, locales = codes)
        }
    }
    if (noAsr) {
        AlertDialog(
            onDismissRequest = { noAsr = false },
            title = { Text(stringResource(R.string.asr_no_provider)) },
            text = { Text(stringResource(R.string.asr_no_provider_message)) },
            confirmButton = {
                TextButton(onClick = {
                    noAsr = false
                    vm.openSettingsAt(NomiViewModel.SettingsTarget.ASR)
                    onOpenSettings()
                }) { Text(stringResource(R.string.common_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { noAsr = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (confirmRetranscribe) {
        // Re-running overwrites the existing txt/json, so it is confirmed first.
        AlertDialog(
            onDismissRequest = { confirmRetranscribe = false },
            title = { Text(stringResource(R.string.clip_detail_retranscribe)) },
            text = { Text(stringResource(R.string.clip_detail_retranscribe_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetranscribe = false
                    if (hasAsr) chooseMode = true else noAsr = true
                }) { Text(stringResource(R.string.clip_detail_retranscribe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetranscribe = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * AI Minutes tab: the whole summary rendered inline, its full view (with poster share) and, when it has
 * headings or lists to plot, the mind map; then the translated summary. The header carries translate /
 * re-summarize.
 */
@Composable
private fun SummarySections(
    clip: LocalClip,
    summary: String?,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    clipTitle: String,
    onOpenSettings: () -> Unit,
) {
    val hasOutline = remember(summary) { summary != null && MarkdownOutline.parse(summary) != null }
    val translating = "${clip.base}:${ClipArtefact.SUMMARY.name}" in ui.translating
    val cfgNow by vm.config.collectAsStateWithLifecycle()
    val hasLlm = cfgNow.llmProviders.configuredProviders.isNotEmpty()
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val summarizeJobs = ClipJobs(allJobs, clip.base, JobKind.SUMMARIZE)
    val translateJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSLATE, "summary")
    var showSummarize by remember { mutableStateOf(false) }
    var translatePicker by remember { mutableStateOf(false) }
    var noLlm by remember { mutableStateOf(false) }
    var fullSummary by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var mindMap by remember { mutableStateOf(false) }
    // Symmetric to transcription: without an LLM, explain and link to settings instead of failing in the sheet.
    val openSummarize = { if (hasLlm) showSummarize = true else noLlm = true }

    Group(
        title = stringResource(R.string.clip_detail_summary),
        trailing = if (summary != null) {
            {
                HeaderIcons {
                    HeaderIcon(Icons.Filled.Translate, stringResource(R.string.clip_detail_translate), enabled = !translating) {
                        if (hasLlm) translatePicker = true else noLlm = true
                    }
                    if (clip.transcript != null) {
                        HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.clip_detail_summarize), onClick = openSummarize)
                    }
                }
            }
        } else {
            null
        },
    ) {
        summarizeJobs.active?.let { JobProgressRow(it, stringResource(R.string.summarize_summarizing)) }
            ?: summarizeJobs.failed?.let { JobFailedRow(it, vm) }
        translateJobs.active?.let { JobProgressRow(it, stringResource(R.string.clip_detail_translating)) }
            ?: translateJobs.failed?.let { JobFailedRow(it, vm) }
        when {
            summary != null -> {
                // The summary tab is the reading surface: the whole document inline, not a teaser behind a link.
                val blocks = remember(summary) { MarkdownBlocks.parse(summary) }
                MarkdownBlocksView(blocks, Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp))
                RowDivider()
                NavRow(stringResource(R.string.clip_detail_view_full_summary), divider = hasOutline) {
                    fullSummary = R.string.clip_detail_summary to summary
                }
                // Only offered when the summary actually has section structure to plot.
                if (hasOutline) {
                    ActionRow(stringResource(R.string.clip_detail_mind_map), icon = MindMapIcon, divider = false) { mindMap = true }
                }
            }
            summarizeJobs.active != null -> Unit
            clip.transcript != null -> ActionRow(
                stringResource(R.string.clip_detail_summarize),
                icon = Icons.Filled.AutoAwesome,
                divider = false,
                onClick = openSummarize,
            )
            else -> EmptyRow(stringResource(R.string.clip_detail_transcribe_first))
        }
        if (summary != null) {
            JobNotes(summarizeJobs.lastDone)
            JobNotes(translateJobs.lastDone)
        }
    }

    val summaryTranslation = readText(clip.summaryTranslation)
    if (summaryTranslation != null) {
        Group(title = stringResource(R.string.clip_detail_translated_summary)) {
            PreviewRow(summaryTranslation)
            NavRow(stringResource(R.string.clip_detail_view_full_translated_summary)) {
                fullSummary = R.string.clip_detail_translated_summary to summaryTranslation
            }
        }
    }

    fullSummary?.let { (titleRes, body) ->
        SummaryFullDialog(stringResource(titleRes), body, poster = clip) { fullSummary = null }
    }
    // Re-read on open rather than capture: the summary can be regenerated while the map is closed.
    if (mindMap && summary != null) MindMapScreen(summary, clipTitle) { mindMap = false }
    if (showSummarize) SummarizeSheet(clip, vm) { showSummarize = false }
    if (translatePicker) {
        TranslatePicker(vm) { lang ->
            translatePicker = false
            if (lang != null) vm.translateArtefact(clip, ClipArtefact.SUMMARY, lang.name, lang.code)
        }
    }
    if (noLlm) NoLlmDialog(vm, onOpenSettings) { noLlm = false }
}

@Composable
private fun NoLlmDialog(vm: NomiViewModel, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llm_no_provider)) },
        text = { Text(stringResource(R.string.llm_no_provider_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                vm.openSettingsAt(NomiViewModel.SettingsTarget.LLM)
                onOpenSettings()
            }) { Text(stringResource(R.string.common_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Select target language → translate. The language list is shared with summary output languages (Microsoft Translator public list). */
@Composable
private fun TranslatePicker(vm: NomiViewModel, onDone: (com.nomily.app.llm.LanguageService.Language?) -> Unit) {
    var languages by remember { mutableStateOf<List<com.nomily.app.llm.LanguageService.Language>>(emptyList()) }
    LaunchedEffect(Unit) { languages = vm.targetLanguages() }
    LanguagePickerDialog(
        title = stringResource(R.string.clip_detail_translate),
        languages = languages,
        defaultOption = null,
        onPick = { code -> onDone(languages.firstOrNull { it.code == code }) },
        onDismiss = { onDone(null) },
    )
}

/** The detail page's 3‑line preview: if segments exist, concatenate their bodies; otherwise use the whole text. */
private fun transcriptPreview(doc: TranscriptDocument): String =
    if (doc.segments.isEmpty()) doc.text.take(200) else doc.segments.joinToString(" ") { it.text }.take(200)

/** What the transcript share button hands out: `[speaker] (m:ss–m:ss) text`, one blank line between segments. */
private fun transcriptShareText(doc: TranscriptDocument): String =
    if (doc.segments.isEmpty()) {
        doc.text
    } else {
        doc.segments.joinToString("\n\n") { seg ->
            val speaker = seg.speaker?.takeIf { it.isNotEmpty() }?.let { "[$it] " }.orEmpty()
            "$speaker(${stampLabel(seg)}) ${seg.text}"
        }
    }

/** Full-screen page shell: title, Close, and optional toolbar actions on the right. */
@Composable
private fun FullPage(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    actions()
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                content()
            }
        }
    }
}

@Composable
private fun ShareAction(text: String) {
    val context = LocalContext.current
    IconButton(onClick = { shareText(context, text) }) {
        Icon(Icons.Filled.IosShare, contentDescription = null)
    }
}

/** Full-screen plain text (translation) with a share button. */
@Composable
private fun FullTextDialog(title: String, body: String, markdown: Boolean, onDismiss: () -> Unit) {
    FullPage(title, onDismiss, actions = { ShareAction(body) }) {
        if (markdown) MarkdownView(body, Modifier.fillMaxSize()) else LongTextReader(body, Modifier.fillMaxSize())
    }
}

/**
 * Full-screen summary: rendered Markdown with mind map, raw-source toggle, share as image (poster) and
 * share text, as on iOS. [poster] supplies the poster header (title, date, duration).
 */
@Composable
private fun SummaryFullDialog(title: String, body: String, poster: LocalClip, onDismiss: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    var mindMap by remember { mutableStateOf(false) }
    var showPoster by remember { mutableStateOf(false) }
    val hasOutline = remember(body) { MarkdownOutline.parse(body) != null }
    FullPage(
        title,
        onDismiss,
        actions = {
            if (hasOutline) {
                IconButton(onClick = { mindMap = true }) {
                    Icon(MindMapIcon, contentDescription = stringResource(R.string.clip_detail_mind_map))
                }
            }
            IconButton(onClick = { showRaw = !showRaw }) {
                Icon(if (showRaw) Icons.AutoMirrored.Filled.Article else Icons.Filled.Code, contentDescription = null)
            }
            IconButton(onClick = { showPoster = true }) {
                Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.clip_detail_share_as_image))
            }
            ShareAction(body)
        },
    ) {
        if (showRaw) LongTextReader(body, Modifier.fillMaxSize(), monospace = true) else MarkdownView(body, Modifier.fillMaxSize())
    }
    if (mindMap) MindMapScreen(body, title) { mindMap = false }
    if (showPoster) {
        SummaryPosterDialog(body, title = poster.title ?: poster.name, date = poster.recordedAt, durationMs = poster.durationMs) { showPoster = false }
    }
}

/**
 * Full-screen segmented reader, sharing the detail page's player: tap a line to jump there; the playing
 * line is highlighted and kept in view while audio plays.
 */
@Composable
private fun TranscriptFullDialog(clip: LocalClip, doc: TranscriptDocument, vm: NomiViewModel, onDismiss: () -> Unit) {
    val shareBody = remember(doc) { transcriptShareText(doc) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    FullPage(stringResource(R.string.clip_detail_transcript), onDismiss, actions = { ShareAction(shareBody) }) {
        if (doc.segments.isEmpty()) {
            LongTextReader(doc.text, Modifier.fillMaxSize())
        } else {
            val active = TranscriptNavigation.activeIndex(doc.segments, positionSeconds(ui, clip))
            val playing = clip.audio != null && ui.playing == clip.audio.name && !ui.playPaused
            val list = rememberLazyListState()
            // Follow the audio while it plays, so the page never drifts away from what the user is hearing.
            LaunchedEffect(active) {
                if (active != null && playing) list.animateScrollToItem(active, -list.layoutInfo.viewportSize.height / 3)
            }
            // Lazy: a three‑hour segment contains thousands of pieces; combining them all at once would freeze the dialog for several seconds.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(doc.segments) { i, seg ->
                    TranscriptSegmentRow(seg, active == i) { seekToSegment(vm, clip, seg) }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

/**
 * Long text reader (translation / transcription without segments / raw summary).
 *
 * Compared to `Column(verticalScroll) { Text(fullText) }`, it solves two issues:
 *
 * 1. **Slow opening.** Placing the entire text in a single `Text` forces a full layout measurement when the dialog opens, causing long passages to exhibit the QA‑reported "2‑second delay when tapping the Japanese translation entry". Splitting by segment into a `LazyColumn` only lays out the currently visible segments, so opening is instantaneous regardless of length.
 * 2. **Too cramped.** CJK (especially Japanese) lacks spaces between words; with default line height the block becomes a solid mass. The increased line and paragraph spacing here improves readability and is intentional, not just extra whitespace.
 */
@Composable
private fun LongTextReader(text: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    // Segment by blank lines; ASR output places one sentence per line, and when there are no blank lines it falls back to line‑based segmentation.
    val paragraphs = remember(text) {
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(paragraphs) { _, para ->
            SelectionContainer {
                Text(
                    para,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    lineHeight = READER_LINE_HEIGHT,
                )
            }
        }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

/** Reading mode line spacing. `bodyMedium` defaults to 20sp, which feels cramped for CJK. */
private val READER_LINE_HEIGHT = 26.sp

/** Short format `m:ss–m:ss`. */
private fun stampLabel(seg: AsrSegment): String {
    fun t(v: Double): String {
        val s = v.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
    return "${t(seg.start)}–${t(seg.end)}"
}

/** Two‑level fallback: `.asr.json` (with segments) → `.txt` (plain full text) → no transcription. */
private fun loadTranscript(clip: LocalClip): TranscriptDocument? {
    clip.transcriptJson?.let { f ->
        runCatching { parseAsrArtefact(f.readText()) }.getOrNull()?.let {
            return TranscriptDocument(it.text, it.segments)
        }
    }
    return readText(clip.transcript)?.let { TranscriptDocument(it, emptyList()) }
}

/** Unified representation of the two transcription forms. */
private class TranscriptDocument(val text: String, val segments: List<AsrSegment>)

private enum class ExportFormat(val ext: String) { M4A("m4a"), WAV("wav") }

/**
 * Export status: re-encoding to **M4A (AAC)** or decoding to **WAV (PCM)** runs as a background task started
 * from the top-right menu; this card only shows while one is running, gave up, or its result is ready
 * ("Exported — tap to share", so a long conversion isn't redone after leaving). Ogg‑Opus cannot be opened
 * by most third‑party apps, so the original is never shared as-is.
 */
@Composable
private fun ExportStatusSection(clip: LocalClip, vm: NomiViewModel, shareWhenReady: Boolean, onShared: () -> Unit) {
    val context = LocalContext.current
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val readyAny by vm.exports.ready.collectAsStateWithLifecycle()
    val exportJobs = ClipJobs(allJobs, clip.base, JobKind.EXPORT)
    val ready = readyAny?.takeIf { exportJobs.active == null }?.let { vm.exports.readyFor(clip.base) }
    LaunchedEffect(ready, shareWhenReady) {
        if (shareWhenReady && ready != null) {
            onShared()
            shareFile(context, vm.exports.fileOf(ready), if (ready.file.endsWith(".wav")) "audio/wav" else "audio/mp4")
        }
    }
    if (exportJobs.active == null && exportJobs.failed == null && ready == null) return

    Group(title = stringResource(R.string.clip_detail_export), footer = stringResource(R.string.clip_detail_export_format_footer)) {
        exportJobs.active?.let { JobProgressRow(it, stringResource(R.string.clip_detail_exporting)) }
            ?: exportJobs.failed?.let { JobFailedRow(it, vm) }
        if (ready != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(start = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clickable {
                        shareFile(context, vm.exports.fileOf(ready), if (ready.file.endsWith(".wav")) "audio/wav" else "audio/mp4")
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = androidx.compose.ui.graphics.Color(0xFF34C759), modifier = Modifier.size(20.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.clip_detail_export_ready), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${stringResource(R.string.clip_detail_export_ready_files, 1)} · ${formatClipBytes(ready.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { vm.exports.discard() }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.clip_detail_clear_export_staging), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ExportFormatDialog(onPick: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clip_detail_export_audio)) },
        text = {
            Column {
                TextButton(onClick = { onPick(ExportFormat.M4A) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.clip_detail_export_as_m4a))
                }
                TextButton(onClick = { onPick(ExportFormat.WAV) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.clip_detail_export_as_wav))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Hero card: the inline player, then file name · size · date in one quiet line. */
@Composable
private fun HeroCard(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel) {
    Group {
        Column(Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 10.dp)) {
            InlineAudioPlayer(clip, ui, vm)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                val date = clip.recordedAt?.let {
                    // Medium date + short time, localized through the current locale.
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault()).format(it) +
                        " " +
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(it)
                }
                Text(
                    listOfNotNull(formatClipBytes(clip.sizeBytes), date).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * Playback progress bar — shared among the detail page's inline player and the library row; avoid duplicating code.
 *
 * During dragging only update the **local** value, without feeding seek commands to the ViewModel each frame: previously `onValueChange` called `seekTo` every frame while a polling coroutine simultaneously wrote back `playPositionMs`, causing contention and the slider to bounce back.
 * Now pressing starts `beginScrub()` (muting during drag), releasing calls `endScrub()` to set the position and resume as needed.
 *
 * @param centered `true` shows "position / total" under the bar; `false` puts position and total at the two ends.
 */
@Composable
internal fun PlaybackScrubber(
    positionMs: Int,
    durationMs: Int,
    vm: NomiViewModel,
    /**
     * Duration calculated from the library (Ogg page scan). `MediaPlayer.duration` is the decoded length; the two can differ by up to a second
     * — e.g., the list shows 0:27 while the player shows 0:28. **The total length always follows the library value**,
     * `durationMs` is used only for the progress bar range.
     */
    totalMs: Int? = null,
    centered: Boolean = true,
    /** Replaces the default time labels; receives the shown position (the drag value while scrubbing). */
    labels: (@Composable (shownMs: Int) -> Unit)? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragValue else positionMs.coerceIn(0, durationMs).toFloat()
    Slider(
        value = shown,
        onValueChange = {
            if (!dragging) { dragging = true; vm.beginScrub() }
            dragValue = it
        },
        onValueChangeFinished = {
            vm.endScrub(dragValue.toInt())
            dragging = false
        },
        valueRange = 0f..durationMs.toFloat(),
    )
    if (labels != null) {
        labels(shown.toInt())
    } else if (centered) {
        Text(
            "${formatClipDuration(shown.toInt())} / ${formatClipDuration(totalMs ?: durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(Modifier.fillMaxWidth()) {
            Text(formatClipDuration(shown.toInt()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                formatClipDuration(totalMs ?: durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Gray one-line empty state inside a card. */
@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 14.dp),
    )
}

/**
 * Confirms one of the independent deletes picked from the top-right menu — audio, transcript and summary
 * go to Recently Deleted separately, nothing cascades.
 */
@Composable
private fun DeleteConfirmDialog(clip: LocalClip, what: ClipArtefact, vm: NomiViewModel, onDone: () -> Unit, onDeletedEverything: () -> Unit) {
    val (title, message) = when (what) {
        ClipArtefact.AUDIO ->
            R.string.clip_detail_delete_audio to R.string.clip_detail_delete_audio_message
        ClipArtefact.TRANSCRIPT ->
            R.string.clip_detail_delete_transcript to R.string.clip_detail_delete_transcript_message
        ClipArtefact.SUMMARY ->
            R.string.clip_detail_delete_summary to R.string.clip_detail_delete_summary_message
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = {
                if (what == ClipArtefact.AUDIO && vm.ui.value.playing == clip.audio?.name) vm.stopPlayback()
                vm.deleteArtefact(clip, what)
                onDone()
                // Deleting the audio closes the page, as on iOS (the clip lives on as a text-only library entry);
                // so does removing the last artefact — no empty shell.
                val leftovers = listOfNotNull(
                    clip.audio.takeIf { what != ClipArtefact.AUDIO },
                    clip.transcript.takeIf { what != ClipArtefact.TRANSCRIPT },
                    clip.summary.takeIf { what != ClipArtefact.SUMMARY },
                )
                if (what == ClipArtefact.AUDIO || leftovers.isEmpty()) onDeletedEverything()
            }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

internal fun formatClipDuration(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
