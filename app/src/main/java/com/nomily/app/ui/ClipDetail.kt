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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.nomily.app.core.asr.SpeakerNames
import com.nomily.app.core.asr.ClockLabel
import com.nomily.app.core.asr.TranscriptNavigation
import com.nomily.app.core.text.MarkdownBlock
import com.nomily.app.core.text.MarkdownBlocks
import com.nomily.app.core.text.TodoExtract
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.nomily.app.audio.AudioEnvelope
import com.nomily.app.core.asr.ClipChapter

/**
 * Clip detail page, mirroring iOS `ClipDetailView`: three tabs in the top bar — Transcript (outline, every
 * transcript line with search / speaker filter, translation), AI Minutes (the editable summary, its mind map
 * card, translated summary) and To-dos (a checklist lifted from the summary) — under the inline player. The AI
 * Q&A is a short ask bar on the bottom edge that opens the conversation sheet. Export, re-transcribe, speaker
 * names and the independent deletes live in the top-right menu; only a running / failed / ready export keeps an
 * inline row.
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
    var showMail by remember { mutableStateOf(false) }
    var transcribeStep by remember { mutableStateOf<TranscribeStep?>(null) }
    var showAsk by remember { mutableStateOf(false) }
    // Share automatically when the export the user just started finishes while they're still here.
    var shareWhenReady by remember { mutableStateOf(false) }
    val title = clip.title ?: RecordingName.displayTitle(clip.name)
    // VAD preview available only in developer mode (waveform icon in the detail page top bar)
    val showVadAction = cfg.developerMode && clip.hasAudio
    val hasAsr = cfg.asrProviders.hasConfiguredProvider
    // The menu is composed by the outer top bar; reading the clip through this state keeps it current.
    val currentClip = rememberUpdatedState(clip)
    val doc = remember(clip.transcriptJson, clip.transcript) { loadTranscript(clip) }
    val currentHasTranscript = rememberUpdatedState(doc != null)
    val hasSpeakers = doc != null && TranscriptNavigation.distinctSpeakers(doc.segments).isNotEmpty()
    var showSpeakerEditor by remember { mutableStateOf(false) }
    val startTranscribe = { rerun: Boolean ->
        transcribeStep = when {
            rerun -> TranscribeStep.CONFIRM_RERUN
            hasAsr -> TranscribeStep.CHOOSE_MODE
            else -> TranscribeStep.NO_ASR
        }
    }
    LaunchedEffect(title, showVadAction, hasSpeakers) {
        onPush(
            PushedPage(
                title = title,
                // The tabs take the title's place, so the content starts right under the bar.
                titleContent = { DetailTabs(tab) { tab = it } },
                actions = {
                    if (showVadAction) TextButton(onClick = { showVad = true }) { Text("VAD") }
                    DetailMenu(
                        currentClip.value,
                        vm,
                        onExport = { chooseExport = true },
                        onExportDocument = { format -> shareWhenReady = true; vm.enqueueExport(currentClip.value, format.ext) },
                        onMail = { showMail = true },
                        onRetranscribe = { startTranscribe(currentHasTranscript.value) },
                        onEditSpeakers = if (hasSpeakers) ({ showSpeakerEditor = true }) else null,
                        onDelete = { pendingDelete = it },
                    )
                },
                // As on iOS the detail page drops the tab bar; the ask bar then sits on the bottom edge.
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

    var speakerNames by remember(clip.base) { mutableStateOf(vm.speakerNames(clip.base)) }
    // Bumped by an in-place edit so the summary is re-read even before the library rescan lands.
    var summaryRevision by remember { mutableIntStateOf(0) }
    val summary = remember(clip.summary?.path, clip.summary?.lastModified(), summaryRevision) { readText(clip.summary) }
    val chat = remember(clip.base) { ClipChat() }
    val qaContext = remember(summary, doc, speakerNames) { ClipQa.context(summary, doc?.text, doc?.segments.orEmpty(), speakerNames) }
    LaunchedEffect(clip.base) { vm.loadChapters(clip.base) }
    val allChapters by vm.chapters.collectAsStateWithLifecycle()
    val chapters = allChapters[clip.base].orEmpty()
    val askPlaceholder = stringResource(
        if (tab == DetailTab.TRANSCRIPT) R.string.clip_detail_ai_ask_placeholder else R.string.clip_detail_ai_ask_placeholder_note,
    )

    if (showSpeakerEditor && doc != null) {
        SpeakerRenameSheet(
            speakers = TranscriptNavigation.distinctSpeakers(doc.segments).map { sp -> sp to doc.segments.count { it.speaker == sp } },
            names = speakerNames,
            onRename = { sp, name -> speakerNames = SpeakerNames.with(speakerNames, sp, name).also { vm.saveSpeakerNames(clip.base, it) } },
            onResetAll = { speakerNames = emptyMap(); vm.saveSpeakerNames(clip.base, emptyMap()) },
            onDismiss = { showSpeakerEditor = false },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Lazy: the whole transcript is listed on the page, and a three-hour recording has thousands of lines.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                if (clip.hasAudio) item(key = "hero") { HeroCard(clip, ui, vm) }
                when (tab) {
                    DetailTab.TRANSCRIPT -> {
                        if (doc != null) {
                            item(key = "outline") { OutlineSection(clip, doc, ui, vm, chapters) }
                        }
                        item(key = "transcript") {
                            TranscriptSections(
                                clip, doc, ui, vm, onOpenSettings,
                                query = transcriptQuery,
                                onQuery = { transcriptQuery = it },
                                speaker = speakerFilter,
                                onSpeaker = { speakerFilter = it },
                                speakerNames = speakerNames,
                                onEditSpeakers = { showSpeakerEditor = true },
                                onTranscribe = startTranscribe,
                            )
                        }
                        if (doc != null && doc.segments.isNotEmpty()) {
                            transcriptRowItems(clip, doc, ui, vm, transcriptQuery, speakerFilter, speakerNames)
                        }
                        item(key = "translation") { TranslationSection(clip) }
                    }
                    DetailTab.SUMMARY -> item(key = "summary") {
                        SummarySections(clip, summary, ui, vm, title, onOpenSettings, onSaved = { summaryRevision++ })
                    }
                    DetailTab.TODOS -> item(key = "todos") { TodosSection(clip.base, summary, hasTranscript = doc != null) }
                }
                if (clip.hasAudio) {
                    item(key = "export") { ExportStatusSection(clip, vm, shareWhenReady) { shareWhenReady = false } }
                }
                item(key = "end") { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
            // With the tab bar hidden this page owns the bottom edge, so the last row clears the navigation bar itself.
            if (qaContext != null) AiAskBar(askPlaceholder) { showAsk = true } else Spacer(Modifier.navigationBarsPadding())
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

    if (showAsk && qaContext != null) AiChatSheet(chat, vm, qaContext, askPlaceholder) { showAsk = false }
    TranscribeDialogs(transcribeStep, { transcribeStep = it }, clip, vm, hasAsr, onOpenSettings)
    if (showMail) MailExportSheet(clip, vm, hasSummary = summary != null, onDismiss = { showMail = false })
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

/** The tabs as they sit in the top bar: plain labels, a short underline under the active one. */
@Composable
private fun DetailTabs(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        DetailTab.entries.forEach { t ->
            UnderlineTab(stringResource(t.titleRes), selected == t, fontSize = 15.sp) { if (selected != t) onSelect(t) }
        }
    }
}

