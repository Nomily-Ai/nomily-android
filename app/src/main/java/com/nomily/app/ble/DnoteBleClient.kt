package com.nomily.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.nomily.app.core.protocol.DnoteProtocol
import com.nomily.app.core.protocol.PacketCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

/**
 * Android side Nomi / D·NOTE BLE client — **L2 platform adaptation layer**.
 *
 * Structure and semantics:
 *
 *  - Notifications are split into three streams by command code: response / file transfer (0x70) / real‑time stream (0x68), handled by `_on_notify`
 *  - The device processes only one command at a time → serialized with [cmdLock]
 *  - `recv(expect:)` aligns by command code, discarding frames pushed by the device (fixes the race where the switch is turned off but cannot be opened)
 *  - Upon receiving 0x55 (physical key stops recording) immediately send ack
 *
 * ⚠️ BLE timing / reconnection / permissions are not automated; they count only after verification on real hardware.
 * Frame encoding/decoding resides in `:core`'s [PacketCodec], which has unit‑test coverage.
 */
@SuppressLint("MissingPermission")
class DnoteBleClient(
    private val context: Context,
    private val device: BluetoothDevice,
) {
    companion object {
        private const val TAG = "DnoteBle"
        private val SERVICE_UUID: UUID = UUID.fromString(DnoteProtocol.SERVICE_UUID)
        private val RX_UUID: UUID = UUID.fromString(DnoteProtocol.RX_CHAR_UUID)
        private val TX_UUID: UUID = UUID.fromString(DnoteProtocol.TX_CHAR_UUID)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_TIMEOUT_MS = 5_000L
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val XFER_TIMEOUT_MS = 30_000L

        /** Real‑time stream silent timeout: treat the stream as broken after 30 seconds without a frame. */
        private const val STREAM_IDLE_TIMEOUT_MS = 30_000L

        /** Upper bound for waiting on MTU negotiation — it only affects throughput; even if negotiation fails, continue onward. */
        private const val MTU_TIMEOUT_MS = 5_000L
    }

    class DnoteException(message: String) : Exception(message)

    /**
     * Received bytes are fewer than declared by the device (or out of order). **Callers must not treat this as transfer complete** —
     * If the device's original file is deleted on top of this, the user ends up with no usable recording on either side.
     */
    class DnoteTransferIncompleteException(message: String) : Exception(message)

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null

    /** Three channels: response / file transfer / real‑time stream. */
    private val respQ = Channel<ByteArray>(Channel.BUFFERED)
    private val xferQ = Channel<ByteArray>(Channel.BUFFERED)
    private val streamQ = Channel<ByteArray>(Channel.BUFFERED)

    /** The device processes only one command at a time. */
    private val cmdLock = Mutex()

    private var connected = CompletableDeferred<Unit>()
    private var mtuReady = CompletableDeferred<Unit>()
    private var servicesReady = CompletableDeferred<Unit>()
    private var notifyReady = CompletableDeferred<Unit>()
    private var writeDone: CompletableDeferred<Unit>? = null

    var negotiatedMtu: Int = 23
        private set

    // ── Connection / Handshake ───────────────────────────────────────────────────

    suspend fun connect(timeoutMs: Long = 20_000) {
        connected = CompletableDeferred()
        mtuReady = CompletableDeferred()
        servicesReady = CompletableDeferred()
        notifyReady = CompletableDeferred()

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(timeoutMs) { connected.await() }

         // ⚠️ **Only one GATT operation can be in flight at a time**. Previously requestMtu() was immediately followed by
        // discoverServices(), which the stack silently dropped (returned false, no callback) —
        // the result was that the connection succeeded and MTU was negotiated, then it hung on service discovery until timeout.
        // Real‑device test: reproduced 100% on Pixel 3 / Android 12.
        // This sequencing constraint is specific to Android.
        if (gatt?.requestMtu(517) != true) mtuReady.complete(Unit)    // Don't wait if it wasn't sent
        // Failure to negotiate MTU should not block the connection: fall back to default 23 if unavailable, functionality still works (just slower)
        runCatching { withTimeout(MTU_TIMEOUT_MS) { mtuReady.await() } }
            .onFailure { Log.w(TAG, "MTU negotiation not received, proceeding with default $negotiatedMtu") }

        if (gatt?.discoverServices() != true) throw DnoteException("discoverServices could not be sent")
        withTimeout(timeoutMs) { servicesReady.await() }

        val svc = gatt?.getService(SERVICE_UUID)
            ?: throw DnoteException("Service ${DnoteProtocol.SERVICE_UUID} not found")
        rx = svc.getCharacteristic(RX_UUID) ?: throw DnoteException("RX_CHAR not found")
        tx = svc.getCharacteristic(TX_UUID) ?: throw DnoteException("TX_CHAR not found")

        enableNotifications(tx!!)
        withTimeout(timeoutMs) { notifyReady.await() }
        Log.i(TAG, "Handshake completed, MTU=$negotiatedMtu")
    }

    /**
     * Notification of unexpected disconnection (device powered off / out of range / connection taken by another phone).
     *
     * **Only triggered once after a successful handshake and when we did not actively [close]** — otherwise automatic reconnection would treat a user‑initiated disconnect or a connection‑attempt failure as a drop, resulting in a reconnect loop the user cannot stop.
     */
    var onUnexpectedDisconnect: (() -> Unit)? = null

    /**
     * Device **actively** reports recording start/stop (0x54 start / 0x55 stop); the second argument is the filename carried in the 0x54 payload.
     *
     * These two are only sent when **the physical key** is pressed — the App's own `0x51`/`0x50` commands only request an ack and do not trigger a notification.
     * Therefore the UI's recording state must be driven from both sides: the App's command is reflected locally, and physical‑key events come via this callback.
     */
    var onRecordingStateChanged: ((recording: Boolean, name: String?) -> Unit)? = null

    @Volatile private var everConnected = false

    @Volatile private var closedByUs = false

    fun close() {
        closedByUs = true
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rx = null
        tx = null
    }

    private fun enableNotifications(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: throw DnoteException("Not connected")
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: throw DnoteException("TX_CHAR missing CCCD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // ── Low‑level send/receive ──────────────────────────────────────────────────────

    private suspend fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val g = gatt ?: throw DnoteException("Not connected")
        val ch = rx ?: throw DnoteException("Not connected")
        drain(respQ)                                    // clear stale responses before sending
        val pkt = PacketCodec.encode(cmd, payload)
        val done = CompletableDeferred<Unit>()
        writeDone = done
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = pkt
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) throw DnoteException("writeCharacteristic returned failure cmd=0x%02X".format(cmd))
        withTimeout(DEFAULT_TIMEOUT_MS) { done.await() }
    }

    private suspend fun sendJson(cmd: Int, json: String) =
        send(cmd, json.toByteArray(Charsets.UTF_8))

    private suspend fun recv(timeoutMs: Long = DEFAULT_TIMEOUT_MS): PacketCodec.Frame {
        val raw = try {
            withTimeout(timeoutMs) { respQ.receive() }
        } catch (e: TimeoutCancellationException) {
            // The user‑visible message omits milliseconds; milliseconds are kept only in the log.
            Log.w(TAG, "Response timeout: ${timeoutMs}ms")
            throw DnoteException(context.getString(com.nomily.app.R.string.ble_error_timeout))
        }
        return PacketCodec.decode(raw) ?: throw DnoteException("Unable to decode response: ${raw.size} bytes")
    }

    /**
     * Receive aligned by command code — the device may push status frames, and blind receives would treat pushes as replies.
     */
    private suspend fun recvExpect(cmd: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PacketCodec.Frame {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw DnoteException("Timeout waiting for cmd=0x%02X".format(cmd))
            val f = recv(remaining)
            if (f.cmd == cmd) return f
        Log.d(TAG, "Discarding unsolicited push cmd=0x%02X (while waiting for 0x%02X)".format(f.cmd, cmd))
        }
    }

    private suspend fun recvJsonExpect(cmd: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val f = recvExpect(cmd, timeoutMs)
        return JSONObject(String(f.payload, Charsets.UTF_8))
    }

    private suspend fun sendExpectAck(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = ACK_TIMEOUT_MS,
    ) {
        send(cmd, payload)
        val f = recvExpect(cmd, timeoutMs)
        if (!f.isAck) throw DnoteException("Command 0x%02X not acknowledged".format(cmd))
    }

    /**
     * Command‑level retry (3 attempts, 1 s apart).
     *
     * **This layer is not optional**: the device sometimes does not answer a command at all — `0x90` has been observed silent for two consecutive 15 s windows. Without retries a single missed response is a hard failure.
     *
     * Before retrying, clear the response queue: frames delayed from the previous round must not be treated as replies in the next round.
     */
    private suspend fun <T> cmdRetry(
        retries: Int = 3,
        delayMs: Long = 1_000,
        op: suspend () -> T,
    ): T {
        var last: Exception? = null
        for (attempt in 1..retries) {
            try {
                return op()
            } catch (e: DnoteException) {
                last = e
                Log.w(TAG, "Command failed (attempt $attempt/$retries): ${e.message}")
            } catch (e: org.json.JSONException) {
                last = e
                Log.w(TAG, "Response not valid JSON (attempt $attempt/$retries): ${e.message}")
            }
            if (attempt < retries) {
                drain(respQ)
                delay(delayMs)
            }
        }
        throw last ?: DnoteException("Command retry failed after $retries attempts")
    }

    // ── High‑level commands ──────────────────────────────────────────────────────

    /** CMD 0x80 — SN / firmware version / battery level / remaining storage / Bluetooth name. */
    suspend fun getDeviceInfo(): JSONObject = cmdLock.withLock { readDeviceInfoLocked() }

    private suspend fun readDeviceInfoLocked(): JSONObject = cmdRetry {
        send(DnoteProtocol.Cmd.DEVICE_INFO)
        recvJsonExpect(DnoteProtocol.Cmd.DEVICE_INFO)
    }

    /**
     * Every mutating command re-reads 0x80 while holding the command lock.
     * UI disabling is only advisory: recording can start from the physical
     * button after Compose last rendered the screen.
     */
    private suspend fun <T> whenNotRecording(op: suspend () -> T): T = cmdLock.withLock {
        if (com.nomily.app.ble.DeviceInfo(readDeviceInfoLocked()).isRecording) {
            throw DnoteException("Stop recording before changing device settings or disconnecting.")
        }
        op()
    }

    /**
     * CMD 0x56 — query current recording status: `rec` / `name` / `recd` (recorded milliseconds) / `size`.
     *
     * `recd` is **the device's own timer**, so recordings started via the physical key are accurate — the UI must not synthesize this from the App's stopwatch.
     */
    suspend fun getRecordingStatus(): JSONObject = cmdLock.withLock {
        cmdRetry {
            send(DnoteProtocol.Cmd.REC_STATUS)
            recvJsonExpect(DnoteProtocol.Cmd.REC_STATUS)
        }
    }

    /** CMD 0x81 — switch states: `ms/led/motor/nc/wav/vad` + `gain`/`nn`/`autoff`. */
    suspend fun getSwitchInfo(): JSONObject = cmdLock.withLock {
        cmdRetry {
            send(DnoteProtocol.Cmd.SWITCH_INFO)
            recvJsonExpect(DnoteProtocol.Cmd.SWITCH_INFO)
        }
    }

    /** The six switches share a single shape: `[on(1)]` + ack. The name is taken from the key of `DnoteProtocol.SWITCH_CMDS`. */
    suspend fun setSwitch(name: String, on: Boolean) {
        val cmd = DnoteProtocol.SWITCH_CMDS[name] ?: throw IllegalArgumentException("Unknown switch: $name")
        whenNotRecording {
            cmdRetry { sendExpectAck(cmd, byteArrayOf(if (on) 1 else 0)) }
        }
    }

    /**
     * CMD 0x88 — turn the device's Wi‑Fi AP on/off (used for fast transfer, v1.47+). ssid / psk are each limited to 32 bytes UTF‑8.
     *
     * ⚠️ **ack only means “command received”**: bringing up the radio on battery power takes 20–45 seconds; before joining the hotspot, poll the `wifiap` bit of 0x80, don’t connect just because you got ack.
     *
     * ⚠️ **When turning off, you must include the same ssid/psk pair used when turning on**, not just send a bare `{"ap":0}`.
     * The documented shape is the bare one, but the firmware actually requires credentials — a bare request results in `ack=false` and the AP continues broadcasting.
     * Reproduced on real hardware: a teardown sending a bare `{"ap":0}` got no ack on all three retries.
     */
    suspend fun setWifiAp(on: Boolean, ssid: String = "", psk: String = "") {
        require(ssid.toByteArray(Charsets.UTF_8).size <= 32) { "SSID exceeds 32 bytes" }
        require(psk.toByteArray(Charsets.UTF_8).size <= 32) { "PSK exceeds 32 bytes" }
        val json = when {
            on -> """{"ap":1,"ssid":"$ssid","psk":"$psk"}"""
            ssid.isNotEmpty() || psk.isNotEmpty() -> """{"ap":0,"ssid":"$ssid","psk":"$psk"}"""
            else -> """{"ap":0}"""
        }
        // Only log the shape, not the values — SSID/PSK are omitted from logs
        Log.i(TAG, "→ 0x88 ap=${if (on) 1 else 0} fields=${if (on || ssid.isNotEmpty()) "ap,psk,ssid" else "ap"}")
        cmdLock.withLock { cmdRetry { sendExpectAck(DnoteProtocol.Cmd.WIFI_AP, json.toByteArray(Charsets.UTF_8), 15_000) } }
    }

    /** CMD 0x8C — microphone gain, 1…9. */
    suspend fun setMicGain(level: Int) {
        require(level in 1..9) { "Gain range 1…9" }
        whenNotRecording { cmdRetry { sendExpectAck(DnoteProtocol.Cmd.MIC_GAIN, byteArrayOf(level.toByte())) } }
    }

    /** CMD 0x8D — noise reduction level, 1…9. */
    suspend fun setNrLevel(level: Int) {
        require(level in 1..9) { "Noise reduction level range 1…9" }
        whenNotRecording { cmdRetry { sendExpectAck(DnoteProtocol.Cmd.NR_LEVEL, byteArrayOf(level.toByte())) } }
    }

    /**
     * CMD 0x8F — idle auto‑shutdown seconds, **big‑endian uint32**.
     * To disable auto‑shutdown, send [DnoteProtocol.IDLE_OFF_NEVER] (sentinel value, not 0).
     */
    suspend fun setIdleOff(seconds: Int) {
        require(seconds > 0) { "To disable auto shutdown, pass IDLE_OFF_NEVER, not 0" }
        val be = byteArrayOf(
            (seconds ushr 24).toByte(), (seconds ushr 16).toByte(),
            (seconds ushr 8).toByte(), seconds.toByte(),
        )
        whenNotRecording { cmdRetry { sendExpectAck(DnoteProtocol.Cmd.IDLE_OFF, be) } }
    }

    /**
     * Bluetooth name. **Length determines which command to use**: ≤ 16 B uses 0x86 with JSON `{"bt":…}`, longer names use 0x8E with **raw UTF‑8** (not JSON).
     */
    suspend fun setBluetoothName(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= DnoteProtocol.BT_NAME_MAX_BYTES) { "Bluetooth name must be 1…${DnoteProtocol.BT_NAME_MAX_BYTES} UTF-8 bytes (currently ${bytes.size})" }
        whenNotRecording {
            cmdRetry {
                val cmd = if (bytes.size <= 16) DnoteProtocol.Cmd.BT_NAME_SHORT else DnoteProtocol.Cmd.BT_NAME_LONG
                if (bytes.size <= 16) {
                    sendJson(cmd, JSONObject().put("bt", name).toString())
                } else {
                    send(cmd, bytes)
                }
                val f = recvExpect(cmd, ACK_TIMEOUT_MS)
                if (!f.isAck) throw DnoteException("Rename not acknowledged")
            }
        }
    }

    /** CMD 0x87 — time synchronization. The device only accepts `yyyyMMddHHmmss` in the device’s local timezone. */
    suspend fun syncTime(date: java.util.Date = java.util.Date()) {
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(date)
        cmdLock.withLock {
            cmdRetry {
                sendJson(DnoteProtocol.Cmd.SYNC_TIME, JSONObject().put("time", stamp).toString())
                val f = recvExpect(DnoteProtocol.Cmd.SYNC_TIME, ACK_TIMEOUT_MS)
                if (!f.isAck) throw DnoteException("Time sync not acknowledged")
            }
        }
    }

    class BondState(val bound: Boolean, val bondId: ByteArray?)

    /** CMD 0xA1 — `[0x00]` not bound / `[0x01][bond_id(16)]` bound. */
    suspend fun queryBondState(): BondState = cmdLock.withLock {
      cmdRetry {
        send(DnoteProtocol.Cmd.QUERY_BOND)
        val f = recvExpect(DnoteProtocol.Cmd.QUERY_BOND, ACK_TIMEOUT_MS)
        if (f.payload.isEmpty()) throw DnoteException("Bond status response empty")
        val bound = (f.payload[0].toInt() and 0xFF) == 0x01
        val id = if (bound && f.payload.size >= 1 + DnoteProtocol.BOND_ID_LENGTH) {
            f.payload.copyOfRange(1, 1 + DnoteProtocol.BOND_ID_LENGTH)
        } else null
        BondState(bound, id)
      }
    }

    /** CMD 0xA0 — bind. Payload `[0x01][bond_id(16)]`; firmware only checks whether a binding exists. */
    suspend fun bindDevice(bondId: ByteArray) {
        require(bondId.size == DnoteProtocol.BOND_ID_LENGTH) { "bondId must be 16 bytes" }
        cmdLock.withLock {
            cmdRetry { sendExpectAck(DnoteProtocol.Cmd.BIND, byteArrayOf(0x01) + bondId) }
        }
    }

    /** CMD 0xA3 — read device‑side encryption switch. */
    suspend fun getEncryptionState(): Boolean = cmdLock.withLock {
      cmdRetry {
        send(DnoteProtocol.Cmd.ENCRYPT_QUERY)
        val f = recvExpect(DnoteProtocol.Cmd.ENCRYPT_QUERY, ACK_TIMEOUT_MS)
        if (f.payload.isEmpty()) throw DnoteException("Encryption status response empty")
        (f.payload[0].toInt() and 0xFF) == 0x01
      }
    }

    /**
     * CMD 0xA2 — enable/disable device‑side ChaCha20 encryption. Payload `[on(1)][key(32)]`.
     * **When disabling, the key must match the one currently stored on the device**, the firmware will reject a mismatched disable request.
     */
    suspend fun setEncryption(on: Boolean, key: ByteArray) {
        require(key.size == DnoteProtocol.CHACHA_KEY_LENGTH) { "key must be 32 bytes" }
        whenNotRecording {
            cmdRetry { sendExpectAck(DnoteProtocol.Cmd.ENCRYPT_SET, byteArrayOf(if (on) 1 else 0) + key) }
        }
    }

    /**
     * CMD 0x51 / 0x50 — start/stop recording.
     *
     * The device replies with **two frames** for each: a proactive 0x56 status + an ack for the command, order unspecified.
     * If you wait for only one frame, the other will be consumed by the next command's recv.
     */
    /**
     * Returns true when the passphrase is not fully set — injected by [com.nomily.app.NomiViewModel] when “device encryption is on and this SN’s key is missing on the phone”. The guard is placed at the command layer rather than on individual buttons: the recording entry points are both the home button and the real‑time page; UI greying out is only a hint, bypassing one would leave an unrecoverable file on the device.
     */
    var passphraseSetupIncomplete: () -> Boolean = { false }

    /** [bypassPassphraseGate] is used only for the passphrase verification flow: that probe clip validates its own recording and is deleted after recording, not the “user creates an unrecoverable file” scenario the guard protects against. User‑facing entry points always use the default value. */
    suspend fun startRecording(realTime: Boolean = true, bypassPassphraseGate: Boolean = false): JSONObject {
        if (!bypassPassphraseGate && passphraseSetupIncomplete()) {
            throw DnoteException(
                context.getString(com.nomily.app.R.string.dnote_error_passphrase_setup_incomplete),
            )
        }
        return startRecordingLocked(realTime)
    }

    private suspend fun startRecordingLocked(realTime: Boolean): JSONObject = cmdLock.withLock {
        cmdRetry {
            send(DnoteProtocol.Cmd.START_REC, byteArrayOf(if (realTime) 1 else 0))
            collectRecordingResponses(DnoteProtocol.Cmd.START_REC)
        }
    }

    suspend fun stopRecording(): JSONObject = cmdLock.withLock {
        cmdRetry {
            send(DnoteProtocol.Cmd.STOP_REC)
            collectRecordingResponses(DnoteProtocol.Cmd.STOP_REC)
        }
    }

    /**
     * CMD 0x68 `0x00` — start real‑time OPUS stream. **First flush any stale stream packets**, otherwise fragments from the previous session will mix into this one.
     *
     * ⚠️ **Do not wait for ack**: the device does not send a response frame for 0x68; audio comes directly from [streamQ].
     * The first version used `sendExpectAck`; on real hardware it caused the whole session to fail after three 10‑second timeouts (observed 2026‑08‑04).
     */
    suspend fun startStream() {
        drain(streamQ)
        cmdLock.withLock { send(DnoteProtocol.Cmd.STREAM, byteArrayOf(0x00)) }
    }

    /** CMD 0x68 `0x01` — stop stream. The device does not respond; sending is sufficient. */
    suspend fun stopStream() {
        cmdLock.withLock { send(DnoteProtocol.Cmd.STREAM, byteArrayOf(0x01)) }
    }

    /**
     * Real‑time OPUS frame stream. Packet shape `[PID][0x68][LC][START(1)][OFFSET(4 LE)][DATA…]`.
     *
     * **Does not go through [cmdLock]**: the stream occupies the channel for the entire recording, and commands like stopStream must be able to interject; data travels via the separate logical channel [streamQ].
     *
     * Termination conditions: STOP flag, recording stopped by physical key, 30 seconds without a frame, or caller cancellation.
     *
     * [firstFrameTimeoutMs] only governs **the first frame**. Devices that never push a stream (recording started by the physical key before the App connects, `upload=0`) would otherwise wait the full 30 seconds, appearing as a broken link; here we instead throw `stream_no_audio` so the UI can explain what happened.
     */
    fun streamAudio(
        firstFrameTimeoutMs: Long = STREAM_IDLE_TIMEOUT_MS,
    ): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        var sawPacket = false
        while (true) {
            val pkt = try {
                withTimeout(if (sawPacket) STREAM_IDLE_TIMEOUT_MS else firstFrameTimeoutMs) { streamQ.receive() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (sawPacket) throw e
                throw DnoteException(
                    context.getString(com.nomily.app.R.string.dnote_error_stream_no_audio),
                )
            }
            sawPacket = true
            if (pkt.size < 4 || (pkt[0].toInt() and 0xFF) != DnoteProtocol.PID) continue
            val lc = pkt[2].toInt() and 0xFF
            when (pkt[3].toInt() and 0xF0) {
                DnoteProtocol.Stream.ERROR -> throw DnoteException("Real-time stream error")
                DnoteProtocol.Stream.STOP -> return@flow
            }
            if (lc > 5) {
                val from = 8
                val to = minOf(3 + lc, pkt.size)
                if (to > from) emit(pkt.copyOfRange(from, to))
            }
        }
    }

    /**
     * Collect the response for stopping recording.
     *
     * ⚠️ **Check the ack success bit**, not just a matching cmd: when the device explicitly rejects (USB charging / storage full / not paired) the UI would otherwise switch to “recording” and later steps build on that false state. A timeout must not `break` into an empty status either — that is indistinguishable from success.
     *
     * ⚠️ **But the “success bit” isn’t the only success form**: firmware 1.50 returns JSON `{"name":"…opus"}` for 0x50/0x51. The determination is in the three branches of the `when` below.
     */
    private suspend fun collectRecordingResponses(ackCmd: Int): JSONObject {
        var status = JSONObject()
        val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw DnoteException("Start/stop recording not acknowledged within ${DEFAULT_TIMEOUT_MS}ms (cmd=0x%02X)".format(ackCmd))
            val f = try {
                recv(remaining)
            } catch (e: DnoteException) {
                throw DnoteException("Start/stop recording did not receive ack (cmd=0x%02X): ${e.message}".format(ackCmd))
            }
            when (f.cmd) {
                DnoteProtocol.Cmd.REC_STATUS ->
                    runCatching { JSONObject(String(f.payload, Charsets.UTF_8)) }
                        .onSuccess { status = it }
                ackCmd -> {
                    // Three response types; don’t only recognize the first:
                    //   1 byte        — success bit, 0x01 means success, others mean rejection
                    //   JSON (starts with `{`) — from firmware 1.50 onward, 0x50/0x51 return
                    //                    `{"name":"20260812181126.opus"}`, **this is a success**,
                    //                    containing the filename of the just‑started/just‑ended recording
                    //   empty         — old firmware raw ack, allowed but logged
                    //
                    // Only checking `payload[0] == 0x01` treats the JSON `{` (0x7B) as a rejection:
                    // The UI shows “Device rejected the start/stop recording command”, but the device is actually recording,
                    // and the outer cmdRetry will resend twice — one tap results in three recordings (tested 2026-08-12).
                    val first = f.payload.firstOrNull()?.toInt()?.and(0xFF)
                    when {
                        f.payload.isEmpty() ->
                            Log.w(TAG, "The ack for start/stop recording lacks the success bit (cmd=0x%02X), treating as passed".format(ackCmd))
                        first == '{'.code ->
                            runCatching { JSONObject(String(f.payload, Charsets.UTF_8)) }
                                .onSuccess { body ->
                                    // Merge with the state pushed by 0x56 instead of overwriting each other
                                    for (k in body.keys()) status.put(k, body.get(k))
                                }
                                .onFailure {
                                    throw DnoteException(
                                        "record start/stop ack looks like JSON but will not parse (cmd=0x%02X)".format(ackCmd)
                                    )
                                }
                        first != 0x01 ->
                            throw DnoteException(
                                "device rejected the record start/stop command (cmd=0x%02X, status=0x%02X)".format(ackCmd, first)
                            )
                    }
                    return status
                }
                else -> Log.d(TAG, "Ignoring cmd=0x%02X during start/stop recording".format(f.cmd))
            }
        }
    }

    /**
     * CMD 0x91 — Delete a recording on the device: send JSON, wait for ack.
     */
    suspend fun deleteFile(name: String) = cmdLock.withLock {
        cmdRetry {
            sendJson(DnoteProtocol.Cmd.FILE_DELETE, """{"name":"$name"}""")
            val f = recvExpect(DnoteProtocol.Cmd.FILE_DELETE, ACK_TIMEOUT_MS)
            if (!f.isAck) throw DnoteException("Delete unacknowledged: $name")
        }
    }

    /**
     * Cancel an ongoing transfer: send `0x70 cmd=0`, then flush remaining packets to a terminated state.
     *
     * **Why we can't stop locally only**: cancellation only stops us; **the device will continue pushing the whole file**.
     * The leftover 0x70 fragments will collide with the next command
     * (cancelling the download + a Wi‑Fi roundtrip makes `getFileList` fail). Therefore we must tell the device to stop,
     * and read until the terminated state so the channel is clean.
     */
    private suspend fun cancelTransfer(name: String) {
        runCatching { sendJson(DnoteProtocol.Cmd.XFER, """{"cmd":0,"name":"$name","offset":0}""") }
        drainXferUntilTerminal()
    }

    private suspend fun drainXferUntilTerminal() {
        Log.i(TAG, "Transfer cancelled: draining remaining packets until termination state")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val pkt = try {
                withTimeout(1_000) { xferQ.receive() }
            } catch (e: Exception) {
                break
            }
            if (pkt.size < 4) continue
            when (pkt[3].toInt() and 0xF0) {
                DnoteProtocol.Xfer.EOF, DnoteProtocol.Xfer.ERROR,
                DnoteProtocol.Xfer.CANCEL, DnoteProtocol.Xfer.NOT_FOUND -> {
                    Log.i(TAG, "Reached end-of-stream during drain, stopping")
                    break
                }
            }
        }
        drain(xferQ)
    }

    /** CMD 0xA0 payload `[0x00]` — Unbind. The device side is permissive; any app can unbind another app's binding. */
    suspend fun unbindDevice() = whenNotRecording {
        cmdRetry { sendExpectAck(DnoteProtocol.Cmd.BIND, byteArrayOf(0x00)) }
    }

    /**
     * CMD 0x94 — Format internal storage. **All recordings are erased**; switches and Bluetooth name are retained.
     * Formatting takes time; timeout is set to 30 s.
     */
    suspend fun formatDisk() = whenNotRecording {
        cmdRetry { sendExpectAck(DnoteProtocol.Cmd.FORMAT_DISK, timeoutMs = 30_000) }
    }

    /** CMD 0x93 — Reset switch state and Bluetooth name to factory defaults. */
    suspend fun factoryReset() = whenNotRecording {
        cmdRetry { sendExpectAck(DnoteProtocol.Cmd.FACTORY_RESET, timeoutMs = 15_000) }
    }

    /**
     * CMD 0x95 — Power off. **The device hangs up immediately after ack**, so disconnection/timeout are considered normal,
     * and are swallowed.
     */
    suspend fun shutdown() = whenNotRecording {
        try {
            sendExpectAck(DnoteProtocol.Cmd.SHUTDOWN, timeoutMs = 5_000)
        } catch (e: DnoteException) {
            Log.i(TAG, "Disconnection/timeout after shutdown is normal: ${e.message}")
        }
    }

    class DeviceFile(val index: Int, val name: String, val size: Int)

    /** CMD 0x90 — The device streams the file list as individual JSON objects, ending with a record where `end == 1`. */
    suspend fun getFileList(): List<DeviceFile> = cmdLock.withLock {
      cmdRetry {
        send(DnoteProtocol.Cmd.FILE_LIST)
        val out = ArrayList<DeviceFile>()
        while (true) {
            val json = recvJsonExpect(DnoteProtocol.Cmd.FILE_LIST, 15_000)
            if (json.optInt("end", 0) == 1) break
            val name = json.optString("name", "")
            if (name.isEmpty() || !json.has("index") || !json.has("size")) {
                Log.w(TAG, "Skipping unexpected package in file list: ${json}")
                continue
            }
            out.add(DeviceFile(json.getInt("index"), name, json.getInt("size")))
        }
        out
      }
    }

    /**
     * CMD 0x70 — Download a recording file.
     *
     * Chunk format `[PID][0x70][LC][START(1)][OFFSET(4 BE)][DATA…]`,
     * the high nibble of START is the status. **The data segment is truncated to LC** (`dataEnd = 3 + lc`).
     */
    suspend fun downloadFile(
        name: String,
        expectedSize: Int = 0,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): ByteArray = cmdLock.withLock {
        try {
            downloadInner(name, expectedSize, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed stop. **Must** tell the device to stop and flush, otherwise leftover packets will collide with the next command.
            // Wrapped in NonCancellable: the parent coroutine is already cancelled, so a regular suspension point would immediately re‑throw.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                cancelTransfer(name)
            }
            throw e
        }
    }

    private suspend fun downloadInner(
        name: String,
        expectedSize: Int,
        onProgress: ((Int, Int) -> Unit)?,
    ): ByteArray {
        drain(xferQ)
        sendJson(DnoteProtocol.Cmd.XFER, """{"cmd":1,"name":"$name","offset":0}""")

        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val pkt = try {
                withTimeout(XFER_TIMEOUT_MS) { xferQ.receive() }
            } catch (e: TimeoutCancellationException) {
                throw DnoteException("Transfer timeout (${XFER_TIMEOUT_MS}ms without any chunks)")
            }
            if (pkt.size < 4 || (pkt[0].toInt() and 0xFF) != DnoteProtocol.PID) {
                throw DnoteException("Malformed transmission fragment")
            }
            val lc = pkt[2].toInt() and 0xFF
            val status = pkt[3].toInt() and 0xF0
            when (status) {
                DnoteProtocol.Xfer.NOT_FOUND -> throw DnoteException("File not found on device: $name")
                DnoteProtocol.Xfer.ERROR -> throw DnoteException("Transfer error on device: $name")
                DnoteProtocol.Xfer.CANCEL -> throw DnoteException("Transfer cancelled: $name")
            }
            if (lc > 5) {
                // OFFSET(4 BE) was originally skipped entirely. The firmware advances linearly, so it must equal
                // the locally received length; duplicate or skipped blocks would have been silently concatenated earlier, producing a corrupted file,
                // and “delete after transfer” would then immediately delete the original on the device.
                val offset = ((pkt[4].toInt() and 0xFF) shl 24) or
                    ((pkt[5].toInt() and 0xFF) shl 16) or
                    ((pkt[6].toInt() and 0xFF) shl 8) or
                    (pkt[7].toInt() and 0xFF)
                if (offset != buf.size()) {
                    throw DnoteTransferIncompleteException(
                        "$name: chunk offset $offset doesn't match the ${buf.size()} bytes received so far. " +
                            "Nothing was deleted from the device.",
                    )
                }
                val from = 8
                val to = minOf(3 + lc, pkt.size)
                if (to > from) {
                    buf.write(pkt, from, to - from)
                    onProgress?.invoke(buf.size(), expectedSize)
                }
            }
            if (status == DnoteProtocol.Xfer.EOF) break
        }
        // Receiving EOF does not mean the file is complete: compare with the size declared in the file list; a short read fails immediately.
        if (expectedSize > 0 && buf.size() != expectedSize) {
            throw DnoteTransferIncompleteException(
                "$name: got ${buf.size()} bytes but the file list said $expectedSize. " +
                    "Nothing was deleted from the device.",
            )
        }
        return buf.toByteArray()
    }

    // ── Notification routing (mirroring `_on_notify` / `dispatchNotification`)─────────

    private fun dispatch(data: ByteArray) {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != DnoteProtocol.PID) return
        when (data[1].toInt() and 0xFF) {
            DnoteProtocol.Cmd.XFER -> xferQ.trySend(data)
            DnoteProtocol.Cmd.STREAM -> streamQ.trySend(data)
            DnoteProtocol.Cmd.REC_STARTED -> handleRecStarted(data)
            DnoteProtocol.Cmd.REC_STOPPED -> ackRecStopped()
            else -> respQ.trySend(data)
        }
    }

    /** When the device stops recording via a physical button, it continuously sends 0x55; reply with an ack to stop it. */
    private fun ackRecStopped() {
        Log.i(TAG, "Received 0x55 (recording stopped), sending ack")
        if (!writeAck(PacketCodec.ackRecStopped())) return
        onRecordingStateChanged?.invoke(false, null)
    }

    /**
     * 0x54 — Device proactively reports “recording started”, payload is `{"name": "….opus"}`.
     *
     * The firmware docs don’t specify whether this needs an ack; **reply with the same as 0x55** (1 byte 0x01):
     * devices that don’t require an ack will ignore it. **The cost of not handling this** is that it falls into [respQ] as an expired response,
     * overwriting the reply of the next command, and the UI recording status won’t catch up until the next 0x80.
     */
    private fun handleRecStarted(data: ByteArray) {
        if (!writeAck(PacketCodec.ackRecStarted())) return
        val name: String? = if (data.size > PacketCodec.HEADER_LENGTH) {
            runCatching {
                val body = data.copyOfRange(PacketCodec.HEADER_LENGTH, data.size).toString(Charsets.UTF_8)
                JSONObject(body).optString("name").takeIf { it.isNotEmpty() }
            }.getOrNull()
        } else {
            null
        }
        Log.i(TAG, "got 0x54 (recording started) name=${name ?: "(no payload / unparsable)"}, sending ack")
        onRecordingStateChanged?.invoke(true, name)
    }

    /** Acks for proactive notifications all use the same write pattern (do not await writeDone; notification callbacks must not block). */
    private fun writeAck(pkt: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = rx ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, pkt, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = pkt
                g.writeCharacteristic(ch)
            }
        }
        return true
    }

    private fun <T> drain(ch: Channel<T>) {
        while (ch.tryReceive().isSuccess) { /* Discard */ }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                everConnected = true
                if (!connected.isCompleted) connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = DnoteException("Connection disconnected status=$statusCode")
                if (!connected.isCompleted) connected.completeExceptionally(e)
                if (!mtuReady.isCompleted) mtuReady.completeExceptionally(e)
                if (!servicesReady.isCompleted) servicesReady.completeExceptionally(e)
                if (!notifyReady.isCompleted) notifyReady.completeExceptionally(e)
                writeDone?.takeIf { !it.isCompleted }?.completeExceptionally(e)
                if (everConnected && !closedByUs) {
                    everConnected = false          // Report only once
                    Log.w(TAG, "Unexpected disconnection status=$statusCode")
                    onUnexpectedDisconnect?.invoke()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
            negotiatedMtu = mtu
            if (!mtuReady.isCompleted) mtuReady.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) servicesReady.complete(Unit)
            else servicesReady.completeExceptionally(DnoteException("Service discovery failed status=$statusCode"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            if (d.uuid == CCCD_UUID && !notifyReady.isCompleted) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) notifyReady.complete(Unit)
                else notifyReady.completeExceptionally(DnoteException("Failed to enable notifications, status=$statusCode"))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            val d = writeDone ?: return
            if (d.isCompleted) return
            if (statusCode == BluetoothGatt.GATT_SUCCESS) d.complete(Unit)
            else d.completeExceptionally(DnoteException("Write failed status=$statusCode"))
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == TX_UUID) dispatch(value)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == TX_UUID) ch.value?.let { dispatch(it) }
        }
    }
}
