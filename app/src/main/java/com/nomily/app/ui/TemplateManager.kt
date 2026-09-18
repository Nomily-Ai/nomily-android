package com.nomily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.core.llm.SummarizeTemplate
import com.nomily.app.core.llm.categoriesInOrder
import com.nomily.app.core.llm.inCategory
import com.nomily.app.llm.PROMPT_MAX_CHARACTERS
import com.nomily.app.llm.localizedTemplateCategory
import com.nomily.app.llm.localizedTemplateName
import com.nomily.app.llm.localizedTemplatePrompt

/**
 * Summary template management: browse by category, clone, edit, delete custom templates, restore defaults.
 *
 * **Built-in templates cannot be edited or deleted**: to change one, clone it first and edit your own copy —
 * otherwise, once the built-in prompts get updated, the user's modified copy would either be overwritten or stuck on the old version forever.
 */
/**
 * Summary template management.
 *
 * Layout (verified via WDA screenshots on 2026-08-06): a **pushed page** (back + centered title + `⋯` at top right);
 * content is **cards grouped by category**, each row being "document icon + name + two-line gray prompt preview";
 * a separate card at the bottom is "+ Add template"; "Restore default templates" is tucked into the top-right `⋯`.
 *
 * Previously it was a full-screen dialog + search box + two text buttons per row (copy/delete) + a persistent "Restore default templates" at the bottom —
 * same functionality, but two completely different UIs. **The search box is removed along with it**: a dozen or so templates don't need one.
 *
 * **Built-in templates cannot be edited or deleted**: tapping a built-in row = clone a copy to modify,
 * tapping a custom row = edit directly; custom rows are **deleted via left swipe**.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TemplateManagerPage(vm: NomiViewModel, onPush: (PushedPage?) -> Unit, onDismiss: () -> Unit) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SummarizeTemplate?>(null) }
    var previewing by remember { mutableStateOf<SummarizeTemplate?>(null) }
    var pendingDelete by remember { mutableStateOf<SummarizeTemplate?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val title = stringResource(R.string.settings_summarize_templates)
    val restoreLabel = stringResource(R.string.summarize_restore_defaults)
    val addLabel = stringResource(R.string.summarize_add_template)
    LaunchedEffect(title) {
        onPush(
            PushedPage(
                title = title,
                actions = {
                    // Move "Add Template" to the top bar: when there are many templates, the entry at the bottom of the original list requires scrolling to reach.
                    // (izbbpcq). Remove the "Add" card below to avoid having two redundant entries.
                    IconButton(onClick = {
                        editing = SummarizeTemplate(
                            id = "custom-${System.nanoTime()}",
                            category = vm.templates.value.firstOrNull()?.category ?: "General Summary",
                            name = "",
                            prompt = "",
                            isBuiltIn = false,
                        )
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = addLabel)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(restoreLabel) },
                            onClick = { menuOpen = false; confirmReset = true },
                        )
                    }
                },
                onBack = onDismiss,
            )
        )
    }
    DisposableEffect(Unit) { onDispose { onPush(null) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        templates.categoriesInOrder().forEach { category ->
            item {
                Text(
                    localizedTemplateCategory(
                        context, category,
                        templates.any { it.category == category && it.isBuiltIn },
                    ).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                )
            }
            item {
                val inCategory = templates.inCategory(category)
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    inCategory.forEachIndexed { i, t ->
                        TemplateRow(
                            name = localizedTemplateName(context, t.name, t.isBuiltIn),
                            prompt = localizedTemplatePrompt(context, t),
                            deletable = !t.isBuiltIn,
                            onDelete = { pendingDelete = t },
                        ) {
                            // Custom templates also enter read-only preview first (with Markdown / raw text toggle),
                            // The edit entry is moved into the preview page — previously it went directly into the plain text editor,
                            // Users couldn't see what their custom templates looked like in terms of layout (ijh28l8).
                            previewing = t
                        }
                        if (i < inCategory.lastIndex) {
                            HorizontalDivider(
                                // The left end of the divider aligns with the title text:
                                // Inline padding 16 + icon 16 + spacing 12 = 44
                                Modifier.padding(start = 44.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
        // The new entry is only in the top bar (izbbpcq), so we don't repeat an Add card here.
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { t ->
        TemplateEditor(
            allCategories = templates.categoriesInOrder(),
            builtInCategories = templates.filter { it.isBuiltIn }.map { it.category }.toSet(),
            template = t,
            onCancel = { editing = null },
            onSave = { saved ->
                vm.updateTemplates { list ->
                    if (list.any { it.id == saved.id }) {
                        list.map { if (it.id == saved.id) saved else it }
                    } else {
                        list + saved
                    }
                }
                editing = null
            },
        )
    }

    previewing?.let { t ->
        TemplatePreview(
            template = t,
            onCancel = { previewing = null },
            // Whether the category name is built-in: check if there are built-in templates under this category — do not use the template itself.
            // isBuiltIn check: when a custom template is attached to a built-in category, the category name falls back to the original English name.
            categoryIsBuiltIn = templates.any { it.category == t.category && it.isBuiltIn },
            onEdit = { previewing = null; editing = t },
            onClone = {
                editing = t.copy(
                    id = "custom-${System.nanoTime()}",
                    name = context.getString(
                        R.string.summarize_copy_name,
                        localizedTemplateName(context, t.name, true),
                    ),
                    prompt = localizedTemplatePrompt(context, t),
                    isBuiltIn = false,
                )
                previewing = null
            },
        )
    }

    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.summarize_delete_title)) },
            text = { Text(stringResource(R.string.summarize_delete_message, localizedTemplateName(context, t.name, false))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateTemplates { list -> list.filterNot { it.id == t.id } }
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.summarize_restore_defaults_title)) },
            text = { Text(stringResource(R.string.summarize_restore_defaults_message)) },
            confirmButton = {
                TextButton(onClick = { vm.resetTemplates(); confirmReset = false }) {
                    Text(stringResource(R.string.summarize_restore_defaults))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** One-line template: document icon + name + two-line gray hint preview; supports custom left-swipe to delete. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TemplateRow(
    name: String,
    prompt: String,
    deletable: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val row = @Composable {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick).padding(16.dp),
            // The document icon is vertically centered in the entire row, not top-aligned
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Prompts contain newlines; inserting them directly into a two-line preview consumes a blank line first — compress into a single paragraph before truncating
                    prompt.replace(Regex("\\s+"), " ").trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (!deletable) {
        row()
        return
    }
    // When scrolled to the bottom, show the confirmation dialog and **always reject dismissal** (return false), as the row will automatically slide back.
    // Previously, allowing the EndToStart state to settle and waiting for the user to tap the red background again caused issues: the row appeared to be gone, but nothing was actually deleted, leading users to believe they had accidentally deleted something.
    // We cannot use LaunchedEffect + reset() here either, because when the dialog appears, the row recomposes, interrupting the reset animation and leaving the row stuck in the swiped-open state.
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.common_delete),
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) { row() }
}

@Composable
private fun TemplatePreview(
    template: SummarizeTemplate,
    categoryIsBuiltIn: Boolean,
    onCancel: () -> Unit,
    /** Entry point for editing custom templates. Built-in templates are not editable, and the top-right corner still shows "Clone". */
    onEdit: () -> Unit,
    onClone: () -> Unit,
) {
    val context = LocalContext.current
    val prompt = localizedTemplatePrompt(context, template)
    // The prompt body itself is Markdown (headings / lists / tables), which appears as
    // raw source symbols like `##` and `|---|` when rendered as plain text. By default,
    // it is rendered, with an "original text" toggle provided for easy copying of the
    // prompt (j2092e6); the cloned version always uses the original text, unaffected
    // by this display mode.
    var showRaw by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            localizedTemplateName(context, template.name, template.isBuiltIn),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            localizedTemplateCategory(context, template.category, categoryIsBuiltIn),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showRaw = !showRaw }) {
                        Icon(
                            if (showRaw) Icons.Filled.Article else Icons.Filled.Code,
                            contentDescription = null,
                        )
                    }
                    if (template.isBuiltIn) {
                        TextButton(onClick = onClone) { Text(stringResource(R.string.summarize_clone)) }
                    } else {
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_done)) }
                }
                if (showRaw) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    MarkdownView(prompt, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Template editor: Category / Name / Prompt fields. */
@Composable
private fun TemplateEditor(
    template: SummarizeTemplate,
    /** Existing categories, for "picking from existing categories". */
    allCategories: List<String>,
    builtInCategories: Set<String>,
    onCancel: () -> Unit,
    onSave: (SummarizeTemplate) -> Unit,
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(template.category) }
    var name by remember { mutableStateOf(template.name) }
    var prompt by remember { mutableStateOf(template.prompt) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card-style overlay: top leaves space for the status bar, exposing the parent view; top corners are rounded
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (template.name.isEmpty()) R.string.summarize_new_template
                            else R.string.summarize_edit_template,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(
                        enabled = name.isNotBlank() && prompt.isNotBlank(),
                        onClick = {
                            onSave(
                                template.copy(
                                    category = category.ifBlank { "General Summary" },
                                    name = name.trim(),
                                    prompt = prompt,
                                    isBuiltIn = false,
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.common_save)) }
                }
                // Category: Default to selecting from existing categories; only manually type a new one if none match.
                // Previously, there was only a free-text input field, so a single typo would create a category with only one template.
                var newCategory by remember { mutableStateOf(false) }
                val existing = remember(allCategories) { allCategories }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.summarize_category), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { newCategory = !newCategory }) {
                        Text(
                            stringResource(
                                if (newCategory) R.string.summarize_pick_existing else R.string.summarize_new,
                            ),
                        )
                    }
                }
                if (newCategory || existing.isEmpty()) {
                    OutlinedTextField(
                        value = category, onValueChange = { category = it },
                        label = { Text(stringResource(R.string.summarize_new_category_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(existing) { c ->
                            AssistChip(
                                onClick = { category = c },
                                label = {
                                    Text(
                                        localizedTemplateCategory(context, c, c in builtInCategories) +
                                            if (c == category) " ✓" else "",
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.summarize_template_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(PROMPT_MAX_CHARACTERS) },
                    label = { Text(stringResource(R.string.summarize_prompt)) },
                    supportingText = {
                        Column {
                            Text(stringResource(R.string.summarize_prompt_count, prompt.length, PROMPT_MAX_CHARACTERS))
                            Text(stringResource(R.string.summarize_prompt_context_hint))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {}
            }
        }
    }
}
