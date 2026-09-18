package com.nomily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nomily.app.NomiViewModel
import com.nomily.app.NomiViewModel.LocalClip
import com.nomily.app.R
import android.content.Intent
import androidx.core.content.FileProvider
import com.nomily.app.audio.AudioExporter
import com.nomily.app.ble.RecordingName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nomily.app.core.asr.AsrSegment
import com.nomily.app.core.asr.parseAsrArtefact
import com.nomily.app.core.clips.ClipArtefact
import java.util.Locale

/**
 * Segment detail page (`all-features-list.md` P1 "Segment Detail Page").
 *
 * Covers hero card (size/duration/date), playback row (including drag-to-seek), transcription and summary reading area,
 * and independent deletion for each of the three products.
 *
 * ⚠️ **The "Generate" buttons for transcription / summary / translation are not yet available** — Azure ASR and LLM services have not been integrated.
 * This page only renders products that already exist on disk: if none, show an empty state instead of a non‑responsive button.
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
    var showVad by remember { mutableStateOf(false) }
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
    DisposableEffect(Unit) { onDispose { onPush(null) } }
    if (showVad) VadPreviewDialog(clip) { showVad = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                HeroCard(clip, ui, vm)

                TranscriptSection(clip, vm)
                // Transcription — if a transcription already exists, first show a "Re-transcribe?" confirmation,
                // because re-running will **overwrite** the existing txt/json
                if (clip.hasAudio) {
                    val cfgNow by vm.config.collectAsStateWithLifecycle()
                    var confirmRetranscribe by remember { mutableStateOf(false) }
                    // Transcription mode: automatic detection / specified language;
                    // The specified `locales` apply only to this run and are not persisted.
                    var chooseMode by remember { mutableStateOf(false) }
                    var showLocales by remember { mutableStateOf(false) }
                    var noProvider by remember { mutableStateOf(false) }
                    val hasAsr = cfgNow.asrProviders.azure?.key?.isNotEmpty() == true ||
                        cfgNow.asrProviders.local?.host?.isNotEmpty() == true
                    val start: (List<String>?) -> Unit = { locales ->
                        if (!hasAsr) noProvider = true else vm.transcribe(clip, locales = locales)
                    }
                    ActionRow(
                        Icons.AutoMirrored.Filled.Message,
                        stringResource(R.string.clip_detail_transcribe),
                        enabled = ui.busy == null,
                    ) {
                        when {
                            !hasAsr -> noProvider = true
                            clip.transcript != null -> confirmRetranscribe = true
                            else -> chooseMode = true
                        }
                    }
                    if (chooseMode) {
                        AlertDialog(
                            onDismissRequest = { chooseMode = false },
                            title = { Text(stringResource(R.string.clip_detail_transcribe)) },
                            text = { Text(stringResource(R.string.asr_languages_footer)) },
                            confirmButton = {
                                TextButton(onClick = { chooseMode = false; start(null) }) {
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
                            if (codes != null) start(codes)
                        }
                    }
                    if (noProvider) {
                        // Previously, attempting transcription without a configured provider would just throw a low‑level error —
                        // Provide a "No transcription service" notice + a direct entry to configure it
                        AlertDialog(
                            onDismissRequest = { noProvider = false },
                            title = { Text(stringResource(R.string.asr_no_provider)) },
                            text = { Text(stringResource(R.string.asr_no_provider_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    noProvider = false
                                    vm.openSettingsAt(NomiViewModel.SettingsTarget.ASR)
                                    onOpenSettings()
                                }) {
                                    Text(stringResource(R.string.common_open_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { noProvider = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                        )
                    }
                    if (confirmRetranscribe) {
                        AlertDialog(
                            onDismissRequest = { confirmRetranscribe = false },
                            title = { Text(stringResource(R.string.clip_detail_retranscribe)) },
                            text = { Text(stringResource(R.string.clip_detail_retranscribe_message)) },
                            confirmButton = {
                                TextButton(onClick = { confirmRetranscribe = false; chooseMode = true }) {
                                    Text(stringResource(R.string.clip_detail_transcribe))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmRetranscribe = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                        )
                    }
                }
                SummarySection(clip, ui, vm, onOpenSettings)

                // The detail page is a full‑screen Dialog that covers the error card on the main screen —
                // If it were also shown here, tapping "Transcribe" would appear to do nothing.
                // (In practice: when no provider is configured, the log contains "No ASR provider is configured", but the UI stays silent.)
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

                if (clip.hasAudio) ExportSection(clip)

                DestructiveSection(clip, vm, onDeletedEverything = onDismiss)
                }
            }
        }
}

/**
 * Summary area: if a summary exists, **render Markdown offline** ([MarkdownView] using marked.js),
 * if no summary but transcription exists, provide a "Summary" entry; if neither exists, prompt to transcribe first.
 *
 * There is a "Translate" button for both summary and transcription, with translated text saved separately (`.summary.translated.md` / `.translated.txt`),
 * **without overwriting the original**.
 */
