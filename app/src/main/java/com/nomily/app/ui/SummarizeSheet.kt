package com.nomily.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.nomily.app.core.llm.SummarizeTemplate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import com.nomily.app.core.llm.storeKey
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.NomiViewModel.LocalClip
import com.nomily.app.R
import com.nomily.app.core.llm.categoriesInOrder
import com.nomily.app.llm.LanguageService
import com.nomily.app.llm.localizedTemplateCategory
import com.nomily.app.llm.localizedTemplateName
import com.nomily.app.llm.localizedTemplatePrompt
import com.nomily.app.llm.localizedTemplateSubtitle
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nomily.app.core.llm.AutoTemplatePicker
import com.nomily.app.core.llm.inCategory

/**
 * Summary panel (iOS `SummarizeSheet`): "Generate note" opens on two modes — **Auto** lets [AutoTemplatePicker]
 * choose the template from the transcript, **Custom** sets template / output language / AI model — then
 * "Generate now" queues the summary as a background task. The template list is split into My Space / For You /
 * Explore; a card selects, its ↗ opens the template's overview. The sheet can be closed while it runs — the
 * result is written to the clip either way — and shows the result when it finishes while open.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SummarizeSheet(clip: LocalClip, vm: NomiViewModel, onDismiss: () -> Unit) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val jobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    var languages by remember { mutableStateOf<List<LanguageService.Language>>(emptyList()) }
    var langCode by remember { mutableStateOf(cfg.lastSummaryLang ?: "") }
    var showLangPicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { languages = vm.targetLanguages() }

    val providerLabel = cfg.llmProviders.primary?.takeIf { it.isNotEmpty() }
        ?.let(com.nomily.app.core.llm.LlmChain::providerLabel) ?: "LLM"
    val langName = languages.firstOrNull { it.code == langCode }?.name
    val sameAsTranscript = stringResource(R.string.summarize_same_as_transcript)

    // The default template (Settings → Templates, long-press) is the custom mode's pre-selection, and the template
    // list opens on the space that holds it. A default that no longer exists is ignored silently.
    val defaultTemplate = remember { cfg.summaryTemplateKey?.let { key -> templates.firstOrNull { it.storeKey == key } } }
    var selected by remember { mutableStateOf(defaultTemplate) }
    var previewing by remember { mutableStateOf<SummarizeTemplate?>(null) }
    var mode by remember { mutableStateOf(GenerateMode.AUTO) }
    var showTemplateList by remember { mutableStateOf(false) }
    var space by remember { mutableStateOf(if (defaultTemplate?.isBuiltIn == true) TemplateSpace.EXPLORE else TemplateSpace.MINE) }
    var editing by remember { mutableStateOf<SummarizeTemplate?>(null) }
    val scope = rememberCoroutineScope()
    var runningTemplate by remember { mutableStateOf<SummarizeTemplate?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    // State comes from the queue ledger, not from a callback: the job often finishes after this sheet is gone.
    val job = jobId?.let { id -> jobs.firstOrNull { it.id == id } }
    val state = when {
        jobId == null -> SummarizeState.PICKING
        job == null -> SummarizeState.FAILED
        job.finishedAt == null -> SummarizeState.RUNNING
        job.lastError != null -> SummarizeState.FAILED
        else -> SummarizeState.DONE
    }
    LaunchedEffect(state) {
        if (state == SummarizeState.DONE) {
            val text = vm.readSummary(clip.base)
            // Finished without a file: the job was cancelled from the task page — nothing to show.
            if (text == null) onDismiss() else result = text
        }
    }
    val errorMsg = when {
        job == null && jobId != null -> stringResource(R.string.jobs_source_missing, stringResource(R.string.jobs_source_transcript))
        else -> job?.lastError
    }
    BackHandler(enabled = previewing != null && state == SummarizeState.PICKING) { previewing = null }
    BackHandler(enabled = previewing == null && showTemplateList && state == SummarizeState.PICKING) { showTemplateList = false }

    fun startSummary(t: SummarizeTemplate) {
        runningTemplate = t
        previewing = null
        showTemplateList = false
        result = null
        jobId = if (vm.hasTranscript(clip.base)) {
            vm.enqueueSummary(clip, if (t.isBuiltIn) t.copy(prompt = localizedTemplatePrompt(context, t)) else t, langName)
        } else {
            "missing"
        }
    }

    fun generateNow() {
        when (mode) {
            GenerateMode.AUTO -> scope.launch {
                val transcript = vm.transcriptText(clip.base).orEmpty()
                AutoTemplatePicker.pick(transcript, templates)?.let { picked ->
                    selected = picked
                    startSummary(picked)
                }
            }
            GenerateMode.CUSTOM -> {
                val t = selected
                if (t != null) startSummary(t) else showTemplateList = true
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Card-style overlay: top leaves space for the status bar, exposing the parent view; top corners are rounded
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                val overview = previewing.takeIf { state == SummarizeState.PICKING }
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            overview?.let { localizedTemplateName(context, it.name, it.isBuiltIn) } ?: stringResource(
                                when (state) {
                                    SummarizeState.PICKING ->
                                        if (showTemplateList) R.string.summarize_choose_template else R.string.summarize_generate_notes
                                    SummarizeState.RUNNING -> R.string.summarize_summarizing
                                    SummarizeState.DONE -> R.string.clip_detail_summary
                                    SummarizeState.FAILED -> R.string.summarize_error
                                },
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (overview != null) {
                            TextButton(onClick = { previewing = null }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(20.dp))
                                Text(stringResource(R.string.summarize_back_to_templates))
                            }
                        } else if (showTemplateList && state == SummarizeState.PICKING) {
                            TextButton(onClick = { showTemplateList = false }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(20.dp))
                                Text(stringResource(R.string.summarize_generate_notes))
                            }
                        } else {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(if (state == SummarizeState.RUNNING) R.string.common_close else R.string.common_cancel))
                            }
                        }
                    },
                    actions = {
                        if (state == SummarizeState.DONE) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )

                when (state) {
                    SummarizeState.RUNNING -> {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(36.dp))
                            Text(
                                stringResource(R.string.summarize_summarizing_with, providerLabel),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            // A long transcript runs for minutes; a changing number reads very differently from a spinner.
                            if (job?.progressDone != null && job.progressTotal != null) {
                                Text(
                                    stringResource(R.string.summarize_chunk_progress, job.progressDone, job.progressTotal),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            runningTemplate?.let { t ->
                                Text(
                                    localizedTemplateName(context, t.name, t.isBuiltIn),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.outline)
                            // Closing doesn't stop it — say so, or people sit and wait.
                            Text(
                                stringResource(R.string.jobs_can_leave_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 10.dp)) {
                                Text(stringResource(R.string.common_close))
                            }
                            // Close only leaves; this stops the job (same as the task page) and leaves too — nothing to wait for.
                            OutlinedButton(
                                onClick = {
                                    jobId?.let { vm.jobs.cancel(it) }
                                    onDismiss()
                                },
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                        return@Column
                    }
                    SummarizeState.DONE -> {
                        // Hitting the token limit yields a truncated summary; it must not look complete.
                        if (job?.wasTruncated == true) {
                            Text(
                                stringResource(R.string.summarize_truncated_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9500),
                                modifier = Modifier.fillMaxWidth().background(Color(0xFFFF9500).copy(alpha = 0.12f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        // A fallback provider stood in, or the transcript was chunked: say so, don't hide the config state.
                        job?.notes?.takeIf { it.isNotEmpty() }?.let { LlmNotices(it) }
                        MarkdownView(result.orEmpty(), Modifier.fillMaxSize().padding(horizontal = 16.dp))
                        return@Column
                    }
                    SummarizeState.FAILED -> {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Failures/degradations are always orange (red is reserved only for destructive actions and “recording”)
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(40.dp))
                            Text(
                                errorMsg.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            TextButton(onClick = { jobId = null }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.common_try_again))
                            }
                        }
                        return@Column
                    }
                    SummarizeState.PICKING -> Unit
                }

                if (overview != null) {
                    TemplateOverview(overview) { startSummary(overview) }
                    return@Column
                }

                // Pre-flight: the primary can't run. Say who will stand in, with a one-tap switch, before a run fails.
                LlmPrimaryGapBanner(cfg, vm)
                val pickLanguage = { showLangPicker = true }
                if (showTemplateList) {
                    TemplateSpaces(
                        templates = templates,
                        space = space,
                        onSpace = { space = it },
                        selected = selected,
                        onSelect = { selected = it },
                        onPreview = { previewing = it },
                        onCreate = {
                            editing = SummarizeTemplate(
                                id = "custom-${System.nanoTime()}",
                                category = templates.firstOrNull()?.category ?: "General Summary",
                                name = "",
                                prompt = "",
                                isBuiltIn = false,
                            )
                        },
                        onEdit = { editing = it },
                        onDelete = { t ->
                            vm.updateTemplates { list -> list.filterNot { it.id == t.id } }
                            if (cfg.summaryTemplateKey == t.storeKey) vm.updateConfig { it.copy(summaryTemplateKey = null) }
                            if (selected?.id == t.id) selected = null
                        },
                        options = { GenerateOptions(langName ?: sameAsTranscript, providerLabel, cfg, vm, onLanguage = pickLanguage, template = null) },
                        onGenerate = { selected?.let(::startSummary) },
                    )
                } else {
                    GeneratePanel(
                        mode = mode,
                        onMode = { mode = it },
                        options = {
                            GenerateOptions(
                                langName ?: sameAsTranscript, providerLabel, cfg, vm, onLanguage = pickLanguage,
                                template = selected?.let { localizedTemplateName(context, it.name, it.isBuiltIn) }
                                    ?: stringResource(R.string.summarize_choose_template),
                                onTemplate = { showTemplateList = true },
                            )
                        },
                        onGenerate = ::generateNow,
                    )
                }
            }
        }
    }

    editing?.let { t ->
        TemplateEditor(
            allCategories = templates.categoriesInOrder(),
            builtInCategories = templates.filter { it.isBuiltIn }.map { it.category }.toSet(),
            template = t,
            onCancel = { editing = null },
            onSave = { saved ->
                vm.updateTemplates { list ->
                    if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved
                }
                selected = saved
                space = TemplateSpace.MINE
                editing = null
            },
        )
    }

    if (showLangPicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.summarize_output_language),
            languages = languages,
            defaultOption = sameAsTranscript,
            onPick = { code ->
                langCode = code
                vm.updateConfig { it.copy(lastSummaryLang = code.takeIf { c -> c.isNotEmpty() }) }
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false },
        )
    }
}

private enum class GenerateMode { AUTO, CUSTOM }

/**
 * The template list's three spaces (Plaud's split): the user's own templates, a curated shelf, and the full
 * library by category.
 */