/**
 * One label of an underlined tab strip — the clip page's tabs and the template picker's spaces share it, so the
 * two pickers in the app look like one product.
 */
@Composable
internal fun UnderlineTab(label: String, selected: Boolean, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            fontSize = fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) ink else Color.Transparent))
    }
}

/**
 * Top-right overflow menu (iOS `ellipsis.circle`): export audio, share the finished export, re-transcribe and
 * speaker names, and the three independent deletes — each entry only while it applies.
 */
@Composable
private fun DetailMenu(
    clip: LocalClip, vm: NomiViewModel, onExport: () -> Unit,
    onExportDocument: (ExportFormat) -> Unit, onMail: () -> Unit,
    onRetranscribe: () -> Unit, onEditSpeakers: (() -> Unit)?, onDelete: (ClipArtefact) -> Unit,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
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
    var docOpen by remember { mutableStateOf(false) }
    val hasTranscript = clip.transcript != null || clip.transcriptJson != null
    val hasSummary = clip.summary != null
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
            if (hasTranscript || hasSummary) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_export_document)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, null) },
                    enabled = !exporting,
                    onClick = { open = false; docOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_email_title)) },
                    leadingIcon = { Icon(Icons.Filled.Email, null) },
                    onClick = { open = false; onMail() },
                )
            }
            if (ready != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_export_ready)) },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                    onClick = {
                        open = false
                        shareFile(context, vm.exports.fileOf(ready), exportMime(ready.file))
                    },
                )
            }
            if (clip.hasAudio || onEditSpeakers != null) HorizontalDivider()
            if (clip.hasAudio) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_retranscribe)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    enabled = clip.base !in ui.transcribing,
                    onClick = { open = false; onRetranscribe() },
                )
            }
            onEditSpeakers?.let { edit ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clip_detail_edit_speaker_names)) },
                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null) },
                    onClick = { open = false; edit() },
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
        DropdownMenu(expanded = docOpen, onDismissRequest = { docOpen = false }) {
            ExportFormat.entries.filter { it.document }.forEach { format ->
                DropdownMenuItem(
                    text = { Text(stringResource(format.labelRes)) },
                    enabled = if (format == ExportFormat.PNG) hasSummary else hasTranscript,
                    onClick = { docOpen = false; onExportDocument(format) },
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
 * In-page transport, mirroring iOS `InlineAudioPlayer`: `position / total`, the waveform (the plain slider when
 * the file can't be decoded), then one row of evenly spaced controls — play, −15 s, +15 s, speed. The clip is
 * loaded paused when the page opens so the bar has a real range before the first tap.
 */
@Composable
private fun InlineAudioPlayer(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel) {
    val audio = clip.audio ?: return
    LaunchedEffect(audio.name) { vm.load(audio) }
    var peaks by remember(audio.path) { mutableStateOf(AudioEnvelope.cached(audio)) }
    LaunchedEffect(audio.path) { if (peaks == null) peaks = AudioEnvelope.load(audio) }
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

    val durationMs = ui.playDurationMs.takeIf { loaded && it > 0 }
    val totalMs = clip.durationMs ?: durationMs ?: 0
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableIntStateOf(0) }
    val shownMs = when {
        dragging -> dragMs
        durationMs != null -> ui.playPositionMs.coerceIn(0, durationMs)
        else -> 0
    }

    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "${formatClipDuration(shownMs)} / ${formatClipDuration(totalMs)}",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = if (dragging) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val bars = peaks
        when {
            bars != null && bars.size > 1 -> WaveformScrubber(
                peaks = bars,
                progress = if (durationMs != null && durationMs > 0) shownMs.toFloat() / durationMs else 0f,
                enabled = durationMs != null,
                onScrub = { fraction ->
                    if (!dragging) { dragging = true; vm.beginScrub() }
                    dragMs = (fraction * (durationMs ?: 0)).toInt()
                },
                onScrubEnded = { fraction ->
                    vm.endScrub((fraction * (durationMs ?: 0)).toInt())
                    dragging = false
                },
            )
            durationMs != null -> PlaybackScrubber(ui.playPositionMs, durationMs, vm, labels = {})
            // Not loaded (another clip is playing): the bar sits at the start; the first tap loads this one.
            else -> Slider(value = 0f, onValueChange = {}, enabled = false)
        }
        PlayerControls(playing, ui.playRate, vm, audio)
    }
}

/** Four equal slots, monochrome (Plaud's row): each item owns a quarter of the width, so the icons keep their rhythm. */
@Composable
private fun PlayerControls(playing: Boolean, rate: Float, vm: NomiViewModel, audio: java.io.File) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.2.dp, ink.copy(alpha = 0.7f), CircleShape)
                    .clickable { vm.togglePlay(audio) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.live_pause else R.string.clip_play),
                    tint = ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkipButton(back = true) { vm.skipBy(audio, -15_000) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkipButton(back = false) { vm.skipBy(audio, 15_000) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            var speedMenu by remember { mutableStateOf(false) }
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { speedMenu = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    speedLabel(rate),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                )
                Icon(Icons.Filled.AutoAwesome, null, tint = ink, modifier = Modifier.size(10.dp))
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                PLAYBACK_SPEEDS.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(speedLabel(speed)) },
                        trailingIcon = { if (kotlin.math.abs(rate - speed) < 0.01f) Icon(Icons.Filled.Check, null) },
                        onClick = { speedMenu = false; vm.setPlaybackRate(speed) },
                    )
                }
            }
        }
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
                modifier = Modifier.size(28.dp).then(if (back) Modifier else Modifier.scale(scaleX = -1f, scaleY = 1f)),
            )
            Text("15", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * The waveform scrubber (iOS `WaveformScrubber`): symmetric bars, the played part in ink and the rest faded.
 * Touching it scrubs — [onScrub] while the finger moves, [onScrubEnded] with the release point — through the
 * same begin / end scrub calls as the slider, so playback behaves identically.
 */
@Composable
private fun WaveformScrubber(
    peaks: FloatArray,
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnded: (Float) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0.92f, 0.92f, 0.92f) else Color(0.12f, 0.12f, 0.12f)
    val faded = if (dark) Color(0.45f, 0.45f, 0.45f) else Color(0.72f, 0.72f, 0.72f)
    val latestScrub by rememberUpdatedState(onScrub)
    val latestEnd by rememberUpdatedState(onScrubEnded)
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    fun fraction(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var x = down.position.x
                        latestScrub(fraction(x))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            x = change.position.x
                            change.consume()
                            latestScrub(fraction(x))
                        }
                        latestEnd(fraction(x))
                    }
                },
            ),
    ) {
        val count = peaks.size
        val barWidth = size.width / count
        val gap = minOf(1.6.dp.toPx(), barWidth * 0.38f)
        val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3)
        for (i in 0 until count) {
            val h = maxOf(2.dp.toPx(), peaks[i] * size.height)
            drawRoundRect(
                color = if (i.toFloat() / count <= progress) ink else faded,
                topLeft = androidx.compose.ui.geometry.Offset(i * barWidth, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(maxOf(0.8f, barWidth - gap), h),
                cornerRadius = radius,
            )
        }
    }
}

