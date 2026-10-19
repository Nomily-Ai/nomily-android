package com.nomily.app.jobs

import android.util.Log
import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class JobKind { TRANSCRIBE, SUMMARIZE, TRANSLATE, EXPORT }

/**
 * One unit of background work on a clip. Everything the run needs is frozen at enqueue time (the prompt with its
 * language instruction, the target language, the export format) — the job may only run after a restart, by
 * which time the template may have changed.
 */
data class WorkJob(
    val id: String,
    val kind: JobKind,
    /** Clip base name. */
    val clip: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val progressDone: Int? = null,
    val progressTotal: Int? = null,
    /** Shown with the result: a fallback provider was used, the transcript was chunked. */
    val notes: List<String> = emptyList(),
    val wasTruncated: Boolean = false,
    val prompt: String? = null,
    val templateName: String? = null,
    val languageName: String? = null,
    /** `transcript` or `summary`. */
    val translateTarget: String? = null,
    /** `m4a` or `wav`. */
    val exportFormat: String? = null,
    val locales: List<String>? = null,
    val enforceMinDuration: Boolean = false,
) {
    val isRunnable: Boolean get() = finishedAt == null && attempts < JobQueue.MAX_ATTEMPTS

    companion object {
        fun transcribe(clip: String, locales: List<String>?, enforceMinDuration: Boolean) =
            WorkJob("transcribe:$clip", JobKind.TRANSCRIBE, clip, locales = locales, enforceMinDuration = enforceMinDuration)

        fun summarize(clip: String, prompt: String, templateName: String) =
            WorkJob("summarize:$clip", JobKind.SUMMARIZE, clip, prompt = prompt, templateName = templateName)

        fun translate(clip: String, target: String, languageName: String) =
            WorkJob("translate:$target:$clip", JobKind.TRANSLATE, clip, languageName = languageName, translateTarget = target)

        fun export(clip: String, format: String) = WorkJob("export:$clip", JobKind.EXPORT, clip, exportFormat = format)
    }
}

/** The job's input is gone (clip deleted, transcript cleared): it can never succeed, so it is dropped, not retried. */
class JobSourceMissing(message: String) : Exception(message)

/**
 * Persistent queue for transcription, summaries, translations and audio exports (`filesDir/jobs.json`).
 *
 * Two lanes run side by side — transcription, and everything else — each one job at a time. A failure is retried
 * with backoff up to [MAX_ATTEMPTS], then the job stays in the ledger as "gave up" with its error and a retry
 * button. Finished jobs are kept a day so the task page and the clip page can show the outcome.
 */
class JobQueue(private val file: File, private val scope: CoroutineScope) {

    enum class State { WAITING, RUNNING, DONE, GAVE_UP }

    /** Progress and result notes from inside a running job. */
    class Reporter(val progress: (Int, Int) -> Unit, val notes: (List<String>, Boolean) -> Unit)

    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<WorkJob>> = _jobs.asStateFlow()
    private val _running = MutableStateFlow(emptySet<String>())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val runners = mutableMapOf<JobKind, suspend (WorkJob, Reporter) -> Unit>()
    private val lanes = mutableMapOf<Boolean, CoJob>()
    private val handles = mutableMapOf<String, CoJob>()

    init { prune() }

    fun register(kind: JobKind, runner: suspend (WorkJob, Reporter) -> Unit) { runners[kind] = runner }

    /** Add a job, or re-arm the same id (summarising the same clip again replaces the old job). */
    @Synchronized
    fun enqueue(job: WorkJob) {
        _jobs.update { list ->
            val i = list.indexOfFirst { it.id == job.id }
            if (i < 0) listOf(job) + list else list.toMutableList().apply { this[i] = job }
        }
        save()
        drain()
    }

    fun state(job: WorkJob): State = when {
        job.id in _running.value -> State.RUNNING
        job.finishedAt != null -> if (job.lastError == null) State.DONE else State.GAVE_UP
        else -> State.WAITING
    }

    /** Stop a job. A cancelled job is finished without error, same as success, and its partial output is not written. */
    @Synchronized
    fun cancel(id: String) {
        handles.remove(id)?.cancel()
        mutate(id) { if (it.finishedAt != null) it else it.copy(finishedAt = now(), lastError = null, progressDone = null, progressTotal = null) }
    }

    @Synchronized
    fun retry(id: String) {
        mutate(id) { it.copy(attempts = 0, lastError = null, finishedAt = null, notes = emptyList(), wasTruncated = false, enqueuedAt = now()) }
        drain()
    }

    @Synchronized
    fun forget(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        save()
    }

    @Synchronized
    fun clearFinished() {
        _jobs.update { list -> list.filter { it.finishedAt == null } }
        save()
    }

    /** Jobs left from a previous run pick up again (call once at launch). */
    fun resume() = drain()

    @Synchronized
    private fun drain() {
        for (transcribeLane in listOf(true, false)) {
            if (lanes[transcribeLane]?.isActive == true) continue
            if (_jobs.value.none { it.isRunnable && (it.kind == JobKind.TRANSCRIBE) == transcribeLane }) continue
            lanes[transcribeLane] = scope.launch { runLane(transcribeLane) }
        }
    }

