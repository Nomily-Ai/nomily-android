package com.nomily.app.core.protocol

/**
 * BLE advertisement parsing for D·NOTE (Card / Clip) recorders — pure Kotlin, no `android.*`.
 *
 * The payload here is the segment **with the CID already stripped** (what Android's
 * `ScanRecord.getManufacturerSpecificData(DNOTE_CID)` returns).
 */

/** Company ID of manufacturer data (BLE SIG little‑endian on the wire). */
const val DNOTE_CID: Int = 0x22A3

/** Minimum BID length derived from DEVICE_SID; any shorter payload is treated as not a D·NOTE advertisement. */
const val ADV_MIN_LENGTH: Int = 13

/**
 * (BID, PID) → product name. Peripherals not listed in the catalog are filtered out of scan results ——
 * **no** fallback based on the local‑name string.
 */
val DNOTE_PRODUCTS: Map<Pair<Int, Int>, String> = mapOf(
    (0x6868 to 0x0000) to "D·NOTE Standard",
    (0x6868 to 0x0001) to "D·NOTE Flagship",
    (0x6868 to 0x0002) to "D·NOTE Luxury",
    (0x6869 to 0x0000) to "D·NOTE R101 (Ring)",
    (0x6869 to 0x0001) to "D·NOTE P101 (Pendant)",
    (0x6869 to 0x0002) to "D·NOTE V05 (2+1 MIC Card)",
    (0x6869 to 0x0003) to "D·NOTE V03 (4+1 MIC Card)",
)

/** Parsed D·NOTE advertisement (CID stripped). */
class DnoteAdv(
    val bid: Int,
    val pid: Int,
    val firmware: Int,
    val hardware: Int,
    val battery: Int,
    val powerState: Int,
    /** 3‑byte raw device identifier. */
    val deviceSid: ByteArray,
) {
    /** Not found in catalog → null (caller filters with `model != null`). */
    val model: String? get() = DNOTE_PRODUCTS[bid to pid]

    /** Display string: **uppercase** hex. */
    val deviceSidHex: String get() = deviceSid.toHexUpper()

    val firmwareStr: String get() = "v${(firmware shr 8) and 0xFF}.${firmware and 0xFF}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnoteAdv) return false
        return bid == other.bid &&
            pid == other.pid &&
            firmware == other.firmware &&
            hardware == other.hardware &&
            battery == other.battery &&
            powerState == other.powerState &&
            deviceSid.contentEquals(other.deviceSid)
    }

    override fun hashCode(): Int {
        var h = bid
        h = 31 * h + pid
        h = 31 * h + firmware
        h = 31 * h + hardware
        h = 31 * h + battery
        h = 31 * h + powerState
        h = 31 * h + deviceSid.contentHashCode()
        return h
    }

    override fun toString(): String =
        "DnoteAdv(bid=0x${bid.toString(16)}, pid=0x${pid.toString(16)}, " +
            "firmware=$firmwareStr, hardware=$hardware, battery=$battery, " +
            "powerState=$powerState, deviceSid=$deviceSidHex, model=$model)"
}

/**
 * Decode manufacturer data with CID 0x22A3 (CID already stripped).
 *
 * payload missing or shorter than 13 bytes → return null (**not** an error branch).
 * Does not validate whether (BID, PID) is in the catalog — filtering is left to the caller via `model != null`.
 */
fun parseDnoteAdvPayload(payload: ByteArray?): DnoteAdv? {
    if (payload == null || payload.size < ADV_MIN_LENGTH) return null
    return DnoteAdv(
        bid = payload.beU16(0),
        pid = payload.beU16(2),
        firmware = payload.beU16(4),
        hardware = payload.beU16(6),
        battery = payload[8].toInt() and 0xFF,
        powerState = payload[9].toInt() and 0xFF,
        deviceSid = payload.copyOfRange(10, 13),
    )
}

/**
 * CID's **on‑the‑wire byte order**: `A3 22` (little‑endian).
 *
 * BLE SIG defines Company ID as little‑endian on the wire, so 0x22A3 appears as `A3 22`.
 */
val DNOTE_CID_WIRE_BYTES: ByteArray = byteArrayOf(0xA3.toByte(), 0x22)

/**
 * Parse a manufacturer-data blob **with CID prefix**.
 *
 * Android's `ScanRecord.getManufacturerSpecificData(DNOTE_CID)` returns data **with the CID already stripped**, which is handled by [parseDnoteAdvPayload]; this function is for cases where raw AD bytes are read directly.
 *
 * blob shorter than 15 bytes (2 CID + 13) or CID mismatch → null.
 */
fun parseDnoteAdvBlob(blob: ByteArray?): DnoteAdv? {
    if (blob == null || blob.size < DNOTE_CID_WIRE_BYTES.size + ADV_MIN_LENGTH) return null
    if (blob[0] != DNOTE_CID_WIRE_BYTES[0] || blob[1] != DNOTE_CID_WIRE_BYTES[1]) return null
    return parseDnoteAdvPayload(blob.copyOfRange(DNOTE_CID_WIRE_BYTES.size, blob.size))
}

/** Read a 2‑byte unsigned integer in big‑endian. */
private fun ByteArray.beU16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private const val HEX_UPPER = "0123456789ABCDEF"

private fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_UPPER[v ushr 4]).append(HEX_UPPER[v and 0x0F])
    }
    return sb.toString()
}
