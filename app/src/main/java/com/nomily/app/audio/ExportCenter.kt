package com.nomily.app.audio

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * The finished audio export waiting to be shared, so a long conversion that completes while the user is away can
 * be shared later instead of redone. It lives in `filesDir/export-staging` (the cache can be cleared at any time)
 * and only one is kept: a new export replaces it, and it expires after [RETENTION_MS].
 */
class ExportCenter(filesDir: File) {

    data class Ready(
        val id: String,
        val file: String,
        val title: String,
        val bytes: Long,
        val createdAt: Long,
        /** Clip base it came from, so only that clip's page offers it. */
        val origin: String?,
    )

    private val root = File(filesDir, "export-staging")
    private val index = File(root, "ready.json")

    private val _ready = MutableStateFlow(load())
    val ready: StateFlow<Ready?> = _ready.asStateFlow()

    /** A fresh staging directory; the previous export is deleted with it. */
    @Synchronized
    fun beginNewStaging(): File {
        discard()
        return File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    }

    @Synchronized
    fun publish(dir: File, file: File, title: String, origin: String?) {
        val r = Ready(dir.name, file.name, title, file.length(), System.currentTimeMillis(), origin)
        index.writeText(
            writeJson(
                buildMap {
                    put("id", r.id); put("file", r.file); put("title", r.title)
                    put("bytes", r.bytes); put("created_at", r.createdAt)
                    r.origin?.let { put("origin", it) }
                },
            ),
        )
        _ready.value = r
    }

    fun fileOf(r: Ready): File = File(File(root, r.id), r.file)

    fun readyFor(origin: String): Ready? = _ready.value?.takeIf { it.origin == origin && fileOf(it).isFile }

    @Synchronized
    fun discard() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        _ready.value = null
    }

    private fun load(): Ready? {
        val r = runCatching {
            val m = JsonParser(index.readText()).parse() as Map<*, *>
            Ready(
                id = m["id"] as String,
                file = m["file"] as String,
                title = m["title"] as? String ?: "",
                bytes = (m["bytes"] as? Number)?.toLong() ?: 0L,
                createdAt = (m["created_at"] as Number).toLong(),
                origin = m["origin"] as? String,
            )
        }.getOrNull()
        // Gone from disk or past its time: clear the leftovers instead of offering a dead share.
        if (r == null || !fileOf(r).isFile || System.currentTimeMillis() - r.createdAt > RETENTION_MS) {
            root.listFiles()?.forEach { it.deleteRecursively() }
            return null
        }
        return r
    }

    companion object {
        /** Long enough to cover "exported on Friday, shared on Monday". */
        const val RETENTION_MS = 72L * 3600 * 1000
    }
}
