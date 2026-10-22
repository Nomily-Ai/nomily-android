package com.nomily.app.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.mutableIntStateOf
import com.nomily.app.core.llm.LlmProviderSpec
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.BuildConfig
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.ui.components.ActionRow
import com.nomily.app.ui.components.Footer
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.NomiDimens
import com.nomily.app.ui.components.RowDivider
import com.nomily.app.ui.components.SubPage
import com.nomily.app.ui.theme.appSwitchColors
import com.nomily.app.core.clips.ClipArtefact
import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.config.AppLanguages
import com.nomily.app.core.config.AppTheme
import com.nomily.app.ui.theme.accentColor
import com.nomily.app.ui.theme.deepColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.nomily.app.data.AppLocale

/**
 * Settings page.
 *
 * This gathers a **single-screen grouped scroll** (platform conventions differ, see Implementation Manual §5.3). All data is read/written directly to `config.json`:
 * ASR provider (Azure / local), LLM provider (6 vendors), default transcription, recording & transfer, developer mode.
 */
@Composable
fun SettingsScreen(vm: NomiViewModel, onPush: (PushedPage?) -> Unit) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 2026-08-03: Changed from modal Dialog to **tab content**. Title and back navigation are handled by the outer Scaffold,
    // so this no longer includes its own title row or Close button.
    // The root settings page **is not a long form**, but a menu: 5 arrowed entries + "About".
    // Each entry pushes a subpage.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var showTemplates by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    val jobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val trashItems by vm.library.trashItems.collectAsStateWithLifecycle()

    // When entering via "Go to settings…" from elsewhere (live page / recording details), navigate directly to the corresponding subpage,
    // do not leave the user on the settings home to find it. Clear the flag immediately after consumption, otherwise back navigation will jump again.
    LaunchedEffect(ui.settingsTarget) {
        when (ui.settingsTarget) {
            NomiViewModel.SettingsTarget.ASR -> page = SettingsPage.Asr
            NomiViewModel.SettingsTarget.LLM -> page = SettingsPage.Llm
            null -> return@LaunchedEffect
        }
        vm.settingsTargetHandled()
    }

    // Partial or unverified speech recognition configurations are excluded from transcription; announce this before returning so the user isn’t surprised later.
    val azure = cfg.asrProviders.azure
    val azureIncomplete = azure != null && (azure.key.isEmpty() || azure.region.isEmpty())
    val azureNeedsAttention = page == SettingsPage.Asr && azure != null &&
        !(azure.key.isEmpty() && azure.region.isEmpty()) &&   // If not configured at all, it’s not considered partial.
        (azureIncomplete || azure.verification != true)
    var showLeaveWarning by remember { mutableStateOf(false) }

    // The back arrow and title are provided by the outer navigation bar (see `PushedPage`) — drawing another one in the subpage would result in duplicate title bars.
    val pushedTitle = page?.let { stringResource(it.titleRes) }
    LaunchedEffect(pushedTitle, azureNeedsAttention) {
        onPush(
            pushedTitle?.let { t ->
                PushedPage(t) { if (azureNeedsAttention) showLeaveWarning = true else page = null }
            },
        )
    }
    // The system back button follows a different path; if not intercepted, it bypasses the above prompt.
