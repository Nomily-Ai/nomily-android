package com.nomily.app.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.nomily.app.core.wifi.WifiProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "WifiXfer"

/**
 * Permissions the system requires before it will serve a `WifiNetworkSpecifier` request: `NEARBY_WIFI_DEVICES`
 * on API 33+, `ACCESS_FINE_LOCATION` below it. Without them the device picker sits on "Searching" until it
 * times out and the user is told the app cancelled the request — no error points at the permission.
 */
fun fastTransferPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

open class WifiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The received bytes do not match the device's declaration (checksum or length). **Never treat this as a successful transmission** —
 * the success branch will delete the original file on the device.
 */
class WifiIntegrityException(message: String) : WifiException(message)

/**
 * Join the device hotspot.
 *
 * **This is a real platform difference**: Android has no equivalent to "writing Wi-Fi configuration into the system",
 * instead it uses `WifiNetworkSpecifier` to request a network that is **only available to this process**.
 *
 * ⚠️ After obtaining the network, you **must call `bindProcessToNetwork`**: the device hotspot has no internet access, and Android will consider it "no internet" and keep the default route on cellular. If not bound, our TCP traffic will go out via cellular,
 * resulting in "cannot connect to 192.168.88.1", while the Wi-Fi icon appears connected.
 */
class HotspotJoiner(context: Context) {

    private val appContext = context.applicationContext
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission")
    suspend fun join(ssid: String, psk: String, timeoutMs: Long = 60_000): Network {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw WifiException("Fast Transfer needs Android 10+ (WifiNetworkSpecifier).")
        }
        // Preconditions: **state them clearly** if not met. Don't let users guess the reason from a spinning dialog that eventually says "App request cancelled"
        // (On this device, location services were off; verified with location_mode=0).
        if (!wifiEnabled()) throw WifiException("Turn on Wi-Fi to use Fast Transfer.")
        if (!locationEnabled()) {
            throw WifiException("Turn on Location services — Android needs it to find the device hotspot.")
        }
        // ⚠️ **Must enable setIsHiddenSsid**: device hotspots don't necessarily broadcast their SSID in beacons.
        // Without it, the system only passively waits for beacons, the dialog stays stuck on "Searching", and is eventually cancelled by our timeout —
        // the user sees "The app has cancelled the request to select a device", which is easily misdiagnosed as an App bug.
        // With it enabled, the system actively probes and can still connect to APs that broadcast normally, so enable it unconditionally.
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setIsHiddenSsid(true)
            .setWpa2Passphrase(psk)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The device hotspot has no external network connection — if this capability requirement is not removed, it will never become available.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val deferred = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Hotspot joined ssid=$ssid")
                deferred.complete(network)
            }

            override fun onUnavailable() {
                deferred.completeExceptionally(
                WifiException(appContext.getString(com.nomily.app.R.string.hotspot_error_join_failed)),
            )
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)

        val network = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                release()
                throw WifiException(appContext.getString(com.nomily.app.R.string.hotspot_error_join_failed))
            }
        // Key: Bind this process's traffic to this network, otherwise it will use cellular
        cm.bindProcessToNetwork(network)
        return network
    }

    private fun wifiEnabled(): Boolean =
        appContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true

    /** On Android 10+, Wi-Fi scanning requires location services to be enabled — if disabled, `WifiNetworkSpecifier` will never find the target. */
    private fun locationEnabled(): Boolean {
        val lm = appContext.getSystemService(android.location.LocationManager::class.java) ?: return true
        return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }

    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        Log.i(TAG, "Hotspot released")
    }
}

/**
 * Fast Transfer TCP client.
 *
 * Commands are pure ASCII, with no terminator:
 * ```
 * list                  → type=0 frame stream, name=="0" is the end sentinel
 * pull {name} {offset}  → type=4 frame stream (v1.47+ uses pull even when starting from 0)
 * delete {name}         → type=3 ack
 * quit                  → type=5 ack, server tears down the AP afterward
 * ```
 * Frame splitting and payload parsing are handled in `:core`'s [WifiProtocol] (pure functions, with unit tests); this module only handles sending and receiving.
 */