@Composable
private fun SummarySection(
    clip: LocalClip,
    ui: NomiViewModel.Ui,
    vm: NomiViewModel,
    onOpenSettings: () -> Unit,
) {
    val summary = clip.summary?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    var showSummarize by remember { mutableStateOf(false) }
    var translateTarget by remember { mutableStateOf<ClipArtefact?>(null) }

    SectionTitleLike(stringResource(R.string.clip_detail_summary))

    var fullSummary by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val cfgNow by vm.config.collectAsStateWithLifecycle()
    var noLlm by remember { mutableStateOf(false) }
    val hasLlm = cfgNow.llmProviders.configuredProviders.isNotEmpty()
    // Symmetric to transcription side: if LLM is not configured, don't place the summary panel and error out; instead explain and provide a link to settings.
    val openSummarize = { if (hasLlm) showSummarize = true else noLlm = true }
    if (noLlm) {
        AlertDialog(
            onDismissRequest = { noLlm = false },
            title = { Text(stringResource(R.string.llm_no_provider)) },
            text = { Text(stringResource(R.string.llm_no_provider_message)) },
            confirmButton = {
                TextButton(onClick = {
                    noLlm = false
                    vm.openSettingsAt(NomiViewModel.SettingsTarget.LLM)
                    onOpenSettings()
                }) {
                    Text(stringResource(R.string.common_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { noLlm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    when {
        summary != null -> {
            MarkdownView(summary, Modifier.fillMaxWidth().height(320.dp))
            // The summary box has a fixed height, so long summaries are truncated — provide a full‑screen "View Full Summary" entry.
            TextButton(onClick = { fullSummary = R.string.clip_detail_summary to summary }) {
                Text(stringResource(R.string.clip_detail_view_full_summary))
            }
            Row {
                OutlinedButton(
                    onClick = openSummarize,
                    enabled = ui.busy == null,
                    modifier = Modifier.weight(1f).padding(end = 4.dp, top = 6.dp),
                ) { Text(stringResource(R.string.clip_detail_summarize)) }
                OutlinedButton(
                    onClick = { translateTarget = ClipArtefact.SUMMARY },
                    enabled = ui.busy == null,
                    modifier = Modifier.weight(1f).padding(start = 16.dp, top = 6.dp),
                ) { Text(stringResource(R.string.clip_detail_translate)) }
            }
        }
        clip.transcript != null -> OutlinedButton(
            onClick = openSummarize,
            enabled = ui.busy == null,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(stringResource(R.string.clip_detail_summarize)) }
        else -> EmptyCard(stringResource(R.string.clip_detail_transcribe_first))
    }

    // Summary translation — previously the translation was only saved to disk and never shown in the UI.
    val summaryTranslation = clip.summaryTranslation
        ?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    if (summaryTranslation != null) {
        Text(
            stringResource(R.string.clip_detail_translated_summary),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        MarkdownView(summaryTranslation, Modifier.fillMaxWidth().height(240.dp))
        TextButton(
            onClick = {
                fullSummary = R.string.clip_detail_translated_summary to summaryTranslation
            },
        ) { Text(stringResource(R.string.clip_detail_view_full_translated_summary)) }
    }

    fullSummary?.let { (titleRes, body) ->
        FullTextDialog(stringResource(titleRes), body, markdown = true) { fullSummary = null }
    }

    if (showSummarize) SummarizeSheet(clip, vm) { showSummarize = false }
    translateTarget?.let { which ->
        TranslatePicker(vm) { name ->
            translateTarget = null
            if (name != null) vm.translateArtefact(clip, which, name)
        }
    }
}

/** Select target language → translate. The language list is shared with summary output languages (Microsoft Translator public list). */
@Composable
private fun TranslatePicker(vm: NomiViewModel, onDone: (String?) -> Unit) {
    var languages by remember { mutableStateOf<List<com.nomily.app.llm.LanguageService.Language>>(emptyList()) }
    LaunchedEffect(Unit) { languages = vm.targetLanguages() }
    LanguagePickerDialog(
        title = stringResource(R.string.clip_detail_translate),
        languages = languages,
        defaultOption = null,
        onPick = { code -> onDone(languages.firstOrNull { it.code == code }?.name) },
        onDismiss = { onDone(null) },
    )
}

/**
 * Transcription area: the detail page shows only **3 lines preview**, tap "View Full Transcription" to open a full‑screen segmented reader
 * (speaker label + timestamp + selectable text).
 *
 * Segments are sourced from `{base}.asr.json`; if that file is missing (e.g., only `.txt` remains) fall back to the whole plain text ——
 * two‑level fallback.
 */
@Composable
private fun TranscriptSection(clip: LocalClip, vm: NomiViewModel) {
    val doc = remember(clip.transcriptJson, clip.transcript) { loadTranscript(clip) }
    var full by remember { mutableStateOf(false) }

    SectionTitleLike(stringResource(R.string.clip_detail_transcript))
    // When no transcription exists, do not add an extra "No transcription yet" line —
    // That line is already in English (Android‑specific key, not included in the 10 languages), so just return.
    if (doc == null) return

    Text(
        transcriptPreview(doc),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
    var translating by remember { mutableStateOf(false) }
    Row {
        TextButton(onClick = { full = true }) {
            Text(stringResource(R.string.clip_detail_view_full_transcript))
        }
        TextButton(onClick = { translating = true }) {
            Text(stringResource(R.string.clip_detail_translate))
        }
    }
    if (translating) {
        TranslatePicker(vm) { name ->
            translating = false
            if (name != null) vm.translateArtefact(clip, ClipArtefact.TRANSCRIPT, name)
        }
    }

    // Transcription translation — same as summary: translation was only saved to disk, with no UI entry.
    val translation = clip.transcriptTranslation
        ?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    var fullTranslation by remember { mutableStateOf(false) }
    if (translation != null) {
        Text(
            stringResource(R.string.clip_detail_translation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            translation,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = { fullTranslation = true }) {
            Text(stringResource(R.string.clip_detail_view_full_translation))
        }
    }
    if (fullTranslation && translation != null) {
        FullTextDialog(
            stringResource(R.string.clip_detail_translation),
            translation,
            markdown = false,
        ) { fullTranslation = false }
    }

    if (full) TranscriptFullDialog(doc) { full = false }
}

/** The detail page's 3‑line preview: if segments exist, concatenate their bodies; otherwise use the whole text. */
private fun transcriptPreview(doc: TranscriptDocument): String =
    if (doc.segments.isEmpty()) doc.text.take(200) else doc.segments.joinToString(" ") { it.text }.take(200)

/** Full‑screen segmented reader — per segment: `[Speaker N]` + `m:ss–m:ss` + text. */
/** Full‑screen view of a long text (summary / translation). Markdown is rendered offline, plain text is laid out directly. */
@Composable
private fun FullTextDialog(
    title: String,
    body: String,
    markdown: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                if (markdown) {
                    MarkdownView(body, Modifier.fillMaxSize())
                } else {
                    LongTextReader(body, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun TranscriptFullDialog(doc: TranscriptDocument, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.clip_detail_transcript),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
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
                                Text(
                                    seg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = READER_LINE_HEIGHT,
                                )
                            }
                        }
                        item { Spacer(Modifier.padding(bottom = 24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Long text reader (translation / transcription without segments / plain‑text summary).
 *
 * Compared to `Column(verticalScroll) { Text(fullText) }`, it solves two issues:
 *
 * 1. **Slow opening.** Placing the entire text in a single `Text` forces a full layout measurement when the dialog opens, causing long passages to exhibit the QA‑reported "2‑second delay when tapping the Japanese translation entry". Splitting by segment into a `LazyColumn` only lays out the currently visible segments, so opening is instantaneous regardless of length.
 * 2. **Too cramped.** CJK (especially Japanese) lacks spaces between words; with default line height the block becomes a solid mass. The increased line and paragraph spacing here improves readability and is intentional, not just extra whitespace.
 */
@Composable
private fun LongTextReader(text: String, modifier: Modifier = Modifier) {
    // Segment by blank lines; ASR output places one sentence per line, and when there are no blank lines it falls back to line‑based segmentation.
    val paragraphs = remember(text) {
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(paragraphs) { _, para ->
            Text(
                para,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = READER_LINE_HEIGHT,
            )
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
    val txt = clip.transcript?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
    return txt?.let { TranscriptDocument(it, emptyList()) }
}

/** Unified representation of the two transcription forms. */
private class TranscriptDocument(val text: String, val segments: List<AsrSegment>)

/**
 * Export: convert to **M4A (AAC)** and hand off to the system share sheet.
 *
 * Why re‑encode: Ogg‑Opus cannot be opened by most third‑party apps; exporting as M4A ensures others can play it.
 * Implemented with `MediaCodec` + `MediaMuxer` (see `audio/AudioExporter.kt`).
 */
@Composable
private fun ExportSection(clip: LocalClip) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionTitleLike(stringResource(R.string.clip_detail_export))
    ActionRow(
        Icons.Filled.IosShare,
        stringResource(if (exporting) R.string.clip_detail_exporting else R.string.clip_detail_export_as_m4a),
        enabled = !exporting,
    ) {
            val src = clip.audio ?: return@ActionRow
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        AudioExporter.exportAsM4a(
                            source = src,
                            outDir = java.io.File(context.cacheDir, "exports"),
                            stem = clip.base,
                        )
                    }
                }
                exporting = false
                result
                    .onSuccess { file ->
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
            }
            }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.clip_detail_export)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun SectionTitleLike(text: String) {
    // Group header: small gray text outside the card, not a divider + bold title.
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Card‑style action row: whole row tappable, left icon, colored text (red for destructive actions). */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                // Left edge of card content = card border + 16
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

/** Hero card: size / duration / date — same source as the library row; avoid duplicating calculations. */
@Composable
private fun HeroCard(clip: LocalClip, ui: NomiViewModel.Ui, vm: NomiViewModel) {
    // Hero card: square play button on the left, large duration text in the middle + gray date and filename, file size at top‑right.
    // "Loaded" and "currently playing" are distinct: when paused the player remains alive, the progress bar must stay, and the button should revert to play.
    val loaded = ui.playing == clip.audio?.name
    val playing = loaded && !ui.playPaused
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (clip.hasAudio) {
                // The hit area must cover the whitespace around the square, not just the drawn 56dp.
                // **clickable must be applied before padding** — reversing the order leaves an 8dp gap
                // where the whitespace falls outside the hit area, resulting in no response to taps.
                Box(
                    Modifier
                        .clickable { clip.audio?.let(vm::togglePlay) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(
                                    if (playing) R.string.live_pause else R.string.clip_play
                                ),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // Stop: the progress bar is permanently inline — without a stop action there is no way to release the player or hide the progress bar,
                // effectively removing an existing capability. It appears only when this item is loaded.
                if (loaded) {
                    IconButton(onClick = vm::stopPlayback) {
                        Icon(
                            Icons.Filled.StopCircle,
                            contentDescription = stringResource(R.string.clip_stop),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                // When duration is unknown, **the entire row is not rendered**.
                // Previously it fell back to "text‑only", which implies the audio has been removed ——
                // labeling a segment that can play but lacks a duration as text‑only is misleading.
                // True "no audio" has its own indicator: the VoiceOverOff icon in the list row.
                clip.durationMs?.let {
                    Text(formatClipDuration(it), style = MaterialTheme.typography.titleLarge)
                }
                clip.recordedAt?.let {
                    Text(
                        // Medium date + short time, localized through the current locale.
                        // "2026-08-04 14:08", which looks different at a glance.
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault()).format(it) +
                            " " +
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    clip.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (clip.hasAudio) {
                Text(
                    formatClipBytes(clip.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Progress bar appears only when loaded. **Do not hide it while paused** — hiding would prevent scrubbing.
    if (loaded && ui.playDurationMs > 0) {
        PlaybackScrubber(ui.playPositionMs, ui.playDurationMs, vm, totalMs = clip.durationMs)
    }
}

/**
 * Playback progress bar — shared among hero card, playback row, and library row; avoid duplicating code.
 *
 * During dragging only update the **local** value, without feeding seek commands to the ViewModel each frame: previously `onValueChange` called `seekTo` every frame while a polling coroutine simultaneously wrote back `playPositionMs`, causing contention and the slider to bounce back.
 * Now pressing starts `beginScrub()` (muting during drag), releasing calls `endScrub()` to set the position and resume as needed.
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
    Text(
        "${formatClipDuration(shown.toInt())} / ${formatClipDuration(totalMs ?: durationMs)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Empty state card: a gray line of text on a white card, not just bare small text. */
@Composable
private fun EmptyCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DestructiveSection(clip: LocalClip, vm: NomiViewModel, onDeletedEverything: () -> Unit) {
    Spacer(Modifier.height(18.dp))
    var pending by remember { mutableStateOf<ClipArtefact?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (clip.hasAudio) {
            ActionRow(
                Icons.Filled.Delete,
                stringResource(R.string.clip_detail_delete_audio),
                destructive = true,
            ) { pending = ClipArtefact.AUDIO }
        }
        if (clip.transcript != null) {
            ActionRow(
                Icons.Filled.Delete,
                stringResource(R.string.clip_detail_delete_transcript),
                destructive = true,
            ) { pending = ClipArtefact.TRANSCRIPT }
        }
        if (clip.summary != null) {
            ActionRow(
                Icons.Filled.Delete,
                stringResource(R.string.clip_detail_delete_summary),
                destructive = true,
            ) { pending = ClipArtefact.SUMMARY }
        }
        Text(
            stringResource(R.string.clip_detail_delete_independent_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Group footnote shares the same vertical line as the card's inner text.
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
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
                    vm.deleteArtefact(clip, what)
                    pending = null
                    // All three items are missing, so the page has nothing to display — close it, don't leave an empty shell.
                    val leftovers = listOfNotNull(
                        clip.audio.takeIf { what != ClipArtefact.AUDIO },
                        clip.transcript.takeIf { what != ClipArtefact.TRANSCRIPT },
                        clip.summary.takeIf { what != ClipArtefact.SUMMARY },
                    )
                    if (leftovers.isEmpty()) onDeletedEverything()
                }) { Text(stringResource(R.string.common_delete)) }
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