// MARK: - Transcript rows

/**
 * One transcript line, mirroring iOS `TranscriptSegmentRow` (Plaud's shape): time and speaker in one small grey
 * line, the sentence below at reading size. Tapping jumps the audio there; the line being spoken is tinted;
 * [highlight] marks search hits in place.
 */
@Composable
private fun TranscriptSegmentRow(seg: AsrSegment, active: Boolean, names: Map<String, String>, highlight: String? = null, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                ClockLabel.mmss(seg.start),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = if (active) accent else secondary,
            )
            seg.speaker?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    speakerLabel(names, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) accent.copy(alpha = 0.85f) else secondary,
                )
            }
        }
        Text(highlighted(seg.text, highlight), fontSize = 16.sp, lineHeight = 23.sp)
    }
}

/** The user's name for [speaker], else the vendor label `Speaker N`. */
@Composable
private fun speakerLabel(names: Map<String, String>, speaker: String): String =
    SpeakerNames.nameOf(names, speaker) ?: stringResource(R.string.clip_detail_speaker, speaker)

/**
 * Rename the speakers of this recording (iOS `SpeakerRenameSheet`): one field per speaker, applied as you type;
 * the line count answers "which one is speaker 2?". An empty field restores the original label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerRenameSheet(
    speakers: List<Pair<String, Int>>,
    names: Map<String, String>,
    onRename: (String, String) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded with what is stored, not with the vendor label, so an untouched field shows the placeholder.
    val drafts = remember { mutableStateMapOf<String, String>().apply { putAll(names) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.clip_detail_rename_speakers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
            speakers.forEach { (id, lines) ->
                androidx.compose.material3.OutlinedTextField(
                    value = drafts[id].orEmpty(),
                    onValueChange = { drafts[id] = it; onRename(id, it) },
                    placeholder = { Text(stringResource(R.string.clip_detail_speaker, id)) },
                    supportingText = { Text(stringResource(R.string.clip_detail_speaker_line_count, lines)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, autoCorrectEnabled = false),
                    trailingIcon = if (drafts[id].orEmpty().isNotEmpty()) {
                        {
                            IconButton(onClick = { drafts[id] = ""; onRename(id, "") }) {
                                Icon(Icons.Filled.Replay, stringResource(R.string.clip_detail_reset_speaker_name))
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text(
                stringResource(R.string.clip_detail_speaker_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (drafts.values.any { it.isNotBlank() }) {
                TextButton(onClick = { drafts.clear(); onResetAll() }) {
                    Text(stringResource(R.string.clip_detail_reset_speaker_names), color = MaterialTheme.colorScheme.error)
                }
            }
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
private fun TranscriptSearchBar(doc: TranscriptDocument, query: String, onQuery: (String) -> Unit, speaker: String?, onSpeaker: (String?) -> Unit, names: Map<String, String>, onEditSpeakers: () -> Unit) {
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
                SpeakerChip(speakerLabel(names, sp), speaker == sp) { onSpeaker(sp) }
            }
            val accent = MaterialTheme.colorScheme.primary
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.12f)).clickable(onClick = onEditSpeakers)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Edit, null, tint = accent, modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.clip_detail_edit_speaker_names), style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
    }
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
            .background(if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * The transcript lines as page items, on one card below the transcript header: every line, in order, with
 * tap-to-seek — or, while searching or filtering, every matching line (highlighted, capped).
 */