class WifiClient(
    /** Used exclusively for localized text: errors thrown at this layer will be displayed as-is on the quick transfer panel. */
    private val context: Context,
    private val host: String = "192.168.88.1",
    private val port: Int = 6718,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var rx = ByteArray(0)
    private val inbox = ArrayDeque<WifiProtocol.Frame>()

    fun connect(connectTimeoutMs: Int = 8_000, readTimeoutMs: Int = 15_000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        s.tcpNoDelay = true
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        Log.i(TAG, "Connected to $host:$port")
    }

    fun close() {
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        rx = ByteArray(0); inbox.clear()
    }

    fun listFiles(): List<WifiProtocol.RemoteFile> {
        send("list")
        val files = mutableListOf<WifiProtocol.RemoteFile>()
        while (true) {
            val f = nextFrame()
            if (f.type != WifiProtocol.Type.LIST) continue
            if (f.msg != 0) throw WifiException("list failed (msg=${f.msg})")
            val entry = WifiProtocol.parseListEntry(f.payload) ?: break
            files += entry
            // Some firmware does not send a separate sentinel; the last real entry has index == total
            if (entry.total > 0 && entry.index >= entry.total) break
        }
        return files
    }

    /**
     * Full download. The first chunk carries the total, and subsequent chunks advance linearly by offset — a mismatch indicates packet loss, so we fail immediately and let the upper layer retry.
     *
     * [expectedSize] is the size declared in the `list`, not 0. When non-zero, it enforces a strict comparison. **A truncated transfer must never be reported as successful**:
     * Once the caller receives success, it will delete the original file on the device.
     */
    fun download(name: String, expectedSize: Int = 0, onProgress: ((Int, Int) -> Unit)? = null): ByteArray {
        send("pull $name 0")
        val buf = java.io.ByteArrayOutputStream()
        var total = 0
        var verified = 0
        var withoutChecksum = 0
        while (true) {
            val f = nextFrame()
            if (f.type != WifiProtocol.Type.PULL && f.type != WifiProtocol.Type.GET) continue
            if (f.msg != 0) throw WifiException("pull $name failed (msg=${f.msg})")
            val chunk = WifiProtocol.parseChunk(f.payload)
            if (total == 0) total = chunk.total
            if (chunk.offset != buf.size()) {
                throw WifiException("Offset gap at ${chunk.offset}; expected ${buf.size()}")
            }
            // The 16-bit checksum built into the shard. 0 = firmware not filled; skip and count, letting the length check below serve as a fallback;
            // If filled but mismatched, it indicates data corruption, so fail directly (the original device component will not be deleted).
            if (chunk.checksum == 0) {
                withoutChecksum++
            } else {
                val actual = WifiProtocol.internetChecksum(chunk.body)
                if (actual != chunk.checksum) {
                    throw WifiIntegrityException(
                        "$name: chunk checksum mismatch at offset ${chunk.offset} " +
                            "(declared=${chunk.checksum}, actual=$actual). Nothing was deleted from the device.",
                    )
                }
                verified++
            }
            buf.write(chunk.body)
            onProgress?.invoke(buf.size(), total)
            if (total > 0 && buf.size() >= total) break
            if (chunk.body.isEmpty()) break
        }

        // Length check. The `body.isEmpty()` above will exit early, and a mid-stream disconnection results in a read timeout —
        // both paths previously treated the "half-received" data as a complete file.
        if (total > 0 && buf.size() != total) {
            throw WifiIntegrityException(
                "$name: got ${buf.size()} bytes but the device announced $total. Nothing was deleted from the device.",
            )
        }
        if (expectedSize > 0 && buf.size() != expectedSize) {
            throw WifiIntegrityException(
                "$name: got ${buf.size()} bytes but the file list said $expectedSize. Nothing was deleted from the device.",
            )
        }
        Log.i(TAG, "Download of $name completed: ${buf.size()}B (verified $verified chunks, firmware without checksum: $withoutChecksum chunks)")
        return buf.toByteArray()
    }

    fun deleteFile(name: String) {
        send("delete $name")
        val f = nextFrame()
        if (f.type != WifiProtocol.Type.DELETE || f.msg != 0) {
            throw WifiException("delete $name failed (type=${f.type}, msg=${f.msg})")
        }
    }

    /** Let the server shut itself down. Failing to send this isn't an error — we'll disconnect and close the AP shortly after. */
    fun quit() {
        runCatching {
            send("quit")
            nextFrame()
        }
    }

    private fun send(cmd: String) {
        val out = output ?: throw WifiException(context.getString(com.nomily.app.R.string.wifi_error_not_connected))
        out.write(cmd.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun nextFrame(): WifiProtocol.Frame {
        while (inbox.isEmpty()) {
            val ins = input ?: throw WifiException(context.getString(com.nomily.app.R.string.wifi_error_not_connected))
            val tmp = ByteArray(65536)
            val n = try {
                ins.read(tmp)
            } catch (e: java.net.SocketTimeoutException) {
                throw WifiException(context.getString(com.nomily.app.R.string.wifi_error_timeout), e)
            }
            if (n <= 0) throw WifiException(context.getString(com.nomily.app.R.string.wifi_error_malformed))
            rx += tmp.copyOfRange(0, n)
            val split = WifiProtocol.splitFrames(rx)
            rx = split.rest
            inbox.addAll(split.frames)
        }
        return inbox.removeFirst()
    }
}
