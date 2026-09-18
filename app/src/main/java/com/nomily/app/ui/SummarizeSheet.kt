package com.nomily.app.ui

import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.nomily.app.core.llm.SummarizeTemplate
import androidx.compose.material.icons.filled.Edit
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

/**
 * Summary panel.
 *
 * First select **output language** (default "follow original", selection will be remembered), then click a template to start;
 * After running, render the Markdown ([MarkdownView]), verify it is correct before saving.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SummarizeSheet(clip: LocalClip, vm: NomiViewModel, onDismiss: () -> Unit) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    var languages by remember { mutableStateOf<List<LanguageService.Language>>(emptyList()) }
    var langCode by remember { mutableStateOf(cfg.lastSummaryLang ?: "") }
    var showLangPicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { languages = vm.targetLanguages() }

    val providerLabel = cfg.llmProviders.primary?.replaceFirstChar { it.uppercase() } ?: "LLM"
    val langName = languages.firstOrNull { it.code == langCode }?.name
    val sameAsTranscript = stringResource(R.string.summarize_same_as_transcript)

    // Four states: select template → generating → result → (failure).
    // **Not persisted after generation**; it stays in the result state until the user clicks the top‑right “Save”, which then writes it to the database;
    // During generation or when unsaved, swiping away / pressing the back key is prohibited (swiping would waste a model run and silently discard the result).
    var state by remember { mutableStateOf(SummarizeState.PICKING) }
    var result by remember { mutableStateOf("") }
    // The result was truncated due to hitting the token limit — show an orange notice at the top of the page so a partial summary doesn't appear complete.
    var truncated by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var runningTemplate by remember { mutableStateOf<SummarizeTemplate?>(null) }
    val scope = rememberCoroutineScope()
    val guard = state == SummarizeState.RUNNING || state == SummarizeState.DONE

    Dialog(
        onDismissRequest = { if (!guard) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !guard,
            dismissOnClickOutside = !guard,
        ),
    ) {
        // Card-style overlay: top leaves space for the status bar, exposing the parent view; top corners are rounded
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Navigation bar: left 「Cancel」 + centered title (title changes with state); 「Save」 appears only in result state
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            stringResource(
                                when (state) {
                                    SummarizeState.PICKING -> R.string.summarize_choose_template
                                    SummarizeState.RUNNING -> R.string.summarize_summarizing
                                    SummarizeState.DONE -> R.string.clip_detail_summary
                                    SummarizeState.FAILED -> R.string.summarize_error
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    },
                    actions = {
                        if (state == SummarizeState.DONE) {
                            TextButton(onClick = {
                                vm.saveSummary(clip, result, langName)
                                onDismiss()
                            }) { Text(stringResource(R.string.common_save)) }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )

                when (state) {
                    SummarizeState.RUNNING -> {
                        Column(
                            Modifier.fillMaxSize(),
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
                            runningTemplate?.let { t ->
                                Text(
                                    localizedTemplateName(context, t.name, t.isBuiltIn),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        return@Column
                    }
                    SummarizeState.DONE -> {
                        // Hitting the token limit yields a **truncated summary**. It used to look exactly the
                        // same as the full result: users saved it, and the quota was still deducted anyway.
                        if (truncated) {
                            Text(
                                stringResource(R.string.summarize_truncated_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9500),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        MarkdownView(result, Modifier.fillMaxSize().padding(horizontal = 16.dp))
                        return@Column
                    }
                    SummarizeState.FAILED -> {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Failures/degradations are always orange (red is reserved only for destructive actions and “recording”)
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF9500),
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                errorMsg ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            TextButton(
                                onClick = { state = SummarizeState.PICKING },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text(stringResource(R.string.common_try_again)) }
                        }
                        return@Column
                    }
                    SummarizeState.PICKING -> Unit
                }
                // This summary sheet has search functionality.
                // The one without search is the template management page; do not confuse the two interfaces. Filter by name/category.
                var search by remember { mutableStateOf("") }
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.summarize_search_templates),
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
                val shown = remember(search, templates) {
                    val q = search.trim().lowercase()
                    if (q.isEmpty()) {
                        templates
                    } else {
                        templates.filter {
                            localizedTemplateName(context, it.name, it.isBuiltIn).lowercase().contains(q) ||
                                it.name.lowercase().contains(q) || it.category.lowercase().contains(q)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    // Output language: one line + current value on the right (click to open a searchable language list), selected options are saved to config
                    item {
                        Card(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { showLangPicker = true }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.summarize_output_language),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    langName ?: sameAsTranscript,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    // Templates are categorized into cards, with inline display of "name + two-line prompt preview" — consistent with the template management page's visual style
                    shown.categoriesInOrder().forEach { category ->
                        item {
                            Text(
                                // Built-in categories/template names use localization
                                localizedTemplateCategory(
                                    context, category,
                                    shown.any { it.category == category && it.isBuiltIn },
                                ).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                            )
                        }
                        item {
                            val inCategory = shown.filter { it.category == category }
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            ) {
                                inCategory.forEachIndexed { i, t ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                runningTemplate = t
                                                state = SummarizeState.RUNNING
                                                val runtimeTemplate = if (t.isBuiltIn) {
                                                    t.copy(prompt = localizedTemplatePrompt(context, t))
                                                } else t
                                                scope.launch {
                                                    runCatching { vm.generateSummary(clip, runtimeTemplate, langName) }
                                                        .onSuccess {
                                                            result = it.text
                                                            truncated = it.truncated
                                                            state = SummarizeState.DONE
                                                        }
                                                        .onFailure { e ->
                                                            errorMsg = e.message
                                                            state = SummarizeState.FAILED
                                                        }
                                                }
                                            }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                    // Built-in = gray document icon, Custom = accent color pencil
                                    Icon(
                                        if (t.isBuiltIn) {
                                            Icons.AutoMirrored.Filled.InsertDriveFile
                                        } else {
                                            Icons.Filled.Edit
                                        },
                                        contentDescription = null,
                                        tint = if (t.isBuiltIn) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(
                                            localizedTemplateName(context, t.name, t.isBuiltIn),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            localizedTemplatePrompt(context, t).replace(Regex("\\s+"), " ").trim(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                    }
                                    if (i < inCategory.lastIndex) {
                                        HorizontalDivider(
                                            Modifier.padding(start = 16.dp),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
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

/** Summarizes the four states of a sheet. */
private enum class SummarizeState { PICKING, RUNNING, DONE, FAILED }

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
