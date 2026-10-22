package com.nomily.app.core.export

import com.nomily.app.core.asr.parseAsrArtefact

/** Pure text assembly for the txt / md document exports and the mail attachments (iOS `DocumentExporter`). */
object DocumentText {

    /**
     * The transcript as exported: the speaker-aware text of `.asr.json` when it parses with a body, else the
     * plain `.txt`; null when neither has content.
     */
    fun transcript(asrJson: String?, plainTxt: String?, speakerNames: Map<String, String>): String? {
        asrJson?.let { raw ->
            parseAsrArtefact(raw)?.textForSummarization(speakerNames)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return plainTxt
    }

    /** `# stem`, then `## summary` (when there is one) and `## transcript`. */
    fun markdown(stem: String, summary: String?, transcript: String, summaryHeading: String, transcriptHeading: String): String {
        val out = StringBuilder("# ").append(stem).append("\n\n")
        if (!summary.isNullOrEmpty()) out.append("## ").append(summaryHeading).append("\n\n").append(summary).append("\n\n")
        out.append("## ").append(transcriptHeading).append("\n\n").append(transcript).append('\n')
        return out.toString()
    }

    /** File stem of a clip name: the part before the last dot. */
    fun stem(clipName: String): String = clipName.substringBeforeLast('.', clipName)

    /** Keeps a title usable as a file name. */
    fun safeFileStem(title: String): String = title.replace(Regex("""[/\\:?%*|"<>]"""), "_").ifBlank { "export" }
}
