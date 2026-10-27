package com.nomily.app.core.clips

/**
 * The artifacts a recording has on disk.
 *
 * Audio / transcription / summary **are deleted independently**: deleting one does not affect the other two. Both the single‑item delete on the segment detail page and the batch cleanup in settings must use the same rules for “which file belongs to which artifact”, so the rules are defined here and invoked from both sides.
 *
 * Pure Kotlin, no `android.*` — placed in `:core` to allow unit testing (suffix rules are prone to silent divergence).
 */
enum class ClipArtefact {
    AUDIO,
    TRANSCRIPT,
    SUMMARY,
    ;

    companion object {
        /** File extension (lowercase, without the dot) that is considered playable audio. */
        val AUDIO_EXTENSIONS = setOf("opus", "ogg", "wav", "m4a", "mp3", "aac", "caf", "flac")

        /**
         * Suffix → artifact, **longer ones take precedence**: `foo.summary.translated.md` must be classified as a summary,
         * and not be matched first by a shorter transcription suffix.
         */
        private val SUFFIX_MAP: List<Pair<String, ClipArtefact>> = listOf(
            ".summary.translated.md" to SUMMARY,
            ".summary.md" to SUMMARY,
            ".translated.json" to TRANSCRIPT,
            ".translated.txt" to TRANSCRIPT,
            ".asr.json" to TRANSCRIPT,
            ".txt" to TRANSCRIPT,
        )

        /**
         * Side‑car file for a custom title. **Intentionally not counted among the three deletable artifacts** — it is metadata for the segment.
         * However, it must be removed when everything else is gone: the database can rebuild entries from any leftover side‑car, and a solitary `.title` would resurrect an empty segment.
         */
        const val TITLE_SUFFIX = ".title"

        fun of(fileName: String): ClipArtefact? {
            if (fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS &&
                fileName.contains('.')
            ) {
                return AUDIO
            }
            return SUFFIX_MAP.firstOrNull { fileName.endsWith(it.first) }?.second
        }

        /** Strip the artifact suffix (or audio extension) to recover the segment base name; returns null if it is not a segment artifact. */
        fun baseOf(fileName: String): String? {
            if (of(fileName) == AUDIO) return fileName.substringBeforeLast('.')
            SUFFIX_MAP.firstOrNull { fileName.endsWith(it.first) }?.let {
                return fileName.dropLast(it.first.length)
            }
            if (fileName.endsWith(TITLE_SUFFIX)) return fileName.dropLast(TITLE_SUFFIX.length)
            return null
        }

        /**
         * Given all filenames in a directory, compute the **orphan `.title` that should be deleted**:
         * it corresponds to a segment that no longer has audio / transcription / summary.
         *
         * Only performs calculation, does not touch the filesystem — deletion is the caller’s responsibility (pure function is easier to test).
         */
        fun orphanTitles(fileNames: Collection<String>): List<String> {
            val withContent = fileNames.mapNotNull { name ->
                if (of(name) != null) baseOf(name) else null
            }.toSet()
            return fileNames.filter { it.endsWith(TITLE_SUFFIX) && baseOf(it) !in withContent }
        }
    }
}
