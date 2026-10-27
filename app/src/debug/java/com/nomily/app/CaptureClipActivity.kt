package com.nomily.app

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.nomily.app.ble.DnoteBleClient
import com.nomily.app.core.crypto.deriveKey
import com.nomily.app.crypto.Argon2KtProvider
import com.nomily.app.core.protocol.DNOTE_CID
import com.nomily.app.core.protocol.parseDnoteAdvPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * **Real-device forensics: capture a "real encrypted recording"** to fill the gap where `decrypt` lacks a real-device sample.
 *
 * Only exists in the debug variant; it never ships in release builds.
 *
 * Flow:
 * ```
 * Scan (filter by CID) → connection handshake → 0x80 device info (get SN)
 *   → 0xA1 check binding; if unbound, bind via 0xA0
 *   → 0xA3 check encryption state (note it down, restore at the end)
 *   → 0xA2 enable encryption with a **known test key**
 *   → 0x51 record for [RECORD_SECONDS] seconds → 0x50 stop
 *   → 0x90 list files, take the newest one → 0x70 download
 *   → write to disk + print the envelope header for turning into a fixture
 *   → restore the encryption toggle to its original state
 * ```
 *
 * **Why a fixed test key instead of deriving from a passphrase**: the fixture only needs "envelope + key → plaintext",
 * and the key is written into the device by us via 0xA2, **no Argon2 needed**. This decouples the "real-device clip"
 * from the "Argon2 512MiB feasibility spike", so the two can proceed independently.
 *
 * How to run:
 * ```
 * adb shell am start -n com.nomily.app/.CaptureClipActivity
 * adb logcat -d -s NOMILYCLIP:V
 * adb pull /sdcard/Android/data/com.nomily.app/files/<name>
 * ```
 */
@SuppressLint("MissingPermission")
class CaptureClipActivity : Activity() {

