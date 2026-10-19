package com.nomily.app.core.clips

import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where each clip in the library came from: `clips/.manifest.json`, same shape as the iOS manifest
 * (`{"downloaded": {key: {size, downloaded_at, transport, local_name, device_serial, device_name}}}`).
 *
 * Keys are `serial::name` for device downloads (two cards can produce the same timestamp name) and a bare name
 * otherwise. A clip with no entry was imported by the user.
 *
 * Thread-safe: every public method is synchronized and writes through to disk.
 */
class ClipManifest(private val file: File) {

    data class Entry(
        val size: Long,
        val downloadedAt: String,
        /** `ble` / `wifi` (from a recorder card), `stream` (live transcription), `watch`. */
        val transport: String,
        val localName: String?,
        val deviceSerial: String? = null,
        val deviceName: String? = null,
    )

    /** "Who recorded this", for the list badge and grouping. */
    data class Source(val kind: Kind, val serial: String? = null, val storedName: String? = null) {
        enum class Kind { CARD, WATCH, PHONE, IMPORTED }

        /** Grouping key: two cards are two groups, so the serial is part of it. */
        val groupKey: String get() = if (kind == Kind.CARD) "card|${serial.orEmpty()}" else kind.name

        /** Last four characters of the serial, or null when it's too short to be worth showing. */
        val serialSuffix: String? get() = serial?.trim()?.takeIf { it.length > 4 }?.takeLast(4)
    }

    private var entries: Map<String, Entry> = load()

    @Synchronized
    fun recordDownload(
        name: String,
        size: Long,
        transport: String,
        deviceSerial: String? = null,
        deviceName: String? = null,
        localName: String? = null,
    ) {
        val k = key(name, deviceSerial)
        // Source fields are set-only: a later record over another link may not carry the serial or name.
        val previous = entries[k]
        entries = entries + (k to Entry(
            size = size,
            downloadedAt = isoNow(),
            transport = transport,
            localName = localName ?: name,
            deviceSerial = deviceSerial ?: previous?.deviceSerial,
            deviceName = deviceName ?: previous?.deviceName,
        ))
        save()
    }

    /** Forget every entry for this on-disk name (device downloads are keyed differently from the disk name). */
    @Synchronized
    fun forget(localName: String) {
        val kept = entries.filterNot { (k, e) -> (e.localName ?: k) == localName }
        if (kept.size != entries.size) { entries = kept; save() }
    }

    /** The raw record for an on-disk name, so a trash restore can put back the exact same key. */
    @Synchronized
    fun rawEntry(localName: String): Pair<String, Entry>? =
        entries.entries.firstOrNull { (k, e) -> (e.localName ?: k) == localName }?.toPair()

    /** Sources by on-disk name, built once per list render rather than scanned per row. */
    @Synchronized
    fun sources(): Map<String, Source> = entries.values.mapNotNull { e ->
        e.localName?.takeIf { it.isNotEmpty() }?.let { it to classify(e) }
    }.toMap()

    @Synchronized
    fun source(localName: String): Source = rawEntry(localName)?.second?.let(::classify) ?: Source(Source.Kind.IMPORTED)

    @Synchronized
    fun clear() { entries = emptyMap(); save() }

    private fun classify(e: Entry) = when (e.transport) {
        "watch" -> Source(Source.Kind.WATCH, storedName = e.deviceName)
        "stream" -> Source(Source.Kind.PHONE, storedName = e.deviceName)
        else -> Source(Source.Kind.CARD, e.deviceSerial, e.deviceName)
    }

    private fun load(): Map<String, Entry> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = JsonParser(file.readText()).parse() as? Map<*, *> ?: return emptyMap()
        val downloaded = root["downloaded"] as? Map<*, *> ?: return emptyMap()
        downloaded.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            (k as? String ?: return@mapNotNull null) to Entry(
                size = (m["size"] as? Number)?.toLong() ?: 0L,
                downloadedAt = m["downloaded_at"] as? String ?: "",
                transport = m["transport"] as? String ?: "ble",
                localName = m["local_name"] as? String,
                deviceSerial = m["device_serial"] as? String,
                deviceName = m["device_name"] as? String,
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save() {
        val json = writeJson(
            mapOf(
                "downloaded" to entries.mapValues { (_, e) ->
                    buildMap {
                        put("size", e.size)
                        put("downloaded_at", e.downloadedAt)
                        put("transport", e.transport)
                        e.localName?.let { put("local_name", it) }
                        e.deviceSerial?.let { put("device_serial", it) }
                        e.deviceName?.let { put("device_name", it) }
                    }
                },
            ),
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }

    private companion object {
        fun key(name: String, serial: String?): String =
            serial?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it::$name" } ?: name

        fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }
}
