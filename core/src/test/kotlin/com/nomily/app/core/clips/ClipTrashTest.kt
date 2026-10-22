package com.nomily.app.core.clips

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipTrashTest {

    private val dir: File = Files.createTempDirectory("clips").toFile()
    private var clock = 1_000_000_000_000L
    private val manifest = ClipManifest(File(dir, ".manifest.json"))
    private val trash = ClipTrash(dir) { clock }

    private fun write(name: String, text: String = name) = File(dir, name).apply { writeText(text) }

    @Test
    fun wholeClipRoundTripRestoresFilesAndManifest() {
        val audio = write("20261016100000.ogg")
        write("20261016100000.txt")
        write("20261016100000.title", "Standup")
        write("20261016100000.speakers.json", """{"1":"Ann"}""")
        manifest.recordDownload("20261016100000.opus", 10, "ble", "SN12345678", "D·NOTE", audio.name)
        assertEquals(ClipManifest.Source.Kind.CARD, manifest.source(audio.name).kind)

        val moved = trash.move(
            listOf(
                ClipTrash.Payload(
                    audio.name, "Standup",
                    ClipTrash.wholeClipFiles(dir, "20261016100000", audio),
                    manifest.rawEntry(audio.name)!!.let { (k, e) ->
                        ClipTrash.Download(k.substringAfter("::"), e.size, e.transport, e.deviceSerial, e.deviceName, audio.name)
                    },
                    "D·NOTE-5678",
                ),
            ),
            manifest,
        )
        assertEquals(1, moved)
        assertFalse(audio.exists())
        assertEquals(ClipManifest.Source.Kind.IMPORTED, manifest.source(audio.name).kind)
        val item = trash.items.single()
        assertTrue(item.hasAudio && item.hasTranscript && !item.hasSummary)
        assertEquals(30, item.daysLeft(clock))

        // Reload from disk: the index survives a restart.
        val reopened = ClipTrash(dir) { clock }
        assertTrue(reopened.restore(reopened.items.single(), manifest, null))
        assertTrue(audio.exists())
        assertTrue(java.io.File(dir, "20261016100000.speakers.json").exists())
        assertEquals("Standup", File(dir, "20261016100000.title").readText())
        assertEquals("SN12345678", manifest.source(audio.name).serial)
        assertTrue(reopened.items.isEmpty())
    }

    @Test
    fun restoreRenamesWholeGroupOnClash() {
        val audio = write("a.ogg")
        write("a.txt")
        trash.move(listOf(ClipTrash.Payload("a.ogg", "a", listOf(audio, File(dir, "a.txt")), null, null)), manifest)
        write("a.ogg", "new")
        assertTrue(trash.restore(trash.items.single(), manifest, null))
        assertTrue(File(dir, "a-2.ogg").exists() && File(dir, "a-2.txt").exists())
        assertEquals("new", File(dir, "a.ogg").readText())
    }

    @Test
    fun expiredItemsArePurged() {
        trash.move(listOf(ClipTrash.Payload("b.txt", "b", listOf(write("b.txt")), null, null)), manifest)
        clock += 31L * 24 * 3600 * 1000
        assertEquals(1, trash.purgeExpired())
        assertTrue(trash.items.isEmpty())
        assertTrue(File(dir, ".trash").listFiles()!!.none { it.isDirectory })
    }

    @Test
    fun readStoreOnlyFlagsClipsAfterOrigin() {
        val store = ClipReadStore(File(dir, ".seen.json"), now = 100)
        assertFalse(store.isUnread("old.ogg", arrivedAt = 50))
        assertTrue(store.isUnread("new.ogg", arrivedAt = 150))
        store.markSeen("new.ogg")
        assertFalse(ClipReadStore(File(dir, ".seen.json")).isUnread("new.ogg", arrivedAt = 150))
    }
}
