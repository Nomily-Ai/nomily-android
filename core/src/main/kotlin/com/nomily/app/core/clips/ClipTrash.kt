package com.nomily.app.core.clips

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import com.nomily.app.core.asr.ClipChapters
import com.nomily.app.core.asr.SpeakerNames
import java.io.File
import java.util.UUID

/**
 * Deleted clips sit in `clips/.trash/<id>/` for [RETENTION_DAYS] days, then are purged.
 *
 * Files are moved, not marked: the library scan never sees them, and a restore puts them back byte for byte.
 * Expired items are purged at app launch, when the trash is opened and on every move — there is no background
 * task to hang it on.
 */
class ClipTrash(private val clipsDir: File, private val now: () -> Long = System::currentTimeMillis) {

    data class Download(
        val name: String,
        val size: Long,
        val transport: String,
        val deviceSerial: String?,
        val deviceName: String?,
        val localName: String,
    )

    data class Item(
        val id: String,
        /** Base shared by every file in the group; a restore rebuilds names from it. */
        val base: String,
        val files: List<String>,
        val deletedAt: Long,
        val title: String,
        val bytes: Long,
        /** Non-null when this deletion took the audio of a device download; the restore re-registers it. */
        val download: Download?,
        /** Source label fixed at deletion time ("D·NOTE-4C6F", "Imported", …). */
        val source: String?,
    ) {
        val expiresAt: Long get() = deletedAt + RETENTION_DAYS * DAY_MS

        /** Days left, rounded up: with three hours left it still says one day. */
        fun daysLeft(now: Long): Int = maxOf(0, ((expiresAt - now + DAY_MS - 1) / DAY_MS).toInt())

        val hasAudio: Boolean get() = files.any { ClipArtefact.of(it) == ClipArtefact.AUDIO }
        val hasTranscript: Boolean get() = files.any { ClipArtefact.of(it) == ClipArtefact.TRANSCRIPT }
        val hasSummary: Boolean get() = files.any { ClipArtefact.of(it) == ClipArtefact.SUMMARY }
    }

    /** One deletion: the files to move (missing ones are skipped) plus what a restore needs. */
    data class Payload(
        val localName: String,
        val title: String,
        val files: List<File>,
        val download: Download?,
        val source: String?,
    )

    private val trashDir = File(clipsDir, ".trash")
    private val indexFile = File(trashDir, "index.json")

    @get:Synchronized
    var items: List<Item> = load()
        private set

    /** Move into the trash; returns how many groups moved. The manifest entry goes only after the files did. */
    @Synchronized
    fun move(payloads: List<Payload>, manifest: ClipManifest): Int {
        val added = mutableListOf<Item>()
        for (p in payloads) {
            val present = p.files.filter { it.exists() }
            if (present.isEmpty()) continue
            val id = UUID.randomUUID().toString()
            val dir = File(trashDir, id)
            val moved = mutableListOf<File>()
            try {
                if (!dir.mkdirs()) error("mkdir $dir")
                for (f in present) {
                    val dest = File(dir, f.name)
                    if (!f.renameTo(dest)) error("move ${f.name}")
                    moved += dest
                }
                added += Item(
                    id = id,
                    base = p.localName.substringBeforeLast('.'),
                    files = moved.map { it.name },
                    deletedAt = now(),
                    title = p.title,
                    bytes = moved.sumOf { it.length() },
                    download = p.download,
                    source = p.source,
                )
                if (p.download != null) manifest.forget(p.localName)
            } catch (_: Exception) {
                // Half an item is worse than none: put back what already moved.
                moved.forEach { it.renameTo(File(clipsDir, it.name)) }
                dir.deleteRecursively()
            }
        }
        if (added.isNotEmpty()) {
            items = items + added
            save()
        }
        purgeExpired()
        return added.size
    }

    /**
     * Put an item back. On a name clash the whole group moves to `base-2` together, so sidecars still pair with
     * the audio. Returns false (and leaves the item in the trash) when any file fails to move.
     */
    @Synchronized
    fun restore(item: Item, manifest: ClipManifest, readStore: ClipReadStore?): Boolean {
        val dir = File(trashDir, item.id)
        if (!dir.isDirectory) { forget(item); return false }
        val suffixes = item.files.map { if (it.startsWith(item.base)) it.drop(item.base.length) else null }
        val targetBase = availableBase(item.base, suffixes.filterNotNull())
        val movedBack = mutableListOf<Pair<File, File>>()
        var audioName: String? = null
        for ((i, name) in item.files.withIndex()) {
            val newName = suffixes[i]?.let { targetBase + it } ?: name
            val from = File(dir, name)
            val to = File(clipsDir, newName)
            if (!from.renameTo(to)) {
                movedBack.forEach { (src, dest) -> dest.renameTo(src) }
                return false
            }
            movedBack += from to to
            if (ClipArtefact.of(newName) == ClipArtefact.AUDIO) audioName = newName
        }
        item.download?.let {
            manifest.recordDownload(it.name, it.size, it.transport, it.deviceSerial, it.deviceName, audioName ?: it.localName)
        }
        // Something restored isn't "new": without this the user would see a red dot on everything they fished back.
        readStore?.markSeen(audioName ?: "$targetBase.ogg")
        dir.deleteRecursively()
        forget(item)
        return true
    }

