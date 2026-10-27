package com.nomily.app.core.clips

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.File

/**
 * Which library clips the user has opened: `clips/.seen.json`.
 *
 * Only clips that arrived after [origin] (the first launch with this ledger) can be unread — otherwise an existing
 * library of hundreds of clips would light up red on upgrade.
 */
class ClipReadStore(private val file: File, now: Long = System.currentTimeMillis()) {

    private var origin: Long
    private val seen = mutableSetOf<String>()

    init {
        val loaded = runCatching { JsonParser(file.readText()).parse() as? Map<*, *> }.getOrNull()
        if (loaded == null) {
            origin = now
            save()
        } else {
            origin = (loaded["origin"] as? Number)?.toLong() ?: now
            (loaded["seen"] as? List<*>)?.filterIsInstance<String>()?.let(seen::addAll)
        }
    }

    @Synchronized
    fun isUnread(name: String, arrivedAt: Long): Boolean = arrivedAt >= origin && name !in seen

    @Synchronized
    fun markSeen(name: String) {
        if (seen.add(name)) save()
    }

    /** Drop deleted clips so a later clip with the same name shows as new again. */
    @Synchronized
    fun forget(names: Collection<String>) {
        if (seen.removeAll(names.toSet())) save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(writeJson(mapOf("origin" to origin, "seen" to seen.toList())))
    }
}
