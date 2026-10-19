package com.nomily.app.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobQueueTest {

    private val dir: File = Files.createTempDirectory("jobs").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun queue() = JobQueue(File(dir, "jobs.json"), scope)

    private suspend fun JobQueue.awaitFinished(id: String): WorkJob = withTimeout(60_000) {
        while (true) {
            jobs.value.firstOrNull { it.id == id && it.finishedAt != null }?.let { return@withTimeout it }
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun successRecordsNotesAndPersists() = runBlocking {
        val q = queue()
        q.register(JobKind.SUMMARIZE) { _, r -> r.progress(1, 2); r.notes(listOf("fell back"), true) }
        q.enqueue(WorkJob.summarize("c1", "p", "t"))
        val done = q.awaitFinished("summarize:c1")
        assertNull(done.lastError)
        assertEquals(listOf("fell back"), done.notes)
        assertTrue(done.wasTruncated)
        assertEquals(JobQueue.State.DONE, q.state(done))
        // Survives a restart.
        assertEquals("p", queue().jobs.value.single().prompt)
    }

    @Test
    fun sourceMissingIsDroppedNotRetried() = runBlocking {
        val q = queue()
        var calls = 0
        q.register(JobKind.TRANSLATE) { _, _ -> calls++; throw JobSourceMissing("gone") }
        q.enqueue(WorkJob.translate("c2", "summary", "French"))
        withTimeout(10_000) { while (q.jobs.value.isNotEmpty()) delay(20) }
        assertEquals(1, calls)
    }

    @Test
    fun failureGivesUpAfterThreeAttemptsThenRetryWorks() = runBlocking {
        val q = queue()
        var fail = true
        var calls = 0
        q.register(JobKind.EXPORT) { _, _ -> calls++; if (fail) error("disk full") }
        q.enqueue(WorkJob.export("c3", "wav"))
        val gaveUp = q.awaitFinished("export:c3")
        assertEquals(3, calls)
        assertEquals("disk full", gaveUp.lastError)
        assertEquals(JobQueue.State.GAVE_UP, q.state(gaveUp))
        fail = false
        q.retry("export:c3")
        delay(50)
        assertNull(q.awaitFinished("export:c3").lastError)
    }

    @Test
    fun oneAtATimeNewestFirst() = runBlocking {
        val q = queue()
        val order = mutableListOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        q.register(JobKind.SUMMARIZE) { j, _ -> if (j.clip == "first") gate.await(); synchronized(order) { order += j.clip } }
        q.register(JobKind.TRANSCRIBE) { j, _ -> synchronized(order) { order += j.clip } }
        q.enqueue(WorkJob.summarize("first", "p", "t"))
        delay(100)   // "first" is running and holds the single lane
        q.enqueue(WorkJob.transcribe("older", null, false))
        q.enqueue(WorkJob.summarize("newer", "p", "t"))
        gate.complete(Unit)
        q.awaitFinished("transcribe:older")
        // Transcription shares the lane, and what was asked for last runs next.
        assertEquals(listOf("first", "newer", "older"), order)
    }
}
