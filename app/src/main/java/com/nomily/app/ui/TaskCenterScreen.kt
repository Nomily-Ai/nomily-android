package com.nomily.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.ble.RecordingName
import com.nomily.app.jobs.JobKind
import com.nomily.app.jobs.JobQueue
import com.nomily.app.jobs.WorkJob
import com.nomily.app.ui.components.Footer
import com.nomily.app.ui.components.Group
import com.nomily.app.ui.components.NomiDimens
import com.nomily.app.ui.components.RowDivider

/**
 * Background tasks: what is queued or running (cancel), and what finished in the last day (gave-up ones offer
 * Try again). The rows describe the clip by its title and the task by kind and detail.
 */
@Composable
fun TaskCenterPage(vm: NomiViewModel, onPush: (PushedPage?) -> Unit, onDismiss: () -> Unit) {
    val jobs by vm.jobs.jobs.collectAsStateWithLifecycle()
    val running by vm.jobs.running.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val active = jobs.filter { it.finishedAt == null }.sortedByDescending { it.enqueuedAt }
    val finished = jobs.filter { it.finishedAt != null }.sortedByDescending { it.finishedAt }

    val title = stringResource(R.string.jobs_page_title)
    val clearLabel = stringResource(R.string.jobs_clear_finished)
    LaunchedEffect(title, finished.isEmpty()) {
        onPush(
            PushedPage(
                title = title,
                actions = { if (finished.isNotEmpty()) TextButton(onClick = { vm.jobs.clearFinished() }) { Text(clearLabel) } },
                onBack = onDismiss,
            ),
        )
    }
    DisposableEffect(Unit) { onDispose { onPush(null) } }
    BackHandler(onBack = onDismiss)

    fun titleOf(job: WorkJob): String =
        ui.clips.firstOrNull { it.base == job.clip }?.let { it.title ?: RecordingName.displayTitle(it.name) }
            ?: RecordingName.displayTitle(job.clip + ".ogg").takeIf { it != job.clip + ".ogg" } ?: job.clip

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NomiDimens.screenInset)) {
        if (active.isEmpty() && finished.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.jobs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        if (active.isNotEmpty()) {
            Group(stringResource(R.string.jobs_section_active)) {
                active.forEachIndexed { i, job ->
                    JobRow(job, titleOf(job), vm.jobs.state(job).let { if (job.id in running) JobQueue.State.RUNNING else it }, vm)
                    if (i < active.lastIndex) RowDivider()
                }
            }
        }
        if (finished.isNotEmpty()) {
            Group(stringResource(R.string.jobs_section_finished)) {
                finished.forEachIndexed { i, job ->
                    JobRow(job, titleOf(job), vm.jobs.state(job), vm)
                    if (i < finished.lastIndex) RowDivider()
                }
            }
        }
        Footer(stringResource(R.string.jobs_footer))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun JobRow(job: WorkJob, title: String, state: JobQueue.State, vm: NomiViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NomiDimens.rowInset, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(job.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            when (state) {
                JobQueue.State.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else -> Text(
                    stringResource(
                        when (state) {
                            JobQueue.State.WAITING -> R.string.jobs_status_waiting
                            JobQueue.State.DONE -> R.string.jobs_status_done
                            else -> R.string.jobs_status_gave_up
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state) {
                        JobQueue.State.DONE -> Color(0xFF34C759)
                        JobQueue.State.GAVE_UP -> Color(0xFFFF9500)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            listOfNotNull(stringResource(kindTitle(job.kind)), detail(job)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        job.lastError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9500), maxLines = 3, modifier = Modifier.padding(top = 2.dp))
        }
        if (job.progressDone != null && job.progressTotal != null) {
            LinearProgressIndicator(
                progress = { job.progressDone.toFloat() / maxOf(job.progressTotal, 1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                JobQueue.State.WAITING, JobQueue.State.RUNNING ->
                    TextButton(onClick = { vm.jobs.cancel(job.id) }) { Text(stringResource(R.string.jobs_cancel)) }
                JobQueue.State.GAVE_UP -> {
                    OutlinedButton(onClick = { vm.jobs.retry(job.id) }) { Text(stringResource(R.string.jobs_retry)) }
                    TextButton(onClick = { vm.jobs.forget(job.id) }) { Text(stringResource(R.string.jobs_remove)) }
                }
                JobQueue.State.DONE -> TextButton(onClick = { vm.jobs.forget(job.id) }) { Text(stringResource(R.string.jobs_remove)) }
            }
        }
    }
}

@Composable
private fun detail(job: WorkJob): String? = when (job.kind) {
    JobKind.SUMMARIZE -> job.templateName
    JobKind.TRANSLATE -> job.languageName?.let {
        stringResource(if (job.translateTarget == "summary") R.string.jobs_detail_translated_summary else R.string.jobs_detail_translated_transcript, it)
    }
    JobKind.EXPORT -> job.exportFormat?.uppercase()
    JobKind.TRANSCRIBE -> job.locales?.joinToString(", ")
}

private fun kindTitle(kind: JobKind) = when (kind) {
    JobKind.TRANSCRIBE -> R.string.jobs_kind_transcribe
    JobKind.SUMMARIZE -> R.string.jobs_kind_summarize
    JobKind.TRANSLATE -> R.string.jobs_kind_translate
    JobKind.EXPORT -> R.string.jobs_kind_export
}

private fun kindIcon(kind: JobKind) = when (kind) {
    JobKind.TRANSCRIBE -> Icons.Filled.GraphicEq
    JobKind.SUMMARIZE -> Icons.Filled.AutoAwesome
    JobKind.TRANSLATE -> Icons.Filled.Translate
    JobKind.EXPORT -> Icons.Filled.IosShare
}