    private suspend fun runLane(transcribeLane: Boolean) {
        while (true) {
            // Oldest first: the ledger is newest first.
            val job = synchronized(this) {
                _jobs.value.lastOrNull { it.isRunnable && (it.kind == JobKind.TRANSCRIBE) == transcribeLane }
            } ?: return
            val runner = runners[job.kind]
            if (runner == null) {
                finish(job.id, "No runner for ${job.kind}")
                continue
            }
            _running.update { it + job.id }
            val reporter = Reporter(
                progress = { done, total -> mutate(job.id, persist = false) { it.copy(progressDone = done, progressTotal = total) } },
                notes = { notes, truncated -> mutate(job.id) { it.copy(notes = notes, wasTruncated = truncated) } },
            )
            val handle = scope.async { runner(job, reporter) }
            synchronized(this) { handles[job.id] = handle }
            val failure = try {
                handle.await()
                null
            } catch (e: CancellationException) {
                // The lane itself being cancelled (scope gone) ends the loop; the job being cancelled is a clean finish.
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Throwable) {
                e
            }
            synchronized(this) { handles.remove(job.id) }
            _running.update { it - job.id }
            when (failure) {
                null -> finish(job.id, null)
                is JobSourceMissing -> {
                    log("Dropping ${job.id}: ${failure.message}")
                    forget(job.id)
                }
                else -> {
                    val reason = failure.message ?: failure.javaClass.simpleName
                    val attempts = (_jobs.value.firstOrNull { it.id == job.id }?.attempts ?: 0) + 1
                    mutate(job.id) { it.copy(attempts = attempts, lastError = reason) }
                    if (attempts >= MAX_ATTEMPTS) {
                        log("Giving up on ${job.id} after $attempts attempts: $reason")
                        finish(job.id, reason)
                    } else {
                        delay(BACKOFF_MS.getOrElse(attempts - 1) { BACKOFF_MS.last() })
                    }
                }
            }
        }
    }

    /** `android.util.Log` throws in plain JVM tests; logging must never take a lane down. */
    private fun log(message: String) { runCatching { Log.i(TAG, message) } }

    private fun finish(id: String, error: String?) =
        mutate(id) { it.copy(finishedAt = now(), lastError = error, progressDone = null, progressTotal = null) }

    @Synchronized
    private fun mutate(id: String, persist: Boolean = true, f: (WorkJob) -> WorkJob) {
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    private fun prune() {
        val cutoff = now() - FINISHED_RETENTION_MS
        var kept = 0
        val list = _jobs.value.filter { j ->
            val f = j.finishedAt ?: return@filter true
            f >= cutoff && ++kept <= FINISHED_LIMIT
        }
        if (list.size != _jobs.value.size) { _jobs.value = list; save() }
    }

    private fun load(): List<WorkJob> = runCatching {
        (JsonParser(file.readText()).parse() as? List<*>).orEmpty().mapNotNull { decode(it as? Map<*, *>) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(writeJson(_jobs.value.map(::encode)))
            tmp.renameTo(file)
        }.onFailure { log("Saving the job ledger failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "JobQueue"
        const val MAX_ATTEMPTS = 3
        private const val FINISHED_RETENTION_MS = 24L * 3600 * 1000
        private const val FINISHED_LIMIT = 60
        private val BACKOFF_MS = listOf(2_000L, 8_000L, 30_000L)

        private fun now() = System.currentTimeMillis()

        private fun encode(j: WorkJob): Map<String, Any?> = buildMap {
            put("id", j.id); put("kind", j.kind.name); put("clip", j.clip); put("attempts", j.attempts)
            put("enqueued_at", j.enqueuedAt)
            j.lastError?.let { put("last_error", it) }
            j.finishedAt?.let { put("finished_at", it) }
            if (j.notes.isNotEmpty()) put("notes", j.notes)
            if (j.wasTruncated) put("was_truncated", true)
            j.prompt?.let { put("prompt", it) }
            j.templateName?.let { put("template_name", it) }
            j.languageName?.let { put("language_name", it) }
            j.translateTarget?.let { put("translate_target", it) }
            j.exportFormat?.let { put("export_format", it) }
            j.locales?.let { put("locales", it) }
            if (j.enforceMinDuration) put("enforce_min_duration", true)
        }

        private fun decode(m: Map<*, *>?): WorkJob? {
            m ?: return null
            return WorkJob(
                id = m["id"] as? String ?: return null,
                kind = runCatching { JobKind.valueOf(m["kind"] as String) }.getOrNull() ?: return null,
                clip = m["clip"] as? String ?: return null,
                attempts = (m["attempts"] as? Number)?.toInt() ?: 0,
                lastError = m["last_error"] as? String,
                enqueuedAt = (m["enqueued_at"] as? Number)?.toLong() ?: now(),
                finishedAt = (m["finished_at"] as? Number)?.toLong(),
                notes = (m["notes"] as? List<*>).orEmpty().filterIsInstance<String>(),
                wasTruncated = m["was_truncated"] == true,
                prompt = m["prompt"] as? String,
                templateName = m["template_name"] as? String,
                languageName = m["language_name"] as? String,
                translateTarget = m["translate_target"] as? String,
                exportFormat = m["export_format"] as? String,
                locales = (m["locales"] as? List<*>)?.filterIsInstance<String>(),
                enforceMinDuration = m["enforce_min_duration"] == true,
            )
        }
    }
}