// ⚠️ **Do not intercept while the soft keyboard is visible**: on Android the first back press dismisses the keyboard; intercepting it would cause the user to type a character, press back to hide the keyboard, and then see a "Leave without verification?" dialog — the keyboard would never dismiss.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = azureNeedsAttention && !imeVisible) { showLeaveWarning = true }
    if (showLeaveWarning) {
        AlertDialog(
            onDismissRequest = { showLeaveWarning = false },
            title = { Text(stringResource(R.string.asr_leave_title)) },
            text = {
                Text(
                    stringResource(
                        if (azureIncomplete) R.string.asr_leave_incomplete_message
                        else R.string.asr_leave_unverified_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showLeaveWarning = false; page = null }) {
                    Text(stringResource(R.string.asr_leave_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveWarning = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    // When switching tabs, this whole block is discarded and the LaunchedEffect above will not run again — cleanup must be done here,
    // otherwise the navigation bar will retain the previous subpage’s title and back arrow.
    DisposableEffect(Unit) { onDispose { onPush(null) } }

    // Template management is also a **pushed page**, so it takes over the full screen like other subpages.
    if (showTemplates) {
        TemplateManagerPage(vm, onPush) { showTemplates = false }
        return
    }
    if (showTrash) {
        TrashPage(vm, onPush) { showTrash = false }
        return
    }
    if (showTasks) {
        TaskCenterPage(vm, onPush) { showTasks = false }
        return
    }

    if (page != null) {
        SubPage {
            when (page!!) {
                SettingsPage.Asr -> AsrSection(cfg, vm)
                SettingsPage.Llm -> LlmSection(cfg, vm)
                // Section order: Delete after transfer → Encryption passphrase →
                // Device‑side encryption → Fast transfer threshold → Wi‑Fi AP
                SettingsPage.Recordings -> {
                    RecordingsSection(cfg, vm)
                    EncryptionSection(ui, vm)
                    DeviceEncryptionSection(ui)
                    FastTransferThresholdSection(cfg, vm)
                    WifiApSection(cfg, vm)
                }
                SettingsPage.Language -> LanguageSection(cfg, vm)
                SettingsPage.Theme -> ThemeSection(cfg, vm)
                SettingsPage.DevicesLibrary -> {
                    DevicesSection(cfg, vm)
                    KnownDevicesSection(cfg, vm)
                    LibrarySection(ui)
                    LibraryDangerSection(vm)
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NomiDimens.screenInset),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            // Row order: Speech recognition / Large language model / Recording & transfer /
// **Summary template** / Device & repository. The summary template sits in the middle, not at the end (previously it was last, rows 4 and 5 were swapped). It is the only row that does not use the SettingsPage enum (it pushes the template management page).
            NavRow(stringResource(SettingsPage.Asr.titleRes)) { page = SettingsPage.Asr }
            RowDivider()
            NavRow(stringResource(SettingsPage.Llm.titleRes)) { page = SettingsPage.Llm }
            RowDivider()
            NavRow(stringResource(SettingsPage.Recordings.titleRes)) { page = SettingsPage.Recordings }
            RowDivider()
            NavRow(stringResource(R.string.settings_summarize_templates)) { showTemplates = true }
            RowDivider()
            NavRow(stringResource(SettingsPage.DevicesLibrary.titleRes)) { page = SettingsPage.DevicesLibrary }
            RowDivider()
            // The count only shows when something is actually in the trash.
            NavRow(
                stringResource(R.string.trash_settings_row),
                value = trashItems.size.takeIf { it > 0 }?.toString(),
            ) { showTrash = true }
            RowDivider()
            // Like the trash row, the count only shows when work is actually unfinished.
            NavRow(
                stringResource(R.string.jobs_open_queue),
                value = jobs.count { it.finishedAt == null }.takeIf { it > 0 }?.toString(),
            ) { showTasks = true }
            RowDivider()
            // Language appears on the last row, right after "About". It does not belong to any functional domain (ASR/LLM/recording/device),
            // inserting it among earlier rows would disrupt the functional order of those four rows. The current selection is shown on the right, visible without tapping.
            NavRow(
                stringResource(SettingsPage.Language.titleRes),
                // Displays the **effective** language, not the raw tag stored in config: older versions or manually edited
                // configs may retain a tag that this build does not translate (e.g., `th`), in which case the UI is actually English,
                // but the row still shows `th` — the label and the displayed language would mismatch.
                value = cfg.appLanguage
                    ?.let { AppLanguages.autonymOf(AppLanguages.resolve(it)) }
                    ?: stringResource(R.string.language_follow_system),
            ) { page = SettingsPage.Language }
            RowDivider()
            // Theme sits next to Language: both are "how the app looks and speaks". The leading dot is the
            // active accent, so the row answers "which one am I on" without being opened.
            val theme = AppTheme.resolve(cfg.appTheme)
            NavRow(
                stringResource(SettingsPage.Theme.titleRes),
                value = stringResource(theme.titleRes),
                leading = {
                    Box(
                        Modifier.padding(end = 10.dp).size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                    )
                },
            ) { page = SettingsPage.Theme }
        }
        AboutSection(cfg, vm)
    }
}

/** The five entries on the root settings page. */
private enum class SettingsPage(val titleRes: Int) {
    Asr(R.string.settings_asr_providers),
    Llm(R.string.settings_llm_providers),
    Recordings(R.string.settings_recordings),
    DevicesLibrary(R.string.settings_devices_library),
    Language(R.string.settings_language),
    Theme(R.string.settings_theme),
}

/** Navigation row with an arrow. [value] is the current value displayed on the right. */
@Composable
private fun NavRow(
    title: String,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        // Material's default arrow icon is 24dp with a very light color; using it directly looks too bold, so we downgrade it one size here
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            // Use onSurfaceVariant for the color instead of the lighter divider color: this arrow has a thin stroke,
            // using the same light color would appear too faint; a slightly darker shade gives comparable visual weight.
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * A single-row switch. `info` uses an inline `?` tooltip, `footer` uses a full-section footnote ——
 * they are not used simultaneously.
 */
@Composable
private fun ToggleRow(
    label: String,
    footer: String? = null,
    info: String? = null,
    checked: Boolean,
    /** Do not draw a divider after the last row in the card. */
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        // The `?` follows the label directly, not floated to the far right
        info?.let { InfoTip(it) }
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = appSwitchColors())
    }
    footer?.let { Footer(it) }
    if (divider) RowDivider()
}

/** A text field bound directly to a config field; after `onCommit` receives a new value, the caller writes it back to config. */
@Composable
private fun Field(
    label: String,
    value: String,
    secure: Boolean = false,
    numeric: Boolean = false,
    /** Host / endpoint: URL keyboard. */
    url: Boolean = false,
    /** End-of-row accessory (e.g., verification ✓ for each provider) — placed at the far right of the row, not on a separate line. */
    trailing: (@Composable () -> Unit)? = null,
    /** Do not draw a divider after the last row in the card. */
    divider: Boolean = true,
    onChange: (String) -> Unit,
) {
    // In grouped tables, input fields are **borderless full‑width**: placeholder gray, value black, with a thin separator between rows.
    // Row height and padding: card row height 45pt, content 16pt left/right, separator inset 16pt from the left.
    // M3's TextField defaults to 56dp height and immutable padding, so we use BasicTextField + DecorationBox here.
    val interaction = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (secure) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            // Keys, regions, hosts and model ids are case-sensitive identifiers: never capitalise or autocorrect them.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = when {
                    numeric -> KeyboardType.Number
                    secure -> KeyboardType.Password
                    url -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
            ),
            interactionSource = interaction,
            modifier = Modifier.weight(1f),
        ) { inner ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction,
                placeholder = {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                // Same height as other rows: all rows in the card are uniformly 48dp (Material's minimum touch target),
                // dropping below 48dp on Android would be an accessibility regression.
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                // A saved secret shows its first and last four characters, so the user can tell which key is stored.
                supportingText = if (secure && value.length > 8) {
                    {
                        Text(
                            "${value.take(4)}····${value.takeLast(4)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    null
                },
            )
        }
        trailing?.let {
            Box(Modifier.padding(end = 8.dp)) { it() }
        }
    }
    if (divider) RowDivider()
}

// ── ASR ──────────────────────────────────────────────────────────────

/** Default port filled in when the user provides a host but no port. Written only when creating the record, not used as the displayed value for an empty field. */
private const val DEFAULT_LOCAL_ASR_PORT = 12300

/**
 * If both host and port are empty, remove the entire local record. Leaving a half record like `{host: "", port: 1}` would cause the UI to treat it as "configured", showing a stray number in the port field.
 */
private fun AppConfig.AsrProviders.Local.orNullIfBlank(): AppConfig.AsrProviders.Local? =
    if (host.isEmpty() && port <= 0) null else this
@Composable
private fun AsrSection(cfg: AppConfig, vm: NomiViewModel) {
    Group(stringResource(R.string.asr_transcription)) {

        // Default transcription: active provider (appears only when a provider is configured)
        // Only services that are fully filled and whose credentials have not been rejected (see `selectable`):
        // selecting anything else would produce no transcription.
        val configured = cfg.asrProviders.selectable.map { key ->
            key to stringResource(
                when (key) {
                    "azure" -> R.string.asr_azure
                    "local" -> R.string.asr_local_server_label
                    "custom" -> R.string.asr_custom_server
                    else -> R.string.asr_remote_live
                },
            )
        }
        if (configured.isNotEmpty()) {
            // Use a single-row Picker style (current value on the right with up/down arrows), not a row of chips
            var openAsr by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { openAsr = true }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.asr_active_provider), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    // The stored primary may refer to a service that is no longer exposed (e.g., local after exiting developer mode),
                    // in that case fall back to the first available option instead of showing a "—" that suggests nothing is configured.
                    configured.firstOrNull { it.first == cfg.defaults.asrPrimary }?.second ?: configured.first().second,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
                DropdownMenu(expanded = openAsr, onDismissRequest = { openAsr = false }) {
                    configured.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                vm.updateConfig { it.withAsrPrimary(key) }
                                openAsr = false
                            },
                        )
                    }
                }
            }
            RowDivider()
        }

        ToggleRow(
            stringResource(R.string.asr_auto_transcribe_after_download),
            info = stringResource(R.string.asr_auto_transcribe_footer),
            checked = cfg.autoTranscribeAfterDownload,
        ) { on -> vm.updateConfig { it.copy(autoTranscribeAfterDownload = on) } }

        if (cfg.autoTranscribeAfterDownload) {
            // 5‑second steps, range 0…600
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.asr_auto_transcribe_min, cfg.minTranscribeDuration),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.updateConfig { it.copy(minTranscribeDuration = (it.minTranscribeDuration - 5).coerceAtLeast(0)) }
                }) { Text("−5") }
                TextButton(onClick = {
                    vm.updateConfig { it.copy(minTranscribeDuration = (it.minTranscribeDuration + 5).coerceAtMost(600)) }
                }) { Text("+5") }
            }
        }

        // Speech → text → summary with no tap in between.
        ToggleRow(
            stringResource(R.string.asr_auto_summarize),
            info = stringResource(R.string.asr_auto_summarize_footer),
            checked = cfg.autoSummarizeAfterTranscription,
        ) { on -> vm.updateConfig { it.copy(autoSummarizeAfterTranscription = on) } }

        ToggleRow(
            stringResource(R.string.asr_local_vad),
            info = stringResource(R.string.asr_local_vad_footer),
            checked = cfg.localVadEnabled,
        ) { on -> vm.updateConfig { it.copy(localVadEnabled = on) } }

        // The queue survives restarts, so a row only appears while transcriptions are queued or have given up.
        val jobs by vm.jobs.jobs.collectAsStateWithLifecycle()
        val inFlight by vm.jobs.running.collectAsStateWithLifecycle()
        // Gave-up jobs are finished in the ledger but still count here: they need the user, and say why.
        val pending = jobs.filter {
            it.kind == com.nomily.app.jobs.JobKind.TRANSCRIBE && (it.finishedAt == null || it.lastError != null)
        }
        ToggleRow(
            stringResource(R.string.asr_background_keep_alive),
            info = stringResource(R.string.asr_background_keep_alive_footer),
            checked = cfg.backgroundKeepAliveEnabled,
            divider = pending.isNotEmpty(),
        ) { on -> vm.setBackgroundKeepAlive(on) }
        if (pending.isNotEmpty()) TranscribeQueueRow(pending, inFlight)

    }

    // Azure is in its own group, with "How to obtain the key" placed on the right side of the group header
    var showAzureGuide by remember { mutableStateOf(false) }
    Group(
        stringResource(R.string.asr_azure_speech),
        trailing = {
            TextButton(onClick = { showAzureGuide = true }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.asr_azure_how_to_get_key), style = MaterialTheme.typography.labelMedium)
            }
        },
        // Providing only the steps to obtain the key leaves users unable to understand "why configure this" and "where the audio is sent".
        // The page lacks a save button; without clear explanation, users won’t know whether the previous entries count.
        footer = stringResource(R.string.asr_azure_purpose_footer) + "\n\n" +
            stringResource(R.string.asr_autosave_note),
    ) {
        // The ✓ at the right end of the subscription key row follows the same pattern as LLM providers: exchange the key for a token
        val azureVerify = remember { VerifyState() }
        // When re-entering the page, display the previous verification result; otherwise "Saved" and "Verified usable" look identical in the UI.
        val lastVerifyFailed = stringResource(R.string.asr_last_verify_failed)
        LaunchedEffect(Unit) {
            when (cfg.asrProviders.azure?.verification) {
                true -> { azureVerify.done = true; azureVerify.error = null }
                false -> azureVerify.error = lastVerifyFailed
                null -> Unit
            }
        }
        Field(
            stringResource(R.string.asr_subscription_key), cfg.asrProviders.azure?.key ?: "", secure = true,
            trailing = {
                val az = cfg.asrProviders.azure
                VerifyIcon(
                    azureVerify,
                    !az?.key.isNullOrEmpty() && !az?.region.isNullOrEmpty(),
                    // The conclusion must be persisted: only then will the next page load distinguish between "saved" and "verification passed",
                    // and the transcription pipeline will know that these credentials have been rejected.
                    onResult = { ok ->
                        vm.updateConfig {
                            val a = it.asrProviders.azure ?: return@updateConfig it
                            it.copy(
                                asrProviders = it.asrProviders.copy(
                                    azure = a.copy(
                                        verifiedFingerprint =
                                            AppConfig.AsrProviders.Azure.fingerprint(a.key, a.region),
                                        verifiedOk = ok,
                                    ),
                                ),
                            )
                        }
                    },
                ) {
                    com.nomily.app.llm.LlmModelService.azure(az?.key ?: "", az?.region ?: "")
                }
            },
        ) { new ->
            // Changing credentials invalidates the previous verification result.
            azureVerify.done = false
            azureVerify.error = null
            vm.updateConfig {
                val az = (it.asrProviders.azure ?: AppConfig.AsrProviders.Azure("", "")).copy(key = new)
                it.copy(asrProviders = it.asrProviders.copy(azure = az))
            }
        }
        // If either the key or region is missing, the verification ✓ on the right is gray and unresponsive. Simply disabling without explanation leaves the user unaware of which part is missing (e.g., `1gvwter`), so the missing item is displayed below that row.
        if (cfg.asrProviders.azure?.key.isNullOrEmpty()) {
            FieldHint(stringResource(R.string.asr_key_required))
        }
        Field(stringResource(R.string.asr_region), cfg.asrProviders.azure?.region ?: "", divider = false) { new ->
            azureVerify.done = false
            azureVerify.error = null
            vm.updateConfig {
                val az = (it.asrProviders.azure ?: AppConfig.AsrProviders.Azure("", "")).copy(region = new)
                it.copy(asrProviders = it.asrProviders.copy(azure = az))
            }
        }
        if (cfg.asrProviders.azure?.region.isNullOrEmpty()) {
            FieldHint(stringResource(R.string.asr_region_required))
        }
        VerifyError(azureVerify)
        // "Configured" and "usable" are different: only after successful verification can we claim the configuration works.
        val azureIncomplete = cfg.asrProviders.azure.let {
            it == null || it.key.isEmpty() || it.region.isEmpty()
        }
        when {
            azureVerify.verifying -> StatusHint(stringResource(R.string.asr_status_verifying))
            azureVerify.error != null -> Unit   // The failure reason is already shown above.
            azureVerify.done -> StatusHint(stringResource(R.string.asr_status_verified), verified = true)
            !azureIncomplete -> StatusHint(stringResource(R.string.asr_status_unverified))
        }
    }
    if (showAzureGuide) AzureSetupGuide { showAzureGuide = false }

    Group(stringResource(R.string.asr_local_server), footer = stringResource(R.string.asr_local_probe_footer)) {
        // A changed host or port invalidates the last probe — a stale "reachable" would be misleading.
        val probeKey = cfg.asrProviders.local?.let { "${it.host}:${it.port}" }
        var probe by remember(probeKey) { mutableStateOf<com.nomily.app.asr.LocalAsrProbe.Result?>(null) }
        var probing by remember(probeKey) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        Field(stringResource(R.string.asr_host), cfg.asrProviders.local?.host ?: "", url = true) { new ->
            vm.updateConfig {
                val lo = (it.asrProviders.local ?: AppConfig.AsrProviders.Local("", DEFAULT_LOCAL_ASR_PORT))
                    .copy(host = new)
                it.copy(asrProviders = it.asrProviders.copy(local = lo.orNullIfBlank()))
            }
        }
        // Only prompt when "partially configured": both fields empty indicates "no local service configured", not an error.
        if (cfg.asrProviders.local?.let { it.host.isEmpty() && it.port > 0 } == true) {
            FieldHint(stringResource(R.string.asr_host_required))
        }
        Field(
            stringResource(R.string.asr_port),
            // port <= 0 means "not configured"; display as empty so the placeholder "Port" is visible,
            // and avoid showing a random number that the user never entered.
            cfg.asrProviders.local?.port?.takeIf { it > 0 }?.toString() ?: "",
            numeric = true,
        ) { new ->
            // Previously it was `toIntOrNull() ?: return@Field`: clearing the input field caused it to be discarded,
            // and once a port was entered it could never be removed.
            val port = new.filter { it.isDigit() }.toIntOrNull() ?: 0
            vm.updateConfig {
                val lo = (it.asrProviders.local ?: AppConfig.AsrProviders.Local("", 0)).copy(port = port)
                it.copy(asrProviders = it.asrProviders.copy(local = lo.orNullIfBlank()))
            }
        }
        if (cfg.asrProviders.local?.let { (it.host.isNotEmpty() || it.port > 0) && it.port !in 1..65535 } == true) {
            FieldHint(stringResource(R.string.asr_port_invalid))
        }
        LocalProbeRow(probe, probing, divider = true) {
            val local = cfg.asrProviders.local
            probing = true
            probe = null
            scope.launch {
                probe = withContext(Dispatchers.IO) {
                    com.nomily.app.asr.LocalAsrProbe.probe(ctx, local?.host ?: "", local?.port ?: 0)
                }
                probing = false
            }
        }
        // For "I don't know what a faster-whisper server is": a prompt for ChatGPT / Claude Code on the computer.
        SetupAssistantRow(cfg.appLanguage)
    }

    CustomAsrGroup(cfg, vm)
    RemoteLiveGroup(cfg, vm)
}

