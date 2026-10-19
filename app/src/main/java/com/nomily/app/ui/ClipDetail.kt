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

/**
 * Clip detail page, mirroring iOS `ClipDetailView`: hero card (opens the player sheet), transcript,
 * translation, summary (with mind map), translated summary, export and independent deletes — each a
 * grouped card in that order.
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
    var showPlayer by remember { mutableStateOf(false) }
    val title = clip.title ?: RecordingName.displayTitle(clip.name)
    // VAD preview available only in developer mode (waveform icon in the detail page top bar)
    val showVadAction = cfg.developerMode && clip.hasAudio
    LaunchedEffect(title, showVadAction) {
        onPush(
            PushedPage(
                title = title,
                actions = if (showVadAction) {
                    { TextButton(onClick = { showVad = true }) { Text("VAD") } }
                } else {
                    null
                },
                onBack = onDismiss,
            )
        )
    }
    // Leaving the page stops this clip's playback, as on iOS.
    DisposableEffect(Unit) {
        onDispose {
            onPush(null)
            if (vm.ui.value.playing == clip.audio?.name) vm.stopPlayback()
        }
    }
    if (showVad) VadPreviewDialog(clip) { showVad = false }
    if (showPlayer) PlayerSheet(clip, ui, vm, title) { showPlayer = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (clip.hasAudio) HeroCard(clip, ui) { showPlayer = true }
            TranscriptSections(clip, ui, vm, onOpenSettings)
            SummarySections(clip, ui, vm, title, onOpenSettings)

            // The detail page is a full‑screen Dialog that covers the error card on the main screen —
            // if it were not shown here too, tapping "Transcribe" would appear to do nothing.
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

            if (clip.hasAudio) ExportSection(clip, vm)
            DestructiveSection(clip, vm, onDeletedEverything = onDismiss)
            Spacer(Modifier.padding(bottom = 24.dp))
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
 * Transcript + translation sections. The header carries translate / re-transcribe once a transcript
 * exists; a clip without one offers a single Transcribe row (auto-detect or pick languages).
 */
@Composable
private fun TranscriptSections(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel, onOpenSettings: () -> Unit) {
    val doc = remember(clip.transcriptJson, clip.transcript) { loadTranscript(clip) }
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
    val hasAsr = cfgNow.asrProviders.azure?.key?.isNotEmpty() == true ||
        cfgNow.asrProviders.local?.host?.isNotEmpty() == true
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
                PreviewRow(transcriptPreview(doc))
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

    if (full && doc != null) TranscriptFullDialog(doc) { full = false }
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
 * Summary + translated-summary sections. A summary offers its full view and, when it has headings or
 * lists to plot, the mind map; the header carries translate / re-summarize.
 */
@Composable
private fun SummarySections(
    clip: LocalClip,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    clipTitle: String,
    onOpenSettings: () -> Unit,
) {
    val summary = readText(clip.summary)
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
                PreviewRow(summary)
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
        SummaryFullDialog(stringResource(titleRes), body) { fullSummary = null }
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

/** Full-screen summary: rendered Markdown with mind map, raw-source toggle and share, as on iOS. */
@Composable
private fun SummaryFullDialog(title: String, body: String, onDismiss: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    var mindMap by remember { mutableStateOf(false) }
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
            ShareAction(body)
        },
    ) {
        if (showRaw) LongTextReader(body, Modifier.fillMaxSize(), monospace = true) else MarkdownView(body, Modifier.fillMaxSize())
    }
    if (mindMap) MindMapScreen(body, title) { mindMap = false }
}