private fun LazyListScope.transcriptRowItems(
    clip: LocalClip,
    doc: TranscriptDocument,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    query: String,
    speaker: String?,
    names: Map<String, String>,
) {
    val active = TranscriptNavigation.activeIndex(doc.segments, positionSeconds(ui, clip))
    val filtering = query.isNotEmpty() || speaker != null
    val rows = if (filtering) TranscriptNavigation.matches(doc.segments, query, speaker) else doc.segments.indices.toList()
    val shown = if (filtering) rows.take(TranscriptNavigation.MAX_MATCHES) else rows
    val extra = rows.size - shown.size
    item(key = "rows-top") { Spacer(Modifier.height(8.dp)) }
    if (shown.isEmpty()) {
        item(key = "rows-empty") {
            CardSlice(first = true, last = true) {
                Text(
                    stringResource(R.string.clip_detail_no_transcript_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 14.dp),
                )
            }
        }
        return
    }
    itemsIndexed(shown, key = { _, i -> "seg-$i" }) { n, i ->
        val seg = doc.segments[i]
        CardSlice(first = n == 0, last = n == shown.lastIndex && extra <= 0) {
            TranscriptSegmentRow(seg, active == i, names, highlight = query.ifEmpty { null }) { seekToSegment(vm, clip, seg) }
        }
    }
    if (extra > 0) {
        item(key = "rows-more") {
            CardSlice(first = false, last = true) {
                Text(
                    "+$extra",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 8.dp),
                )
            }
        }
    }
}

