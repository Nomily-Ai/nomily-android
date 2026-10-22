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

/**
 * Summary panel.
 *
 * Pick the **output language** (default "same as transcript", remembered), then a template tile opens its overview;
 * "Use this template" queues the summary as a background task. The sheet can be closed while it runs — the
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

    // The default template (Settings → Templates, long-press) opens pre-selected: one tap on "Use this template".
    // A default that no longer exists is ignored silently.
    var previewing by remember {
        mutableStateOf(cfg.summaryTemplateKey?.let { key -> templates.firstOrNull { it.storeKey == key } })
    }
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
                                    SummarizeState.PICKING -> R.string.summarize_choose_template
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
                    TemplateOverview(overview) {
                        runningTemplate = overview
                        previewing = null
                        result = null
                        if (vm.hasTranscript(clip.base)) {
                            val t = if (overview.isBuiltIn) overview.copy(prompt = localizedTemplatePrompt(context, overview)) else overview
                            jobId = vm.enqueueSummary(clip, t, langName)
                        } else {
                            jobId = "missing"
                        }
                    }
                    return@Column
                }

                // Pre-flight: the primary can't run. Say who will stand in, with a one-tap switch, before a run fails.
                LlmPrimaryGapBanner(cfg, vm)
                // Output language: one line + current value on the right, opening a searchable language list.
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .clickable { showLangPicker = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Language, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.summarize_output_language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    Text(langName ?: sameAsTranscript, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
                TemplateGrid(templates, onOpen = { previewing = it }, defaultKey = cfg.summaryTemplateKey)
            }
        }
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
