package com.nomily.app.core.finder

/** One "last seen" record. Coordinates are optional: a time without a place is still useful. */
data class SeenRecord(
    val at: Long,
    val place: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

object CardFinderLogic {
    /** A card is not a tracker: enough to answer "where did I leave it today", no movement log. */
    const val HISTORY_LIMIT = 20
    const val HISTORY_SHOWN = 10
    const val ALERT_DEDUP_MS = 5_000L
    const val STALE_MS = 3_000L
    const val VERY_NEAR_RSSI = -60
    const val NEAR_RSSI = -75

    /** Newest first, capped at [HISTORY_LIMIT]. */
    fun addSeen(history: List<SeenRecord>, record: SeenRecord, limit: Int = HISTORY_LIMIT): List<SeenRecord> =
        (listOf(record) + history).take(limit)

    /** The place arrives after the record exists: fill the entry stamped [at] instead of appending a second one. */
    fun patchSeen(history: List<SeenRecord>, at: Long, place: String?, lat: Double?, lon: Double?): List<SeenRecord> =
        history.map { if (it.at == at) it.copy(place = place, latitude = lat, longitude = lon) else it }

    /** True when an alert at [now] is outside the dedup window after [lastAlertAt] (null = never alerted). */
    fun shouldAlert(lastAlertAt: Long?, now: Long, windowMs: Long = ALERT_DEDUP_MS): Boolean =
        lastAlertAt == null || now - lastAlertAt > windowMs

    /** 0 unknown / 1 far / 2 near / 3 very near. No advert for [STALE_MS], or an invalid reading, is unknown. */
    fun proximityLevel(rssi: Int?, advertAgeMs: Long): Int = when {
        rssi == null || rssi >= 0 || advertAgeMs > STALE_MS -> 0
        rssi >= VERY_NEAR_RSSI -> 3
        rssi >= NEAR_RSSI -> 2
        else -> 1
    }
}