/**
 * User-defined OpenAI-compatible transcription server (iOS `customSection`): endpoint / API key / model / language
 * + connection test. A half-typed endpoint must not linger as a configured provider, so a blank one drops the record.
 */
@Composable
private fun CustomAsrGroup(cfg: AppConfig, vm: NomiViewModel) {
    val c = cfg.asrProviders.custom
    val save = { nc: AppConfig.AsrProviders.Custom ->
        vm.updateConfig {
            it.copy(asrProviders = it.asrProviders.copy(custom = nc.takeIf { n -> n.endpoint.isNotBlank() }))
                .adoptAsrPrimaryIfUnset()
        }
    }
    val current = c ?: AppConfig.AsrProviders.Custom(endpoint = "")
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // A changed endpoint or key invalidates the last probe and the fetched model list.
    val probeKey = "${c?.endpoint}|${c?.apiKey}"
    var probe by remember(probeKey) { mutableStateOf<com.nomily.app.asr.LocalAsrProbe.Result?>(null) }
    var probing by remember(probeKey) { mutableStateOf(false) }
    var models by remember(c?.endpoint) { mutableStateOf<List<String>>(emptyList()) }
    var manual by remember(c?.endpoint) { mutableStateOf(false) }
    var loadingModels by remember(c?.endpoint) { mutableStateOf(false) }
    var modelNotice by remember(c?.endpoint) { mutableStateOf<String?>(null) }

    suspend fun loadModels() {
        val cur = cfg.asrProviders.custom
        if (cur == null || cur.endpoint.isBlank()) {
            modelNotice = ctx.getString(R.string.asr_custom_models_none)
            return
        }
        loadingModels = true
        modelNotice = null
        val list = withContext(Dispatchers.IO) { com.nomily.app.asr.CustomAsrProbe.listModels(cur.endpoint, cur.apiKey.orEmpty()) }
        models = list.orEmpty()
        manual = false
        modelNotice = if (list != null) {
            ctx.getString(R.string.asr_custom_models_loaded, list.size)
        } else {
            ctx.getString(R.string.asr_custom_models_none)
        }
        loadingModels = false
    }

    Group(stringResource(R.string.asr_custom_server), footer = stringResource(R.string.asr_custom_probe_footer)) {
        Field(stringResource(R.string.asr_custom_endpoint), c?.endpoint ?: "", url = true) { new ->
            save(current.copy(endpoint = new))
        }
        Field(stringResource(R.string.asr_custom_api_key), c?.apiKey ?: "", secure = true) { new ->
            save(current.copy(apiKey = new.ifEmpty { null }))
        }
        if (models.isNotEmpty() && !manual) {
            var open by remember { mutableStateOf(false) }
            val model = c?.model
            // Keep the saved value even when the server no longer lists it.
            val options = if (model.isNullOrEmpty() || model in models) models else listOf(model) + models
            Row(
                Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.llm_model), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    model ?: stringResource(R.string.llm_select_model),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { save(current.copy(model = m)); open = false })
                    }
                }
            }
            modelNotice?.let { StatusHint(it) }
            TextButton(onClick = { manual = true }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.asr_custom_models_manual), modifier = Modifier.padding(start = 6.dp))
            }
            RowDivider()
        } else {
            Field(
                stringResource(R.string.asr_custom_model), c?.model ?: "",
                trailing = {
                    IconButton(
                        onClick = { scope.launch { loadModels() } },
                        enabled = !loadingModels && !c?.endpoint.isNullOrBlank(),
                    ) {
                        if (loadingModels) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.asr_custom_load_models))
                        }
                    }
                },
                divider = modelNotice == null,
            ) { new -> save(current.copy(model = new.ifEmpty { null })) }
            modelNotice?.let {
                StatusHint(it)
                RowDivider()
            }
        }
        Field(stringResource(R.string.asr_custom_language), c?.language ?: "") { new ->
            save(current.copy(language = new.ifEmpty { null }))
        }
        LocalProbeRow(probe, probing, label = stringResource(R.string.asr_custom_probe)) {
            val cur = cfg.asrProviders.custom
            probing = true
            probe = null
            scope.launch {
                probe = withContext(Dispatchers.IO) {
                    com.nomily.app.asr.CustomAsrProbe.probe(ctx, cur?.endpoint ?: "", cur?.apiKey.orEmpty())
                }
                probing = false
                // The key is proven good — fetch the model dropdown too, so the next tap is picking a model.
                if (probe?.ok == true) loadModels()
            }
        }
    }
}

