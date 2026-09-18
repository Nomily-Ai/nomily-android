package com.nomily.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nomily.app.R
import com.nomily.app.llm.LanguageService

/**
 * Pre-select candidate languages before transcription.
 *
 * **Applies only to this transcription, not persisted**: canceling or selecting none means not passing `locales`,
 * in which case Azure will use its automatic detection among its 15 locales. Maximum of 4.
 */
@Composable
fun AzureLocalesPicker(initial: List<String>, onDone: (List<String>?) -> Unit) {
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(initial.toSet()) }
    val catalog = LanguageService.AZURE_MULTI_LANGUAGE_LOCALES
    val filtered = remember(search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) {
            catalog
        } else {
            catalog.filter {
                it.name.lowercase().contains(q) ||
                    it.nativeName.lowercase().contains(q) ||
                    it.code.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.asr_specify_languages)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.live_search_languages)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.asr_max_languages_hint, LanguageService.AZURE_MAX_SELECTIONS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it.code }) { lang ->
                        val on = lang.code in picked
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    picked = when {
                                        on -> picked - lang.code
                                        // When full, stop adding (no error popup, just ignore)
                                        picked.size >= LanguageService.AZURE_MAX_SELECTIONS -> picked
                                        else -> picked + lang.code
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(lang.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    lang.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (on) Text("✓", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                // Azure determines language per sentence: when the audio is actually monolingual, providing extra candidates can cause
                // some sentences to be classified as a neighboring language and transcribed with the wrong model (this was encountered by QA on Chinese audio).
                // If a second language is selected, explain it on the spot instead of leaving only a generic note.
                val multi = picked.size >= 2
                Text(
                    stringResource(
                        if (multi) R.string.asr_multi_language_warning
                        else R.string.asr_languages_footer
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (multi) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text(stringResource(R.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
