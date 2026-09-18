package com.nomily.app.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.nomily.app.core.clips.ClipArtefact
import java.io.File

/**
 * Import an external audio file into the library.
 *
 * Rules: **first filter by an extension whitelist** (unsupported types should not end up in the clips directory as an unplayable dead entry), keep the original filename, and append `-1` / `-2` suffixes on name collisions.
 *
 * The whitelist is taken directly from `:core`’s [ClipArtefact.AUDIO_EXTENSIONS] — the library uses it to list entries and to decide which files are audio, so copying it here would eventually diverge.
 */
object AudioImporter {

    private const val TAG = "AudioImport"

    class UnsupportedTypeException(val ext: String) : Exception("unsupported audio type: .$ext")

    /**
     * Copy the `uri` into [dir] and return the saved file.
     *
     * The filename is taken from SAF’s `DISPLAY_NAME` (the name shown to the user); if unavailable, fall back to the last segment of the uri —
     * *The name only affects display and deduplication, and failure to obtain it should not cause the whole import to fail.*
     */
    fun import(context: Context, uri: Uri, dir: File): File {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in ClipArtefact.AUDIO_EXTENSIONS) throw UnsupportedTypeException(ext)

        dir.mkdirs()
        val target = uniqueDestination(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("couldn't open $uri")
        Log.i(TAG, "Imported $name → ${target.name}")
        return target
    }

    /** On name collision, append `-1`, `-2`, … until there’s no conflict. */
    fun uniqueDestination(dir: File, originalName: String): File {
        val first = File(dir, originalName)
        if (!first.exists()) return first
        val stem = originalName.substringBeforeLast('.', originalName)
        val ext = originalName.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext"
            val f = File(dir, candidate)
            if (!f.exists()) return f
            n++
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
}