/** Full‑screen segmented reader — per segment: `[Speaker N]` + `m:ss–m:ss` + text. */
@Composable
private fun TranscriptFullDialog(doc: TranscriptDocument, onDismiss: () -> Unit) {
    val shareBody = remember(doc) { transcriptShareText(doc) }
    FullPage(stringResource(R.string.clip_detail_transcript), onDismiss, actions = { ShareAction(shareBody) }) {
        if (doc.segments.isEmpty()) {
            LongTextReader(doc.text, Modifier.fillMaxSize())
        } else {
            // Lazy: a three‑hour segment contains thousands of pieces; combining them all at once would freeze the dialog for several seconds.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(doc.segments) { seg ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            seg.speaker?.takeIf { it.isNotEmpty() }?.let { sp ->
                                Text(
                                    stringResource(R.string.clip_detail_speaker, sp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                stampLabel(seg),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        SelectionContainer {
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = READER_LINE_HEIGHT,
                            )
                        }
                    }
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
 * Export: re-encode to **M4A (AAC)** or decode to **WAV (PCM)** as a background task, then hand off to the share
 * sheet. Ogg‑Opus cannot be opened by most third‑party apps, so the original is never shared as-is. The finished
 * file stays on the page ("Exported — tap to share") so a long conversion isn't redone after leaving.
 */
@Composable
private fun ExportSection(clip: LocalClip, vm: NomiViewModel) {
    val context = LocalContext.current
    val allJobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val readyAny by vm.exports.ready.collectAsStateWithLifecycle()
    val exportJobs = ClipJobs(allJobs, clip.base, JobKind.EXPORT)
    var choose by remember { mutableStateOf(false) }
    // Share automatically when the export the user just started finishes while they're still here.
    var shareWhenReady by remember { mutableStateOf(false) }
    val ready = readyAny?.takeIf { exportJobs.active == null }?.let { vm.exports.readyFor(clip.base) }
    LaunchedEffect(ready, shareWhenReady) {
        if (shareWhenReady && ready != null) {
            shareWhenReady = false
            shareFile(context, vm.exports.fileOf(ready), if (ready.file.endsWith(".wav")) "audio/wav" else "audio/mp4")
        }
    }

    Group(title = stringResource(R.string.clip_detail_export), footer = stringResource(R.string.clip_detail_export_format_footer)) {
        val busy = exportJobs.active != null
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { choose = true }
                .heightIn(min = NomiDimens.rowMinHeight)
                .padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.IosShare,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(NomiDimens.actionIconSize),
            )
            Text(
                stringResource(R.string.clip_detail_export_audio),
                style = MaterialTheme.typography.bodyLarge,
                color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = NomiDimens.actionIconGap).weight(1f),
            )
        }
        exportJobs.active?.let { RowDivider(); JobProgressRow(it, stringResource(R.string.clip_detail_exporting)) }
            ?: exportJobs.failed?.let { RowDivider(); JobFailedRow(it, vm) }
        if (ready != null) {
            RowDivider()
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

    if (choose) {
        ExportFormatDialog(
            onPick = { format ->
                choose = false
                shareWhenReady = true
                vm.enqueueExport(clip, format.ext)
            },
            onDismiss = { choose = false },
        )
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

/** Hero card: tapping anywhere opens the player sheet. Play icon · duration and size · date · file name. */
@Composable
private fun HeroCard(clip: LocalClip, ui: NomiViewModel.Ui, onOpenPlayer: () -> Unit) {
    val playing = ui.playing == clip.audio?.name && !ui.playPaused
    Group {
        Row(
            // The whole card is the target, not just the artwork and the labels.
            Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer).padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.live_pause else R.string.clip_play),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Unknown duration leaves the slot empty rather than implying the audio is gone.
                    clip.durationMs?.let {
                        Text(formatClipDuration(it), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatClipBytes(clip.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                clip.recordedAt?.let {
                    Text(
                        // Medium date + short time, localized through the current locale.
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault()).format(it) +
                            " " +
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    clip.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

/** Player sheet: large play/pause circle and a scrubber, half-height, as on iOS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel, title: String, onDismiss: () -> Unit) {
    val audio = clip.audio ?: return
    val loaded = ui.playing == audio.name
    val playing = loaded && !ui.playPaused
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
            // The file couldn't be loaded: say why instead of showing a play button that does nothing.
            ui.playError?.takeIf { it.first == audio.name }?.let { (_, reason) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Block, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFF9500), modifier = Modifier.size(40.dp))
                    Text(
                        stringResource(R.string.clip_detail_playback_failed, reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                return@Column
            }
            // The hit area is larger than the drawn circle so the padding around it doesn't swallow taps.
            Box(
                Modifier.size(140.dp).clickable(onClick = { vm.togglePlay(audio) }),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(120.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.live_pause else R.string.clip_play),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (loaded && ui.playDurationMs > 0) {
                    PlaybackScrubber(ui.playPositionMs, ui.playDurationMs, vm, totalMs = clip.durationMs, centered = false)
                } else {
                    // Not loaded yet: the bar sits at the start; the first play loads the player.
                    Slider(value = 0f, onValueChange = {}, enabled = false)
                    Row {
                        Text(formatClipDuration(0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatClipDuration(clip.durationMs ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Playback progress bar — shared among the player sheet and the library row; avoid duplicating code.
 *
 * During dragging only update the **local** value, without feeding seek commands to the ViewModel each frame: previously `onValueChange` called `seekTo` every frame while a polling coroutine simultaneously wrote back `playPositionMs`, causing contention and the slider to bounce back.
 * Now pressing starts `beginScrub()` (muting during drag), releasing calls `endScrub()` to set the position and resume as needed.
 *
 * @param centered `true` shows "position / total" under the bar; `false` puts position and total at the two ends, as the player sheet does.
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
    if (centered) {
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

/** Audio, transcript and summary delete independently — each row appears only while that artefact exists. */
@Composable
private fun DestructiveSection(clip: LocalClip, vm: NomiViewModel, onDeletedEverything: () -> Unit) {
    var pending by remember { mutableStateOf<ClipArtefact?>(null) }
    val rows = listOfNotNull(
        ClipArtefact.AUDIO.takeIf { clip.hasAudio },
        ClipArtefact.TRANSCRIPT.takeIf { clip.transcript != null },
        ClipArtefact.SUMMARY.takeIf { clip.summary != null },
    )

    Group(footer = stringResource(R.string.clip_detail_delete_independent_footer)) {
        rows.forEachIndexed { i, what ->
            val label = when (what) {
                ClipArtefact.AUDIO -> R.string.clip_detail_delete_audio
                ClipArtefact.TRANSCRIPT -> R.string.clip_detail_delete_transcript
                ClipArtefact.SUMMARY -> R.string.clip_detail_delete_summary
            }
            ActionRow(stringResource(label), icon = Icons.Filled.Delete, destructive = true, divider = i < rows.lastIndex) {
                pending = what
            }
        }
    }

    pending?.let { what ->
        val (title, message) = when (what) {
            ClipArtefact.AUDIO ->
                R.string.clip_detail_delete_audio to R.string.clip_detail_delete_audio_message
            ClipArtefact.TRANSCRIPT ->
                R.string.clip_detail_delete_transcript to R.string.clip_detail_delete_transcript_message
            ClipArtefact.SUMMARY ->
                R.string.clip_detail_delete_summary to R.string.clip_detail_delete_summary_message
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (what == ClipArtefact.AUDIO && vm.ui.value.playing == clip.audio?.name) vm.stopPlayback()
                    vm.deleteArtefact(clip, what)
                    pending = null
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
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

internal fun formatClipDuration(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