/** One slice of a card split across list items: the card's colour, rounded only at its first and last slice. */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val r = NomiDimens.cardCorner
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(start = 8.dp, end = 8.dp, top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp),
    ) { content() }
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
private fun TodosSection(base: String, summary: String?, hasTranscript: Boolean) {
    val todos = remember(summary) { summary?.let(TodoExtract::fromSummary).orEmpty() }
    Group {
        when {
            todos.isNotEmpty() -> TodoChecklist(todos, base, Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp))
            summary != null -> EmptyRow(stringResource(R.string.clip_detail_todos_empty))
            hasTranscript -> EmptyRow(stringResource(R.string.clip_detail_todos_need_summary))
            else -> EmptyRow(stringResource(R.string.clip_detail_transcribe_first))
        }
    }
}

/**
 * The to-dos as a checklist (iOS `TodoChecklistView`): each item row has a tap-to-check circle and checking strikes
 * it through. Checks persist per recording, keyed by the row text — a regenerated summary that rewrites a row
 * starts it unchecked again. Headings, quotes and rules keep the plain rendering.
 */
@Composable
private fun TodoChecklist(blocks: List<MarkdownBlock>, base: String, modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences(TODO_PREFS, android.content.Context.MODE_PRIVATE)
    var done by remember(base) { mutableStateOf(prefs.getStringSet(base, emptySet()).orEmpty()) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownBlock.Kind.Bullet, is MarkdownBlock.Kind.Ordered, MarkdownBlock.Kind.Paragraph -> {
                    val checked = block.text in done
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) {
                                done = if (checked) done - block.text else done + block.text
                                prefs.edit().putStringSet(base, done).apply()
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (checked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (checked) Color(0xFF34C759) else secondary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            inlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (checked) secondary else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (checked) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                else -> MarkdownBlocksView(listOf(block))
            }
        }
    }
}

private const val TODO_PREFS = "todo-checked"

// MARK: - AI Q&A

/**
 * Q&A state for one clip, mirroring iOS `AIChatModel`. Held by the page (not persisted, as on iOS) so closing
 * the sheet keeps the conversation.
 */
private class ClipChat {
    val messages = mutableStateListOf<ClipQa.Message>()
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val canSend: Boolean get() = !busy && draft.isNotBlank()

    fun clear() {
        messages.clear()
        error = null
    }
}

/**
 * The short strip on the page's bottom edge (iOS `AIChatAskBar`): a button that looks like an input, opening the
 * conversation sheet — nothing expands over the transcript or the summary.
 */
@Composable
private fun AiAskBar(placeholder: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
                .fillMaxWidth()
                .shadow(3.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                // The green → purple → blue hairline is what makes the box read as "AI" rather than as a search field.
                .border(1.2.dp, Brush.horizontalGradient(ASK_BAR_GRADIENT), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = accent, modifier = Modifier.size(16.dp))
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowCircleUp, null, tint = accent.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
        }
    }
}

private val ASK_BAR_GRADIENT = listOf(Color(0xFF4DC78C), Color(0xFF8C73F2), Color(0xFF59A6F2))

/**
 * The conversation (iOS `AIChatSheet`): bubbles above, the input riding the keyboard below, Clear and Done in
 * the header. Opens with the field focused.
 */