    private companion object {
        const val TAG = "NOMILYCLIP"
        const val SCAN_TIMEOUT_MS = 45_000L
        const val RECORD_SECONDS = 6L

        /**
         * The key written to the device **is derived from a passphrase**: `Argon2id(TEST_PASSPHRASE, salt = SN reported by device 0x80)`.
         *
         * Earlier version used the fixed byte sequence `00 01 … 1F`, which only allowed verification of “any 32B key → device encryption → we can decrypt”,
         * **could not verify the full chain “passphrase → Argon2 → key → decrypt real device file”**. After switching to derivation the whole chain is closed,
         * and also pins a real risk: **the exact form of the SN when used as salt** (whether padded / case differences) —
         * a single‑character difference yields a completely different key, and **no error is reported, it just fails to decrypt**.
         *
         * ⚠️ This passphrase is for forensics only. After collection, the device stores only the key derived from it.
         */
        const val TEST_PASSPHRASE = "testpass"

        /**
         * By default, only connect to devices whose broadcast name contains this string.
         *
         * Previously we discovered halfway through a run that we scanned V05: `scanOne` uses whichever appears first, and our
         * clip and derived key are bound to D·NOTE's SN — **connecting to the wrong device results in a different SN and key**.
         * You can override with `--es device D·NOTE` / `--es device V05`.
         */
        const val DEFAULT_DEVICE_NAME_CONTAINS = "D·NOTE"

        /** GATT 133 (0x85, generic connection failure) is common on Android; retrying a few times usually suffices. */
        const val CONNECT_ATTEMPTS = 3

        /** Binding flag: the firmware only checks whether it is bound, the content is arbitrary. */
        val BOND_ID = ByteArray(16) { (0xB0 + it).toByte() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 15f
            setPadding(48, 96, 48, 48)
    text = "Preparing collection…"
        }
        setContentView(status)
        // Screen off causes MIUI to terminate the process along with its logs — keep the screen on for the entire collection
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    Log.i(TAG, "════════ CaptureClipActivity start ════════")
        scope.launch { runCapture() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun runCapture() {
        var client: DnoteBleClient? = null
        try {
    say("Scanning devices…")
    Log.i(TAG, "Starting scan (up to ${SCAN_TIMEOUT_MS / 1000}s) — please let the recording card broadcast")
    val want = intent?.getStringExtra("device") ?: DEFAULT_DEVICE_NAME_CONTAINS
    Log.i(TAG, "Only connect devices whose broadcast name contains \"$want\" (can change with --es device)")
    val found = scanOne(want) ?: run {
        fail("Did not find D·NOTE device within ${SCAN_TIMEOUT_MS / 1000}s")
                return
            }
    Log.i(TAG, "Selected device ${found.name} / ${found.address} model=${found.model}")
    say("Connecting to ${found.name}…")

            // GATT 133 retry: initial connection failures are common on Android.
            var connected = false
            for (attempt in 1..CONNECT_ATTEMPTS) {
                client = DnoteBleClient(this, found.device)
                try {
                    client.connect()
                    connected = true
                    break
                } catch (e: Exception) {
    Log.w(TAG, "Connection failed (attempt $attempt/$CONNECT_ATTEMPTS): ${e.message}")
                    client.close()
                    client = null
                    if (attempt < CONNECT_ATTEMPTS) delay(1500)
                }
            }
    if (!connected || client == null) { fail("Failed to connect after $CONNECT_ATTEMPTS attempts"); return }
    val c = client!!
    Log.i(TAG, "Connected, MTU=${c.negotiatedMtu}")

            val info = c.getDeviceInfo()
    Log.i(TAG, "Device info = $info")
    val sn = info.optString("sn", info.optString("SN", "(unknown)"))
    Log.i(TAG, "SN = $sn   ← the 18 bytes in the envelope should be it")

            // SN is the Argon2 salt — pin down its exact form; a single character difference yields a completely different key.
    Log.i(TAG, "SN length = ${sn.length}, ASCII bytes = ${sn.toByteArray(Charsets.US_ASCII).hex()}")

            // "Download only" mode: skip binding/write key/recording, directly list + download the latest entry.
            // Use case: after the first two collections, the device stops responding after recording, but **the recording itself succeeded**,
            // and at that point the device already stores a key derived from the passphrase — so simply retrieving that file is sufficient,
            // no need to have the device record again.
            //   adb shell am start -n com.nomily.app/.CaptureClipActivity --ez skip_record true
            val skipRecord = intent?.getBooleanExtra("skip_record", false) ?: false
            val derivedKeyForReport = deriveKey(TEST_PASSPHRASE, sn, Argon2KtProvider())
            if (skipRecord) {
                Log.i(TAG, "── Download-only mode: skipping bind/write key/record ──")
                Log.i(TAG, "Passphrase \"$TEST_PASSPHRASE\" derived key = ${derivedKeyForReport.hex()}")
                say("Fetching file list…")
                val fs = c.getFileList()
                Log.i(TAG, "Total ${fs.size} files on device")
                fs.forEach { Log.i(TAG, "  #${it.index} ${it.name} ${it.size}B") }
                val t = fs.maxByOrNull { it.index } ?: run { fail("No files on device"); return }
                say("Downloading ${t.name}…")
                val b = c.downloadFile(t.name, t.size)
                Log.i(TAG, "Download complete: ${b.size} bytes")
                val f = File(getExternalFilesDir(null), t.name)
                f.writeBytes(b)
                Log.i(TAG, "Saved to disk ${f.absolutePath}")
                report(b, sn, t.name, derivedKeyForReport)
                say("Completed (download-only mode)")
                return
            }

            // Binding stays after the download-only branch: download-only mode must write no
            // state to the device, and binding earlier makes the "read-only check" silently re-bind it.
            val bond = c.queryBondState()
            Log.i(TAG, "Bond status bound=${bond.bound}")
            if (!bond.bound) {
                say("Binding device…")
                c.bindDevice(BOND_ID)
                Log.i(TAG, "Bound")
            }

            val encBefore = c.getEncryptionState()
            Log.i(TAG, "Encryption switch (before collection) = $encBefore")

            say("Deriving key from passphrase (Argon2id, ~1.6s)…")
            val derivedKey = deriveKey(TEST_PASSPHRASE, sn, Argon2KtProvider())
            Log.i(TAG, "Passphrase = \"$TEST_PASSPHRASE\"")
            Log.i(TAG, "Derived key = ${derivedKey.hex()}   ← Argon2id(passphrase, salt=SN), computed by argon2kt")

            say("Writing the derived key to the device…")
            c.setEncryption(true, derivedKey)
            Log.i(TAG, "Encryption enabled, using the **passphrase-derived** key (no longer fixed test bytes)")

            say("Recording for ${RECORD_SECONDS}s…")
            Log.i(TAG, "Start recording = ${c.startRecording(realTime = false)}")
            delay(RECORD_SECONDS * 1000)
            Log.i(TAG, "Stop recording = ${c.stopRecording()}")
            delay(1500)                                  // Allow time for the device to write to storage

            say("Fetching file list...")
            val files = c.getFileList()
            Log.i(TAG, "Total ${files.size} files on the device")
            files.takeLast(5).forEach { Log.i(TAG, "  #${it.index} ${it.name} ${it.size}B") }
            val target = files.maxByOrNull { it.index } ?: run {
                fail("No file on the device")
                return
            }

            say("Downloading ${target.name}…")
            val blob = c.downloadFile(target.name, target.size) { got, total ->
                if (total > 0 && got % 4096 < 256) Log.d(TAG, "Downloaded $got/$total")
            }
            Log.i(TAG, "Download complete: ${blob.size} bytes")

            val out = File(getExternalFilesDir(null), target.name)
            out.writeBytes(blob)
            Log.i(TAG, "Persisted to disk ${out.absolutePath}")

            report(blob, sn, target.name, derivedKey)

            // Do not attempt to disable encryption: the firmware behavior is
            // "v1.50 automatically enables encryption on binding and refuses to disable it", so the earlier "restore" step could never succeed.
            Log.i(TAG, "Encryption toggle is disabled (v1.50: binding automatically enables it and prevents disabling)."
                + "The device currently stores the key derived from the above passphrase — simply reset it with your own passphrase.")
            say("Done, see logcat -s NOMILYCLIP")
        } catch (e: Exception) {
            Log.e(TAG, "Collection failed: ${e::class.simpleName}: ${e.message}", e)
            fail("${e::class.simpleName}: ${e.message}")
        } finally {
            client?.close()
        }
    }

    /** Split the envelope header for printing, making it easy to create a fixture. */
    private fun report(blob: ByteArray, sn: String, name: String, key: ByteArray) {
        Log.i(TAG, "════════ Collection Result ════════")
        Log.i(TAG, "Filename           : $name")
        Log.i(TAG, "Total length       : ${blob.size} bytes")
        if (blob.size < 60) {
            Log.w(TAG, "Less than 60 bytes, not a complete envelope — likely not actually encrypted")
            return
        }
        val magic = blob.copyOfRange(0, 6)
        Log.i(TAG, "magic  [0..6)    : ${magic.hex()}  \"${String(magic, Charsets.US_ASCII)}\"")
        Log.i(TAG, "SN     [6..24)   : \"${String(blob.copyOfRange(6, 24), Charsets.US_ASCII)}\"   (0x80 reports \"$sn\")")
        Log.i(TAG, "nonce  [24..36)  : ${blob.copyOfRange(24, 36).hex()}")
        Log.i(TAG, "verify [36..60)  : ${blob.copyOfRange(36, 60).hex()}")
        Log.i(TAG, "Ciphertext length : ${blob.size - 60} bytes")
        Log.i(TAG, "Passphrase        : \"$TEST_PASSPHRASE\"")
        Log.i(TAG, "Derived key       : ${key.hex()}")
        Log.i(TAG, "Full chain        : Passphrase → Argon2id(salt=SN) → key → write to device → record → download. "
            + "The desktop side can close the loop by re-deriving the key and decrypting.")
        Log.i(TAG, "──── Full packet hex (fixture's envelope field) ────")
        blob.hex().chunked(240).forEach { Log.i(TAG, it) }
    }

    // ── Scan ──────────────────────────────────────────────────────────

    private class Found(
        val device: android.bluetooth.BluetoothDevice,
        val name: String,
        val address: String,
        val model: String?,
    )

    private suspend fun scanOne(nameContains: String): Found? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null

        return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (cont.isCompleted) return
                        val rec = result.scanRecord ?: return
                        // CID little-endian was verified on real hardware on 2026-07-30, so it's safe to filter by it here
                        val payload = rec.getManufacturerSpecificData(DNOTE_CID) ?: return
                        val adv = parseDnoteAdvPayload(payload) ?: return
                        if (adv.model == null) return          // Exclude items not listed in the product catalog
                        val nm = rec.deviceName ?: ""
                        if (!nm.contains(nameContains)) {
                            Log.d(TAG, "Skipping $nm (looking for ones containing \"$nameContains\")")
                            return
                        }
                        scanner.stopScan(this)
                        cont.resume(
                            Found(result.device, rec.deviceName ?: "(Unnamed)", result.device.address, adv.model)
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.e(TAG, "Scan failed errorCode=$errorCode")
                        if (!cont.isCompleted) cont.resume(null)
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private fun say(s: String) = runOnUiThread { status?.text = s }

    private fun fail(s: String) {
        Log.e(TAG, s)
        say("Failed: $s")
    }

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