private enum class TemplateSpace(val titleRes: Int) {
    MINE(R.string.summarize_space_mine),
    RECOMMENDED(R.string.summarize_space_recommended),
    EXPLORE(R.string.summarize_space_explore),
}

/** Editors' Picks: the everyday four plus the formats most recordings fall into. */
private val EDITOR_PICK_NAMES = listOf(
    "Quick Summary", "Meeting Minutes", "Key Points", "To-Do List",
    "Formal Meeting Minutes", "BANT Customer Analysis", "Interview Summary", "Lecture Notes",
)

/** Inspiration: formats that turn a recording into something other than prose. */
private val INSPIRATION_NAMES = listOf(
    "Key Data Points", "Q&A Format", "Brainstorm Synthesis", "Decision Log", "Daily Log", "Long Article to Slide Deck Outline",
)

/** Auto / Custom, then "Generate now" — Plaud's generate sheet, same order. */
@Composable
private fun GeneratePanel(mode: GenerateMode, onMode: (GenerateMode) -> Unit, options: @Composable () -> Unit, onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModeCard(
                title = stringResource(R.string.summarize_auto_generate),
                subtitle = stringResource(R.string.summarize_auto_generate_desc),
                icon = Icons.Filled.AutoAwesome,
                tint = Color(0xFF7373F2),
                selected = mode == GenerateMode.AUTO,
            ) { onMode(GenerateMode.AUTO) }
            ModeCard(
                title = stringResource(R.string.summarize_custom_generate),
                subtitle = null,
                icon = Icons.Filled.Tune,
                tint = MaterialTheme.colorScheme.onSurface,
                selected = mode == GenerateMode.CUSTOM,
            ) { onMode(GenerateMode.CUSTOM) }
            if (mode == GenerateMode.CUSTOM) options()
        }
        BottomActionBar(stringResource(R.string.summarize_generate_now), enabled = true, onClick = onGenerate)
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), shape)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(22.dp))
    }
}

