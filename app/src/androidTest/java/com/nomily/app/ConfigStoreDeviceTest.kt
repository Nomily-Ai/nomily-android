package com.nomily.app

import androidx.test.core.app.ApplicationProvider
import com.nomily.app.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [ConfigStore] real-device test — verifies that P0 #8 “configuration persistence” **on a real file system** holds.
 *
 * Parsing/serialization itself is covered in `:core`'s `AppConfigTest` (pure JVM unit test);
 * Here we only verify the three things that `:core` cannot test: **actually persisted to disk, debounce truly merged writes, truly atomic replacement**.
 */
class ConfigStoreDeviceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var store: ConfigStore
    private lateinit var file: File

    private fun app() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        file = File(app().filesDir, "config.json")
        file.delete()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        store = ConfigStore(app(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        File(app().filesDir, "config.json.tmp").delete()
    }

    /** No file should be created without reason when no configuration has been modified — creating one indicates an unnecessary write. */
    @Test
 fun createsNoFileWhenNothingIsWritten() {
        assertFalse("Construction alone already wrote to disk", file.exists())
        assertEquals(true, store.config.value.autoReconnectEnabled)
    }

    /** Change once → after 250 ms it is flushed to disk; reading the memory value immediately after the change should already reflect the update (UI does not wait for debounce). */
    @Test
    fun oneChangeIsFlushedAndVisibleInMemoryImmediately() = runBlocking {
        store.setAutoReconnect(false)
        assertEquals("In-memory value should change immediately", false, store.config.value.autoReconnectEnabled)
        assertFalse("Should not be on disk yet inside the debounce window", file.exists())

        delay(ConfigStore.DEBOUNCE_MS + 400)
    assertTrue("Should be persisted after debounce window", file.exists())
    assertTrue("""\"auto_reconnect_enabled\":false should be in file""",
            file.readText().replace(" ", "").contains("\"auto_reconnect_enabled\":false"))
    }

    /**
     * **The purpose of debouncing**: after 20 consecutive changes, write to disk only once.
     *
     * The criterion is not whether the file is correct (that would also be true after 20 writes), but **how many times** it was written ——
     * Using the file's mtime count: if written only once, after 20 modifications the mtime will have a single value.
     * Here we use a more direct method: after the 20th change, immediately check that the file **does not exist** (all 20 changes were merged within the window),
     * then after the window passes, the file appears and its content is the **last** value.
     */
    @Test
    fun consecutiveChangesAreMergedIntoASingleWrite() = runBlocking {
        repeat(20) { i ->
            store.update { it.copy(minTranscribeDuration = i) }
            delay(10)                    // Total 200ms < 250ms window
        }
    assertFalse("All 20 modifications within debounce window, should not have persisted yet", file.exists())

        delay(ConfigStore.DEBOUNCE_MS + 400)
        val text = file.readText().replace(" ", "")
    assertTrue("Persisted value should be the last one 19, not an intermediate one", text.contains("\"min_transcribe_duration\":19"))
    }

    /** Atomic replace: after writing, no `.tmp` should be left, and the file must be parsable (not a partial JSON). */
    @Test
    fun atomicReplaceLeavesNoTempFile() = runBlocking {
        store.rememberDevice("AA:BB:CC:DD:EE:FF", "D·NOTE", "AABBCC")
        store.saveNow()

    assertFalse("Should not leave .tmp", File(app().filesDir, "config.json.tmp").exists())

        // Read back a brand‑new store from disk — this is the true path that remains after restarting the app
        val reread = ConfigStore(app(), scope).config.value
        val rec = reread.devices["AA:BB:CC:DD:EE:FF"]
        assertEquals("D·NOTE", rec?.name)
        assertEquals("AABBCC", rec?.deviceSid)
    assertTrue("last_connected should be overwritten", (rec?.lastConnected?.length ?: 0) >= 20)
    }

    /**
     * The timestamp format must be comparable in lexicographic order as a time sequence — `mostRecentDevice()` picks it this way.
     *
     * Here we record two devices consecutively; the later one must be selected.
     */
    @Test
    fun timestampFormatSortsLexicographicallyByRecency() = runBlocking {
        store.rememberDevice("MAC-A", "Connected first", "AAAAAA")
        delay(5)
        store.rememberDevice("MAC-B", "Connected second", "BBBBBB")
        store.saveNow()

        val reread = ConfigStore(app(), scope).config.value
        assertEquals("MAC-B", reread.mostRecentDevice()?.first)
        assertTrue(
    "Format should be ISO-8601 with milliseconds UTC (ending with Z)",
            reread.devices["MAC-B"]!!.lastConnected!!.endsWith("Z"),
        )
    }
}
