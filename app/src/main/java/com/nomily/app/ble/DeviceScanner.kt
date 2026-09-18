package com.nomily.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.nomily.app.core.protocol.DNOTE_CID
import com.nomily.app.core.protocol.DnoteAdv
import com.nomily.app.core.protocol.parseDnoteAdvPayload
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE scanning.
 *
 * **Filter by (BID, PID) directory, not by name**: peripherals with `model == null` are discarded;
 * the name is only for display.
 *
 * CID is little‑endian — confirmed on real hardware.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    companion object {
        private const val TAG = "DeviceScanner"
    }

    /** A discovered device. All fields in `adv` come from the `:core` parser. */
    class Discovered(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int,
        val adv: DnoteAdv,
    ) {
        val address: String get() = device.address
    }

    /**
     * Continuous scanning; each discovery/update emits **the current full result set** (sorted by SID) so the UI doesn't have to deduplicate.
     */
    fun scan(): Flow<List<Discovered>> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            close(IllegalStateException("Bluetooth not enabled"))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Unable to obtain BluetoothLeScanner"))
            return@callbackFlow
        }

        val found = LinkedHashMap<String, Discovered>()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord ?: return
                val payload = rec.getManufacturerSpecificData(DNOTE_CID) ?: return
                val adv = parseDnoteAdvPayload(payload) ?: return
                if (adv.model == null) return          // Items not in the product catalog are never shown
                found[result.device.address] = Discovered(
                    device = result.device,
                    name = rec.deviceName ?: adv.model!!,
                    rssi = result.rssi,
                    adv = adv,
                )
                trySend(found.values.sortedBy { it.adv.deviceSidHex })
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed errorCode=$errorCode")
                close(IllegalStateException("Scan failed errorCode=$errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        Log.i(TAG, "Starting scan")

        awaitClose {
            runCatching { scanner.stopScan(cb) }
            Log.i(TAG, "Stopping scan")
        }
    }
}
