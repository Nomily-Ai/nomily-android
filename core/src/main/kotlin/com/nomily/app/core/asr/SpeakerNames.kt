package com.nomily.app.core.asr

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.File

/**
 * User-given names for the diarized speakers of one recording, stored as `{base}.speakers.json`
 * (a JSON object `raw speaker id → name`) beside `{base}.asr.json`. A sidecar rather than part of the
 * transcript so a re-transcription keeps the names; the file is shared with iOS, so the shape is fixed.
 */
object SpeakerNames {
    const val SUFFIX = ".speakers.json"

    /** Tolerant: anything that is not an object of strings gives an empty map; blank names are dropped. */
    fun parse(json: String?): Map<String, String> {
        val root = runCatching { JsonParser(json ?: return emptyMap()).parse() }.getOrNull() as? Map<*, *>
            ?: return emptyMap()
        return buildMap {
            for ((k, v) in root) {
                val name = (v as? String)?.trim().orEmpty()
                if (k is String && name.isNotEmpty()) put(k, name)
            }
        }
    }

    fun serialize(names: Map<String, String>): String = writeJson(names)

    /** [names] with [speaker] set to [name]; a blank name removes the entry. The stored value is trimmed. */
    fun with(names: Map<String, String>, speaker: String, name: String): Map<String, String> {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) names - speaker else names + (speaker to trimmed)
    }

    /** The user's name for [speaker], or null when there is none. */
    fun nameOf(names: Map<String, String>, speaker: String?): String? =
        speaker?.let { names[it] }?.takeIf { it.isNotBlank() }

    fun read(dir: File, base: String): Map<String, String> {
        val f = File(dir, base + SUFFIX)
        return if (f.isFile) parse(runCatching { f.readText() }.getOrNull()) else emptyMap()
    }

    /** Writes [names]; an empty map deletes the file so an untouched clip looks untouched on disk. */
    fun write(dir: File, base: String, names: Map<String, String>) {
        val f = File(dir, base + SUFFIX)
        if (names.isEmpty()) {
            f.delete()
            return
        }
        val tmp = File(dir, "$base$SUFFIX.tmp")
        tmp.writeText(serialize(names))
        if (!tmp.renameTo(f)) {
            f.writeText(serialize(names))
            tmp.delete()
        }
    }
}
