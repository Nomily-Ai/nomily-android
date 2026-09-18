package com.nomily.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.nomily.app.core.protocol.DNOTE_CID
import com.nomily.app.core.protocol.parseDnoteAdvPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * **Physical device forensic probe, exists only in the debug variant (`app/src/debug/`), and will not be included in the release package.**
 *
 * The sole purpose is to capture the device's broadcast **raw upload bytes** for evidence — and also verify the byte order of the CID,
 * and feed the real‑device data into the `:core` parsing function for the first time.
 *
 * Why implement this as an Activity instead of an instrumented test: this Xiaomi HyperOS disables adb's
 * `pm grant` command (error `Neither user 2000 nor current process has
 * GRANT_RUNTIME_PERMISSIONS`), so runtime permissions can only be requested by the app itself and granted by a single user tap.
 *
 * **Do not add a filter when scanning** — adding a filter presets the CID byte order; if the assumption is wrong you’ll get “no scan result” instead of the answer.
 *
 * How to run:
 * ```
 * adb shell am start -n com.nomily.app/.AdvDumpActivity   # on the phone tap "Allow"
 * adb logcat -d -s NOMILYADV:V
 * ```
 */
class AdvDumpActivity : Activity() {

    private companion object {
        const val TAG = "NOMILYADV"
        const val SCAN_MILLIS = 20_000L
        const val REQ_PERMS = 1
        const val AD_TYPE_MANUFACTURER = 0xFF

        /** Little-endian on the wire (BLE SIG standard): 0x22A3 → on the wire `A3 22` */
        val CID_LITTLE_ENDIAN = byteArrayOf(0xA3.toByte(), 0x22)
        /** Big-endian upper limit (literal as printed in the spec): line `22 A3` */
        val CID_BIG_ENDIAN = byteArrayOf(0x22, 0xA3.toByte())
    }

    private val seen = ConcurrentHashMap<String, Boolean>()
    private val hits = ConcurrentHashMap<String, Boolean>()
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
            setPadding(48, 96, 48, 48)
    text = "Requesting Bluetooth permission…"
        }
        setContentView(status)

        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startScan() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQ_PERMS) return
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            startScan()
        } else {
    Log.e(TAG, "Permission denied: $denied — cannot scan")
    say("Permission denied: $denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
    Log.e(TAG, "Bluetooth is off")
    say("Bluetooth is off")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
    Log.e(TAG, "Cannot obtain BluetoothLeScanner")
    say("Cannot obtain scanner")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .build()

    Log.i(TAG, "Starting scan for ${SCAN_MILLIS / 1000}s (no filter — adding one yields preset answer)")
    say("Scanning…")
        scanner.startScan(null, settings, cb)

        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(cb)
    Log.i(TAG, "════════ End: scanned ${seen.size} devices, hit D·NOTE CID ${hits.size} devices ════════")
    if (hits.isEmpty()) {
        Log.w(TAG, "No hits: device not broadcasting / out of range / or neither CID byte order matches")
    }
    say("Done: scanned ${seen.size} devices, hit ${hits.size}")
        }, SCAN_MILLIS)
    }

    private val cb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord ?: return
            val raw = rec.bytes ?: return
            val addr = result.device.address
            if (seen.putIfAbsent(addr, true) != null) return

            val mfg = adPayload(raw, AD_TYPE_MANUFACTURER) ?: return
            if (mfg.size < 2) return

            val little = mfg.startsWith(CID_LITTLE_ENDIAN)
            val big = mfg.startsWith(CID_BIG_ENDIAN)
            if (!little && !big) return                 // Other devices

            hits[addr] = true
            Log.i(TAG, "──────── matched D·NOTE CID ────────")
            Log.i(TAG, "addr=$addr rssi=${result.rssi} name=${rec.deviceName ?: "(none)"}")
            Log.i(TAG, "whole AD, raw bytes : ${raw.hex()}")
            Log.i(TAG, "0xFF section, raw   : ${mfg.hex()}   <- first two bytes = the CID as it goes on air")
            Log.i(
                TAG,
    "Decision           : " + if (little) {
        "A3 22 → little-endian upper, as expected"
    } else {
        "22 A3 → big-endian upper, unexpected, requires manual decision"
                },
            )
            Log.i(
                TAG,
                "getManufacturerSpecificData(0x%04X) = %s".format(
                    DNOTE_CID,
                    rec.getManufacturerSpecificData(DNOTE_CID)?.hex() ?: "null",
                ),
            )

            val payload = mfg.copyOfRange(2, mfg.size)
            val adv = parseDnoteAdvPayload(payload)
            if (adv == null) {
    Log.i(TAG, ":core parse      : null (payload ${payload.size} bytes, less than 13)")
            } else {
    Log.i(TAG, ":core parse      : bid=0x%04X pid=0x%04X model=%s".format(adv.bid, adv.pid, adv.model))
                Log.i(TAG, "                 firmware=${adv.firmware} (${adv.firmwareStr}) hardware=${adv.hardware}")
                Log.i(TAG, "                 battery=${adv.battery}% power=${adv.powerState} sid=${adv.deviceSidHex}")
    Log.i(TAG, "fixture payload hex (CID stripped): ${payload.hex()}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
    Log.e(TAG, "Scan start failed errorCode=$errorCode")
    say("Scan failed errorCode=$errorCode")
        }
    }

    private fun say(s: String) = runOnUiThread { status?.text = s }

    /** AD structure `[len][type][data...]` concatenated, len includes type itself. */
    private fun adPayload(raw: ByteArray, wantType: Int): ByteArray? {
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0) return null
            val type = if (i + 1 < raw.size) raw[i + 1].toInt() and 0xFF else return null
            if (type == wantType) {
                val from = i + 2
                val to = minOf(i + 1 + len, raw.size)
                return if (to > from) raw.copyOfRange(from, to) else null
            }
            i += len + 1
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