/**
 * Remote secure live server (iOS `remoteLiveSection`): `wss://` endpoint + required API key, scheme validation and a
 * connection test. Live transcription only. The key lives in [com.nomily.app.data.SecretStore], never in config.json.
 */
@Composable
private fun RemoteLiveGroup(cfg: AppConfig, vm: NomiViewModel) {
    val r = cfg.asrProviders.remoteLive
    val save = { nr: AppConfig.AsrProviders.RemoteLive ->
        vm.updateConfig {
            it.copy(asrProviders = it.asrProviders.copy(remoteLive = nr.takeIf { n -> n.endpoint.isNotBlank() }))
                .adoptAsrPrimaryIfUnset()
        }
    }
    val current = r ?: AppConfig.AsrProviders.RemoteLive(endpoint = "")
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val probeKey = "${r?.endpoint}|${r?.apiKey}"
    var probe by remember(probeKey) { mutableStateOf<com.nomily.app.asr.LocalAsrProbe.Result?>(null) }
    var probing by remember(probeKey) { mutableStateOf(false) }
    Group(stringResource(R.string.asr_remote_live), footer = stringResource(R.string.asr_remote_footer)) {
        Field(stringResource(R.string.asr_remote_endpoint), r?.endpoint ?: "", url = true) { new ->
            save(current.copy(endpoint = new))
        }
        // An endpoint that can never work: an explicit plaintext ws:// (the LAN shape) or something unparsable.
        if (!r?.endpoint.isNullOrBlank() && com.nomily.app.core.asr.RemoteLiveEndpoint.listenUrl(r!!.endpoint) == null) {
            FieldHint(stringResource(R.string.asr_remote_err_invalid_scheme))
        }
        Field(stringResource(R.string.asr_remote_api_key), r?.apiKey ?: "", secure = true) { new ->
            save(current.copy(apiKey = new.ifEmpty { null }))
        }
        LocalProbeRow(probe, probing, label = stringResource(R.string.asr_remote_probe)) {
            val cur = cfg.asrProviders.remoteLive
            if (cur == null || cur.endpoint.isBlank()) {
                probe = com.nomily.app.asr.LocalAsrProbe.Result(false, error = ctx.getString(R.string.asr_local_probe_no_host))
                return@LocalProbeRow
            }
            probing = true
            probe = null
            scope.launch {
                probe = withContext(Dispatchers.IO) {
                    com.nomily.app.asr.RemoteLiveProbe.probe(ctx, cur.endpoint, cur.apiKey.orEmpty())
                }
                probing = false
            }
        }
    }
}

/**
 * "Test connection" for the local server: filled-in and reachable are different things — a stopped service, a
 * mistyped port or a phone on another network otherwise only surface as a failed transcription later.
 */
@Composable
private fun LocalProbeRow(
    result: com.nomily.app.asr.LocalAsrProbe.Result?,
    probing: Boolean,
    divider: Boolean = false,
    label: String = stringResource(R.string.asr_local_probe),
    onProbe: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(start = NomiDimens.rowInset, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            val small = MaterialTheme.typography.labelSmall
            when {
                probing -> Text(stringResource(R.string.asr_status_verifying), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result == null -> Unit
                !result.ok -> Text(result.error.orEmpty(), style = small, color = MaterialTheme.colorScheme.error)
                else -> {
                    result.detail?.let {
                        Text(stringResource(R.string.asr_local_probe_reachable, it), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result.warning?.let { Text(it, style = small, color = Color(0xFFFF9500)) }
                }
            }
        }
        IconButton(onClick = onProbe, enabled = !probing) {
            if (probing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (result?.ok == false) Icons.Filled.Cancel else Icons.Filled.CheckCircleOutline,
                    contentDescription = label,
                    tint = when {
                        result == null -> MaterialTheme.colorScheme.outline
                        result.ok -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
    if (divider) RowDivider()
}

/**
 * Copies the one-prompt setup (local faster-whisper server + Ollama) for the user to paste into ChatGPT / Claude Code
 * on their computer. A row, not a footer link: the point is copying it on the phone.
 */
@Composable
private fun SetupAssistantRow(appLanguage: String?) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2_500)
            copied = false
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Nomily setup", com.nomily.app.core.config.SetupAssistant.prompt(appLanguage)),
                )
                copied = true
            }
            .heightIn(min = NomiDimens.rowMinHeight)
            .padding(horizontal = NomiDimens.rowInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
            contentDescription = null,
            tint = if (copied) com.nomily.app.ui.theme.AppSwitchGreen else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                stringResource(if (copied) R.string.setup_assistant_copied else R.string.setup_assistant_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.setup_assistant_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Configuration status hint (verified / verifying / saved but not verified), positioned like [FieldHint] but not using the error color. */
@Composable
private fun StatusHint(text: String, verified: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = NomiDimens.rowInset, end = NomiDimens.rowInset, bottom = 4.dp),
    )
}

// ── LLM ──────────────────────────────────────────────────────────────
@Composable
private fun LlmSection(cfg: AppConfig, vm: NomiViewModel) {
    // One flat provider list (tap a row to edit it); when at least one provider is configured, a top card holds the
    // "Active service" selector.
    val configured = cfg.llmProviders.configuredProviders
    val primaryGap = com.nomily.app.core.llm.LlmChain.primaryGapWithFallback(cfg)
    if (configured.isNotEmpty() || primaryGap != null) {
        Group("") {
            var open by remember { mutableStateOf(false) }
            // An incomplete primary isn't in `configured`; show it as "X (not configured)" rather than a blank value.
            val primary = cfg.llmProviders.primary?.takeIf { it.isNotEmpty() }
            val current = configured.firstOrNull { it.first == primary }?.second
                ?: primary?.let { stringResource(R.string.llm_unconfigured, com.nomily.app.core.llm.LlmChain.providerLabel(it)) }
                ?: stringResource(R.string.common_none)
            Row(
                Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.asr_active_provider), style = MaterialTheme.typography.bodyLarge)
                InfoTip(stringResource(R.string.llm_active_provider_footer))
                Spacer(Modifier.weight(1f))
                Text(
                    current,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_none)) },
                        onClick = {
                            vm.updateConfig { it.copy(llmProviders = it.llmProviders.copy(primary = null)) }
                            open = false
                        },
                    )
                    configured.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                vm.updateConfig { it.copy(llmProviders = it.llmProviders.copy(primary = key)) }
                                open = false
                            },
                        )
                    }
                }
            }
            // The reason it can't run, and a one-tap switch to the provider that would stand in.
            if (primaryGap != null) LlmPrimaryGapBanner(cfg, vm)
        }
    }

    LlmProviderList(cfg, vm)
}