/**
 * Template / output language / AI model — the rows that do something; there is no speaker-labelling switch to
 * fake. [template] null leaves the template row out (the template list itself is where one is picked). The model
 * row sets the primary provider, the same field as in Settings, so this run and the next use it.
 */
@Composable
private fun GenerateOptions(
    language: String,
    providerLabel: String,
    cfg: com.nomily.app.core.config.AppConfig,
    vm: NomiViewModel,
    onLanguage: () -> Unit,
    template: String?,
    onTemplate: () -> Unit = {},
) {
    var modelMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))) {
        if (template != null) {
            OptionRow(stringResource(R.string.summarize_template_row), template, onTemplate)
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline)
        }
        OptionRow(stringResource(R.string.summarize_output_language), language, onLanguage)
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline)
        Box {
            OptionRow(stringResource(R.string.summarize_ai_model), providerLabel) { modelMenu = true }
            androidx.compose.material3.DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
                cfg.llmProviders.configuredProviders.forEach { (key, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = { if (key == cfg.llmProviders.primary) Icon(Icons.Filled.Check, null) },
                        onClick = {
                            modelMenu = false
                            vm.updateConfig { it.copy(llmProviders = it.llmProviders.copy(primary = key)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

/** The primary button pinned under a scrolling sheet body. */
@Composable
private fun BottomActionBar(label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The template list (iOS template spaces): search, the My Space / For You / Explore strip, two-column cards and a
 * "Generate note" bar that runs the selected card. Cards credit "Nomily" — there is no marketplace behind them,
 * so no invented authors or usage counts.
 */
@Composable
private fun TemplateSpaces(
    templates: List<SummarizeTemplate>,
    space: TemplateSpace,
    onSpace: (TemplateSpace) -> Unit,
    selected: SummarizeTemplate?,
    onSelect: (SummarizeTemplate) -> Unit,
    onPreview: (SummarizeTemplate) -> Unit,
    onCreate: () -> Unit,
    onEdit: (SummarizeTemplate) -> Unit,
    onDelete: (SummarizeTemplate) -> Unit,
    options: @Composable () -> Unit,
    onGenerate: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var search by remember { mutableStateOf("") }
    val q = search.trim().lowercase()
    fun matches(t: SummarizeTemplate) = q.isEmpty() ||
        localizedTemplateName(context, t.name, t.isBuiltIn).lowercase().contains(q) ||
        t.name.lowercase().contains(q) || t.category.lowercase().contains(q) ||
        (t.summary != null && localizedTemplateSubtitle(context, t).lowercase().contains(q))
    fun named(names: List<String>) = names.mapNotNull { n -> templates.firstOrNull { it.name == n && it.isBuiltIn } }.filter(::matches)

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text(stringResource(R.string.summarize_search_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TemplateSpace.entries.forEach { s ->
                UnderlineTab(stringResource(s.titleRes), selected = space == s, modifier = Modifier.weight(1f)) { onSpace(s) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            fun section(key: String, title: String, count: Int?, list: List<SummarizeTemplate>) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h:$key") { SpaceHeader(title, count) }
                items(list, key = { "$key:${it.id}" }) { t ->
                    SpaceCard(t, t.storeKey == selected?.storeKey, { onSelect(t) }, { onPreview(t) }, onEdit, onDelete)
                }
            }
            when (space) {
                TemplateSpace.MINE -> {
                    val mine = templates.filter { !it.isBuiltIn && matches(it) }
                    section("mine", context.getString(R.string.summarize_my_templates), mine.size, mine)
                    // Always there, even with no templates yet: creating them is the point of the space.
                    item(key = "create") { CreateTemplateCard(onCreate) }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "options") {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            options()
                            Text(
                                stringResource(R.string.summarize_mine_space_footer),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                TemplateSpace.RECOMMENDED -> {
                    named(EDITOR_PICK_NAMES).takeIf { it.isNotEmpty() }?.let { section("picks", context.getString(R.string.summarize_editors_picks), null, it) }
                    named(INSPIRATION_NAMES).takeIf { it.isNotEmpty() }?.let { section("ideas", context.getString(R.string.summarize_inspiration), null, it) }
                }
                TemplateSpace.EXPLORE -> templates.categoriesInOrder().forEach { category ->
                    val inCategory = templates.inCategory(category).filter(::matches)
                    if (inCategory.isNotEmpty()) {
                        section("c:$category", localizedTemplateCategory(context, category, inCategory.any { it.isBuiltIn }), null, inCategory)
                    }
                }
            }
        }
        BottomActionBar(stringResource(R.string.summarize_generate_notes), enabled = selected != null, onClick = onGenerate)
    }
}

@Composable
private fun SpaceHeader(title: String, count: Int?) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        count?.let { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
}

/**
 * One template card: icon, name, a line of "what this is for", and the ↗ that opens the overview. A tap selects —
 * generating is its own tap on the bar below. Custom templates get Edit / Delete on long-press.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SpaceCard(
    t: SummarizeTemplate,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onEdit: (SummarizeTemplate) -> Unit,
    onDelete: (SummarizeTemplate) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    var menuOpen by remember { mutableStateOf(false) }
    val subtitle = remember(t) {
        if (t.summary != null) localizedTemplateSubtitle(context, t) else localizedTemplatePrompt(context, t).trim().take(56) + "..."
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), shape)
            .combinedClickable(onClick = onSelect, onLongClick = if (t.isBuiltIn) null else ({ menuOpen = true }))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(30.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(templateIcon(t), null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.OpenInFull,
                contentDescription = stringResource(R.string.summarize_preview_template),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onPreview).padding(4.dp).size(12.dp),
            )
        }
        Text(
            localizedTemplateName(context, t.name, t.isBuiltIn),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!t.isBuiltIn) Badge(stringResource(R.string.summarize_custom_badge), accent)
            Spacer(Modifier.weight(1f))
            // The product's own name where Plaud prints an author.
            Text("Nomily", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
        }
        if (!t.isBuiltIn) {
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_edit)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menuOpen = false; onEdit(t) },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete(t) },
                )
            }
        }
    }
}

/** The dashed "Create Template" card. */
@Composable
private fun CreateTemplateCard(onClick: () -> Unit) {
    val dash = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.EditNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.summarize_create_template), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card glyph per category, so every built-in gets a recognisable mark; custom templates without a known category get a pencil. */
private fun templateIcon(t: SummarizeTemplate): androidx.compose.ui.graphics.vector.ImageVector = when (t.category) {
    "Meeting Notes" -> Icons.Outlined.Event
    "Action Items" -> Icons.Outlined.Checklist
    "General Summary" -> Icons.Outlined.Description
    "Professional" -> Icons.Outlined.Work
    "Academic" -> Icons.Outlined.School
    "Creative" -> Icons.Outlined.Brush
    "Speech" -> Icons.Outlined.GraphicEq
    "Call" -> Icons.Outlined.Phone
    "Interview" -> Icons.Outlined.Badge
    "Medical" -> Icons.Outlined.MedicalServices
    "Sales" -> Icons.AutoMirrored.Outlined.TrendingUp
    "Consulting" -> Icons.Outlined.Lightbulb
    "Education" -> Icons.AutoMirrored.Outlined.MenuBook
    "Construction" -> Icons.Outlined.Construction
    "IT & Engineering" -> Icons.Outlined.Memory
    "Legal" -> Icons.Outlined.Gavel
    "Real Estate" -> Icons.Outlined.Apartment
    "Finance" -> Icons.Outlined.Payments
    "Utility" -> Icons.Outlined.Build
    "Workplace" -> Icons.Outlined.Groups
    else -> if (t.isBuiltIn) Icons.Outlined.Description else Icons.Outlined.EditNote
}

/** Template filter chip: the curated built-ins, the user's own, or one category. */
private sealed interface TemplateFilter {
    data object Recommended : TemplateFilter
    data object Mine : TemplateFilter
    data class Category(val name: String) : TemplateFilter
}

/**
 * Templates as tiles by category, with a search field. A tile opens the template's overview; nothing runs from
 * the grid itself. Shared by the summary sheet and the template settings page.
 *
 * @param defaultKey [storeKey] of the default template, which gets a "Default" badge.
 * @param filterChips Show the Recommended / Mine / per-category chip row (settings page).
 * @param longPressMenu Items of the tile's long-press menu (label to action); none = no menu.
 */
@Composable
fun TemplateGrid(
    templates: List<SummarizeTemplate>,
    onOpen: (SummarizeTemplate) -> Unit,
    defaultKey: String? = null,
    filterChips: Boolean = false,
    longPressMenu: ((SummarizeTemplate) -> List<Pair<String, () -> Unit>>)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<TemplateFilter>(TemplateFilter.Recommended) }
    TextField(
        value = search,
        onValueChange = { search = it },
        placeholder = { Text(stringResource(R.string.summarize_search_templates), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (filterChips) {
        val chips = listOf(
            TemplateFilter.Recommended to stringResource(R.string.summarize_chip_recommended),
            TemplateFilter.Mine to stringResource(R.string.summarize_chip_custom),
        ) + templates.categoriesInOrder().map { c ->
            TemplateFilter.Category(c) to localizedTemplateCategory(context, c, templates.any { it.category == c && it.isBuiltIn })
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chips.size) { i ->
                val (value, label) = chips[i]
                val on = filter == value
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                        .clickable { filter = value }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
    val shown = remember(search, templates, filter, filterChips) {
        val base = if (!filterChips) templates else when (val f = filter) {
            TemplateFilter.Recommended -> templates.filter { it.isBuiltIn }
            TemplateFilter.Mine -> templates.filter { !it.isBuiltIn }
            is TemplateFilter.Category -> templates.filter { it.category == f.name }
        }
        val q = search.trim().lowercase()
        if (q.isEmpty()) base else base.filter {
            localizedTemplateName(context, it.name, it.isBuiltIn).lowercase().contains(q) ||
                it.name.lowercase().contains(q) || it.category.lowercase().contains(q) ||
                // The one-line description too, so "interview" finds templates that only mention it there.
                (it.summary != null && localizedTemplateSubtitle(context, it).lowercase().contains(q))
        }
    }
    if (shown.isEmpty()) {
        EmptyState(R.string.summarize_no_matching_templates, R.string.summarize_search_templates, Icons.Filled.Search)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        shown.categoriesInOrder().forEach { category ->
            val inCategory = shown.filter { it.category == category }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:$category") {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        localizedTemplateCategory(context, category, inCategory.any { it.isBuiltIn }),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.summarize_template_count, inCategory.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            items(inCategory, key = { it.id }) { t ->
                TemplateTile(t, isDefault = defaultKey != null && t.storeKey == defaultKey, onClick = { onOpen(t) }, menu = longPressMenu?.let { m -> { m(t) } })
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TemplateTile(
    t: SummarizeTemplate,
    isDefault: Boolean,
    onClick: () -> Unit,
    menu: (() -> List<Pair<String, () -> Unit>>)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 88.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = menu?.let { { menuOpen = true } })
            .padding(12.dp),
    ) {
        if (menu != null) {
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu().forEach { (label, action) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (t.isBuiltIn) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Filled.Edit,
                contentDescription = null,
                tint = if (t.isBuiltIn) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.weight(1f))
            if (isDefault) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Text(
                    stringResource(R.string.summarize_default_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                )
            }
            if (!t.isBuiltIn) Badge(stringResource(R.string.summarize_custom_badge), MaterialTheme.colorScheme.primary)
        }
        Text(
            localizedTemplateName(context, t.name, t.isBuiltIn),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Badge(text: String, tint: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** What a template is and what it asks the model — read before running it. [onUse] is the only way to start. */
@Composable
fun TemplateOverview(t: SummarizeTemplate, useLabel: String = stringResource(R.string.summarize_use_template), onUse: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (t.isBuiltIn) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Filled.Edit, null,
                        tint = if (t.isBuiltIn) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(localizedTemplateName(context, t.name, t.isBuiltIn), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(localizedTemplateCategory(context, t.category, t.isBuiltIn), MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.isBuiltIn) Badge(stringResource(R.string.summarize_built_in_badge), MaterialTheme.colorScheme.onSurfaceVariant)
                        else Badge(stringResource(R.string.summarize_custom_badge), MaterialTheme.colorScheme.primary)
                    }
                }
            }
            localizedTemplateSubtitle(context, t).takeIf { t.summary != null && it.isNotBlank() }?.let {
                LabelledBlock(stringResource(R.string.summarize_template_about), it, monospace = false)
            }
            LabelledBlock(stringResource(R.string.summarize_template_prompt_label), localizedTemplatePrompt(context, t), monospace = true)
        }
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                Text(useLabel, Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun LabelledBlock(label: String, text: String, monospace: Boolean) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}

/** Summarizes the four states of a sheet. */
private enum class SummarizeState { PICKING, RUNNING, DONE, FAILED }

/** Notes under a result: fallback provider used / transcript chunked. */
@Composable
fun LlmNotices(notes: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        notes.forEach { note ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(14.dp),
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * The primary LLM provider is incomplete: say what's missing and who stands in, plus a one-tap switch to that
 * fallback. Nothing when the primary is fine. With no fallback at all it just says no provider can run.
 */
@Composable
fun LlmPrimaryGapBanner(cfg: com.nomily.app.core.config.AppConfig, vm: NomiViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val chain = com.nomily.app.core.llm.LlmChain
    val gap = chain.primaryGapWithFallback(cfg)
    val message = when {
        gap != null -> {
            val text = com.nomily.app.llm.LlmClient.gapText(context, gap.first)
            gap.second?.let { stringResource(R.string.summarize_preflight_fallback, text, chain.providerLabel(it)) } ?: text
        }
        chain.candidateChain(cfg).isEmpty() -> stringResource(R.string.llm_error_no_provider)
        else -> return
    }
    val orange = Color(0xFFFF9500)
    Column(
        Modifier.fillMaxWidth().background(orange.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = orange, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = orange, modifier = Modifier.padding(start = 6.dp))
        }
        chain.suggestedPrimary(cfg)?.let { fix ->
            OutlinedButton(
                onClick = { vm.updateConfig { it.copy(llmProviders = it.llmProviders.copy(primary = fix)) } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.padding(top = 6.dp).heightIn(min = 30.dp),
            ) {
                Text(stringResource(R.string.llm_adopt_as_primary, chain.providerLabel(fix)), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Language selection.
 *
 * Navigation bar (left "Cancel" + centered title), **search box**, embedded cards on a gray background;
 * "Follow original text" is a separate card placed at the top; each row shows "Language name ... language code in monospace font on the right".
 * Previously, there was no search box (over a hundred languages required scrolling), the title was left-aligned, and the rows displayed local language names instead of codes.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerDialog(
    title: String,
    languages: List<LanguageService.Language>,
    defaultOption: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, languages) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) {
            languages
        } else {
            languages.filter {
                it.name.lowercase().contains(q) || it.code.lowercase().contains(q) ||
                    it.nativeName.lowercase().contains(q)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card-style overlay: top leaves space for the status bar, exposing the parent view; top corners are rounded
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(title) },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.live_search_languages),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    defaultOption?.let { label ->
                        item {
                            Card(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(16.dp),
                                )
                            }
                        }
                    }
                    item {
                        Card(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            filtered.forEachIndexed { i, lang ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPick(lang.code) }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        lang.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        lang.code,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (i < filtered.lastIndex) {
                                    HorizontalDivider(
                                        Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