    /** Permanently delete one item. Irreversible — the caller confirms first. */
    @Synchronized
    fun purge(item: Item) {
        File(trashDir, item.id).deleteRecursively()
        forget(item)
    }

    @Synchronized
    fun purgeAll() {
        items.forEach { File(trashDir, it.id).deleteRecursively() }
        items = emptyList()
        save()
    }

    @Synchronized
    fun purgeExpired(): Int {
        val t = now()
        val expired = items.filter { it.expiresAt <= t }
        if (expired.isEmpty()) return 0
        expired.forEach { File(trashDir, it.id).deleteRecursively() }
        items = items - expired.toSet()
        save()
        return expired.size
    }

    private fun forget(item: Item) {
        items = items.filterNot { it.id == item.id }
        save()
    }

    private fun availableBase(base: String, suffixes: List<String>): String {
        fun taken(candidate: String) = suffixes.any { File(clipsDir, candidate + it).exists() }
        if (!taken(base)) return base
        var n = 2
        while (taken("$base-$n")) n++
        return "$base-$n"
    }

    private fun load(): List<Item> {
        val loaded = runCatching {
            val root = JsonParser(indexFile.readText()).parse() as? Map<*, *>
            (root?.get("items") as? List<*>).orEmpty().mapNotNull { parseItem(it as? Map<*, *>) }
        }.getOrDefault(emptyList())
        // Index entries whose directory is gone are dropped; directories the index doesn't know are orphans from a
        // failed index write — no UI can reach them, so they are cleared.
        val live = loaded.filter { File(trashDir, it.id).isDirectory }
        val known = live.map { it.id }.toSet()
        trashDir.listFiles()?.filter { it.isDirectory && it.name !in known }?.forEach { it.deleteRecursively() }
        return live
    }

    private fun parseItem(m: Map<*, *>?): Item? {
        m ?: return null
        val d = m["download"] as? Map<*, *>
        return Item(
            id = m["id"] as? String ?: return null,
            base = m["base"] as? String ?: return null,
            files = (m["files"] as? List<*>).orEmpty().filterIsInstance<String>(),
            deletedAt = (m["deleted_at"] as? Number)?.toLong() ?: return null,
            title = m["title"] as? String ?: "",
            bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
            download = d?.let {
                Download(
                    name = it["name"] as? String ?: return@let null,
                    size = (it["size"] as? Number)?.toLong() ?: 0L,
                    transport = it["transport"] as? String ?: "ble",
                    deviceSerial = it["device_serial"] as? String,
                    deviceName = it["device_name"] as? String,
                    localName = it["local_name"] as? String ?: return@let null,
                )
            },
            source = m["source"] as? String,
        )
    }

    private fun save() {
        trashDir.mkdirs()
        val json = writeJson(
            mapOf(
                "items" to items.map { i ->
                    buildMap {
                        put("id", i.id)
                        put("base", i.base)
                        put("files", i.files)
                        put("deleted_at", i.deletedAt)
                        put("title", i.title)
                        put("bytes", i.bytes)
                        i.source?.let { put("source", it) }
                        i.download?.let { d ->
                            put(
                                "download",
                                buildMap {
                                    put("name", d.name)
                                    put("size", d.size)
                                    put("transport", d.transport)
                                    d.deviceSerial?.let { put("device_serial", it) }
                                    d.deviceName?.let { put("device_name", it) }
                                    put("local_name", d.localName)
                                },
                            )
                        }
                    }
                },
            ),
        )
        indexFile.writeText(json)
    }

    companion object {
        const val RETENTION_DAYS = 30L
        private const val DAY_MS = 24L * 3600 * 1000

        /**
         * Files of one clip for a whole-clip delete. Built from the base, not by scanning the directory; includes the
         * JSON sidecars, since a leftover `.asr.json` alone would rebuild a clip with no readable text.
         */
        fun wholeClipFiles(clipsDir: File, base: String, audio: File?): List<File> =
            listOfNotNull(audio) + listOf(
                ".txt", ".asr.json", ".translated.txt", ".translated.json",
                ".summary.md", ".summary.translated.md", ClipArtefact.TITLE_SUFFIX, SpeakerNames.SUFFIX,
                ClipChapters.SUFFIX,
            ).map { File(clipsDir, base + it) }
    }
}