/** Verification status: four states for a single "verify" operation (pending / verifying / passed / failed) plus the fetched model list. */
private class VerifyState {
    var models by mutableStateOf<List<String>>(emptyList())
    var verifying by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * Verify button — a circular check icon placed at the **right end of the first row**, not occupying a full row.
 * Gray ✓ pending / … verifying / accent color ✓ passed / red ✗ failed; failure reason follows at the end of the card (e.g., HTTP 4xx),
 * otherwise users cannot tell whether the issue is a bad key or a network problem.
 */
@Composable
private fun VerifyIcon(
    v: VerifyState,
    canVerify: Boolean,
    /** Persistent storage location for verification results (used by Azure). By default not persisted, affecting only the current page view. */
    onResult: ((Boolean) -> Unit)? = null,
    fetch: () -> com.nomily.app.llm.LlmModelService.Result,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    IconButton(
        enabled = canVerify && !v.verifying,
        onClick = {
            v.verifying = true
            v.error = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { fetch() }
                v.models = r.models
                // Prefer localized reasons; httpCode is attached only as secondary information.
                val reason = r.errorRes?.let { res ->
                    ctx.getString(res) + (r.httpCode?.let { " (HTTP $it)" } ?: "")
                } ?: r.error
                v.error = if (r.ok) null else (reason ?: ctx.getString(R.string.azure_verify_unexpected))
                v.verifying = false
                v.done = true
                onResult?.invoke(r.ok)
            }
        },
    ) {
        if (v.verifying) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (v.error != null) Icons.Filled.Cancel else Icons.Filled.CheckCircleOutline,
                contentDescription = null,
                tint = when {
                    v.error != null -> MaterialTheme.colorScheme.error
                    v.done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

/** Field-level hint (e.g., required missing), placed below the input row, using the same red as verification failure reasons. */
@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = NomiDimens.rowInset, end = NomiDimens.rowInset, bottom = 4.dp),
    )
}