@Composable
private fun AiChatSheet(chat: ClipChat, vm: NomiViewModel, material: String, placeholder: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
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
    LaunchedEffect(chat.messages.size, chat.busy) {
        if (chat.messages.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    if (chat.messages.isNotEmpty()) {
                        TextButton(onClick = chat::clear, modifier = Modifier.align(Alignment.CenterStart)) {
                            Text(stringResource(R.string.clip_detail_ai_ask_clear))
                        }
                    }
                    Text(
                        stringResource(R.string.clip_detail_ai_ask_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (chat.messages.isEmpty() && !chat.busy) {
                        // The empty state doubles as the grounding note.
                        item {
                            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.clip_detail_ai_ask_empty_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.clip_detail_ai_ask_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    items(chat.messages) { message -> ChatBubble(message) }
                    if (chat.busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.clip_detail_ai_ask_thinking),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    chat.error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                }
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            if (chat.draft.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BasicTextField(
                                value = chat.draft,
                                onValueChange = { chat.draft = it },
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        IconButton(onClick = { send() }, enabled = chat.canSend) {
                            Icon(
                                Icons.Filled.ArrowCircleUp,
                                contentDescription = stringResource(R.string.clip_detail_ai_ask_title),
                                tint = if (chat.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A question on the right in the accent tint, an answer on the left in grey; both selectable. */
@Composable
private fun ChatBubble(message: ClipQa.Message) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
        SelectionContainer(
            Modifier
                .padding(start = if (message.isUser) 40.dp else 0.dp, end = if (message.isUser) 0.dp else 40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (message.isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
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
 * Transcript tab header card: transcription / translation progress, the search field and speaker chips, or a
 * single Transcribe row when there is no transcript yet. The lines themselves follow as page items
 * ([transcriptRowItems]); a transcript without segments shows its text here. The header carries translate /
 * re-transcribe once a transcript exists.
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
    speakerNames: Map<String, String>,
    onEditSpeakers: () -> Unit,
    /** Starts the transcribe flow; `true` re-runs over an existing transcript (confirmed first). */
    onTranscribe: (Boolean) -> Unit,
) {
    val cfgNow by vm.config.collectAsStateWithLifecycle()
    val transcribing = clip.base in ui.transcribing
    val translating = "${clip.base}:${ClipArtefact.TRANSCRIPT.name}" in ui.translating
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val transcribeJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSCRIBE)
    val translateJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSLATE, "transcript")
    var translatePicker by remember { mutableStateOf(false) }
    var noLlm by remember { mutableStateOf(false) }
    var fullText by remember { mutableStateOf(false) }
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
                        HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.clip_detail_retranscribe)) { onTranscribe(true) }
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
                    // No lines to list (a flat transcript can be book-length): a preview and the lazy reader.
                    PreviewRow(transcriptPreview(doc))
                    NavRow(stringResource(R.string.clip_detail_view_full_transcript)) { fullText = true }
                } else {
                    TranscriptSearchBar(doc, query, onQuery, speaker, onSpeaker, speakerNames, onEditSpeakers)
                }
            }
            clip.hasAudio && !transcribing -> ActionRow(
                stringResource(R.string.clip_detail_transcribe),
                icon = Icons.AutoMirrored.Filled.Message,
                divider = false,
            ) { onTranscribe(false) }
            !transcribing -> EmptyRow(stringResource(R.string.clip_detail_transcribe_first))
        }
    }

    if (fullText && doc != null) {
        FullTextDialog(stringResource(R.string.clip_detail_transcript), doc.text, markdown = false) { fullText = false }
    }
    if (translatePicker) {
        TranslatePicker(vm) { lang ->
            translatePicker = false
            if (lang != null) vm.translateArtefact(clip, ClipArtefact.TRANSCRIPT, lang.name, lang.code)
        }
    }
    if (noLlm) NoLlmDialog(vm, onOpenSettings) { noLlm = false }
}

/** The translated transcript: a preview and the full text. */
@Composable
private fun TranslationSection(clip: LocalClip) {
    val translation = readText(clip.transcriptTranslation) ?: return
    var full by remember { mutableStateOf(false) }
    Group(title = stringResource(R.string.clip_detail_translation)) {
        PreviewRow(translation)
        NavRow(stringResource(R.string.clip_detail_view_full_translation)) { full = true }
    }
    if (full) FullTextDialog(stringResource(R.string.clip_detail_translation), translation, markdown = false) { full = false }
}

/** Steps of starting a transcription, shown at page level so the top-right menu can start one from any tab. */
private enum class TranscribeStep { CONFIRM_RERUN, CHOOSE_MODE, LOCALES, NO_ASR }

/**
 * Re-running overwrites the existing transcript, so it is confirmed first; then auto-detect or picked languages
 * (the picked `locales` apply to this run only); without a speech service, a pointer to its settings.
 */
@Composable
private fun TranscribeDialogs(
    step: TranscribeStep?,
    onStep: (TranscribeStep?) -> Unit,
    clip: LocalClip,
    vm: NomiViewModel,
    hasAsr: Boolean,
    onOpenSettings: () -> Unit,
) {
    when (step) {
        null -> Unit
        TranscribeStep.CONFIRM_RERUN -> AlertDialog(
            onDismissRequest = { onStep(null) },
            title = { Text(stringResource(R.string.clip_detail_retranscribe)) },
            text = { Text(stringResource(R.string.clip_detail_retranscribe_message)) },
            confirmButton = {
                TextButton(onClick = { onStep(if (hasAsr) TranscribeStep.CHOOSE_MODE else TranscribeStep.NO_ASR) }) {
                    Text(stringResource(R.string.clip_detail_retranscribe), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
        TranscribeStep.CHOOSE_MODE -> AlertDialog(
            onDismissRequest = { onStep(null) },
            title = { Text(stringResource(R.string.clip_detail_transcribe)) },
            text = { Text(stringResource(R.string.asr_languages_footer)) },
            confirmButton = {
                TextButton(onClick = { onStep(null); vm.transcribe(clip) }) {
                    Text(stringResource(R.string.asr_transcribe_auto))
                }
            },
            dismissButton = {
                TextButton(onClick = { onStep(TranscribeStep.LOCALES) }) {
                    Text(stringResource(R.string.asr_specify_languages))
                }
            },
        )
        TranscribeStep.LOCALES -> AzureLocalesPicker(emptyList()) { codes ->
            onStep(null)
            if (codes != null) vm.transcribe(clip, locales = codes)
        }
        TranscribeStep.NO_ASR -> AlertDialog(
            onDismissRequest = { onStep(null) },
            title = { Text(stringResource(R.string.asr_no_provider)) },
            text = { Text(stringResource(R.string.asr_no_provider_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onStep(null)
                    vm.openSettingsAt(NomiViewModel.SettingsTarget.ASR)
                    onOpenSettings()
                }) { Text(stringResource(R.string.common_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * The outline (iOS `outlineSection`): a timestamped table of contents above the transcript. Tapping a chapter
 * seeks the player there; the chapter under the playhead is tinted. Without one: generate it, or say why a flat
 * transcript (no segment timings) can't have one rather than offer a button that can only fail.
 */
@Composable
private fun OutlineSection(clip: LocalClip, doc: TranscriptDocument, ui: NomiViewModel.Ui, vm: NomiViewModel, chapters: List<ClipChapter>) {
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val jobs = ClipJobs(allJobs, clip.base, JobKind.OUTLINE)
    val hasTimeline = remember(doc) { doc.segments.any { it.text.isNotBlank() } }
    val position = positionSeconds(ui, clip)
    // Nothing reads as "current" before playback has moved.
    val activeStart = if (position > 0) chapters.lastOrNull { it.start <= position }?.start else null
    Group(
        title = stringResource(R.string.clip_detail_outline),
        trailing = if (chapters.isNotEmpty() && jobs.active == null) {
            { HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.clip_detail_outline_regenerate)) { vm.enqueueOutline(clip) } }
        } else {
            null
        },
        footer = if (chapters.isEmpty() && hasTimeline) stringResource(R.string.clip_detail_outline_hint) else null,
    ) {
        val active = jobs.active
        val failed = jobs.failed
        when {
            active != null -> JobProgressRow(active, stringResource(R.string.clip_detail_outline_generating))
            chapters.isNotEmpty() -> chapters.forEachIndexed { i, chapter ->
                ChapterRow(chapter, active = chapter.start == activeStart) {
                    clip.audio?.let { vm.seekAndPlay(it, (chapter.start * 1000).toInt()) }
                }
                if (i < chapters.lastIndex) RowDivider()
            }
            failed != null -> JobFailedRow(failed, vm)
            hasTimeline -> ActionRow(
                stringResource(R.string.clip_detail_outline_generate),
                icon = Icons.Filled.AutoAwesome,
                divider = false,
            ) { vm.enqueueOutline(clip) }
            else -> Text(
                stringResource(R.string.clip_detail_outline_no_timeline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ChapterRow(chapter: ClipChapter, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = NomiDimens.rowInset, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            ClockLabel.mmss(chapter.start),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 44.dp).padding(top = 1.dp),
        )
        Text(chapter.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * AI Minutes tab: the whole summary rendered inline under an "AI-generated" caption — editable in place — and,
 * when it has headings or lists to plot, the mind map card; then the translated summary. The header carries
 * edit / translate / re-summarize.
 */
@Composable
private fun SummarySections(
    clip: LocalClip,
    summary: String?,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    clipTitle: String,
    onOpenSettings: () -> Unit,
    onSaved: () -> Unit,
) {
    val hasOutline = remember(summary) { summary != null && MarkdownOutline.parse(summary) != null }
    val translating = "${clip.base}:${ClipArtefact.SUMMARY.name}" in ui.translating
    val cfgNow by vm.config.collectAsStateWithLifecycle()
    val hasLlm = cfgNow.llmProviders.configuredProviders.isNotEmpty()
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val summarizeJobs = ClipJobs(allJobs, clip.base, JobKind.SUMMARIZE)
    val translateJobs = ClipJobs(allJobs, clip.base, JobKind.TRANSLATE, "summary")
    val scope = rememberCoroutineScope()
    var showSummarize by remember { mutableStateOf(false) }
    var translatePicker by remember { mutableStateOf(false) }
    var noLlm by remember { mutableStateOf(false) }
    var fullTranslation by remember { mutableStateOf(false) }
    var mindMap by remember { mutableStateOf(false) }
    // The markdown under edit; null while reading.
    var draft by remember(clip.base) { mutableStateOf<String?>(null) }
    // Symmetric to transcription: without an LLM, explain and link to settings instead of failing in the sheet.
    val openSummarize = { if (hasLlm) showSummarize = true else noLlm = true }

    Group(
        title = stringResource(R.string.clip_detail_summary),
        trailing = if (summary != null) {
            {
                HeaderIcons {
                    HeaderIcon(Icons.Filled.Edit, stringResource(R.string.common_edit), enabled = draft == null) { draft = summary }
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
            summary != null -> Column(
                Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.clip_detail_ai_generated_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                val editing = draft
                if (editing != null) {
                    SummaryEditor(
                        editing,
                        onChange = { draft = it },
                        onCancel = { draft = null },
                        onSave = {
                            val text = editing.trim()
                            if (text.isNotEmpty()) {
                                scope.launch {
                                    vm.saveSummary(clip.base, text)
                                    draft = null
                                    onSaved()
                                }
                            }
                        },
                    )
                } else {
                    // The summary tab is the reading surface: the whole document inline, not a teaser behind a link.
                    val blocks = remember(summary) { MarkdownBlocks.parse(summary) }
                    MarkdownBlocksView(blocks)
                    // Only offered when the summary actually has section structure to plot.
                    if (hasOutline) MindMapPreviewCard(summary, clipTitle) { mindMap = true }
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
            NavRow(stringResource(R.string.clip_detail_view_full_translated_summary)) { fullTranslation = true }
        }
    }

    if (fullTranslation && summaryTranslation != null) {
        SummaryFullDialog(stringResource(R.string.clip_detail_translated_summary), summaryTranslation, poster = clip) { fullTranslation = false }
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

/**
 * In-place summary editor: the markdown itself, plain and monospaced — users edit what the note actually is.
 * Saving writes the summary file, which the page, poster, mind map and export all read.
 */
@Composable
private fun SummaryEditor(text: String, onChange: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                .padding(10.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Button(onClick = onSave, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                Text(stringResource(R.string.common_done), Modifier.padding(start = 4.dp))
            }
        }
    }
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

private enum class ExportFormat(val ext: String, val labelRes: Int, val document: Boolean = false) {
    M4A("m4a", R.string.clip_detail_export_as_m4a),
    WAV("wav", R.string.clip_detail_export_as_wav),
    TXT("txt", R.string.clip_detail_export_as_txt, true),
    MD("md", R.string.clip_detail_export_as_md, true),
    PDF("pdf", R.string.clip_detail_export_as_pdf, true),
    PNG("png", R.string.clip_detail_export_as_png, true),
}

private fun exportMime(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
    "wav" -> "audio/wav"
    "txt" -> "text/plain"
    "md" -> "text/markdown"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    else -> "audio/mp4"
}

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
            shareFile(context, vm.exports.fileOf(ready), exportMime(ready.file))
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
                        shareFile(context, vm.exports.fileOf(ready), exportMime(ready.file))
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
                ExportFormat.entries.filter { !it.document }.forEach { format ->
                    TextButton(onClick = { onPick(format) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(format.labelRes))
                    }
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

/**
 * "Send by Email": picks which of summary (.md), transcript (.txt) and mind map (.png) to attach, then hands the
 * files to a mail app through a chooser. The summary and the mind map need a summary to exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailExportSheet(clip: LocalClip, vm: NomiViewModel, hasSummary: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(hasSummary) }
    var transcript by remember { mutableStateOf(clip.transcript != null || clip.transcriptJson != null) }
    var mindMap by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val stem = clip.name.substringBeforeLast('.')

    @Composable
    fun Toggle(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(label),
                modifier = Modifier.weight(1f),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            androidx.compose.material3.Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        }
    }

    fun send() {
        busy = true
        error = null
        scope.launch {
            runCatching { vm.prepareMailAttachments(clip.base, summary, transcript, mindMap) }
                .onSuccess { files ->
                    busy = false
                    val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "message/rfc822"
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.clip_detail_email_subject, stem))
                        putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.clip_detail_email_body))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // The chooser forwards the grant only for URIs it can see in ClipData.
                        clipData = android.content.ClipData.newRawUri(null, uris.first()).also { cd -> uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }
                    }
                    try {
                        context.startActivity(android.content.Intent.createChooser(send, null))
                        onDismiss()
                    } catch (_: android.content.ActivityNotFoundException) {
                        error = context.getString(R.string.clip_detail_email_unavailable)
                    }
                }
                .onFailure {
                    busy = false
                    error = it.message ?: context.getString(R.string.clip_detail_email_unavailable)
                }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.clip_detail_email_title), style = MaterialTheme.typography.titleMedium)
            Toggle(R.string.clip_detail_email_attach_summary, summary, hasSummary) { summary = it }
            Toggle(R.string.clip_detail_email_attach_transcript, transcript, true) { transcript = it }
            Toggle(R.string.clip_detail_email_attach_mindmap, mindMap, hasSummary) { mindMap = it }
            Text(
                stringResource(R.string.clip_detail_email_attach_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            OutlinedButton(
                onClick = ::send,
                enabled = !busy && (summary || transcript || mindMap),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.clip_detail_email_compose))
            }
        }
    }
}
