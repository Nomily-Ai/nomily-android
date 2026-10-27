package com.nomily.app.ble

import com.nomily.app.core.protocol.DnoteProtocol
import org.json.JSONObject

/**
 * Reading of 0x80 / 0x81 responses.
 *
 * Only expose fields needed by the UI as properties, **keeping the original JSON**: the firmware may add fields,
 * the modeling layer should not decide whether other fields can be read (same as `AppConfig` “unmodeled keys are retained as‑is”).
 */
class DeviceInfo(val raw: JSONObject) {
    val serial: String? get() = raw.optStringOrNull("sn")
    val firmware: String? get() = raw.optStringOrNull("v")
    val deviceTime: String? get() = raw.optStringOrNull("time")
    val bluetoothName: String? get() = raw.optStringOrNull("bt")
    val bluetoothMac: String? get() = raw.optStringOrNull("btaddr")
    val battery: Int? get() = raw.optIntOrNull("bat")
    val isCharging: Boolean get() = raw.optInt("usb", 0) == 1
    val isRecording: Boolean get() = raw.optInt("rec", 0) == 1

    /** Whether the Wi‑Fi AP used for fast transfer is up. The ack of 0x88 only means the command was received; **this bit indicates the radio is ready**. */
    val wifiApOn: Boolean get() = raw.optInt("wifiap", 0) == 1
    val freeMb: Int? get() = raw.optIntOrNull("df")
    val totalMb: Int? get() = raw.optIntOrNull("total_df")
}

class SwitchInfo(val raw: JSONObject) {
    val massStorage: Boolean get() = raw.optInt("ms", 0) == 1
    val led: Boolean get() = raw.optInt("led", 0) == 1
    val motor: Boolean get() = raw.optInt("motor", 0) == 1
    val noiseCancel: Boolean get() = raw.optInt("nc", 0) == 1
    val saveWav: Boolean get() = raw.optInt("wav", 0) == 1
    val vad: Boolean get() = raw.optInt("vad", 0) == 1
    val micGain: Int get() = raw.optInt("gain", 0)
    val noiseReduction: Int get() = raw.optInt("nn", 0)
    val idleOff: Int get() = raw.optInt("autoff", 0)

    val idleOffIsNever: Boolean get() = idleOff == DnoteProtocol.IDLE_OFF_NEVER
}

/** `optString` returns an empty string instead of null when a key is missing — treat the empty string as “none” so the UI doesn’t show a blank line. */
private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) optInt(key) else null

/**
 * Device filename `20260415214207.opus` → human‑readable title “2026‑04‑15 21:42:07”.
 *
 * **medium date + medium time** (QA requires seconds, not just minutes); if parsing fails, return the filename unchanged.
 *
 * Previously both Android lists displayed the raw filename in a fixed‑width font, a noticeable platform difference.
 */
object RecordingName {
    private const val PATTERN = "yyyyMMddHHmmss"

    /**
     * Recording time from a filename: the whole stem, or else the first timestamp embedded in it —
     * a merged clip `A+B.m4a` takes the older half's time, so it keeps its place in the library.
     */
    fun date(filename: String): java.util.Date? {
        val stem = filename.substringBeforeLast('.')
        return parseExact(stem) ?: embeddedCandidates(stem).firstNotNullOfOrNull(::parseExact)
    }

    private fun parseExact(s: String): java.util.Date? {
        if (s.length != PATTERN.length || s.any { !it.isDigit() }) return null
        // The parser must not be a shared singleton: SimpleDateFormat is not thread‑safe, and Compose may access it from multiple threads.
        val parser = java.text.SimpleDateFormat(PATTERN, java.util.Locale.US)
        parser.isLenient = false
        return runCatching { parser.parse(s) }.getOrNull()
    }

    /** Timestamps hiding inside a stem: a 14‑digit run, or `8 digits-6 digits` joined back together. */
    internal fun embeddedCandidates(stem: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""\d+""").findAll(stem).forEach { m ->
            val run = m.value
            if (run.length == 14) {
                out += run
            } else if (run.length == 8 && stem.getOrNull(m.range.last + 1) == '-') {
                val after = Regex("""\d+""").matchAt(stem, m.range.last + 2)?.value
                if (after?.length == 6) out += run + after
            }
        }
        return out
    }

    fun displayTitle(filename: String): String {
        val d = date(filename) ?: return filename
        return java.text.DateFormat
            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
            .format(d)
    }
}