/** Reason for verification failure, placed at the end of the card. */
@Composable
private fun VerifyError(v: VerifyState) {
    v.error?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── LLM provider list + editor ────────────────────────────────────────

private fun brandDrawable(id: String): Int? = when (id) {
    "openai" -> R.drawable.brand_openai
    "claude" -> R.drawable.brand_claude
    "gemini" -> R.drawable.brand_gemini
    "openRouter" -> R.drawable.brand_openrouter
    "ollama" -> R.drawable.brand_ollama
    "qwen" -> R.drawable.brand_qwen
    "deepseek" -> R.drawable.brand_deepseek
    "doubao" -> R.drawable.brand_doubao
    else -> null
}

/** The vendor's logo (monochrome, drawn white) on its brand-coloured square. `custom` has no vendor, so it keeps a symbol. */
@Composable
private fun ProviderMark(spec: LlmProviderSpec) {
    val color = Color(0xFF000000L or spec.tint.toLong())
    Box(
        Modifier.size(32.dp).background(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))),
            RoundedCornerShape(8.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val brand = brandDrawable(spec.id)
        when {
            brand != null -> Icon(
                androidx.compose.ui.res.painterResource(brand), contentDescription = null,
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
            spec.id == "custom" -> Icon(Icons.Filled.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            else -> Text(spec.mark, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** `host/path` of an address, or the address itself when it doesn't parse. */
private fun hostAndPath(endpoint: String): String {
    val u = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return endpoint
    val host = u.host ?: return endpoint
    return host + (u.path?.takeIf { it.length > 1 } ?: "")
}

@Composable
private fun LlmProviderList(cfg: AppConfig, vm: NomiViewModel) {
    val ctx = LocalContext.current
    val latency = remember { com.nomily.app.llm.LlmLatencyMemory(ctx) }
    // Bumped after a probe so the rows re-read the remembered latency.
    var latencyTick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LlmProviderSpec?>(null) }
    val p = cfg.llmProviders
    Group(stringResource(R.string.llm_grid_title), footer = stringResource(R.string.llm_grid_footer)) {
        LlmProviderSpec.ALL.forEachIndexed { i, spec ->
            val key = p.apiKeyValue(spec).orEmpty()
            val model = p.modelValue(spec).orEmpty()
            val endpoint = p.endpointValue(spec).orEmpty().ifEmpty { spec.defaultEndpoint }
            Row(
                Modifier.fillMaxWidth().clickable { editing = spec }
                    .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderMark(spec)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            model.isNotEmpty() -> model
                            endpoint.isNotEmpty() -> hostAndPath(endpoint)
                            else -> stringResource(R.string.llm_not_configured)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                val ms = remember(latencyTick, spec.id) { latency.millis(spec.id) }
                if (ms != null) {
                    Text(
                        "$ms ms", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (p.hasAnyConfig(spec)) {
                    val (res, ok) = when {
                        model.isEmpty() -> R.string.llm_preset_needs_model to false
                        spec.keyRequired && key.isEmpty() -> R.string.llm_preset_needs_key to false
                        else -> R.string.llm_preset_ready to true
                    }
                    Text(
                        stringResource(res), style = MaterialTheme.typography.labelSmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else Color(0xFFE08A00),
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            if (i < LlmProviderSpec.ALL.lastIndex) RowDivider()
        }
    }
    editing?.let { spec ->
        LlmProviderEditor(spec, cfg, vm, latency, onProbed = { latencyTick++ }, onClose = { editing = null })
    }
}

/** Fetch state of the model list. */
private sealed interface ModelFetch {
    data object Idle : ModelFetch
    data object Loading : ModelFetch
    data object Success : ModelFetch
    data class Failed(val reason: String) : ModelFetch
}

/**
 * Key + address + model for one provider, in the same order every time. The address is pre-filled from the spec;
 * where the API fixes it (OpenAI / Claude / Gemini) the row is shown read-only. Model lists are chat-capable only.
 */
@Composable
private fun LlmProviderEditor(
    spec: LlmProviderSpec,
    cfg: AppConfig,
    vm: NomiViewModel,
    latency: com.nomily.app.llm.LlmLatencyMemory,
    onProbed: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = cfg.llmProviders
    val key = p.apiKeyValue(spec).orEmpty()
    val model = p.modelValue(spec).orEmpty()
    val endpoint = p.endpointValue(spec).orEmpty().ifEmpty { spec.defaultEndpoint }

    var models by remember { mutableStateOf(spec.curatedModels) }
    var manualModel by remember { mutableStateOf(spec.curatedModels.isEmpty()) }
    var fetch by remember { mutableStateOf<ModelFetch>(ModelFetch.Idle) }
    var fetchHint by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<com.nomily.app.llm.LlmProbe.Result?>(null) }

    fun edit(autoPrimary: Boolean = false, f: (AppConfig.LlmProviders) -> AppConfig.LlmProviders) =
        vm.updateConfig { c ->
            val n = f(c.llmProviders)
            c.copy(llmProviders = if (autoPrimary) n.autoSelectPrimaryIfFirst() else n)
        }

    // Fill in what the user shouldn't have to type: the address first, then a default model. Never overwrites a choice.
    LaunchedEffect(spec.id) {
        if (spec.endpointEditable && p.endpointValue(spec).isNullOrEmpty() && spec.defaultEndpoint.isNotEmpty()) {
            edit { it.withEndpointUrl(spec, spec.defaultEndpoint) }
        }
        if (p.modelValue(spec).isNullOrEmpty() && !spec.local) {
            spec.curatedModels.firstOrNull()?.let { first -> edit { it.withModel(spec, first) } }
        }
    }

    // The saved value is always offered: a model renamed upstream, or an `ep-…` id typed by hand, is in no fetched list.
    val pickerOptions = if (model.isNotEmpty() && model !in models) listOf(model) + models else models
    val options = if (query.isEmpty()) pickerOptions else pickerOptions.filter { it.contains(query, ignoreCase = true) }
    val canProbe = model.isNotEmpty() && (!spec.keyRequired || key.isNotEmpty())

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = NomiDimens.screenInset, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderMark(spec)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(spec.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (endpoint.isEmpty()) stringResource(R.string.llm_not_configured) else hostAndPath(endpoint),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.common_done)) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NomiDimens.screenInset),
                ) {
                    // Key + address
                    val consoleLabel = stringResource(R.string.llm_preset_console)
                    val fixedNote = stringResource(R.string.llm_endpoint_fixed)
                    val keyFooter = listOfNotNull(
                        spec.console?.takeIf { spec.keyRequired }?.let { "$consoleLabel: $it" },
                        fixedNote.takeIf { !spec.endpointEditable },
                    ).joinToString("\n").ifEmpty { null }
                    Group(footer = keyFooter) {
                        Field(
                            stringResource(if (spec.keyRequired) R.string.llm_api_key else R.string.llm_api_key_optional),
                            key, secure = true,
                        ) { new ->
                            edit(autoPrimary = true) { it.withApiKey(spec, new) }
                            fetch = ModelFetch.Idle; probe = null
                        }
                        if (spec.endpointEditable) {
                            Field(stringResource(R.string.llm_preset_endpoint_filled), endpoint, url = true, divider = false) { new ->
                                edit { it.withEndpointUrl(spec, new) }
                                fetch = ModelFetch.Idle; probe = null
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.llm_endpoint_url), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    endpoint, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }

                    // Model
                    val modelFooter = listOfNotNull(
                        spec.note,
                        stringResource(R.string.llm_chat_only_note),
                        fetchHint,
                    ).joinToString("\n\n")
                    val fetchEnabled = fetch != ModelFetch.Loading && (!spec.keyRequired || key.isNotEmpty())
                    Group(stringResource(R.string.llm_model), footer = modelFooter) {
                        if (manualModel || pickerOptions.isEmpty()) {
                            Field(
                                stringResource(R.string.llm_model_placeholder, spec.curatedModels.firstOrNull() ?: "model-name"),
                                model,
                            ) { new -> edit { it.withModel(spec, new) }; probe = null }
                        } else {
                            var open by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().clickable { open = true }
                                    .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.llm_model), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    model.ifEmpty { stringResource(R.string.llm_select_model) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(2f, fill = false),
                                )
                                Icon(
                                    Icons.Filled.UnfoldMore, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                                )
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    options.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m) },
                                            onClick = { edit { it.withModel(spec, m) }; probe = null; open = false },
                                        )
                                    }
                                }
                            }
                            // OpenRouter alone lists hundreds of models: filter before scrolling.
                            if (models.size > 15) {
                                RowDivider()
                                Field(stringResource(R.string.llm_model_search), query) { query = it }
                            }
                        }
                        RowDivider()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = fetchEnabled) {
                                    fetch = ModelFetch.Loading; fetchHint = null; query = ""
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            com.nomily.app.llm.LlmModelService.fetchModels(ctx, spec, endpoint, key)
                                        }
                                        when {
                                            r.ok && r.models.isNotEmpty() -> {
                                                models = r.models; fetch = ModelFetch.Success; manualModel = false; fetchHint = r.detail
                                            }
                                            // Reachable but nothing callable: the key was fine, so don't show a bare failure.
                                            r.ok -> {
                                                models = emptyList()
                                                fetch = ModelFetch.Failed(ctx.getString(R.string.llm_no_chat_models))
                                                fetchHint = if (spec.curatedModels.isEmpty()) ctx.getString(R.string.llm_preset_builtin_hint) else null
                                            }
                                            else -> {
                                                fetch = ModelFetch.Failed(
                                                    r.errorRes?.let { ctx.getString(it) } ?: r.error ?: ctx.getString(R.string.llm_no_models_on_server),
                                                )
                                                models = spec.curatedModels
                                                fetchHint = ctx.getString(R.string.llm_preset_builtin_hint)
                                            }
                                        }
                                    }
                                }
                                .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.llm_preset_fetch_models), style = MaterialTheme.typography.bodyLarge,
                                color = if (fetchEnabled || fetch == ModelFetch.Loading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.weight(1f))
                            if (fetch == ModelFetch.Loading) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else if (pickerOptions.isNotEmpty() && !manualModel) {
                                Text(
                                    "${if (models.isEmpty()) spec.curatedModels.size else models.size}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        (fetch as? ModelFetch.Failed)?.let {
                            Text(
                                it.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = NomiDimens.rowInset, vertical = 4.dp),
                            )
                        }
                        if (pickerOptions.isNotEmpty() && !manualModel) {
                            TextButton(onClick = { manualModel = true }, modifier = Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.llm_preset_manual_model), style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (spec.curatedModels.isNotEmpty()) {
                            TextButton(onClick = { manualModel = false }, modifier = Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.llm_use_curated_models), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // Probe last: it needs a key and a model, and it is the only row that spends a token of quota.
                    val probeNote = stringResource(R.string.llm_test_note)
                    val probeNeed = stringResource(if (spec.keyRequired && key.isEmpty()) R.string.llm_test_need_key else R.string.llm_test_need_model)
                    Group(stringResource(R.string.llm_test_section), footer = if (canProbe) probeNote else "$probeNote\n\n$probeNeed") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = canProbe && !probing) {
                                    probing = true; probe = null
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            com.nomily.app.llm.LlmProbe.test(ctx, spec, endpoint, key, model)
                                        }
                                        probing = false; probe = r
                                        // Only a pass is worth remembering: a failure is usually a half-typed key.
                                        if (r.ok) latency.save(spec.id, r.millis) else latency.clear(spec.id)
                                        onProbed()
                                    }
                                }
                                .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val r = probe
                            when {
                                probing -> {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.llm_test_running), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                                r != null -> {
                                    Icon(
                                        if (r.ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel, contentDescription = null,
                                        tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text(
                                            if (r.ok) stringResource(R.string.llm_test_ok, r.millis) else r.error.orEmpty(),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        if (r.ok) {
                                            val band = stringResource(
                                                when (com.nomily.app.core.llm.LlmModels.latencyBand(r.millis)) {
                                                    0 -> R.string.llm_test_band_fast
                                                    1 -> R.string.llm_test_band_normal
                                                    2 -> R.string.llm_test_band_slow
                                                    else -> R.string.llm_test_band_very_slow
                                                },
                                            )
                                            Text(
                                                "$band · ${r.detail.orEmpty()}", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    val tint = if (canProbe) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = tint)
                                    Text(
                                        stringResource(R.string.llm_test_button), Modifier.padding(start = 10.dp),
                                        style = MaterialTheme.typography.bodyLarge, color = tint,
                                    )
                                }
                            }
                        }
                    }

                    if (spec.id == "ollama") Group { SetupAssistantRow(cfg.appLanguage) }

                    if (p.hasAnyConfig(spec)) {
                        Group {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    edit { it.removed(spec) }
                                    latency.clear(spec.id)
                                    onProbed()
                                    onClose()
                                }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.llm_preset_remove), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── Recordings & Transfer ────────────────────────────────────────────
/**
 * Interface language selection page.
 *
 * The first option is "Follow system" (also the default when not set), followed by the full list in [AppLanguages.SUPPORTED].
 * Language names are always shown in their **native names**, not translated: most users arriving at this screen likely cannot understand the current UI.
 *
 * After selection, the config is persisted first, then `recreate()` is called to apply the new language resources — the order must not be reversed,
 * rationale is documented in [NomiViewModel.setAppLanguage].
 */
@Composable
private fun LanguageSection(cfg: AppConfig, vm: NomiViewModel) {
    val context = LocalContext.current
    // Like NavRow: check the actually effective language to avoid a situation where no rows are checked because a non‑translated tag is stored
    val effective = cfg.appLanguage?.let { AppLanguages.resolve(it) }
    fun choose(tag: String?) {
        if (tag == cfg.appLanguage) return          // Tapping the current item does nothing; avoid unnecessarily recreating the UI.
        vm.setAppLanguage(tag) {
            (context as? Activity)?.let { AppLocale.applyAndRecreate(it) }
        }
    }
    Group("", footer = stringResource(R.string.language_footer)) {
        LanguageRow(
            label = stringResource(R.string.language_follow_system),
            selected = effective == null,
        ) { choose(null) }
        AppLanguages.SUPPORTED.forEachIndexed { i, lang ->
            LanguageRow(
                label = lang.autonym,
                selected = effective.equals(lang.tag, ignoreCase = true),
                divider = i != AppLanguages.SUPPORTED.lastIndex,
            ) { choose(lang.tag) }
        }
    }
}

/** A single language row: name on the left, checkmark on the right when selected. */
@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (divider) RowDivider()
}

/** Display name / one-line description of each theme; strings `theme_<tag>` and `theme_<tag>_subtitle` like iOS. */
private val AppTheme.titleRes: Int
    get() = when (this) {
        AppTheme.CRIMSON -> R.string.theme_crimson
        AppTheme.GRAPHITE -> R.string.theme_graphite
        AppTheme.OCEAN -> R.string.theme_ocean
        AppTheme.FOREST -> R.string.theme_forest
        AppTheme.VIOLET -> R.string.theme_violet
        AppTheme.AMBER -> R.string.theme_amber
    }

private val AppTheme.subtitleRes: Int
    get() = when (this) {
        AppTheme.CRIMSON -> R.string.theme_crimson_subtitle
        AppTheme.GRAPHITE -> R.string.theme_graphite_subtitle
        AppTheme.OCEAN -> R.string.theme_ocean_subtitle
        AppTheme.FOREST -> R.string.theme_forest_subtitle
        AppTheme.VIOLET -> R.string.theme_violet_subtitle
        AppTheme.AMBER -> R.string.theme_amber_subtitle
    }

/**
 * Colour theme picker (iOS ThemePickerView). Each row paints its own swatch — a gradient circle with a
 * small control mock — so the choice is made by looking. Picking writes `config.json` at once and the
 * root [com.nomily.app.ui.theme.NomilyTheme] repaints without a relaunch.
 */
@Composable
private fun ThemeSection(cfg: AppConfig, vm: NomiViewModel) {
    val current = AppTheme.resolve(cfg.appTheme)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Group("", footer = stringResource(R.string.theme_footer)) {
        AppTheme.entries.forEachIndexed { i, theme ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { if (theme != current) vm.setAppTheme(theme.tag) }
                    .heightIn(min = NomiDimens.rowMinHeight)
                    .padding(horizontal = NomiDimens.rowInset, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(theme.accentColor(dark), theme.deepColor(dark)),
                            ),
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.width(16.dp).height(6.dp)
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f), RoundedCornerShape(50)),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(stringResource(theme.titleRes), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(theme.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (theme == current) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = theme.accentColor(dark),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (i != AppTheme.entries.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun RecordingsSection(cfg: AppConfig, vm: NomiViewModel) {
    // The first card on this screen **has no group title** (the page title already indicates "Recording & Transfer"),
    // so no title is provided here; order: delete switch → encryption passphrase (appended by the caller) → fast transfer threshold.
    Group("") {
        ToggleRow(
            stringResource(R.string.rec_settings_delete_after_transfer),
            info = stringResource(R.string.rec_settings_delete_after_transfer_footer),
            checked = cfg.autoDeleteAfterTransfer,
            divider = false,
        ) { on -> vm.updateConfig { it.copy(autoDeleteAfterTransfer = on) } }
    }
}

/**
 * Fast transfer threshold — **single-row Picker**: current value displayed on the right with up/down arrows, six fixed options
 * (0/256/512/1024/2048/5120, where 0 means notify as soon as there is pending transfer).
 */
@Composable
private fun FastTransferThresholdSection(cfg: AppConfig, vm: NomiViewModel) {
    val options = listOf(0, 256, 512, 1024, 2048, 5120)
    fun format(kb: Int): String = if (kb < 1024) "$kb KB" else {
        val mb = kb / 1024.0
        if (mb == Math.floor(mb)) "${mb.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
    }
    var open by remember { mutableStateOf(false) }
    Group("") {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.rec_settings_fast_transfer_threshold),
                style = MaterialTheme.typography.bodyLarge,
            )
            InfoTip(stringResource(R.string.rec_settings_fast_transfer_threshold_footer))
            Spacer(Modifier.weight(1f))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        format(cfg.fastTransferThresholdKb),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.width(160.dp),
                ) {
                    options.forEach { kb ->
                        DropdownMenuItem(
                            text = { Text(format(kb)) },
                            onClick = {
                                vm.updateConfig { it.copy(fastTransferThresholdKb = kb) }
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fast‑transfer hotspot credentials — the "Wi‑Fi AP (fast transfer)" section: SSID and password are editable and persisted.
 *
 * The purpose is **manual hotspot connection**: after the device starts an AP, if auto‑join fails, the user can manually connect using these two lines in the system Wi‑Fi settings. If the credentials change each time, the stored system record becomes invalid.
 * Leaving them empty causes a pair (`dnote-xxxx`) to be auto‑generated on the next fast transfer and written back here.
 */
@Composable
private fun WifiApSection(cfg: AppConfig, vm: NomiViewModel) {
    // When entering the page, fill in empty credentials; otherwise these two rows remain blank,
    // and the user has no SSID/password to copy in system Wi‑Fi, requiring the first fast transfer to generate them.
    // Format: `DNOTE-` + 4 uppercase hex bytes for SSID, password is 8 uppercase hex bytes.
    var showGenerated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ap = cfg.wifiAp
        if (ap == null || (ap.ssid.isEmpty() && ap.psk.isEmpty())) {
            vm.updateConfig {
                it.copy(wifiAp = AppConfig.WifiAp("DNOTE-${randomHex(4)}", randomHex(8)))
            }
            showGenerated = true
        }
    }
    if (showGenerated) {
        AlertDialog(
            onDismissRequest = { showGenerated = false },
            title = { Text(stringResource(R.string.rec_settings_wifi_generated)) },
            text = { Text(stringResource(R.string.rec_settings_wifi_generated_message)) },
            confirmButton = {
                TextButton(onClick = { showGenerated = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
    Group(
        stringResource(R.string.rec_settings_wifi_ap),
        trailing = { InfoTip(stringResource(R.string.rec_settings_wifi_credentials_footer)) },
    ) {
        Field(stringResource(R.string.rec_settings_ssid), cfg.wifiAp?.ssid ?: "") { new ->
            vm.updateConfig {
                it.copy(wifiAp = AppConfig.WifiAp(new, it.wifiAp?.psk ?: ""))
            }
        }
        Field(
            stringResource(R.string.rec_settings_password), cfg.wifiAp?.psk ?: "",
            secure = true, divider = false,
        ) { new ->
            vm.updateConfig {
                it.copy(wifiAp = AppConfig.WifiAp(it.wifiAp?.ssid ?: "", new))
            }
        }
    }
    // WPA2 passphrase must be 8–63 characters. This field is editable; shortening it will cause the system to reject the fast transfer
    // (`invalid WPA/WPA2 Passphrase.`). Explain this immediately rather than failing later.
    val psk = cfg.wifiAp?.psk.orEmpty()
    if (psk.isNotEmpty() && psk.length !in 8..63) {
        Text(
            stringResource(R.string.fast_transfer_invalid_psk),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 6.dp),
        )
    }
}

// ── Devices & Library ────────────────────────────────────────────────
@Composable
private fun DevicesSection(cfg: AppConfig, vm: NomiViewModel) {
    // The card's title is "Connection" (not the page name); the description text is **outside the card**
    Group(stringResource(R.string.device_connection)) {
        ToggleRow(
            stringResource(R.string.devices_auto_reconnect),
            checked = cfg.autoReconnectEnabled,
            divider = false,
        ) { on -> vm.setAutoReconnect(on) }
    }
    GroupFooter(stringResource(R.string.devices_auto_reconnect_footer))
}

/**
 * Gray footnote **outside** the card.
 *
 * Indented 16dp on both left and right — the card itself starts at x=16, and the footnote text aligns vertically with the card's row text.
 */
@Composable
private fun GroupFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

// ── Encryption Passphrase ───────────────────────────────────────────────────────
//
// This reflects the **local side** status: it applies even when the device is not connected. The device‑side encryption toggle is a firmware behavior,
// not changed here.
@Composable
private fun EncryptionSection(ui: NomiViewModel.Ui, vm: NomiViewModel) {
    Group(stringResource(R.string.encryption_section_header)) {
        val connected = !ui.deviceSn.isNullOrEmpty()

        Row(
            Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.encryption_local_key),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    if (ui.hasLocalKey) R.string.encryption_configured else R.string.encryption_missing,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (ui.hasLocalKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        RowDivider()

        // "How do I know my passphrase?" — you don’t, and the app can’t show it: only a derived key is stored, the passphrase itself is never persisted. Rather than providing a never‑existent "view" button, we explain it here.
        if (ui.hasLocalKey) {
            Text(
                stringResource(R.string.encryption_cannot_reveal_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = NomiDimens.rowInset,
                    vertical = 8.dp,
                ),
            )
            RowDivider()
        }

        // Three actions follow the "Local key" within the same card — order:
        // Local key → Set passphrase → Rotate → Clear. Device‑side encryption is **a separate section** (see `DeviceEncryptionSection` below),
        // originally sandwiched between the local key and these three actions, splitting the section in two.
        // When the device is already encrypted, this flow performs **verification**: record a probe, attempt decryption with the entered passphrase, and only store it locally if correct.
        // Naming the entry "Set passphrase" again would mislead users into thinking they are creating a new one.
        ActionRow(
            stringResource(
                if (ui.encryptionOn == true) R.string.encryption_verify_button
                else R.string.encryption_set_button
            ),
            enabled = connected,
        ) {
            vm.needPassphrase(true, NomiViewModel.PassphraseMode.SET)
        }
        // Straight to the rotate sheet: it carries the irreversibility warning, and the final confirm counts the recordings at risk.
        ActionRow(stringResource(R.string.encryption_rotate_button), enabled = connected) {
            vm.needPassphrase(true, NomiViewModel.PassphraseMode.ROTATE)
        }
        ActionRow(
            stringResource(R.string.encryption_clear_button),
            enabled = ui.hasLocalKey,
            destructive = true,
            divider = false,
        ) { vm.clearLocalKey() }

    }
    GroupFooter(stringResource(R.string.encryption_section_footer))
}

/**
 * Device-side encryption status — read-only: **its own section**, no group heading,
 * shown only when the device is connected and has reported a status; while enabled, a firmware note is appended outside the card.
 */
@Composable
private fun DeviceEncryptionSection(ui: NomiViewModel.Ui) {
    val on = ui.encryptionOn ?: return
    Group("") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.device_encryption_state_row),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (on) R.string.device_encryption_state_on else R.string.device_encryption_state_off),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (on) GroupFooter(stringResource(R.string.device_encryption_firmware_footer))
}

// ── Known devices ─────────────────────────────────────────────────────────
@Composable
private fun KnownDevicesSection(cfg: AppConfig, vm: NomiViewModel) {
    Group(stringResource(R.string.devices_known_devices)) {
        if (cfg.devices.isEmpty()) {
            Text(
                stringResource(R.string.devices_no_devices_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 12.dp),
            )
        }
        cfg.devices.entries
            .sortedByDescending { it.value.lastConnected ?: "" }
            .forEach { (id, rec) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, style = MaterialTheme.typography.bodyLarge)
                        Text(id, style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        rec.lastConnected?.let {
                            Text(stringResource(R.string.devices_last_seen, it),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // Provide an explicit delete button
                    TextButton(onClick = { vm.forgetDevice(id) }) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            }
    }
}

// ── Database Statistics ────────────────────────────────────────────────────────
@Composable
private fun LibrarySection(ui: NomiViewModel.Ui) {
    // ⓘ Follow the **group header**, not the "File" line
    Group(
        stringResource(R.string.devices_library),
        trailing = { InfoTip(stringResource(R.string.devices_library_footer)) },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.devices_files), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Text(
                "${ui.clips.size}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.devices_total_size), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Text(
                formatClipBytes(ui.clips.sumOf { it.sizeBytes }),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Tiered Batch Cleanup ──────────────────────────────────────────────────────
@Composable
private fun LibraryDangerSection(vm: NomiViewModel) {
    Group(stringResource(R.string.devices_danger_zone)) {
        var pending by remember { mutableStateOf<Cleanup?>(null) }
        // These three lines are **destructive** (red text entire line), not an accent color button
        Cleanup.entries.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().clickable { pending = c }.heightIn(min = NomiDimens.rowMinHeight).padding(horizontal = NomiDimens.rowInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(c.title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (i < Cleanup.entries.lastIndex) RowDivider()
        }
        pending?.let { c ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(c.title)) },
                text = { Text(stringResource(c.message)) },
                confirmButton = {
                    TextButton(onClick = { vm.cleanupLibrary(c.target); pending = null }) {
                        Text(stringResource(R.string.common_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    }
}

/** Four-level cleanup — `target == null` means "all". */
private enum class Cleanup(val title: Int, val message: Int, val target: ClipArtefact?) {
    AUDIO(R.string.devices_clear_audio, R.string.devices_clear_audio_message, ClipArtefact.AUDIO),
    TRANSCRIPTS(R.string.devices_clear_transcripts, R.string.devices_clear_transcripts_message, ClipArtefact.TRANSCRIPT),
    SUMMARIES(R.string.devices_clear_summaries, R.string.devices_clear_summaries_message, ClipArtefact.SUMMARY),
    ALL(R.string.devices_clear_all_data, R.string.devices_clear_all_data_message, null),
}

// ── About (Version + 7-tap Developer Mode) ──────────────────────────────────
@Composable
private fun AboutSection(cfg: AppConfig, vm: NomiViewModel) {
    Group(stringResource(R.string.settings_about)) {
        var taps by remember { mutableIntStateOf(0) }
        var toast by remember { mutableStateOf<String?>(null) }
        val toastEnabled = stringResource(R.string.developer_mode_toast_enabled)
        val toastDisabled = stringResource(R.string.developer_mode_toast_disabled)

        // Tapping the version number 7 times toggles developer mode. Three changes:
        // ① The **entire line** is now a clickable area (users shouldn't need to aim at the version number string);
        //    Previously, only the TextButton was clickable and had an accent color, making it look like a button;
        // ② Hints about remaining taps only appear after 3 taps (previously, hints appeared on the 1st tap);
        // ③ If there's no activity for 2 seconds, the counter resets and the hint disappears. After switching, a toast message appears and auto-dismisses.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    taps += 1
                    if (taps >= TAPS_TO_TOGGLE) {
                        taps = 0
                        val on = !cfg.developerMode
                        vm.updateConfig { it.copy(developerMode = on) }
                        toast = if (on) toastEnabled else toastDisabled
                    }
                }
                .heightIn(min = NomiDimens.rowMinHeight)
                .padding(horizontal = NomiDimens.rowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                // Corresponds to versionName (versionCode) — originally hardcoded as "1.0", so changing the version number won't update it.
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The click count and toast will auto-update after 2 seconds
        LaunchedEffect(taps, toast) {
            if (taps == 0 && toast == null) return@LaunchedEffect
            kotlinx.coroutines.delay(2000)
            taps = 0
            toast = null
        }
        val toastText = toast
        if (toastText != null) {
            Text(
                toastText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        } else if (cfg.developerMode) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = DeveloperModeAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.developer_mode_status_on),
                    style = MaterialTheme.typography.labelSmall,
                    color = DeveloperModeAccent,
                )
            }
        } else if (taps >= TAPS_BEFORE_HINT) {
            Text(
                stringResource(
                    if (cfg.developerMode) R.string.developer_mode_taps_to_disable else R.string.developer_mode_taps_to_enable,
                    TAPS_TO_TOGGLE - taps,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

private const val TAPS_TO_TOGGLE = 7
private const val TAPS_BEFORE_HINT = 3

/** Uppercase hex of n bytes (used for automatic Wi-Fi credential generation). */
private fun randomHex(bytes: Int): String =
    (0 until bytes).joinToString("") { "%02X".format(kotlin.random.Random.nextInt(256)) }

/** The "Developer mode enabled" line uses a fixed orange color, not following the theme's accent color. */
private val DeveloperModeAccent = androidx.compose.ui.graphics.Color(0xFFFF9500)

/** "Transcription queue": how many are running, waiting and gave up, plus why the first one gave up. */
@Composable
private fun TranscribeQueueRow(pending: List<com.nomily.app.jobs.WorkJob>, inFlight: Set<String>) {
    val running = pending.count { it.id in inFlight }
    val waiting = pending.count { it.finishedAt == null && it.id !in inFlight }
    val stuck = pending.filter { it.finishedAt != null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running > 0) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(R.string.asr_queue_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                listOfNotNull(
                    if (running > 0) stringResource(R.string.asr_queue_running, running) else null,
                    if (waiting > 0) stringResource(R.string.asr_queue_waiting, waiting) else null,
                    if (stuck.isNotEmpty()) stringResource(R.string.asr_queue_stuck, stuck.size) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only the first that gave up: listing them all would fill the screen, and what matters is knowing something is stuck.
        stuck.firstOrNull()?.let { j ->
            Text(
                stringResource(
                    R.string.asr_queue_stuck_detail,
                    com.nomily.app.ble.RecordingName.displayTitle(j.clip),
                    j.attempts,
                    j.lastError.orEmpty(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
