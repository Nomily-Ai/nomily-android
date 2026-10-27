package com.nomily.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nomily.app.R

/**
 * Azure Speech provisioning guide.
 *
 * Three steps (create account / create Speech resource / obtain key and region) + free tier table.
 */
@Composable
fun AzureSetupGuide(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card-style popup: top leaves space for the status bar exposing the parent view, with rounded top corners
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
                        stringResource(R.string.azure_guide_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Step(
                        1,
                        stringResource(R.string.azure_guide_step1_title),
                        listOf(
                            stringResource(R.string.azure_guide_step1_item1),
                            stringResource(R.string.azure_guide_step1_item2),
                        ),
                    )
                    Step(
                        2,
                        stringResource(R.string.azure_guide_step2_title),
                        listOf(
                            stringResource(R.string.azure_guide_step2_item1),
                            stringResource(R.string.azure_guide_step2_item2),
                            stringResource(R.string.azure_guide_step2_item3),
                            stringResource(R.string.azure_guide_step2_item4),
                            stringResource(R.string.azure_guide_step2_item5),
                            stringResource(R.string.azure_guide_step2_item6),
                        ),
                    )
                    Step(
                        3,
                        stringResource(R.string.azure_guide_step3_title),
                        listOf(
                            stringResource(R.string.azure_guide_step3_item1),
                            stringResource(R.string.azure_guide_step3_item2),
                            stringResource(R.string.azure_guide_step3_item3),
                        ),
                    )
                    FreeTier()
                    Text("", modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.azure_guide_step, number, title),
            style = MaterialTheme.typography.titleSmall,
        )
        items.forEachIndexed { i, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
                Text(item, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FreeTier() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.azure_guide_free_tier_title), style = MaterialTheme.typography.titleSmall)
        HorizontalDivider()
        TierRow(
            stringResource(R.string.azure_guide_fast_transcription),
            stringResource(R.string.azure_guide_fast_transcription_value),
        )
        TierRow(
            stringResource(R.string.azure_guide_realtime_transcription),
            stringResource(R.string.azure_guide_realtime_transcription_value),
        )
        TierRow(
            stringResource(R.string.azure_guide_speaker_diarization),
            stringResource(R.string.azure_guide_speaker_diarization_value),
        )
        TierRow(
            stringResource(R.string.azure_guide_audio_formats),
            stringResource(R.string.azure_guide_audio_formats_value),
        )
    }
}

@Composable
private fun TierRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
