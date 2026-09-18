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
import com.nomily.app.core.protocol.DNOTE_CID
import com.nomily.app.core.protocol.parseDnoteAdvPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * **Irreversible device cleanup tool**, present only in the debug variant.
 *
 * Requirement as of 2026-07-30: clean all state left on the device during debugging,
 * to prevent subsequent debugging from being polluted by stale data. The order matters:
 *
 * ```
 * 0x94 Format storage   → Erase all recordings (switches and Bluetooth name are kept)
 * 0x93 Factory reset    → Reset switch state + Bluetooth name to defaults
 * 0xA0 [0x00] Unbind    → Clear binding (placed later to avoid rejection of the first two steps due to unbound state)
 * 0x95 Power off       → After ack the device disconnects immediately, which is normal
 * ```
 *
 * ⚠️ **One item cannot be cleared**: the ChaCha20 key stored on the device. v1.50 automatically enables encryption on binding and **refuses to disable it**,
 * `0xA2` can only overwrite, not erase. Therefore, guaranteeing that "no residual key remains on the device" is impossible — we can only say
 * that it currently holds the debug‑period key (derived from the debug passphrase), and the next user can overwrite it by resetting with their own passphrase.
 *
 * Run command: `adb shell am start -n com.nomily.app/.DeviceWipeActivity --es device D·NOTE`
 */
@SuppressLint("MissingPermission")
class DeviceWipeActivity : Activity() {

    private companion object {
        const val TAG = "NOMILYWIPE"
        const val SCAN_TIMEOUT_MS = 45_000L
        const val CONNECT_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 15f
            setPadding(48, 96, 48, 48)
            text = "Preparing to clean up..."
        }
        setContentView(status)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "════════ DeviceWipeActivity started ════════")
        scope.launch { run() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun run() {
        val want = intent?.getStringExtra("device") ?: "D·NOTE"
        var client: DnoteBleClient? = null
        try {
            Log.i(TAG, "Target: broadcast name contains \"$want\"")
            say("Scanning $want …")
            val found = scanOne(want) ?: run { fail("Could not find \"$want\""); return }
            Log.i(TAG, "Selected ${found.name} / ${found.address} model=${found.model}")

            say("Connecting…")
            for (attempt in 1..CONNECT_ATTEMPTS) {
                client = DnoteBleClient(this, found.device)
                try { client.connect(); break } catch (e: Exception) {
                    Log.w(TAG, "Connection failed ($attempt/$CONNECT_ATTEMPTS): ${e.message}")
                    client.close(); client = null
                    if (attempt < CONNECT_ATTEMPTS) delay(1500)
                }
            }
            val c = client ?: run { fail("Connection failed"); return }
            Log.i(TAG, "Connected, MTU=${c.negotiatedMtu}")

            Log.i(TAG, "Device info before cleanup = ${c.getDeviceInfo()}")
            runCatching { c.getFileList() }
                .onSuccess { fs -> Log.i(TAG, "File count before cleanup = ${fs.size}"); fs.forEach { Log.i(TAG, "  #${it.index} ${it.name} ${it.size}B") } }
                .onFailure { Log.w(TAG, "Failed to get file list (non-blocking cleanup): ${it.message}") }

            say("Formatting storage (0x94, max 30s)...")
            step("0x94 Format storage") { c.formatDisk() }

            say("Factory reset (0x93)...")
            step("0x93 Factory reset") { c.factoryReset() }

            say("Unbinding (0xA0)...")
            step("0xA0 Unbind device") { c.unbindDevice() }

            runCatching { c.getFileList() }
 .onSuccess { Log.i(TAG, "File count after cleanup = ${it.size}") }
                .onFailure { Log.w(TAG, "Failed to get list after cleanup: ${it.message}") }
            runCatching { c.queryBondState() }
                .onSuccess { Log.i(TAG, "Bond state after cleanup bound=${it.bound}") }
                .onFailure { Log.w(TAG, "Failed to query bond state after cleanup: ${it.message}") }

            Log.i(TAG, "⚠️ Items that cannot be cleared: ChaCha20 key stored on the device. v1.50 encryption prevents closing; 0xA2 can only overwrite.")

            say("Shutting down (0x95)…")
            c.shutdown()
            Log.i(TAG, "════════ Cleanup complete, shutdown command sent ════════")
            say("Done: formatted + factory reset + unbound + shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e::class.simpleName}: ${e.message}", e)
            fail("${e::class.simpleName}: ${e.message}")
        } finally {
            client?.close()
        }
    }

    private suspend fun step(name: String, op: suspend () -> Unit) {
        try {
            op()
    Log.i(TAG, "✅ $name completed")
        } catch (e: Exception) {
    Log.e(TAG, "❌ $name failed: ${e.message}")
            throw e
        }
    }

    private class Found(
        val device: android.bluetooth.BluetoothDevice,
        val name: String,
        val address: String,
        val model: String?,
    )

    private suspend fun scanOne(nameContains: String): Found? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (cont.isCompleted) return
                        val rec = result.scanRecord ?: return
                        val payload = rec.getManufacturerSpecificData(DNOTE_CID) ?: return
                        val adv = parseDnoteAdvPayload(payload) ?: return
                        if (adv.model == null) return
                        val nm = rec.deviceName ?: ""
                        if (!nm.contains(nameContains)) return
                        scanner.stopScan(this)
                        cont.resume(Found(result.device, nm, result.device.address, adv.model))
                    }
                    override fun onScanFailed(errorCode: Int) {
    Log.e(TAG, "Scan failed errorCode=$errorCode")
                        if (!cont.isCompleted) cont.resume(null)
                    }
                }
                scanner.startScan(null, ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private fun say(s: String) = runOnUiThread { status?.text = s }
    private fun fail(s: String) { Log.e(TAG, s); say("Failed: $s") }
}
