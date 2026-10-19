package com.nomily.app.data

import android.content.Context
import com.nomily.app.R
import com.nomily.app.core.clips.ClipArtefact
import com.nomily.app.core.clips.ClipManifest
import com.nomily.app.core.clips.ClipReadStore
import com.nomily.app.core.clips.ClipTrash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The library's bookkeeping next to the clip files: where each clip came from, which ones the user has opened,
 * and the recycle bin. All three live in `filesDir/clips` as dot-files the scan ignores.
 */
class LibraryStore(ctx: Context, private val localized: () -> Context) {

    val dir: File = File(ctx.filesDir, "clips").apply { mkdirs() }
    val manifest = ClipManifest(File(dir, ".manifest.json"))
    val reads = ClipReadStore(File(dir, ".seen.json"))
    private val trash = ClipTrash(dir)

    private val _trashItems = MutableStateFlow(trash.items)
    val trashItems: StateFlow<List<ClipTrash.Item>> = _trashItems.asStateFlow()

    /** "D·NOTE-5678", "This phone", "Imported" … — the list badge, group header and trash all use this one. */
    fun displayName(source: ClipManifest.Source, ctx: Context = localized()): String = when (source.kind) {
        ClipManifest.Source.Kind.WATCH -> ctx.getString(R.string.library_source_watch)
        ClipManifest.Source.Kind.PHONE -> ctx.getString(R.string.library_source_phone)
        ClipManifest.Source.Kind.IMPORTED -> ctx.getString(R.string.library_source_imported)
        ClipManifest.Source.Kind.CARD -> {
            val unit = source.storedName?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.library_source_card)
            source.serialSuffix?.let { "$unit-$it" } ?: unit
        }
    }

    /**
     * Move whole clips or single artefacts into the trash. The source label is captured first, because moving the
     * audio unregisters the manifest entry it comes from.
     */
    fun moveToTrash(requests: List<TrashRequest>): Int {
        val payloads = requests.map { r ->
            ClipTrash.Payload(
                localName = r.localName,
                title = r.title,
                files = r.files,
                download = if (r.takesAudio) downloadRecord(r.localName) else null,
                source = displayName(manifest.source(r.localName)),
            )
        }
        val moved = trash.move(payloads, manifest)
        reads.forget(requests.filter { it.takesAudio }.map { it.localName })
        _trashItems.value = trash.items
        return moved
    }

    data class TrashRequest(val localName: String, val title: String, val files: List<File>, val takesAudio: Boolean)

    fun restore(item: ClipTrash.Item): Boolean = trash.restore(item, manifest, reads).also { _trashItems.value = trash.items }

    fun purge(item: ClipTrash.Item) { trash.purge(item); _trashItems.value = trash.items }

    fun purgeAll() { trash.purgeAll(); _trashItems.value = trash.items }

    fun purgeExpired() { trash.purgeExpired(); _trashItems.value = trash.items }

    /** Only device downloads (`serial::name` keys) need re-registering on restore; the rest have nothing to download again. */
    private fun downloadRecord(localName: String): ClipTrash.Download? {
        val (key, e) = manifest.rawEntry(localName) ?: return null
        val serial = e.deviceSerial ?: return null
        return ClipTrash.Download(key.removePrefix("$serial::"), e.size, e.transport, serial, e.deviceName, e.localName ?: localName)
    }

    companion object {
        /** Files of one artefact of a clip, plus the `.title` when nothing else would be left for it to name. */
        fun artefactFiles(dir: File, base: String, what: ClipArtefact, others: Boolean): List<File> {
            val files = dir.listFiles()?.filter { ClipArtefact.baseOf(it.name) == base && ClipArtefact.of(it.name) == what }.orEmpty()
            val title = File(dir, base + ClipArtefact.TITLE_SUFFIX).takeIf { !others && it.exists() }
            return files + listOfNotNull(title)
        }
    }
}
