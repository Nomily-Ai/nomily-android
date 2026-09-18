package com.nomily.app

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.audio.OPUS_FRAME_SIZE
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nomily.app.ble.DeviceScanner
import com.nomily.app.ble.DnoteBleClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nomily.app.R
import com.nomily.app.core.audio.oggDurationMs
import com.nomily.app.core.audio.oggDurationMsFromTail
import com.nomily.app.core.clips.ClipArtefact
import com.nomily.app.data.ConfigStore
import com.nomily.app.data.KeyVault
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State machine for the P0 main flow: scan → connect → list files → retrieve one (download/decrypt/wrap) → play.
 *
 * The steps in `:core` are covered by unit tests; this layer only orchestrates.
 * BLE timing and UI are **human‑judged** and must be observed on real devices.
 */
class NomiViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "NomiVM"
        private const val CONNECT_ATTEMPTS = 3

        /** When tapping the connect capsule for a known device, give up after not finding it for up to 10 seconds. */
        private const val KNOWN_CONNECT_TIMEOUT_MS = 10_000L

        /** 0x80 polling interval. 30 seconds — enough for battery/space to be reliable without wasting BLE. */
        private const val DEVICE_POLL_INTERVAL_MS = 30_000L

        /** Upper bound for waiting for the first frame of a real‑time session. */
        private const val LIVE_FIRST_FRAME_TIMEOUT_MS = 10_000L

        /** Interval for reading 0x56 during recording. 1 second — timing must keep up with the second ticks. */
        private const val REC_ELAPSED_POLL_MS = 1_000L

        /** Probe recording length for passphrase verification — 2.5 seconds: enough to generate an encrypted fragment, and BLE download is fast. */
        private const val PROBE_RECORD_MS = 2_500L
        /** How long to wait before reconnecting after an ASR disconnection. */
        private const val LIVE_RECONNECT_DELAY_MS = 1_500L

        /**
         * Fast‑transfer hotspot SSID — **a fixed value, not randomly generated**.
         *
         * The device always advertises this exact SSID; only the password differs per
         * transfer. The SSID cannot be chosen by the phone: a per‑transfer random name
         * is never discoverable, because the device is the access point.
         */
        private const val FAST_TRANSFER_SSID = "FastUpload"

        /** Each of the three fast‑transfer connection phases may wait up to 60 seconds. */
        private const val FAST_TRANSFER_AP_TIMEOUT_MS = 60_000L
        private const val FAST_TRANSFER_WIFI_JOIN_TIMEOUT_MS = 60_000L
        private const val FAST_TRANSFER_TCP_TIMEOUT_MS = 60_000L
        private const val TCP_SINGLE_CONNECT_TIMEOUT_MS = 5_000
        private const val TCP_CONNECT_RETRY_DELAY_MS = 2_000L

    }

    enum class PassphraseMode { SET, ROTATE }

    data class Ui(
        val scanning: Boolean = false,
        val discovered: List<DeviceScanner.Discovered> = emptyList(),
        val connectedName: String? = null,
        val deviceSn: String? = null,
        val deviceInfo: String? = null,
        /** Full response for 0x80 / 0x81 — the device panel needs to display each field; a summary string is insufficient. */
        val info: com.nomily.app.ble.DeviceInfo? = null,
        /** Negotiated MTU — required for the "Connected · MTU 512" line at the top of the device panel. */
        val mtu: Int? = null,
        /** Fast‑transfer panel (non‑empty = panel is open). */
        val fastTransfer: FastTransfer? = null,
        val switches: com.nomily.app.ble.SwitchInfo? = null,
        val encryptionOn: Boolean? = null,
        val bound: Boolean? = null,
        val files: List<DnoteBleClient.DeviceFile> = emptyList(),
        /**
         * Reading the device file list — only for the refresh button in the top bar.
         *
         * Cannot reuse [busy]: [busy] means "something is in progress" (pairing, connecting, starting/stopping recording…),
         * using it as the condition for the refresh spinner would also spin during pairing, which is not the intended behavior.
         */
        val filesLoading: Boolean = false,
        /**
         * Reason for failure when reading the file list.
         *
         * **Do not use the global [error]**: this error is shown as an empty state within the device section (warning triangle + one-line reason),
         * not as a dismissible card at the top. Placing it in the global error would make the UI look inconsistent,
         * and the card would overlay the list, requiring the user to dismiss it manually.
         */
        val filesError: String? = null,
        val busy: String? = null,
        val progress: Pair<Int, Int>? = null,
        /**
         * Name of the device file currently being downloaded. [progress] is a global value; row rendering must claim it,
         * otherwise as soon as any file is transferring, every row in the list will show the same progress bar and stop button
         * (the stop button is bound to the same callback, so clicking any row stops the same transfer).
         */
        val downloadingName: String? = null,
        /**
         * Deep‑link target: the sub‑page that the settings screen should automatically navigate to.
         * If only the top‑level settings tab is opened, the user would still have to locate that section (28t09us).
         * After the UI consumes it, [settingsTargetHandled] must be called to clear it,
         * otherwise returning from the sub‑page will immediately trigger another navigation.
         */
        val settingsTarget: SettingsTarget? = null,
        val error: String? = null,
        val needPassphrase: Boolean = false,
        val clips: List<LocalClip> = emptyList(),
        /**
         * Currently **loaded** audio file name (not the same as "currently playing") — the player remains alive when paused,
         * so this field still holds the file name; together with [playPaused] it represents the "paused" state.
         * It becomes null only after the player is released (stopped).
         */
        val playing: String? = null,
        /** Loaded but not playing: user paused, scrubbed the timeline, another app took focus, or playback finished and stopped at the start. */
        val playPaused: Boolean = false,
        val playPositionMs: Int = 0,
        val playDurationMs: Int = 0,
        /**
         * Recorded duration reported by the device during recording (milliseconds, 0x56's `recd`). **Not the app's stopwatch** ——
         * an app started via a physical key does not know the start point; only the device's timing is accurate.
         */
        val recordingElapsedMs: Int? = null,
        /** Automatic reconnection toggle (persisted in config.json as `auto_reconnect_enabled`). */
        val autoReconnect: Boolean = true,
        /** Whether the local Keystore already contains a key for the current device — shown on the settings page "Local Key". */
        val hasLocalKey: Boolean = false,
        /** Purpose of the passphrase input field: initial setup / rotation (rotation writes a new key to the device). */
        val passphraseMode: PassphraseMode = PassphraseMode.SET,
        /** Probe verification determines the passphrase is incorrect — shows “Passphrase mismatch” and prompts the user to try again. */
        val passphraseMismatch: Boolean = false,
        /**
         * This passphrase setting is **the one that pops up immediately after a successful binding**.
         * A note: encryption is enforced by the firmware,
         * the passphrase cannot be recovered, and recording won’t start until it’s set; users who enter from the settings page don’t need this part.
         */
        val passphraseFirstBind: Boolean = false,
        /**
         * If verification fails but the device has no recordings, temporarily store the passphrase the user just entered and ask whether to write it to the device (lossless). See the description in [submitPassphrase].
         */
        val passphraseAdoptOffer: String? = null,
        /** Automatically reconnecting — distinct from user‑initiated connection, with different UI wording. */
        val reconnecting: Boolean = false,
        /** Live transcription session. */
        val live: Live = Live(),
    )

    /**
     * State of a live transcription session.
     *
     * [partial] is a temporary result **that will be overwritten by the next one**, while [finals] are the finalized paragraphs from the server.
     */
    data class Live(
        val running: Boolean = false,
        /** Paused: ASR disconnected, BLE stream received but frames dropped (do not split BLE to avoid breaking the device’s stream). */
        val paused: Boolean = false,
        /** The segment name saved to the library for this session (available after stopping). */
        val savedClip: String? = null,
        /** "Device has a complete recording, replace it?" — appears only if the session was paused. */
        val pendingRepair: PendingRepair? = null,
        /** Replacement result banner: success / “The device’s version remains; retrieve it yourself.” */
        val repairState: RepairState = RepairState.IDLE,
        val provider: String = "",
        val partial: String = "",
        /** Temporary translation during live translation (the same sentence as [partial] in another language). */
        val partialTranslation: String = "",
        val finals: List<LiveLine> = emptyList(),
        /** Used for five‑state UI text: Connecting / Ready / Sending / Failed / Reconnecting. */
        val status: LiveStatus = LiveStatus.IDLE,
        val error: String? = null,
        /** Number of audio bytes received in this session — both “duration” and “size” readings in the control bar are derived from it. */
        val bytes: Int = 0,
        /**
         * When starting a session, we **cannot determine** whether the device is already recording. The UI shows a confirmation dialog accordingly:
         * Sending 0x51 blindly will cut off the segment the user is recording with the physical button.
         */
        val pendingStartConfirm: Boolean = false,
    )

    enum class LiveStatus { IDLE, CONNECTING, READY, STREAMING, FAILED, RECONNECTING }

    /** Fast transfer stages (excluding pollingAP, which is handled in awaitWifiAp). */
    enum class FtStage {
        STOPPING, STARTING_AP, POLLING_AP, JOINING, JOIN_FAILED, CONNECTING, DOWNLOADING, CLEANUP, DONE, FAILED
    }

    enum class FtFileStatus { PENDING, DOWNLOADING, DONE, FAILED }

    /** In fast transfer, each file is listed on one line: name / size / transferred / status. */
    data class FtFile(
        val name: String,
        val size: Int,
        val got: Int = 0,
        val status: FtFileStatus = FtFileStatus.PENDING,
        val error: String? = null,
    )

    /**
     * All states of the fast‑transfer panel. **Non‑null = panel is open** (press the button to push the sheet; the whole process stays within it).
     * Previously Android only injected the stage into a global `busy` string, resulting in a single progress bar on the UI, which made it impossible to see which step was stuck or the progress of each file.
     */
    data class FastTransfer(
        val stage: FtStage = FtStage.STOPPING,
        /** Current attempt number / total attempts for the hotspot to become active. */
        val pollAttempt: Int = 0,
        val pollTotal: Int = 0,
        val ssid: String? = null,
        val psk: String? = null,
        val error: String? = null,
        /**
         * During cleanup, we cannot verify whether the hotspot was turned off or not. The next fast transfer will likely fail to join, so we must surface this on the panel — a silent return would leave the panel appearing normal while the issue remains only in the logs.
         */
        val apStillOn: Boolean = false,
        val files: List<FtFile> = emptyList(),
    )

    /** Final state of gap replacement. */
    enum class RepairState { IDLE, SUCCEEDED, NEEDS_MANUAL_TRANSFER }

    /** A segment in the transcription stream: original text + (translation when live translation is enabled). */
    data class LiveLine(val text: String, val translation: String = "")

    /**
     * The “gap” left by a paused session: the phone’s recording lacks audio from the pause period, while the device’s version is complete — ask the user whether to replace it with the device’s recording.
     */
    data class PendingRepair(
        val deviceFileName: String,
        val clipName: String,
        val expectedSize: Int,
        /**
         * This session is **attached to the segment the device is already recording**, and the user never paused.
         * The same suggestion, rephrased — it would be wrong to tell someone who never paused that “your recording was interrupted in the middle.”
         */
        val wasAttached: Boolean = false,
    )

    /**
     * An entry in the library.
     *
     * `recordedAt` is parsed from the filename `yyyyMMddHHmmss` — **not** the file’s write time:
     * Download order does not equal recording order; sorting by write time makes the list appear chaotic (the issue is noted in `all-features-list.md` P3 “Device recording time sorting”).
     * `durationMs` reads the Ogg page‑end granule without decoding.
     */
    class LocalClip(
        /** Segment base name (`20260730155017`) — shared by all related artifacts. */
        val base: String,
        val audio: File?,
        val transcript: File?,
        /** `{base}.asr.json` — structured transcription required by the segment reader; if absent, fall back to the plain‑text [transcript]. */
        val transcriptJson: File?,
        val summary: File?,
        /** `{base}.translated.txt` — translation of the transcription (saved separately, does not overwrite the original). */
        val transcriptTranslation: File?,
        /** `{base}.summary.translated.md` — translated summary. */
        val summaryTranslation: File?,
        val durationMs: Int?,
        val recordedAt: Date?,
        /** Custom/LLM short title stored in `{base}.title`; if missing, use the recording time as the title. */
        val title: String?,
    ) {
        val name: String get() = audio?.name ?: base
        val hasAudio: Boolean get() = audio != null
        val sizeBytes: Long get() = audio?.length() ?: 0L
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val scanner = DeviceScanner(app)
    private val pipeline = ClipPipeline(app)
    /** Deep‑link target for the settings page. */
    enum class SettingsTarget { ASR, LLM }

    private val configStore = ConfigStore(app, viewModelScope)
    private val templateStore = com.nomily.app.llm.TemplateStore(app)
    private val languageService = com.nomily.app.llm.LanguageService(app)

    /** Summary templates (editable in the settings page; the summary panel displays them by category). */
    val templates: StateFlow<List<com.nomily.app.core.llm.SummarizeTemplate>> get() = templateStore.templates
    private val keyVault = KeyVault(
        app,
        app.getSharedPreferences("dnote-keys", android.content.Context.MODE_PRIVATE),
    )
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var clipsJob: Job? = null
    private var fetchJob: Job? = null
    private var fastTransferRetry: CompletableDeferred<Unit>? = null
    private var recordJob: Job? = null
    private var pollJob: Job? = null
    /**
     * Retrieve localized strings.
     * Here we fetch values by key only — previously these locations contained English literals, which would appear on the Chinese UI.
     */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** "Header: Reason" (header is localized, reason comes from the exception). */
    private fun failed(@StringRes titleId: Int, e: Throwable): String =
        "${str(titleId)}: ${e.message}"

    private var elapsedJob: Job? = null
    private val transcribeJobs = mutableMapOf<String, Job>()
    private var client: DnoteBleClient? = null
    /** Key of the currently connected device in `config.devices` (= BLE address). Unbinding and removal should delete records using this key. */
    private var connectedAddress: String? = null
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    /**
     * Seeking is asynchronous: between the return of `seekTo` and the `OnSeekCompleteListener`, `currentPosition` still reports the **old position**, causing the polling coroutine to snap the progress bar back.
     */
    private var seeking = false
    /** Whether audio is playing before dragging the progress bar — after release, only the playback that was active resumes. */
    private var resumeAfterScrub = false
    private var audioFocusRequest: Any? = null
    private val audioManager: android.media.AudioManager
        get() = getApplication<Application>()
            .getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * Another app takes audio focus (incoming call, another player) → pause and sync the UI to “paused”.
     * **Regaining focus does not automatically resume playback** — the user intentionally switched away.
     */
    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            // CAN_DUCK not handled: the system will duck the volume itself, and slightly lowering speech is better than pausing the entire segment.
            else -> Unit
        }
    }
    private var adapterStateReceiver: BroadcastReceiver? = null

    private fun set(f: (Ui) -> Ui) { _ui.value = f(_ui.value) }

    /**
     * Runtime permissions an action needs before it can run. Only an Activity can ask, so the gate is
     * published here, `MainActivity` launches the request and answers through [onPermissionResult].
     */
    data class PermissionRequest(val permissions: List<String>, val action: Action) {
        enum class Action { LIVE, FAST_TRANSFER }
    }

    private val _permissionRequest = MutableStateFlow<PermissionRequest?>(null)
    val permissionRequest: StateFlow<PermissionRequest?> = _permissionRequest.asStateFlow()

    /** Asked once per ViewModel: a refused notification never blocks a session, so don't nag on every start. */
    private var liveNotificationAsked = false
    private var pendingLiveAssumeIdle = false
    private var pendingFastTransferFiles: List<DnoteBleClient.DeviceFile>? = null

    private fun notGranted(permissions: List<String>): List<String> = permissions.filter {
        androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), it) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Called by `MainActivity` once the system dialog is answered, granted or not. */
    fun onPermissionResult(request: PermissionRequest) {
        _permissionRequest.value = null
        when (request.action) {
            PermissionRequest.Action.LIVE -> startLive(pendingLiveAssumeIdle)
            PermissionRequest.Action.FAST_TRANSFER -> {
                val files = pendingFastTransferFiles ?: return
                pendingFastTransferFiles = null
                if (notGranted(com.nomily.app.wifi.fastTransferPermissions()).isEmpty()) {
                    fastTransfer(files)
                } else {
                    set { it.copy(error = str(R.string.fast_transfer_permission_denied)) }
                }
            }
        }
    }

    init {
        refreshClips()
        set { it.copy(autoReconnect = configStore.config.value.autoReconnectEnabled) }
        maybeAutoReconnect()
        watchRecordingElapsed()
        registerAdapterStateReceiver()
    }

    // ── Phone Bluetooth Switch ──────────────────────────────────────────────────

    /**
     * Monitor the phone’s Bluetooth adapter switch.
     *
     * Previously we never listened to this event, so after turning Bluetooth off and on during a transfer: the GATT handle was dead, but `client` remained, the UI still showed connected, and subsequent downloads hit the dead handle — this is why “retry after re‑enabling Bluetooth still fails”.
     *
     * When turning off, treat it as a disconnection and clean up (**do not** automatically reconnect: with the adapter off, reconnection will inevitably fail and be abandoned immediately, and nothing will trigger it later); only attempt reconnection when turning on.
     *
     * Make it idempotent: whether `onConnectionStateChange` is also called when the adapter is turned off varies across implementations; when both paths are taken, the second time should be a no‑op.
     */
    private fun registerAdapterStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF ->
                        onAdapterUnavailable()
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth is on, attempting auto-reconnect")
                        maybeAutoReconnect()
                    }
                }
            }
        }
        runCatching {
            getApplication<Application>().registerReceiver(
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
            adapterStateReceiver = receiver
        }.onFailure { Log.w(TAG, "Failed to register Bluetooth state broadcast: ${it.message}") }
    }

    private fun onAdapterUnavailable() {
        reconnectJob?.cancel(); reconnectJob = null
        pollJob?.cancel(); pollJob = null
        val active = client ?: return
        Log.w(TAG, "Bluetooth is off, discarding connection")
        active.onUnexpectedDisconnect = null      // This disconnection is handled here; do not also go through onDropped
        active.onRecordingStateChanged = null
        runCatching { active.close() }
        client = null
        connectedAddress = null
        set { Ui(clips = it.clips, autoReconnect = it.autoReconnect) }
    }

    /**
     * During recording, read 0x56 once per second and feed the device‑reported recorded duration to the UI.
     *
     * Attach to the **edge** of the `rec` flag rather than starting/stopping via the command: recordings started with the physical button generate 0x54 notifications, which bypass [toggleRecording]; if only commands were considered, physical‑button recordings would not be timed.
     */
    private fun watchRecordingElapsed() {
        viewModelScope.launch {
            _ui.map { it.info?.isRecording == true }
                .distinctUntilChanged()
                .collect { recording ->
                    elapsedJob?.cancel()
                    if (!recording) {
                        set { it.copy(recordingElapsedMs = null) }
                        return@collect
                    }
                    elapsedJob = viewModelScope.launch {
                        while (true) {
                            val c = client
                            // Do not inject commands during transfer — same policy as 0x80 polling; a slightly slower timer is better than disrupting the transfer.
                            if (c != null && _ui.value.progress == null) {
                                runCatching { withContext(Dispatchers.IO) { c.getRecordingStatus() } }
                                    .onSuccess { j ->
                                        if (j.has("recd")) {
                                            set { it.copy(recordingElapsedMs = j.optInt("recd")) }
                                        }
                                    }
                                    .onFailure { Log.w(TAG, "Read 0x56 failed (ignored): ${it.message}") }
                            }
                            kotlinx.coroutines.delay(REC_ELAPSED_POLL_MS)
                        }
                    }
                }
        }
    }

    // ── Automatic Reconnect (P0 #2)───────────────────────────────────────────────

    /**
     * Identify by `device_sid` instead of MAC address.
     *
     * MAC addresses can be randomized on some Android models and will change when switching phones; `device_sid` comes from the manufacturer’s broadcast data and is **the card’s own identity**.
     */
    fun maybeAutoReconnect() {
        if (!configStore.config.value.autoReconnectEnabled) return
        if (client != null) return
        val (_, rec) = configStore.config.value.mostRecentDevice() ?: return
        val wantSid = rec.deviceSid ?: run {
            // Old records lack SID (`device_sid` was added later) — if unrecognizable, do not connect arbitrarily
            Log.i(TAG, "Recent device ${rec.name} has no device_sid, skipping auto-reconnect")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            set { it.copy(reconnecting = true, error = null) }
            try {
                // Connect as soon as the target is scanned; if not found, keep waiting (user manual action will cancel this job)
                val target = scanner.scan()
                    .mapNotNull { list -> list.firstOrNull { it.adv.deviceSidHex == wantSid } }
                    .first()
                Log.i(TAG, "Auto-reconnect hit sid=$wantSid (${target.name})")
                set { it.copy(reconnecting = false) }
                connect(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Auto-reconnect abandoned: ${e.message}")
                set { it.copy(reconnecting = false) }
            }
        }
    }

    /**
     * Connect to a **known device** (previously stored in the configuration).
     *
     * When not connected, tapping the device name on the connection capsule uses this: directly reconnect to the most recent device without showing the scan page.
     *
     * Identify by `device_sid` instead of MAC (same rationale as [maybeAutoReconnect]); if old records lack SID, fall back to address matching — manual taps are user‑initiated, so it’s worth a few extra attempts compared to auto‑reconnect.
     * If not found, give up after 10 seconds, the UI returns to the disconnected gray state, and the user can tap again to retry; only report an error if scanning itself cannot start (e.g., Bluetooth is off).
     */
    fun connectKnown(address: String) {
        if (client != null && connectedAddress == address) return
        val rec = configStore.config.value.devices[address] ?: return

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            set { it.copy(reconnecting = true, error = null) }
            val target = try {
                kotlinx.coroutines.withTimeoutOrNull(KNOWN_CONNECT_TIMEOUT_MS) {
                    scanner.scan()
                        .mapNotNull { list ->
                            list.firstOrNull { d ->
                                rec.deviceSid?.let { d.adv.deviceSidHex == it } ?: (d.address == address)
                            }
                        }
                        .first()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // **Must catch**: when the phone’s Bluetooth is off, `scanner.scan()` throws “Bluetooth not enabled” directly.
                // Throwing into viewModelScope causes a crash — tapping the capsule makes the app disappear (observed on a real device).
                // The neighboring [maybeAutoReconnect] already catches this; do the same here and display the reason.
                Log.w(TAG, "Failed to connect capsule to ${rec.name}: ${e.message}")
                set { it.copy(reconnecting = false, error = failed(R.string.scanner_connection_failed, e)) }
                return@launch
            }
            set { it.copy(reconnecting = false) }
            if (target == null) {
                Log.i(TAG, "Connecting capsule to ${rec.name}: no response within ${KNOWN_CONNECT_TIMEOUT_MS}ms, giving up")
                return@launch
            }
            connect(target)
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        configStore.setAutoReconnect(enabled)
        set { it.copy(autoReconnect = enabled) }
        if (enabled) maybeAutoReconnect() else { reconnectJob?.cancel(); set { it.copy(reconnecting = false) } }
    }

    // ── Configuration (settings page read/write)────────────────────────────────────────────
    //
    // The settings page directly modifies fields in config.json (provider key, switches, thresholds, …). ConfigStore writes them to disk with a 250 ms debounce, and unknown keys are preserved as‑is (see AppConfig).

    /** The settings page uses this as its data source. */
    val config: StateFlow<com.nomily.app.core.config.AppConfig> get() = configStore.config

    /** General configuration‑change entry point. All settings go through it — maintains a single debounce and preserves unknown keys. */
    fun updateConfig(transform: (com.nomily.app.core.config.AppConfig) -> com.nomily.app.core.config.AppConfig) {
        configStore.update(transform)
        // autoReconnect also has a UI mirror (top switch) to stay in sync
        set { it.copy(autoReconnect = configStore.config.value.autoReconnectEnabled) }
    }

    /**
     * Change the UI language. `null` = follow system.
     *
     * **Must write to disk before invoking callbacks**: `AppLocale.wrap` reads `config.json` synchronously in Activity’s `attachBaseContext`, while [updateConfig] uses a 250 ms debounce — calling `recreate()` directly would read the old value that hasn’t been written yet, making the UI appear “no response”; a second tap then takes effect (the previous write only lands at that point).
     */
    fun setAppLanguage(tag: String?, onSaved: () -> Unit) {
        configStore.update { it.copy(appLanguage = tag) }
        viewModelScope.launch {
            configStore.saveNow()
            onSaved()
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────

    fun startScan() {
        reconnectJob?.cancel(); reconnectJob = null   // The user initiated the action; do not let the background reconnect automatically
        scanJob?.cancel()
        set { Ui(scanning = true, clips = it.clips) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { list -> set { it.copy(discovered = list) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopScan()/connect() intentional cancellation — normal path, should not be logged as an error
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scan error", e)
                set { it.copy(scanning = false, error = e.message) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        set { it.copy(scanning = false) }
    }

    // ── Connection ──────────────────────────────────────────────────────────

    fun connect(d: DeviceScanner.Discovered) {
        stopScan()
        viewModelScope.launch {
            // The spinner should start **before the first await**: the subsequent getDeviceInfo must be queued after the transmission command lock, and this waiting period is the only feedback the user sees.
            set { it.copy(busy = str(R.string.fast_transfer_stage_connecting), error = null) }
            client?.let { current ->
                val fresh = runCatching {
                    withContext(Dispatchers.IO) { com.nomily.app.ble.DeviceInfo(current.getDeviceInfo()) }
                }.getOrNull()
                if ((fresh ?: _ui.value.info)?.isRecording == true) {
                    set { it.copy(busy = null, error = str(R.string.device_unavailable_while_recording)) }
                    return@launch
                }
            }
            // Before switching devices, **cleanly tear down the old link**. The card firmware accepts only one central: if the old GATT remains open, the new device can’t connect (users reported “after binding a second device, I can’t switch, even after rebooting the device”).
// Additionally, the old client’s onUnexpectedDisconnect remains registered — if it later disconnects, it will call onDropped(), clearing the UI state of the **new connection**, making it appear as if the new device disconnected on its own.
// Even when reconnecting to the same device, do the teardown: connectWithRetry creates a new DnoteBleClient, and leaving the old GATT open results in two links to the same card.
            client?.let { old ->
                Log.i(TAG, "Disconnect existing connection before connecting ($connectedAddress → ${d.address})")
                reconnectJob?.cancel(); reconnectJob = null
                pollJob?.cancel(); pollJob = null
                old.onUnexpectedDisconnect = null
                old.onRecordingStateChanged = null
                withContext(Dispatchers.IO) { old.close() }
                client = null
                connectedAddress = null
            }
            try {
                val c = withContext(Dispatchers.IO) { connectWithRetry(d) }
                client = c
                c.onUnexpectedDisconnect = { onDropped() }
                c.onRecordingStateChanged = { recording, name -> onDeviceRecordingChanged(recording, name) }
                // “Initialization incomplete”: the firmware forces encryption, but the host lacks the derived passphrase key.
// The criterion is based on the actual state (encryption flag + whether the Keystore contains a key for this SN). Do not store a separate “completed” flag — such a flag could diverge from the real state.
                c.passphraseSetupIncomplete = { _ui.value.encryptionOn == true && !_ui.value.hasLocalKey }
                val info = c.getDeviceInfo()
                val sn = info.optString("sn")

                // Remember this device (each connection updates last_connected).
                // Identify by device_sid, not just MAC — after changing phones or MAC randomization, the same card can still be recognized.
                configStore.rememberDevice(d.address, d.name, d.adv.deviceSidHex)
                connectedAddress = d.address

                val bond = c.queryBondState()
                val justBound = !bond.bound
                if (!bond.bound) {
                    // v1.47+: without a bond, the device refuses recording. Here we only bond when needed, without altering other states.
                    set { it.copy(busy = str(R.string.pairing_pairing_now)) }
                    // **Reuse the stored bond_id.** A fresh bond_id per reconnection looks like a “new phone” each time and overwrites the device’s bond record. Use the existing one if stored; generate one only when none exists.
                    val stored = configStore.config.value.devices[d.address]?.bondId
                    val bondId = stored?.let { hex -> runCatching { hexToBytes(hex) }.getOrNull() }
                        ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                    c.bindDevice(bondId)
                    configStore.setBondId(d.address, d.name, bondId.toHexLower())
                }

                // Retrieve the previously derived key from the Keystore — this is the user‑visible half of P0 #8:
                // After restarting the app, no need to wait 1.6 s for Argon2 or re‑enter the passphrase.
                if (pipeline.cachedKeyFor(sn) == null) {
                    keyVault.load(sn)?.let { k ->
                        pipeline.seedCachedKey(sn, k)
                        Log.i(TAG, "Retrieved key for SN=$sn from Keystore")
                    }
                }
                val hasKey = pipeline.cachedKeyFor(sn) != null

                val enc = runCatching { c.getEncryptionState() }.getOrNull()
                set {
                    it.copy(
                        connectedName = d.name,
                        deviceSn = sn,
                        mtu = c.negotiatedMtu,
                        deviceInfo = "v${info.optString("v")} · ${info.optInt("bat")}% · " +
                            "${info.optInt("df")}/${info.optInt("total_df")} free",
                        info = com.nomily.app.ble.DeviceInfo(info),
                        bound = true,
                        encryptionOn = enc,
                        hasLocalKey = hasKey,
                        busy = null,
                    )
                }
                // Firmware v1.50 automatically enables encryption during bonding, so the first thing after bonding is to direct the user to set a passphrase:
// We cannot first land on the recording‑enabled home page, because anything recorded during that period would never be decryptable on this phone (rv7my090). The user may dismiss it and set it later — the recording entry remains disabled until the passphrase is set, and the banner at the top of the library provides a shortcut back to this step.
                if (justBound && enc == true && !hasKey) {
                    set { it.copy(needPassphrase = true, passphraseMode = PassphraseMode.SET, passphraseFirstBind = true) }
                }
                // Synchronize time upon connection. Failures should not block the main flow —
                // Inaccurate time only affects filenames, not usability.
                runCatching { withContext(Dispatchers.IO) { c.syncTime() } }
                    .onFailure { Log.w(TAG, "Time sync failed (does not affect usage): ${it.message}") }
                refreshSwitches()
                loadFiles()
                startDeviceInfoPoll()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                set { it.copy(busy = null, error = failed(R.string.scanner_connection_failed, e)) }
            }
        }
    }

    /** GATT error 133 is common on first connection on Android — retry instead of failing immediately. */
    private suspend fun connectWithRetry(d: DeviceScanner.Discovered): DnoteBleClient {
        var last: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            val c = DnoteBleClient(getApplication(), d.device)
            try {
                c.connect()
                return c
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "Connection failed ($attempt/$CONNECT_ATTEMPTS): ${e.message}")
                c.close()
            }
        }
        throw last ?: IllegalStateException("Unable to connect")
    }

    /**
     * User‑initiated disconnection. **Also cancel automatic reconnection** — otherwise, clicking disconnect would be immediately followed by an auto‑reconnect, leading the user to think the button is broken. Automatic reconnection only handles “unexpected disconnections”.
     */
    fun disconnect() {
        val c = client ?: return
        viewModelScope.launch {
            val fresh = runCatching {
                withContext(Dispatchers.IO) { com.nomily.app.ble.DeviceInfo(c.getDeviceInfo()) }
            }.getOrNull()
            if ((fresh ?: _ui.value.info)?.isRecording == true) {
                set { it.copy(error = str(R.string.device_unavailable_while_recording)) }
                return@launch
            }
            disconnectNow()
        }
    }

    /** Internal cleanup after shutdown/unbind and after the recording guard passed. */
    private fun disconnectNow() {
        reconnectJob?.cancel(); reconnectJob = null
        pollJob?.cancel(); pollJob = null
        client?.onUnexpectedDisconnect = null
        client?.onRecordingStateChanged = null
        client?.close(); client = null
        connectedAddress = null
        set { Ui(clips = it.clips, autoReconnect = it.autoReconnect) }
    }

    /** Unexpected disconnection: clear the connection state, then use the switch to decide whether to auto‑recover. */
    private fun onDropped() {
        Log.w(TAG, "Device disconnected")
        pollJob?.cancel(); pollJob = null
        client = null
        set {
            Ui(
                clips = it.clips,
                autoReconnect = it.autoReconnect,
                error = if (it.autoReconnect) null else "Device disconnected",
            )
        }
        maybeAutoReconnect()
    }

    /**
     * Device‑initiated recording start/stop (physical button) — 0x54 / 0x55.
     *
     * Only update the local `rec` flag, without re‑reading 0x80: the notification arrives on the GATT callback thread, and sending a command there would contend for the [DnoteBleClient] command lock with ongoing transfers. Let the 30‑second poll handle slow drift.
     * After stopping recording, **refresh the file list** — otherwise the newly recorded entry won’t appear until the user manually taps Refresh.
     */
    private fun onDeviceRecordingChanged(recording: Boolean, name: String?) {
        Log.i(TAG, "Device recording state changed: recording=$recording name=${name ?: "-"}")
        set { ui ->
            val raw = ui.info?.raw ?: org.json.JSONObject()
            raw.put("rec", if (recording) 1 else 0)
            if (name != null) raw.put("name", name)
            ui.copy(info = com.nomily.app.ble.DeviceInfo(raw))
        }
        // Clear list on start, refresh list on stop — both edges must be handled. Recordings started via the physical button follow this path.
        if (recording) set { it.copy(files = emptyList()) } else loadFiles()
    }

    /**
     * Read 0x80 every 30 seconds.
     *
     * Recording start/stop is now event‑driven (0x54/0x55); this loop only covers slow drift:
     * battery, remaining space, and provides a fallback state in case a notification is missed.
     * Skip during transfer — transfer has no resume, inserting a command would be too costly.
     */
    private fun startDeviceInfoPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(DEVICE_POLL_INTERVAL_MS)
                val c = client ?: continue
                if (_ui.value.progress != null || _ui.value.busy != null) continue
                runCatching { withContext(Dispatchers.IO) { c.getDeviceInfo() } }
                    .onSuccess { j -> set { it.copy(info = com.nomily.app.ble.DeviceInfo(j)) } }
                    .onFailure { Log.w(TAG, "Polling 0x80 failed (ignored): ${it.message}") }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHexLower(): String =
        joinToString("") { "%02x".format(it) }

    // ── Device control panel (P1)─────────────────────────────────────────────
    //
    // Each action = send a command → **read 0x81 once**.
    // Reading back is critical: firmware may reject or clamp (e.g., gain out of range); if we only trust local optimistic updates,
    // the UI would show a value the device never accepted.

    /** Re-read 0x81. Failure to read is not an error — the panel just lacks a piece, which is better than a popup. */
    private fun refreshSwitches() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { c.getSwitchInfo() } }
                // Same as loadFiles: when the device is switched, discard it; don't copy the previous device's switch state onto the new panel.
                .onSuccess { j -> if (c === client) set { it.copy(switches = com.nomily.app.ble.SwitchInfo(j)) } }
                .onFailure { Log.w(TAG, "Failed to read switch state: ${it.message}") }
        }
    }

    /** Re-read 0x80 + 0x81 — the panel's “refresh”. */
    fun refreshDeviceState() {
        val c = client ?: return
        viewModelScope.launch {
            runCatching {
                val j = withContext(Dispatchers.IO) { c.getDeviceInfo() }
                set { it.copy(info = com.nomily.app.ble.DeviceInfo(j)) }
            }.onFailure { Log.w(TAG, "Failed to read device info: ${it.message}") }
            refreshSwitches()
        }
    }

    /**
     * Common skeleton for panel actions: set busy → send command → read back 0x81 → clear busy; failures go to the error branch.
     * `after` is for actions that need to “do and then disconnect” (shutdown / unbond).
     */
    private fun deviceOp(
        @StringRes labelId: Int,
        after: (() -> Unit)? = null,
        op: suspend (DnoteBleClient) -> Unit,
    ) {
        val c = client ?: return
        viewModelScope.launch {
            set { it.copy(busy = str(labelId), error = null) }
            try {
                withContext(Dispatchers.IO) { op(c) }
                set { it.copy(busy = null) }
                if (after != null) after() else refreshSwitches()
            } catch (e: Exception) {
                Log.e(TAG, "Device command failed: ${str(labelId)}", e)
                set { it.copy(busy = null, error = failed(R.string.device_couldnt_update, e)) }
            }
        }
    }

    fun setSwitch(name: String, on: Boolean) = deviceOp(R.string.encryption_progress_working) { it.setSwitch(name, on) }

    fun setMicGain(level: Int) = deviceOp(R.string.encryption_progress_working) { it.setMicGain(level) }

    fun setNrLevel(level: Int) = deviceOp(R.string.encryption_progress_working) { it.setNrLevel(level) }

    fun setIdleOff(seconds: Int) = deviceOp(R.string.device_saving_idle_off) { it.setIdleOff(seconds) }

    fun renameDevice(name: String) = deviceOp(R.string.device_saving_name) { c ->
        c.setBluetoothName(name)
        // The name is in 0x80, not in 0x81 — after changing it you must re-read 0x80, otherwise the panel will still show the old name.
        val j = c.getDeviceInfo()
        set { it.copy(info = com.nomily.app.ble.DeviceInfo(j), connectedName = name) }
    }

    /** Format: erase all recordings on the device (locally retrieved ones are unaffected). */
    fun formatDisk() = deviceOp(R.string.device_formatting) { c ->
        c.formatDisk()
        set { it.copy(files = emptyList()) }
    }

    /** Factory reset: only reset switches and Bluetooth name; recordings are retained. */
    fun factoryReset() = deviceOp(R.string.device_resetting) { it.factoryReset() }

    /** Shutdown: the device disconnects immediately, so after completion capture the connection state directly instead of waiting for a timeout. */
    fun shutdown() = deviceOp(R.string.device_shutting_down, after = { disconnectNow() }) { it.shutdown() }

    /**
     * Pairing (bonding).
     *
     * If the device wasn't bonded during connection (device rejected, or user previously unbonded) there is no other way to recover,
     * the “unpaired” banner on the panel can be tapped here to retry. **Reuse the stored bond_id**, for the same reason as `connect()`:
     * changing the id each time makes the device think it's a “new phone”, and the device‑side bond record gets repeatedly overwritten.
     */
    fun pairDevice() = deviceOp(R.string.pairing_pairing_now) { c ->
        val addr = connectedAddress
        val stored = addr?.let { configStore.config.value.devices[it]?.bondId }
        val bondId = stored?.let { hex -> runCatching { hexToBytes(hex) }.getOrNull() }
            ?: ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        c.bindDevice(bondId)
        if (addr != null) configStore.setBondId(addr, _ui.value.connectedName ?: "", bondId.toHexLower())
        set { it.copy(bound = true) }
    }

    /** Unbond: the device returns to “unbonded”; after v1.47 it will reject recording until re‑bonded. */
    fun unbindDevice() = deviceOp(R.string.pairing_unpairing, after = { disconnectNow() }) { it.unbindDevice() }

    /**
     * Unbond and remove device — four steps, order must not change:
     *
     * 1. Turn off device‑side encryption (**best‑effort**: if the device refuses to drop the key it shouldn't block the whole removal), then clear the locally stored key;
     * 2. **Format before unbonding**: erase recordings on the device so the card doesn't pass a previous user's audio to the next user.
     *    If this step fails, **abort** (no try/catch) — other states remain unchanged, user can retry;
     * 3. Unbond;
     * 4. Remove the device from the bonded list and then disconnect — keeping a connection to a removed device is meaningless.
     */
    fun unbindAndRemove() = deviceOp(R.string.device_unbinding_and_removing, after = { disconnectNow() }) { c ->
        val sn = _ui.value.deviceSn
        if (!sn.isNullOrEmpty()) {
            pipeline.cachedKeyFor(sn)?.let { key ->
                runCatching { c.setEncryption(on = false, key = key) }
                    .onFailure { Log.w(TAG, "Failed to disable encryption (continuing removal): ${it.message}") }
            }
            runCatching { keyVault.clear(sn) }
                .onFailure { Log.w(TAG, "Failed to clear key from Keystore: ${it.message}") }
        }
        c.formatDisk()
        c.unbindDevice()
        connectedAddress?.let { addr ->
            configStore.update { cfg -> cfg.copy(devices = cfg.devices - addr) }
        }
        set { it.copy(files = emptyList()) }
    }

    // ── Files ──────────────────────────────────────────────────────────

    fun loadFiles() {
        val c = client ?: return
        viewModelScope.launch {
            set {
                it.copy(
                    busy = str(R.string.device_files_reading_file_list),
                    filesLoading = true,
                    filesError = null,
                    error = null,
                )
            }
            try {
                val fs = withContext(Dispatchers.IO) { c.getFileList() }
                // The user may have switched to another device in the meantime — this list belongs to the **previous device** and must not be applied to the new panel.
                if (c !== client) return@launch
                set {
                    it.copy(
                        files = fs.sortedByDescending { f -> f.name },
                        busy = null,
                        filesLoading = false,
                        filesError = null,
                    )
                }
            } catch (e: Exception) {
                // Likewise: when switching devices the old connection is torn down, so this will inevitably throw “not connected”.
                // If not caught, the user who just switched would see “Unable to read device: not connected”,
                // even though the new device is actually connected (real device reproduced, 2026-08-12).
                if (c !== client) return@launch
                set {
                    it.copy(
                        busy = null,
                        filesLoading = false,
                        filesError = e.message ?: str(R.string.device_files_couldnt_read_device),
                    )
                }
            }
        }
    }

    /**
     * Recording start/stop (CMD 0x51 / 0x50).
     *
     * The state **is based on the `rec` bit reported by device 0x80**,
     * so after sending the command we read back 0x80 once, rather than keeping a local boolean that could diverge —
     * the device also has a physical button, so the local copy will eventually become inconsistent.
     */
    fun toggleRecording() {
        val c = client ?: return
        if (recordJob?.isActive == true) return
        val recording = _ui.value.info?.isRecording == true
        recordJob = viewModelScope.launch {
            set { it.copy(busy = if (recording) "Stopping recording…" else "Recording…", error = null) }
            try {
                val info = withContext(Dispatchers.IO) {
                    if (recording) c.stopRecording() else c.startRecording(realTime = false)
                    kotlinx.coroutines.delay(1500)      // Time for the device to persist to storage / change state
                    c.getDeviceInfo()
                }
                // When starting recording **clear the file list**:
                // During recording the device rejects 0x90; the screen would show an soon‑to‑expire old list,
                // and the user could still tap to download it — clear it so the “device recording” overlay takes over.
                set {
                    it.copy(
                        info = com.nomily.app.ble.DeviceInfo(info),
                        busy = null,
                        files = if (recording) it.files else emptyList(),
                    )
                }
                if (recording) loadFiles()
            } catch (e: Exception) {
                Log.e(TAG, "Recording start/stop failed", e)
                set { it.copy(busy = null, error = failed(R.string.device_files_operation_failed, e)) }
            }
        }
    }

    // ── Transcription (P1: Azure batch transcription / auto‑transcribe after download / provider chain)──────────

    private val transcription by lazy {
        com.nomily.app.asr.TranscriptionService(File(getApplication<Application>().filesDir, "clips"))
    }

    /**
     * Transcribe a local segment.
     *
     * @param enforceMinDuration Minimum duration gate for automatic transcription; **does not block when the user manually taps “Transcribe”** —
     *   The user explicitly wants this segment, so it should not be blocked by a config option.
     */
    fun transcribe(clip: LocalClip, enforceMinDuration: Boolean = false, locales: List<String>? = null) {
        val audio = clip.audio ?: return
        if (transcribeJobs.containsKey(clip.base)) return
        transcribeJobs[clip.base] = viewModelScope.launch {
            set { it.copy(busy = str(R.string.device_files_transcribing), error = null) }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    transcription.transcribe(
                        audio,
                        configStore.config.value,
                        enforceMinDuration = enforceMinDuration,
                        locales = locales,
                    )
                }
                Log.i(TAG, "Transcription completed ${clip.base}: ${outcome.result.segments.size} segments, provider=${outcome.result.provider}")
                // Downgrade notice is not an error, but the user should know a non‑preferred provider is being used
                val warn = outcome.warnings.joinToString("; ").ifEmpty { null }
                set { it.copy(busy = null, error = warn) }
                refreshClips()
            } catch (e: com.nomily.app.asr.TranscriptionService.TooShortException) {
                Log.i(TAG, "Skipping transcription (too short): ${e.message}")
                set { it.copy(busy = null, error = e.message) }
            } catch (e: com.nomily.app.asr.AsrException) {
                Log.e(TAG, "Transcription failed", e)
                // Entries with messageRes provide a user‑visible reason using localized text; others continue to use “title: reason”
                val msg = e.messageRes?.let { str(it) } ?: failed(R.string.device_files_operation_failed, e)
                set { it.copy(busy = null, error = msg) }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                set { it.copy(busy = null, error = failed(R.string.device_files_operation_failed, e)) }
            } finally {
                transcribeJobs.remove(clip.base)
            }
        }
    }

    // ── Wi‑Fi Fast Transfer (P1)─────────────────────────────────────────────
    //
    // Fast‑transfer flow:
    //   0x88 enable AP → poll the wifiap bit of 0x80 (RF takes 20–45 s, **ack does not mean ready**)
    //   → join hotspot (Android uses WifiNetworkSpecifier and must bindProcessToNetwork)
    //   → TCP pull → write to storage using the same decryption pipeline as BLE **identical** (ClipPipeline.ingestRaw)
    //   → quit + release network + 0x88 disable AP
    //
    // ⚠️ Cleanup must complete: leaving the AP on drains power, and not unbinding the network forces all subsequent app requests onto a network without internet.
    // Therefore teardown is placed in finally, each step wrapped in runCatching; a failure in one step does not block the next.

    fun fastTransfer(files: List<DnoteBleClient.DeviceFile>) {
        val c = client ?: return
        val sn = _ui.value.deviceSn ?: return
        if (files.isEmpty()) return
        val key = pipeline.cachedKeyFor(sn)
        if (_ui.value.encryptionOn == true && key == null) {
            set { it.copy(needPassphrase = true) }
            return
        }
        if (fetchJob?.isActive == true) return
        val missingPermissions = notGranted(com.nomily.app.wifi.fastTransferPermissions())
        if (missingPermissions.isNotEmpty()) {
            pendingFastTransferFiles = files
            _permissionRequest.value =
                PermissionRequest(missingPermissions, PermissionRequest.Action.FAST_TRANSFER)
            return
        }
        val (ssid, psk) = wifiCredentials()
        // WPA2 passphrase must be 8–63 characters. The two lines on the settings page are user‑editable; shortening them causes the system to reject the join (`invalid WPA/WPA2 Passphrase.`), and the panel only shows “connection failed, tap to retry” — retrying any number of times won’t help. Explain this before starting the hotspot.
        if (psk.length !in 8..63) {
            set { it.copy(error = str(R.string.fast_transfer_invalid_psk)) }
            return
        }
        // The panel shows content as soon as it opens: stage, AP credentials, file list
        set {
            it.copy(
                fastTransfer = FastTransfer(
                    stage = FtStage.STOPPING,
                    ssid = ssid,
                    psk = psk,
                    files = files.map { f -> FtFile(f.name, f.size) },
                ),
            )
        }
        fetchJob = viewModelScope.launch {
            val joiner = com.nomily.app.wifi.HotspotJoiner(getApplication())
            val wifi = com.nomily.app.wifi.WifiClient(getApplication())
            try {
                // ① Stop recording first.
                // Skipping this step has been observed to cause: while the device records, 0x88 ack's and 0x80 reports wifiap=1,
                // **but the hotspot is not actually broadcasting** (the phone scans 8 times, 40 APs, none found,
                // system dialog says “device not found”).
                val recordingNow = withContext(Dispatchers.IO) {
                    c.getDeviceInfo().optInt("rec", 0) == 1
                }
                if (recordingNow) {
                    set { it.copy(busy = str(R.string.fast_transfer_stage_stopping), error = null) }
                    ftStage(FtStage.STOPPING)
                    // A failed 0x50 must abort fast transfer. Continuing into
                    // AP setup while the device is still writing races file
                    // finalisation with list/download and can yield a missing
                    // or truncated recording.
                    withContext(Dispatchers.IO) { c.stopRecording() }
                }

                set { it.copy(busy = str(R.string.fast_transfer_stage_starting_ap), error = null) }
                ftStage(FtStage.STARTING_AP)
                // After a previous round is cancelled or interrupted in background, the device often remains at `wifiap=1` but stops broadcasting the SSID.
// If we immediately enable AP, polling sees the state already 1 and proceeds, but joining inevitably fails,
// leaving the user only with “power‑cycle the device” as a remedy. Turn off the lingering AP first so the device’s Wi‑Fi state machine restarts the enable sequence.
                val staleAp = runCatching {
                    withContext(Dispatchers.IO) { com.nomily.app.ble.DeviceInfo(c.getDeviceInfo()).wifiApOn }
                }.getOrDefault(false)
                if (staleAp) {
                    Log.i(TAG, "Device still reports wifiap=1 on entry – turn off and restart")
                    turnOffAp(c, ssid, psk)
                    kotlinx.coroutines.delay(2_000)
                    // This round has just started; the “hotspot not turned off” warning at cleanup must not be triggered by the entry‑cleanup step.
                    set { it.copy(fastTransfer = it.fastTransfer?.copy(apStillOn = false)) }
                }
                withContext(Dispatchers.IO) { c.setWifiAp(on = true, ssid = ssid, psk = psk) }
                if (!awaitWifiAp(c)) throw IllegalStateException(str(R.string.fast_transfer_ap_not_broadcasting))
                // ② After wifiap=1, wait a few seconds for the RF to stabilize. Without the
                // wait, the hotspot join often fails.
                kotlinx.coroutines.delay(3_000)

                // Both Wi‑Fi join failure and TCP timeout end up in JOIN_FAILED:
// the device AP stays on, allowing the user to retry “join Wi‑Fi → TCP”; only on cancel or when the flow completes does finally close the AP, unbind the network, and restore BLE.
                while (true) {
                    set { it.copy(busy = str(R.string.fast_transfer_stage_joining_wifi)) }
                    ftStage(FtStage.JOINING)
                    try {
                        joiner.join(ssid, psk, FAST_TRANSFER_WIFI_JOIN_TIMEOUT_MS)
                    } catch (e: Exception) {
                        joiner.release()
                        awaitFastTransferRetry(e)
                        // Join failures are usually because the device reports wifiap=1 but isn’t actually broadcasting. Reusing the same dead AP yields identical failures; before retrying, have the device turn the hotspot off and on again.
                        cycleAp(c, ssid, psk)
                        continue
                    }

                    set { it.copy(busy = str(R.string.fast_transfer_stage_connecting)) }
                    ftStage(FtStage.CONNECTING)
                    val tcpError = connectTcpForWindow(wifi)
                    if (tcpError == null) break

                    // If the hotspot is joined but TCP is unreachable for 60 s: first unbind that network; on retry, request the hotspot network again, matching the behavior of unbinding then re‑requesting.
                    wifi.close()
                    joiner.release()
                    awaitFastTransferRetry(tcpError)
                    // Join succeeds but TCP cannot connect for a minute, indicating the reported `wifiap=1` does not match the actual broadcast.
                    cycleAp(c, ssid, psk)
                }

                var done = 0
                ftStage(FtStage.DOWNLOADING)
                for (f in files) {
                    set {
                        it.copy(busy = "${str(R.string.fast_transfer_stage_downloading)} ${done + 1}/${files.size}")
                    }
                    ftFile(f.name) { it.copy(status = FtFileStatus.DOWNLOADING) }
                    val bytes = withContext(Dispatchers.IO) {
                        // Include the size declared in the list: short reads or corruption must raise an error, not fall through to the “delete from device after transfer” branch below.
                        wifi.download(f.name, f.size) { got, total ->
                            set { ui -> ui.copy(progress = if (total > 0) got to total else null) }
                            ftFile(f.name) { it.copy(got = got) }
                        }
                    }
                    withContext(Dispatchers.IO) { pipeline.ingestRaw(bytes, f.name, key) }
                    // Downloaded bytes are not a completed transfer until the
                    // encrypted payload has been decoded and durably written.
                    // Marking DONE before ingest made missing/wrong-key failures
                    // show a green file row even though nothing playable existed.
                    ftFile(f.name) { it.copy(status = FtFileStatus.DONE, got = it.size) }
                    // Same as recorder: after successful save, delete from the device over the same TCP channel.
                    if (configStore.config.value.autoDeleteAfterTransfer) {
                        runCatching { withContext(Dispatchers.IO) { wifi.deleteFile(f.name) } }
                            .onFailure { Log.w(TAG, "Failed to delete ${f.name} after fast transfer: ${it.message}") }
                    }
                    done++
                }
                set { it.copy(busy = null, progress = null) }
                ftStage(FtStage.DONE)
                refreshClips()
                Log.i(TAG, "Fast transfer completed $done/${files.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fast transfer failed", e)
                val msg = failed(R.string.device_files_operation_failed, e)
                set {
                    it.copy(
                        busy = null,
                        progress = null,
                        error = msg,
                        // The panel stays in the failure state: keep the error reason and the list of transferred files visible, don’t flash away.
                        fastTransfer = it.fastTransfer?.copy(
                            // Preserve JOIN_FAILED on hotspot join failure (the panel uses this to offer a “retry”).
                            stage = if (it.fastTransfer.stage == FtStage.JOIN_FAILED) {
                                FtStage.JOIN_FAILED
                            } else {
                                FtStage.FAILED
                            },
                            error = msg,
                        ),
                    )
                }
            } finally {
                fastTransferRetry = null
                // The terminal state (DONE / FAILED / JOIN_FAILED) is already set in the try/catch;
// during cleanup first show “Cleaning up…”, **only after cleanup restore the terminal state** —
// previously setting CLEANUP directly overwrote DONE, leaving the panel stuck on “Cleaning up…”, with no “Close” button (real device reproduced: files were all saved but the panel kept spinning).
                val terminal = _ui.value.fastTransfer?.stage ?: FtStage.FAILED
                ftStage(FtStage.CLEANUP)
                // ⚠️ The entire cleanup must be [NonCancellable]: when cancelled/exiting the job is already marked cancelled,
                // each `withContext(Dispatchers.IO)` immediately throws CancellationException,
                // so the command to turn off the AP **never reaches the RF** — the device stays at wifiap=1 and stops broadcasting,
                // the next fast transfer can only recover by power‑cycling the device.
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { withContext(Dispatchers.IO) { wifi.quit() } }
                    runCatching { wifi.close() }
                    runCatching { joiner.release() }
                    turnOffAp(client, ssid, psk)
                }
                set { it.copy(busy = null, progress = null) }
                ftStage(terminal)
                loadFiles()
            }
        }
    }

    /**
     * Turn off the device hotspot and read back to confirm. If it cannot be turned off or the verification fails, set `apStillOn` —
     * For the user this is the same issue: the next fast transfer will likely fail to join.
     *
     * ⚠️ Turning off the AP **must include the same credentials used when turning it on**, a bare `{"ap":0}` is rejected and the AP continues broadcasting
     * (reproduced on real hardware: three retries, no ack).
     */
    private suspend fun turnOffAp(c: DnoteBleClient?, ssid: String, psk: String) {
        val target = c ?: return
        val off = runCatching { withContext(Dispatchers.IO) { target.setWifiAp(on = false, ssid = ssid, psk = psk) } }
        if (off.isFailure) {
            Log.w(TAG, "Failed to turn off AP: ${off.exceptionOrNull()?.message}")
            set { it.copy(fastTransfer = it.fastTransfer?.copy(apStillOn = true)) }
            return
        }
        kotlinx.coroutines.delay(1_000)
        // Read 0x80 once to confirm it was truly turned off — ack does not equal AP being down
        val still = runCatching {
            withContext(Dispatchers.IO) { com.nomily.app.ble.DeviceInfo(target.getDeviceInfo()).wifiApOn }
        }
        when (still.getOrNull()) {
            false -> Log.i(TAG, "wifiap=0 after teardown")
            true -> {
                Log.w(TAG, "setWifiAp(off) acked but wifiap still 1 – need to power-cycle device while broadcasting")
                set { it.copy(fastTransfer = it.fastTransfer?.copy(apStillOn = true)) }
            }
            null -> {
                Log.w(TAG, "Failed to read 0x80 after turning off AP – hotspot status unknown")
                set { it.copy(fastTransfer = it.fastTransfer?.copy(apStillOn = true)) }
            }
        }
    }

    /**
     * Turn off the device hotspot, then turn it back on, and wait again for `wifiap=1`.
     *
     * [awaitWifiAp] only reads the status bit, so we must actually turn it off first: the bit goes from 1 to 0 and back to 1,
     * the resulting state corresponds to the newly started AP, not a leftover from the previous round.
     */
    private suspend fun cycleAp(c: DnoteBleClient, ssid: String, psk: String) {
        ftStage(FtStage.STARTING_AP)
        turnOffAp(c, ssid, psk)
        set { it.copy(fastTransfer = it.fastTransfer?.copy(apStillOn = false)) }
        kotlinx.coroutines.delay(2_000)
        withContext(Dispatchers.IO) { c.setWifiAp(on = true, ssid = ssid, psk = psk) }
        if (!awaitWifiAp(c)) throw IllegalStateException(str(R.string.fast_transfer_ap_not_broadcasting))
        kotlinx.coroutines.delay(3_000)
    }

    private fun ftStage(stage: FtStage) =
        set { it.copy(fastTransfer = it.fastTransfer?.copy(stage = stage)) }

    private fun ftFile(name: String, edit: (FtFile) -> FtFile) = set { ui ->
        ui.copy(
            fastTransfer = ui.fastTransfer?.let { ft ->
                ft.copy(files = ft.files.map { if (it.name == name) edit(it) else it })
            },
        )
    }

    /** Retry short connections within a 60‑second window; returns null to indicate TCP is already connected. */
    private suspend fun connectTcpForWindow(wifi: com.nomily.app.wifi.WifiClient): Exception? {
        val deadline = SystemClock.elapsedRealtime() + FAST_TRANSFER_TCP_TIMEOUT_MS
        var attempt = 0
        var lastError: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++
            val remaining = deadline - SystemClock.elapsedRealtime()
            val connectTimeout = minOf(TCP_SINGLE_CONNECT_TIMEOUT_MS.toLong(), remaining).toInt()
            try {
                withContext(Dispatchers.IO) { wifi.connect(connectTimeoutMs = connectTimeout) }
                return null
            } catch (e: Exception) {
                lastError = e
                wifi.close()
                Log.i(TAG, "TCP connection attempt $attempt failed: ${e.message}")
                val retryDelay = minOf(TCP_CONNECT_RETRY_DELAY_MS, deadline - SystemClock.elapsedRealtime())
                if (retryDelay > 0) kotlinx.coroutines.delay(retryDelay)
            }
        }
        return lastError ?: com.nomily.app.wifi.WifiException("Timed out connecting to the device TCP service.")
    }

    /** Keep the device AP on, remain in the failure state awaiting user retry or cancellation. */
    private suspend fun awaitFastTransferRetry(error: Exception) {
        val message = failed(R.string.device_files_operation_failed, error)
        val signal = CompletableDeferred<Unit>()
        fastTransferRetry = signal
        set {
            it.copy(
                busy = null,
                progress = null,
                error = message,
                fastTransfer = it.fastTransfer?.copy(
                    stage = FtStage.JOIN_FAILED,
                    error = message,
                ),
            )
        }
        try {
            signal.await()
        } finally {
            if (fastTransferRetry === signal) fastTransferRetry = null
        }
        set {
            it.copy(
                error = null,
                fastTransfer = it.fastTransfer?.copy(error = null),
            )
        }
    }

    /**
     * Retry fast transfer. When in JOIN_FAILED, resume the original flow but only redo “join Wi‑Fi → TCP”;
     * the legacy terminal state retains the fallback of restarting from the file list.
     */
    fun retryFastTransfer() {
        fastTransferRetry?.let { signal ->
            if (signal.isActive) {
                signal.complete(Unit)
                return
            }
        }
        val ft = _ui.value.fastTransfer ?: return
        val remaining = _ui.value.files.filter { f ->
            ft.files.any { it.name == f.name && it.status != FtFileStatus.DONE }
        }
        if (remaining.isEmpty()) {
            set { it.copy(fastTransfer = null) }
            return
        }
        fastTransfer(remaining)
    }

    /** Close the fast‑transfer panel; if a transfer is in progress, cancel it as well. */
    fun dismissFastTransfer(cancel: Boolean) {
        if (cancel) fetchJob?.cancel()
        set { it.copy(fastTransfer = null) }
    }

    /**
     * "Automatically delete device files after transfer" (settings page `rec_settings.delete_after_transfer`, enabled by default).
     *
     * ⚠️ This toggle **was previously not wired at all**: the value is read to render the switch, but nowhere is it used to actually delete.
     * The UI text says “removed from device after retrieval”, leading users to think device storage is freed, which is not the case.
     *
     * Deletion failure does not count as retrieval failure: the file is safely stored locally, but the original remains on the device; a simple note suffices ("saved locally, but the device kept the original").
     */
    private suspend fun maybeDeleteFromDevice(c: DnoteBleClient, deviceFileName: String) {
        if (!configStore.config.value.autoDeleteAfterTransfer) return
        try {
            withContext(Dispatchers.IO) { c.deleteFile(deviceFileName) }
            Log.i(TAG, "Deleted $deviceFileName from device after retrieval")
            loadFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete $deviceFileName after retrieval", e)
            set { it.copy(error = "$deviceFileName: saved locally, but the device kept the original (${e.message})") }
        }
    }

    /** Poll the `wifiap` bit of 0x80, waiting up to 60 seconds. */
    private suspend fun awaitWifiAp(
        c: DnoteBleClient,
        timeoutMs: Long = FAST_TRANSFER_AP_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val total = (timeoutMs / 2_000).toInt()
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            // Report the attempt count to the fast‑transfer panel: the device needs 30–45 s to start the hotspot (longer on battery),
            // and if the UI shows no progress, the user will think it’s stuck.
            set {
                it.copy(
                    fastTransfer = it.fastTransfer?.copy(
                        stage = FtStage.POLLING_AP,
                        pollAttempt = attempt,
                        pollTotal = total,
                    ),
                )
            }
            val on = runCatching {
                withContext(Dispatchers.IO) { com.nomily.app.ble.DeviceInfo(c.getDeviceInfo()).wifiApOn }
            }.getOrNull()
            Log.i(TAG, "Waiting for AP: wifiap=$on (attempt $attempt/$total)")
            if (on == true) return true
            kotlinx.coroutines.delay(2_000)
        }
        return false
    }

    /**
     * Fast‑transfer hotspot credentials: SSID is fixed to [FAST_TRANSFER_SSID], password is generated each time and not persisted
     * (recorder does this — the password is only used for this round, storing it serves no purpose).
     *
     * Character set follows recorder: exclude easily confused characters like I/O/l/o, and ensure at least one uppercase, one lowercase, and one digit.
     */
    /**
     * Fast‑transfer hotspot credentials:
     * **If stored, reuse; if not, generate a pair (`dnote-xxxx` + 12‑character password) and write to config**.
     *
     * Previously it was “fixed SSID `FastUpload` + random password each time, not persisted” —
     * the settings page displayed and allowed editing of these two values, and the user manually connected using them;
     * changing the password each time invalidated the Wi‑Fi record stored in the phone OS.
     */
    private fun wifiCredentials(): Pair<String, String> {
        configStore.config.value.wifiAp?.let { ap ->
            if (ap.ssid.isNotEmpty() && ap.psk.isNotEmpty()) return ap.ssid to ap.psk
        }
        val suffix = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val ssid = "dnote-$suffix"
        val psk = randomPassword()
        configStore.update { it.copy(wifiAp = AppConfig.WifiAp(ssid, psk)) }
        return ssid to psk
    }

    private fun randomPassword(length: Int = 12): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnpqrstuvwxyz"
        val digits = "0123456789"
        val all = upper + lower + digits
        val rnd = java.security.SecureRandom()
        val chars = mutableListOf(
            upper[rnd.nextInt(upper.length)],
            lower[rnd.nextInt(lower.length)],
            digits[rnd.nextInt(digits.length)],
        )
        while (chars.size < length) chars += all[rnd.nextInt(all.length)]
        chars.shuffle(rnd)
        return chars.joinToString("")
    }

    fun needPassphrase(need: Boolean, mode: PassphraseMode = PassphraseMode.SET) =
        set { it.copy(needPassphrase = need, passphraseMode = mode, passphraseFirstBind = false) }

    /**
     * Clear the key stored on this phone for the device.
     *
     * **Affects only the phone**: the device’s encryption switch and already‑encrypted recordings remain untouched — this operation is “this phone forgets the passphrase”, not “make the device decrypt”. After clearing, fetching encrypted recordings will prompt for the passphrase again.
     */
    fun clearLocalKey() {
        val sn = _ui.value.deviceSn ?: return
        runCatching { keyVault.clear(sn) }
            .onFailure { Log.w(TAG, "Failed to clear local key: ${it.message}") }
        pipeline.forgetCachedKey(sn)
        set { it.copy(hasLocalKey = false) }
    }

    fun submitPassphrase(passphrase: String) {
        val sn = _ui.value.deviceSn ?: return
        val rotate = _ui.value.passphraseMode == PassphraseMode.ROTATE
        viewModelScope.launch {
            set { it.copy(needPassphrase = false, busy = str(R.string.encryption_progress_deriving), error = null) }
            // The trial passphrase **must not overwrite the existing good key** — deriveAndCache would directly replace the cache,
            // if validation fails (or errors mid‑process) we must restore the old one, otherwise a single user typo would make existing recordings unreadable.
            val previousKey = pipeline.cachedKeyFor(sn)
            try {
                // 512 MiB Argon2, ~1.6 s — must run off the main thread
                val key = withContext(Dispatchers.Default) { pipeline.deriveAndCache(passphrase, sn) }
                // Rotation: write the new key to the device (0xA2). **Old recordings encrypted with the previous key become permanently unreadable** —
                // therefore this path is only taken when the user explicitly selects key rotation.
                if (rotate) {
                    val c = client ?: throw IllegalStateException(str(R.string.encryption_error_device_not_connected))
                    set { it.copy(busy = str(R.string.encryption_progress_writing_key)) }
                    // **Store on the phone first, then write to the device**, if writing to the device fails, roll back.
                    // Reversing the order loses data: the device changes its key while the phone has not stored it → every subsequent recording is permanently inaccessible.
                    // Failing to store on the phone can be retried; failing to write the new key to the device is irreversible.
                    val previousStored = runCatching { keyVault.load(sn) }.getOrNull()
                    runCatching { keyVault.save(sn, key) }
                        .onFailure { throw IllegalStateException(str(R.string.encryption_error_key_store_failed), it) }
                    try {
                        withContext(Dispatchers.IO) { c.setEncryption(on = true, key = key) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write device key – rolling back saved key", e)
                        runCatching {
                            if (previousStored != null) keyVault.save(sn, previousStored) else keyVault.clear(sn)
                        }.onFailure { Log.e(TAG, "Rollback of local key also failed: ${it.message}") }
                        throw e
                    }
                }
                // Device is already encrypted, and it's not a rotation: **verify once before storing**.
                // If not verified, a typo in the passphrase will be considered a successful store, and later no recording can be decrypted,
                // and by then the user won't remember what they originally typed.
                if (!rotate && _ui.value.encryptionOn == true) {
                    val c = client
                    if (c == null) {
                        pipeline.forgetCachedKey(sn)
                        set { it.copy(busy = null, error = str(R.string.encryption_error_connect_first)) }
                        return@launch
                    }
                    when (verifyByProbe(c, key)) {
                        ProbeResult.MISMATCH -> {
                            // Don't keep an incorrect key in memory; if a good key existed, put it back unchanged
                            if (previousKey != null) pipeline.seedCachedKey(sn, previousKey)
                            else pipeline.forgetCachedKey(sn)
                            // A freshly unpacked V05 will inevitably reach this point: the v1.50 firmware, during binding, used a key the phone has never seen to open the encryption, so the **first** passphrase the user enters
                            // cannot match. Saying "incorrect passphrase" here is both inaccurate and a dead end —
                            // the user has no old passphrase to recall. When nothing exists on the device,
                            // writing the entered passphrase directly loses nothing; that's what we ask.
                            val onDevice = runCatching {
                                withContext(Dispatchers.IO) { c.getFileList() }
                            }.getOrNull()?.count { it.name.isNotEmpty() }
                            if (onDevice == 0) {
                                set { it.copy(busy = null, passphraseAdoptOffer = passphrase) }
                            } else {
                                set { it.copy(busy = null, passphraseMismatch = true) }
                            }
                            return@launch
                        }
                        // The device is actually not encrypted: nothing to verify, treat as stored
                        ProbeResult.MATCHES, ProbeResult.DEVICE_NOT_ENCRYPTING -> Unit
                    }
                }
                // Store into Keystore (accounted per SN, see KeyVault's R1/R2/R3).
                // A storage failure should not cause this decryption to fail — the key is already in memory.
                runCatching { keyVault.save(sn, key) }
                    .onFailure { Log.w(TAG, "Key storage in Keystore failed (still usable this time): ${it.message}") }
                set { it.copy(busy = null, hasLocalKey = true) }
            } catch (e: Exception) {
                // Verification error mid-way (device offline / download failure): this key wasn't verified, don't keep it in cache
                Log.w(TAG, "Passphrase flow failed", e)
                if (previousKey != null) pipeline.seedCachedKey(sn, previousKey) else pipeline.forgetCachedKey(sn)
                set { it.copy(busy = null, error = failed(R.string.device_files_operation_failed, e)) }
            }
        }
    }

    /** Three possible results of probe verification. */
    private enum class ProbeResult { MATCHES, MISMATCH, DEVICE_NOT_ENCRYPTING }

    /**
     * Verify the passphrase using a **temporary recording** — step by step:
     * Record 2.5 seconds → stop → locate this new segment → download → try decrypting with the candidate key.
     * The encrypted envelope contains a verify block; using the wrong key will explicitly report [DecryptError.Kind.VERIFY_MISMATCH],
     * allowing us to **determine** whether the passphrase is correct, instead of discovering it later when the user retrieves recordings.
     *
     * **Regardless of success or failure, delete the probe from the device**: this recording is not intended for the user and should not be left for them to clean up.
     */
    private suspend fun verifyByProbe(c: DnoteBleClient, key: ByteArray): ProbeResult {
        // Snapshot of the list before recording. **If unavailable, exit immediately** — an empty set would make every real recording on the device appear "new", effectively removing the safeguard below that says "if unrecognized, do not touch".
        val before = runCatching { withContext(Dispatchers.IO) { c.getFileList() } }
            .onFailure { Log.w(TAG, "Passphrase probe: failed to list files before recording – no files modified", it) }
            .getOrNull()?.map { it.name }?.toSet()
            ?: throw IllegalStateException(str(R.string.encryption_error_probe_not_found))

        set { it.copy(busy = str(R.string.encryption_progress_recording)) }
        // The probe recording is made **to set the passphrase**, and is deleted after recording. Using a guard turns it into a self‑lock: if the passphrase isn’t fully set →
        // the probe can’t record → the passphrase can never be set.
        withContext(Dispatchers.IO) { c.startRecording(realTime = false, bypassPassphraseGate = true) }
        kotlinx.coroutines.delay(PROBE_RECORD_MS)

        set { it.copy(busy = str(R.string.encryption_progress_stopping)) }
        // If stopping the recording fails, retry once: otherwise the device remains in recording state without the user knowing.
        val stopInfo = runCatching { withContext(Dispatchers.IO) { c.stopRecording() } }
            .getOrElse {
                Log.w(TAG, "Passphrase probe: stop recording failed, retrying once – ${it.message}")
                withContext(Dispatchers.IO) { c.stopRecording() }
            }
        val stoppedName = stopInfo.optString("name").takeIf { it.isNotEmpty() }

        set { it.copy(busy = str(R.string.encryption_progress_locating)) }
        kotlinx.coroutines.delay(500)                       // Allow time for the firmware to write to storage
        val after = withContext(Dispatchers.IO) { c.getFileList() }
        // **Only recognize “files that did not exist before recording”**. Previously, when unrecognized it would fall back to “the newest file on the device”,
        // and that probe path, once downloaded, was **unconditionally deleted** — deleting the user's own recording.
        // If unrecognized, exit and touch nothing.
        val probe = after.firstOrNull { it.name == stoppedName && it.name !in before }
            ?: after.singleOrNull { it.name !in before }
            ?: throw IllegalStateException(str(R.string.encryption_error_probe_not_found))

        set { it.copy(busy = str(R.string.encryption_progress_downloading)) }
        val blob = try {
            withContext(Dispatchers.IO) { c.downloadFile(probe.name, probe.size) { _, _ -> } }
        } catch (e: Exception) {
            runCatching { withContext(Dispatchers.IO) { c.deleteFile(probe.name) } }
            throw e
        }
        runCatching { withContext(Dispatchers.IO) { c.deleteFile(probe.name) } }
            .onFailure { Log.w(TAG, "Failed to delete probe: ${it.message}") }

        set { it.copy(busy = str(R.string.encryption_progress_verifying)) }
        if (!com.nomily.app.core.crypto.isEncrypted(blob)) return ProbeResult.DEVICE_NOT_ENCRYPTING
        return try {
            withContext(Dispatchers.Default) { com.nomily.app.core.crypto.decryptBytes(blob, key) }
            ProbeResult.MATCHES
        } catch (e: com.nomily.app.core.crypto.DecryptError) {
            if (e.kind == com.nomily.app.core.crypto.DecryptError.Kind.VERIFY_MISMATCH) {
                ProbeResult.MISMATCH
            } else {
                throw e
            }
        }
    }

    /** Dismiss the “passphrase mismatch” prompt (when the user retries or cancels). */
    fun clearPassphraseMismatch(retry: Boolean) =
        set { it.copy(passphraseMismatch = false, needPassphrase = retry) }

    /**
     * Respond to “Write this passphrase to the device?”. If agreed, treat it as a rotation — there are no recordings on the device,
     * so the rotation is lossless, and future recordings can be opened with this passphrase.
     */
    fun resolveAdoptOffer(accept: Boolean) {
        val pending = _ui.value.passphraseAdoptOffer
        set { it.copy(passphraseAdoptOffer = null) }
        if (!accept || pending == null) return
        set { it.copy(passphraseMode = PassphraseMode.ROTATE) }
        submitPassphrase(pending)
    }

    /** User has acknowledged “data will be sent to a third party” — record once and don’t ask again. */
    fun consentCloudEgress() = updateConfig { it.copy(cloudEgressConsented = true) }

    /** Forget a paired device (delete from Settings → “Known Devices”). */
    fun forgetDevice(id: String) = updateConfig { it.copy(devices = it.devices - id) }

    /**
     * Tiered batch cleanup (Settings → Danger Zone):
     * `target == null` means “all”, otherwise only delete that category of artefacts.
     */
    fun cleanupLibrary(target: ClipArtefact?) {
        val dir = File(getApplication<Application>().filesDir, "clips")
        // Count and report items that couldn’t be deleted to the user. Previously the return value of `delete()` was ignored,
        // so “cleanup complete” was **unconditionally** announced — the files were actually still present.
        var failed = 0
        dir.listFiles()?.forEach { f ->
            val hit = target?.let { ClipArtefact.of(f.name) == it } ?: true
            if (hit && !f.delete()) {
                failed++
                Log.w(TAG, "Failed to delete ${f.name} during cleanup")
            }
        }
        // Only the sidecar `.title` remains, which would resurrect an empty segment in the library — sweep it away.
        val left = dir.listFiles()?.map { it.name } ?: emptyList()
        ClipArtefact.orphanTitles(left).forEach { if (!File(dir, it).delete()) failed++ }
        if (failed > 0) {
            set { it.copy(error = str(R.string.devices_cleanup_partial_message, failed)) }
        }
        refreshClips()
    }

    fun fetch(file: DnoteBleClient.DeviceFile) {
        val c = client ?: return
        val sn = _ui.value.deviceSn ?: return
        val key = pipeline.cachedKeyFor(sn)
        if (_ui.value.encryptionOn == true && key == null) {
            set { it.copy(needPassphrase = true) }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            try {
                set { it.copy(downloadingName = file.name) }
                val res = withContext(Dispatchers.IO) {
                    pipeline.fetch(c, file, sn, key) { stage, got, total ->
                        set { it.copy(busy = stage.label, progress = if (total > 0) got to total else null) }
                    }
                }
                set { it.copy(busy = null, progress = null, downloadingName = null) }
                refreshClips()
                Log.i(TAG, "Retrieved ${res.oggFile.name}: ${res.durationMs / 1000.0}s, encrypted=${res.wasEncrypted}")
                // P3 “Automatically delete device file after transfer”. **Reaching this point guarantees a playable artefact** ——
                // Missing key or decryption failure throws an exception in the pipeline, never reaching this branch,
                // so the issue of “deleting an undecrypted original from the device” cannot occur.
                maybeDeleteFromDevice(c, file.name)
                // P1 “Auto-transcribe after download”: when the switch is off, nothing happens; when on, it’s still subject to the minimum duration threshold.
                if (configStore.config.value.autoTranscribeAfterDownload) {
                    val base = res.oggFile.name.substringBeforeLast(".")
                    _ui.value.clips.firstOrNull { it.base == base }
                        ?.let { transcribe(it, enforceMinDuration = true) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Retrieval cancelled by user")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Retrieval failed", e)
                set { it.copy(busy = null, progress = null, downloadingName = null, error = "Fetch failed: ${e.message}") }
            }
        }
    }

    // ── Local Segments / Playback ───────────────────────────────────────────────

    /**
     * Scan the library. **Group by segment base name**, not by file — a recording consists of three files: audio, transcription, and summary.
     * Classification follows the `:core` `ClipArtefact` rule.
     *
     * Before scanning, clean orphan `.title` sidecars: a segment that only has a title sidecar will be rebuilt as an empty entry.
     */
    /**
     * Import external audio (P2 “External Audio Import”): **import one by one**, a failure does not affect the others,
     * and the failure list is reported to the user in a single batch at the end.
     */
    fun importAudio(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        val dir = File(ctx.filesDir, "clips")
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.library_import_audio), error = null) }
            val failures = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { com.nomily.app.audio.AudioImporter.import(ctx, uri, dir) }
                        .exceptionOrNull()
                        ?.let { e ->
                            when (e) {
                                is com.nomily.app.audio.AudioImporter.UnsupportedTypeException ->
                                    ctx.getString(R.string.library_import_unsupported, e.ext)
                                else -> e.message ?: e.javaClass.simpleName
                            }
                        }
                }
            }
            set {
                it.copy(
                    busy = null,
                    // Prepend a header to the failure list; otherwise the UI shows a raw string of errors.
                    error = failures.takeIf { f -> f.isNotEmpty() }
                        ?.joinToString("\n", prefix = "${str(R.string.library_import_failed)}\n"),
                )
            }
            refreshClips()
        }
    }

    // ── Live Transcription (P1 Live)──────────────────────────────────────────
    //
    // Flow: device `0x51 realtime` + `0x68 start` → BLE pushes OPUS frames → WebSocket sends to ASR
    // → partial/final results return to UI. The foreground Service only prevents the process from being reclaimed (platform differences, see LiveSessionService).

    private var liveJob: Job? = null
    private var liveAsr: com.nomily.app.asr.LiveAsr? = null
    private var liveStartedRecording = false
    /** Raw OPUS frames received in this session, written to temporary files on the fly (**not loaded into memory**; even after an hour it's only around 7 MB). */
    private var liveBuffer: java.io.OutputStream? = null
    private var liveBufferFile: File? = null
    private var liveDidPause = false
    /**
     * This ASR shutdown is **initiated by us** (pause / stop / connection change).
     *
     * If not distinguished, the `Closed` event triggered by `close()` would be treated as a disconnection, causing an immediate automatic reconnection after pause
     * (observed on real device 2026-08-04: after tapping Pause, the log showed a ws connected line right away).
     */
    private var liveIntentionalClose = false

    /**
     * The device’s `name → size` snapshot at the start of the session. At stop, use it to **identify** the file generated by this session:
     * the one that didn’t exist before the session (normal start), or the one that **grew** when attaching to an existing recording.
     *
     * `null` = snapshot failure → cannot identify, never guess (the previous “largest name” could be an unrelated old recording,
     * and the replacement process would later overwrite and delete it).
     */
    private var preSessionFiles: Map<String, Int>? = null

    /** This session is **attached to a segment the device is already recording** (started via hardware button); the user never paused. */
    private var liveAttachedToExisting = false

    /**
     * This session **has received at least one transcription result**. A opened socket only indicates a connection to the server,
     * not that transcription is active — the UI’s “connected” status is based on this.
     */
    private var liveSawResult = false

    /** A provider that connected but never produced a result: do not reconnect it in this session, switch to the next one. */
    private val liveDeadProviders = mutableSetOf<String>()

    /**
     * @param assumeIdle Only the branch “unknown state → user clicks start anyway” passes true,
     *   all others are false.
     */
    fun startLive(assumeIdle: Boolean = false) {
        val c = client ?: return
        if (_ui.value.live.running) return
        // The foreground-service notification is the only sign the session is still running once the user leaves the app;
        // on API 33+ showing it needs POST_NOTIFICATIONS. A refusal doesn't stop the session, so ask once and carry on either way.
        if (!liveNotificationAsked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            liveNotificationAsked = true
            val needed = notGranted(listOf(android.Manifest.permission.POST_NOTIFICATIONS))
            if (needed.isNotEmpty()) {
                pendingLiveAssumeIdle = assumeIdle
                _permissionRequest.value = PermissionRequest(needed, PermissionRequest.Action.LIVE)
                return
            }
        }
        val cfg = configStore.config.value
        val asr = try {
            newLiveAsr(cfg)
        } catch (e: Exception) {
            set { it.copy(live = it.live.copy(error = e.message, status = LiveStatus.FAILED)) }
            return
        }
        liveAsr = asr
        liveDidPause = false
        liveAttachedToExisting = false
        liveSawResult = false
        liveDeadProviders.clear()
        preSessionFiles = null
        // Write temporary files while receiving: after the session ends, package into Ogg and store in the library
        liveBufferFile = File(getApplication<Application>().cacheDir, "live-stream.opus.raw").also { f ->
            f.delete()
            liveBuffer = f.outputStream().buffered(1 shl 16)
        }
        set {
            it.copy(
                live = Live(
                    running = true,
                    provider = providerLabel(cfg),
                    status = LiveStatus.CONNECTING,
                ),
            )
        }
        com.nomily.app.LiveSessionService.start(getApplication())

        liveJob = viewModelScope.launch {
            try {
                asr.connect { ev -> onLiveEvent(ev) }
                // If the device is already recording (started by hardware button or previous session didn’t finish), **don’t send 0x51** ——
                // that would cause the device to split the ongoing segment. Just attach to the stream.
                //
                // Three-state, not two-state: query now → use cache → **unknown**.
                // Previously, unknown collapsed to “not recording”, leading to sending 0x51 to a device that might be recording,
                // which would rotate the segment the user recorded with the hardware button. If it can’t be determined, stop and ask a person, don’t guess.
                val fresh = runCatching { withContext(Dispatchers.IO) { c.getDeviceInfo() } }
                    .getOrNull()?.let { com.nomily.app.ble.DeviceInfo(it) }
                val cached = _ui.value.info
                val recording: Boolean = when {
                    fresh != null -> fresh.isRecording
                    cached != null -> cached.isRecording
                    assumeIdle -> false
                    else -> {
                        Log.w(TAG, "Starting real-time session: unable to query device recording state, ask user before sending 0x51")
                        runCatching { asr.close() }
                        liveAsr = null
                        com.nomily.app.LiveSessionService.stop(getApplication())
                        liveBuffer?.runCatching { close() }
                        liveBuffer = null
                        liveBufferFile?.delete()
                        liveBufferFile = null
                        set {
                            it.copy(
                                live = Live(running = false, pendingStartConfirm = true, status = LiveStatus.IDLE),
                            )
                        }
                        return@launch
                    }
                }
                // Before using the device’s recorder, snapshot the file list: at stop, use it to recognize “the file generated by this session”
                // (the one that didn’t exist before / the one that grew when attaching to an existing recording). If the snapshot fails, don’t guess,
                // later fall back to “fetch it directly from the device”.
                preSessionFiles = runCatching {
                    withContext(Dispatchers.IO) { c.getFileList() }.associate { it.name to it.size }
                }.getOrNull()
                if (preSessionFiles == null) {
                    Log.w(TAG, "Starting real-time session: file list snapshot failed – fallback to manual transfer prompt")
                }
                liveAttachedToExisting = recording
                // The live‑upload switch is fixed at **the moment recording starts**: 0x51 with 0x01, or when the app is connected
                // push back 0x01 for 0x54. Recordings started by the hardware button before the app connects have `upload=0`,
                // 0x68 will be accepted but the firmware never pushes frames; the protocol has no command to enable it mid‑session.
                // Clarify this now so users don’t stare at “Listening… 0 KB” until timeout.
                if (recording) {
                    val upload = runCatching {
                        withContext(Dispatchers.IO) { c.getRecordingStatus() }.optInt("upload", 1)
                    }.getOrDefault(1)
                    if (upload == 0) {
                        Log.w(TAG, "Starting real-time session: device recording upload=0 – cannot get real-time stream")
                        throw IllegalStateException(str(R.string.live_attach_no_upload))
                    }
                }
                withContext(Dispatchers.IO) {
                    if (!recording) {
                        c.startRecording(realTime = true)
                        liveStartedRecording = true
                    }
                    c.startStream()
                }
                set { it.copy(live = it.live.copy(status = LiveStatus.STREAMING)) }
                var pendingBytes = 0
                withContext(Dispatchers.IO) {
                    // First frame timeout 10 seconds: a streaming device emits a frame every 20 ms, so 10 seconds is sufficient;
                // the default 30 seconds is reserved for gaps during the session, not for the initial packet.
                    c.streamAudio(firstFrameTimeoutMs = LIVE_FIRST_FRAME_TIMEOUT_MS).collect { frames ->
                        // Setting on every frame would overwhelm the UI (50 fps) — batch updates every half‑second,
                // the two counters in the control bar only display to the second, which is sufficient.
                        pendingBytes += frames.size
                        if (pendingBytes >= OPUS_FRAME_SIZE * 25) {
                            val add = pendingBytes
                            pendingBytes = 0
                            set { it.copy(live = it.live.copy(bytes = it.live.bytes + add)) }
                        }
                        // During pause, **continue receiving and storing, just don’t send to ASR** (disconnecting BLE might stop the device’s stream).
                // Swallow write failures: stopLive closes the buffered stream, and we might be writing the last frame —
                // that isn’t a session error; it shouldn’t push the UI into FAILED.
                        runCatching { liveBuffer?.write(frames) }
                        if (!_ui.value.live.paused) liveAsr?.send(frames)
                    }
                }
                // Stream ends naturally (device stopped recording)
                stopLive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Real-time session failed", e)
                set { it.copy(live = it.live.copy(error = e.message, status = LiveStatus.FAILED)) }
                stopLive()
            }
        }
    }

    /** Pause: disconnect ASR; BLE and device recording remain unchanged. */
    fun pauseLive() {
        if (!_ui.value.live.running || _ui.value.live.paused) return
        liveDidPause = true
        commitLivePartial()
        liveIntentionalClose = true
        liveAsr?.close(); liveAsr = null
        set { it.copy(live = it.live.copy(paused = true, status = LiveStatus.IDLE)) }
    }

    /** Resume: open a new ASR connection and continue sending frames. */
    fun resumeLive() {
        if (!_ui.value.live.running || !_ui.value.live.paused) return
        runCatching {
            liveIntentionalClose = false
            val asr = newLiveAsr(configStore.config.value)
            liveAsr = asr
            asr.connect { ev -> onLiveEvent(ev) }
        }.onSuccess {
            set { it.copy(live = it.live.copy(paused = false, status = LiveStatus.STREAMING, error = null)) }
        }.onFailure { e ->
            set { it.copy(live = it.live.copy(error = e.message, status = LiveStatus.FAILED)) }
        }
    }

    /** Consolidate unfinished temporary lines into a segment. */
    private fun commitLivePartial() = set { ui ->
        val live = ui.live
        if (live.partial.isBlank()) {
            ui
        } else {
            ui.copy(
                live = live.copy(
                    finals = live.finals + LiveLine(live.partial, live.partialTranslation),
                    partial = "",
                    partialTranslation = "",
                ),
            )
        }
    }

    /**
     * Respond to the confirmation dialog “Unable to determine if the device is recording”.
     * `start == true` means the user chose “Start anyway” — the **user** assumes the risk of interruption, not us guessing for them.
     */
    fun resolveLiveStartConfirm(start: Boolean) {
        set { it.copy(live = it.live.copy(pendingStartConfirm = false)) }
        if (start) startLive(assumeIdle = true)
    }

    fun stopLive() {
        val c = client
        val startedByUs = liveStartedRecording
        liveStartedRecording = false
        liveJob?.cancel(); liveJob = null
        liveIntentionalClose = true
        liveAsr?.close(); liveAsr = null
        com.nomily.app.LiveSessionService.stop(getApplication())
        // Finalize temporary results into a completed entry — otherwise the last half‑sentence would be lost
        set { ui ->
            val live = ui.live
            ui.copy(
                live = live.copy(
                    running = false,
                    status = LiveStatus.IDLE,
                    partial = "",
                    partialTranslation = "",
                    finals = if (live.partial.isNotBlank()) {
                        live.finals + LiveLine(live.partial, live.partialTranslation)
                    } else {
                        live.finals
                    },
                ),
            )
        }
        val hadPauses = liveDidPause
        liveDidPause = false
        val buffer = liveBufferFile
        runCatching { liveBuffer?.close() }
        liveBuffer = null
        liveBufferFile = null

        if (c != null) {
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { c.stopStream() } }
                    .onFailure { Log.w(TAG, "Failed to stop stream: ${it.message}") }
                var stopInfo: org.json.JSONObject? = null
                if (startedByUs) {
                    stopInfo = runCatching { withContext(Dispatchers.IO) { c.stopRecording() } }
                        .onFailure { Log.w(TAG, "Failed to stop recording: ${it.message}") }
                        .getOrNull()
                }
                saveLiveClip(c, buffer, stopInfo, hadPauses)
                loadFiles()
            }
        }
    }

    /**
     * Store the audio from this live session into the library.
     *
     * **Prefer the segment name returned by the device** (the `name` field in the `stopRecording` status JSON); if unavailable, use the newest file on the device:
     * the phone’s clock and the device’s clock differ by 1–2 seconds, so using local time for the name would not match the device file,
     * causing subsequent “replace with the device’s full recording” and auto‑deletion to fail.
     */
    private suspend fun saveLiveClip(
        c: DnoteBleClient,
        buffer: File?,
        stopInfo: org.json.JSONObject?,
        hadPauses: Boolean,
    ) {
        if (buffer == null || !buffer.isFile || buffer.length() < com.nomily.app.core.audio.OPUS_FRAME_SIZE) {
 Log.i(TAG, "Real-time session did not receive enough audio (${buffer?.length() ?: 0}B), not storing segment")
            buffer?.delete()
            return
        }
        val deviceName = stopInfo?.optString("name")?.takeIf { it.isNotEmpty() }
            ?: resolveSessionDeviceFile(c)
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()) + ".opus"
        // When attached to a segment the device is already recording, the phone’s copy only overwrites audio **after the attachment**. Storing with the device’s filename,
        // a several‑minute recording becomes a few‑second “truncated” segment in the library. Such sessions are named by attachment time,
        // and the replacement prompt still knows which device file it refers to.
        val name = if (liveAttachedToExisting) stamp else (deviceName ?: stamp)

        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val raw = buffer.readBytes()
                pipeline.ingestRaw(raw, name, key = null).oggFile
            }
        }.onFailure { Log.e(TAG, "Failed to store real-time segment", it) }.getOrNull()
        buffer.delete()
        if (saved == null) return

        // Persist the transcription text as well: live‑generated paragraphs become the segment’s transcript
        val lines = _ui.value.live.finals
        if (lines.isNotEmpty()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val base = saved.name.substringBeforeLast('.')
                    File(saved.parentFile, "$base.txt").writeText(lines.joinToString("\n") { it.text })
                }
            }.onFailure { Log.w(TAG, "Failed to write real-time transcription: ${it.message}") }
        }
        set { it.copy(live = it.live.copy(savedClip = saved.name)) }
        Log.i(TAG, "Real-time session saved as ${saved.name}")

        // If paused (or this session is attached to a segment the device is already recording) → the phone’s copy is incomplete,
        // the device’s copy is full; ask the user whether to replace. In attachment scenarios **there is no pause**, the wording is a single sentence.
        val attached = liveAttachedToExisting
        if ((hadPauses || attached) && deviceName != null) {
            val size = stopInfo?.optInt("size") ?: 0
            set {
                it.copy(
                    live = it.live.copy(
                        pendingRepair = PendingRepair(deviceName, saved.name, size, wasAttached = attached),
                    ),
                )
            }
        } else if (deviceName != null && configStore.config.value.autoDeleteAfterTransfer) {
            runCatching { withContext(Dispatchers.IO) { c.deleteFile(deviceName) } }
                .onFailure { Log.w(TAG, "Failed to delete device file after real-time session: ${it.message}") }
        }
    }

    /**
     * When the device does not return a filename, identify **the segment generated by this session**.
     *
     * Identity comes from the [preSessionFiles] snapshot, not a guess:
     * - Normal start → the one that **did not exist** before the session;
     * - Attached to an existing recording → the one that **grew** during the session.
     *
     * If the snapshot is missing or candidates are not unique → return `null`, and the caller falls back to “fetch directly from the device”.
     * The old implementation used “the one with the largest name”, which could pick an unrelated old recording, and the replacement process would later overwrite and delete it — exactly the bug this change fixes.
     */
    private suspend fun resolveSessionDeviceFile(c: DnoteBleClient): String? {
        val before = preSessionFiles ?: run {
            Log.w(TAG, "Unable to identify device file for this session: no pre-session snapshot — not guessing")
            return null
        }
        val after = runCatching { withContext(Dispatchers.IO) { c.getFileList() } }
            .onFailure { Log.w(TAG, "Failed to retrieve file list while identifying session file: ${it.message}") }
            .getOrNull() ?: return null
        val candidates = if (liveAttachedToExisting) {
            after.filter { f -> before[f.name]?.let { f.size > it } == true }
        } else {
            after.filter { it.name !in before.keys }
        }
        return when (candidates.size) {
            1 -> candidates.first().name
            else -> {
                Log.w(TAG, "Unable to identify device file for this session: ${candidates.size} candidates — not guessing")
                null
            }
        }
    }

    /** Replace the phone’s copy with the complete recording from the device. */
    fun repairLiveClip(repair: PendingRepair) {
        val c = client ?: return
        val sn = _ui.value.deviceSn
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.live_repair_downloading), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val blob = c.downloadFile(repair.deviceFileName, repair.expectedSize) { got, total ->
                        set { it.copy(progress = if (total > 0) got to total else null) }
                    }
                    val res = pipeline.ingestRaw(blob, repair.deviceFileName, sn?.let { pipeline.cachedKeyFor(it) })
                    // “Fixed” must mean **a truly playable file was produced**. An empty artefact doesn’t count as success,
                    // and we must not delete the device’s sole complete recording afterward.
                    if (!res.oggFile.isFile || res.oggFile.length() <= 0L) {
                        throw IllegalStateException(str(R.string.live_repair_not_playable))
                    }
                    res
                }
            }.onFailure { e ->
                Log.w(TAG, "Replacement failed — device file ${repair.deviceFileName} left unchanged", e)
                set { it.copy(error = str(R.string.live_repair_failed, e.message ?: "")) }
            }.onSuccess {
                if (configStore.config.value.autoDeleteAfterTransfer) {
                    runCatching { withContext(Dispatchers.IO) { c.deleteFile(repair.deviceFileName) } }
                        .onFailure { Log.w(TAG, "Failed to delete device file after replacement: ${it.message}") }
                }
                set { it.copy(live = it.live.copy(repairState = RepairState.SUCCEEDED)) }
            }
            set { it.copy(busy = null, progress = null, live = it.live.copy(pendingRepair = null)) }
            refreshClips()
            loadFiles()
        }
    }

    /**
     * User chooses “keep the phone’s copy”. The device’s file **is the only complete copy of this recording**, so it should remain unchanged —
     * previously it would be deleted according to “delete after transfer”, effectively destroying the audio that was just offered to the user.
     */
    fun declineLiveRepair(repair: PendingRepair) {
        set {
            it.copy(
                live = it.live.copy(
                    pendingRepair = null,
                    // The full recording remains on the device; inform the user they can retrieve it themselves
                    repairState = RepairState.NEEDS_MANUAL_TRANSFER,
                ),
            )
        }
        Log.i(TAG, "User declined replacement: device file ${repair.deviceFileName} left unchanged")
    }

    /** Dismiss the replacement result banner. */
    fun dismissRepairState() = set { it.copy(live = it.live.copy(repairState = RepairState.IDLE)) }

    private fun onLiveEvent(ev: com.nomily.app.asr.LiveAsrEvent) {
        when (ev) {
            is com.nomily.app.asr.LiveAsrEvent.Partial -> {
                liveSawResult = true
                set {
                    it.copy(
                        live = it.live.copy(
                            partial = ev.text,
                            partialTranslation = ev.translation,
                            status = LiveStatus.STREAMING,
                        ),
                    )
                }
            }
            is com.nomily.app.asr.LiveAsrEvent.Final -> {
                liveSawResult = true
                set {
                    it.copy(
                        live = it.live.copy(
                            finals = it.live.finals + LiveLine(ev.text, ev.translation),
                            partial = "",
                            partialTranslation = "",
                            status = LiveStatus.STREAMING,
                        ),
                    )
                }
            }
            is com.nomily.app.asr.LiveAsrEvent.Closed -> {
                if (liveIntentionalClose) {
                    liveIntentionalClose = false
                    return
                }
                if (!_ui.value.live.running || _ui.value.live.paused) return
                // A service that was shut down without ever producing a result is a **dead end**: reconnecting to it only consumes reconnection attempts,
                // while the UI keeps showing it as active. Log it and switch to the next provider in the chain.
                if (!liveSawResult) {
                    val dead = providerLabel(configStore.config.value)
                    Log.w(TAG, "ASR provider $dead connected but produced no results — considered dead end, switching to next")
                    liveDeadProviders += dead
                }
                // Session still active but receives a close = disconnection; reconnect ASR once (BLE remains unchanged)
                Log.w(TAG, "ASR connection dropped: ${ev.error ?: "closed by the server"}, reconnecting")
                set { it.copy(live = it.live.copy(status = LiveStatus.RECONNECTING, error = ev.error)) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(LIVE_RECONNECT_DELAY_MS)
                    if (!_ui.value.live.running) return@launch
                    runCatching {
                        val asr = newLiveAsr(configStore.config.value, skip = liveDeadProviders)
                        liveAsr = asr
                        asr.connect { e -> onLiveEvent(e) }
                        // Socket opened **does not mean** transcription is active — state stays at “reconnecting”,
                // only after the first result arrives is it considered connected (Partial/Final branches set STREAMING).
                        set { it.copy(live = it.live.copy(error = null)) }
                    }.onFailure { e ->
                        set { it.copy(live = it.live.copy(status = LiveStatus.FAILED, error = e.message)) }
                    }
                }
            }
        }
    }

    /** Use Azure if available, otherwise use the local server — the same credential logic as the batch transcription provider chain. */
    private fun newLiveAsr(
        cfg: com.nomily.app.core.config.AppConfig,
        skip: Set<String> = emptySet(),
    ): com.nomily.app.asr.LiveAsr {
        val azure = cfg.asrProviders.azure
        if (azure != null && azure.key.isNotEmpty() && azure.region.isNotEmpty() && "Azure" !in skip) {
            val target = cfg.lastTargetLang
            val source = cfg.lastSourceLang ?: "en-US"
            // Only route to the translation endpoint when target language **differs** from source; if identical (or not selected) only perform recognition.
            val wantTranslation = !target.isNullOrEmpty() &&
                target.substringBefore('-').lowercase() != source.substringBefore('-').lowercase()
            return if (wantTranslation) {
                com.nomily.app.asr.AzureLiveTranslation(azure.key, azure.region, source, target)
            } else {
                com.nomily.app.asr.AzureLiveAsr(azure.key, azure.region, source)
            }
        }
        val local = cfg.asrProviders.local
        if (local != null && local.host.isNotEmpty() && local.port > 0 && "Local" !in skip) {
            return com.nomily.app.asr.LocalLiveAsr(local.host, local.port)
        }
        throw IllegalStateException(str(R.string.asr_no_provider_configured))
    }

    private fun providerLabel(cfg: com.nomily.app.core.config.AppConfig): String {
        val azure = cfg.asrProviders.azure
        return if (azure != null && azure.key.isNotEmpty()) "Azure" else "Local"
    }

    // ── Summary / Translation (P2)─────────────────────────────────────────────

    fun updateTemplates(
        transform: (List<com.nomily.app.core.llm.SummarizeTemplate>) ->
        List<com.nomily.app.core.llm.SummarizeTemplate>,
    ) = templateStore.update(transform)

    fun resetTemplates() = templateStore.resetToDefaults()

    /** Target language table (shared for summary output language and on‑demand translation). Fetched online once; fall back to cache if unavailable. */
    suspend fun targetLanguages(): List<com.nomily.app.llm.LanguageService.Language> =
        withContext(Dispatchers.IO) { languageService.targetLanguages() }

    /**
     * Generate a summary:
     * template prompt + **output language directive** → LLM → write `{base}.summary.md`;
     * then request a short title written to `{base}.title` (**best‑effort**: title failure does not affect the summary).
     *
     * @param languageName Target language name in English; null = follow source language
     */
    /**
     * Generate a summary **without persisting** — the result is first returned to the sheet for user review.
     *
     * Splitting into “generate / save” steps is a behavior change effective 2026-08-06: previously, after selecting a template the window closed,
     * the background process wrote directly to the library, giving the user no chance to preview before deciding to keep it.
     */
    suspend fun generateSummary(
        clip: LocalClip,
        template: com.nomily.app.core.llm.SummarizeTemplate,
        languageName: String?,
    ): com.nomily.app.llm.LlmClient.Completion {
        val transcript = clip.transcript?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(str(R.string.clip_detail_transcribe_first))
        return withContext(Dispatchers.IO) {
            val cfg = configStore.config.value
            val system = com.nomily.app.core.llm.withLanguageInstruction(template.prompt, languageName)
            com.nomily.app.llm.LlmClient.complete(system, transcript, cfg)
        }
    }

    /** Write the user‑approved summary into the library, also generating a short title (title failure does not affect the summary). */
    fun saveSummary(clip: LocalClip, summary: String, languageName: String?) {
        val dir = File(getApplication<Application>().filesDir, "clips")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    File(dir, "${clip.base}.summary.md").writeText(summary)
                    // The short title is a nice‑to‑have addition: on failure only log a message, the summary itself is already stored
                    runCatching {
                        val cfg = configStore.config.value
                        val titleSystem = com.nomily.app.core.llm.withLanguageInstruction(
                            com.nomily.app.core.llm.TITLE_PROMPT, languageName,
                        )
                        val title = com.nomily.app.llm.LlmClient
                            .complete(titleSystem, summary.take(1000), cfg)
                            .text.trim()
                        if (title.isNotEmpty()) File(dir, "${clip.base}.title").writeText(title)
                    }.onFailure { Log.w(TAG, "Failed to generate title (does not affect summary): ${it.message}") }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to save summary", e)
                set { it.copy(error = failed(R.string.summarize_error, e)) }
            }
            refreshClips()
        }
    }

    /**
     * Translate transcription or summary on demand — write `{base}.translated.txt` / `{base}.summary.translated.md`.
     */
    fun translateArtefact(clip: LocalClip, which: ClipArtefact, languageName: String) {
        val src = when (which) {
            ClipArtefact.TRANSCRIPT -> clip.transcript
            ClipArtefact.SUMMARY -> clip.summary
            else -> null
        }?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() } ?: return
        val dir = File(getApplication<Application>().filesDir, "clips")
        val out = when (which) {
            ClipArtefact.TRANSCRIPT -> File(dir, "${clip.base}.translated.txt")
            else -> File(dir, "${clip.base}.summary.translated.md")
        }
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.clip_detail_translating), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val res = com.nomily.app.llm.LlmClient
                        .translate(src, languageName, configStore.config.value)
                    out.writeText(res.text)
                    res.truncated
                }
            }.onFailure { e ->
                Log.e(TAG, "Translation failed", e)
                set { it.copy(error = failed(R.string.summarize_error, e)) }
            }.onSuccess { truncated ->
                // When the translation hits the token limit, the result is **truncated**, and we should note it even if it gets written to disk
                if (truncated) set { it.copy(error = str(R.string.summarize_truncated_warning)) }
            }
            set { it.copy(busy = null) }
            refreshClips()
        }
    }

    /**
     * Merge two adjacent recordings (P2 “adjacent segment merge”) — order is fixed **old first, new second**.
     *
     * After success **delete all artifacts of the two source segments**:
     * The merged audio is a new file, so the old transcription/summary no longer applies; the user must run transcription again if needed.
     */
    fun mergeClips(older: LocalClip, newer: LocalClip) {
        val dir = File(getApplication<Application>().filesDir, "clips")
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.library_merging), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val a = older.audio ?: throw IllegalStateException("missing audio: ${older.name}")
                    val b = newer.audio ?: throw IllegalStateException("missing audio: ${newer.name}")
                    com.nomily.app.audio.AudioMerger.merge(a, b, dir)
                    deleteAllArtefacts(dir, older.base)
                    deleteAllArtefacts(dir, newer.base)
                }
            }.onFailure { e ->
                Log.e(TAG, "Merge failed", e)
                set { it.copy(error = failed(R.string.library_merge_failed, e)) }
            }
            set { it.copy(busy = null) }
            refreshClips()
        }
    }

    /** Delete **all** artifacts of a given base name (audio/transcription/translation/summary/title) — shared with merge and full‑record deletion. */
    private fun deleteAllArtefacts(dir: File, base: String) {
        dir.listFiles()?.forEach { f ->
            if (ClipArtefact.baseOf(f.name) == base) f.delete()
        }
    }

    /**
     * Rescan the library directory.
     *
     * **The whole scan runs on an IO thread** and publishes results once finished. Previously it was synchronous, called directly from init and on every tab switch; on a 1890‑item / 25 GB library that blocked the main thread for several seconds (QA reported 14 s first‑screen, tab‑switch lag and heating all stem from this).
     *
     * Two other previous overheads were also removed:
     * - `files.firstOrNull { it.name == ... }` performed four linear scans of the entire directory for each clip — 1890 × ≈ 7000 files, resulting in tens of millions of string comparisons. Replaced with a `name → File` map, O(1) lookup.
     * - `oggDurationMs(f.readBytes())` read the **entire audio file into memory** just to compute duration. Now reads the last 256 KB (duration depends only on the last page’s granule) and caches (size, mtime) to disk, so rescans don’t recompute.
     */
    fun refreshClips() {
        // Cancel if the previous run hasn’t finished: download, merge, delete, or device connection all invoke this,
        //         // Without deduplication a large library would trigger multiple full scans concurrently, which could overwrite each other.
        clipsJob?.cancel()
        clipsJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { scanClips() }
            set { it.copy(clips = list) }
        }
    }

    private fun scanClips(): List<LocalClip> {
        val dir = File(getApplication<Application>().filesDir, "clips")
        val files = dir.listFiles()?.toList() ?: emptyList()

        ClipArtefact.orphanTitles(files.map { it.name }).forEach { File(dir, it).delete() }

        // A single map lookup replaces repeated linear scans of the whole directory
        val byName: Map<String, File> = files.associateBy { it.name }

        val byBase = files
            .filter { ClipArtefact.of(it.name) != null }
            .groupBy { ClipArtefact.baseOf(it.name) ?: it.name }

        val durations = DurationCache(File(dir, ".durations.json"))

        val list = byBase.map { (base, group) ->
            val audio = group.firstOrNull { ClipArtefact.of(it.name) == ClipArtefact.AUDIO }
            LocalClip(
                base = base,
                audio = audio,
                // Prefer `.txt` for the main text; `.asr.json` is the structured form of the same transcription and isn’t needed for display
                transcript = byName["$base.txt"]
                    ?: group.firstOrNull { ClipArtefact.of(it.name) == ClipArtefact.TRANSCRIPT },
                transcriptJson = byName["$base.asr.json"],
                summary = byName["$base.summary.md"]
                    ?: group.firstOrNull { ClipArtefact.of(it.name) == ClipArtefact.SUMMARY },
                // Translation and `.title` are not counted among the three artifact types; retrieve them from the whole directory
                transcriptTranslation = byName["$base.translated.txt"],
                summaryTranslation = byName["$base.summary.translated.md"],
                durationMs = audio?.let { durations.durationOf(it) },
                // If the filename isn’t `yyyyMMddHHmmss` (external audio imported), fall back to the file’s timestamp.
                // Previously it fell back to null, and sorting used `?: 0L`, causing imported clips to always sink to the bottom of the list.
                // For “text‑only” entries without audio, we take the timestamp from any artifact in the group, which is better than having none.
                recordedAt = parseRecordedAt(base)
                    ?: (audio ?: group.firstOrNull())?.let { Date(it.lastModified()) },
                // `.title` is not one of the three artifact types (`ClipArtefact.of` returns null for it),
                //                 // Therefore it isn’t in the group — it must be fetched from the whole directory, not assumed to be in the group.
                title = byName["$base${ClipArtefact.TITLE_SUFFIX}"]
                    ?.let { f -> runCatching { f.readText().trim() }.getOrNull() }
                    ?.takeIf { t -> t.isNotEmpty() },
            )
        }.sortedByDescending { it.recordedAt?.time ?: 0L }   // Sort by recording time, not by file‑creation time

        durations.persist(byName.keys)
        return list
    }

    /**
     * Duration cache: `name → (size, mtime, duration)`, stored under the clips directory.
     *
     * As long as a file’s size and mtime stay unchanged, its duration is stable, so rescans can look it up directly. When a file is first encountered, only the **last 256 KB** is read: an Ogg file’s duration is determined by the granule in the final page, so the whole file isn’t needed.
     * If the window can’t locate a page header (file smaller than the window or not Ogg), we fall back to parsing the entire file.
     */
    private class DurationCache(private val file: File) {
        private val entries = HashMap<String, Triple<Long, Long, Int?>>()
        private var dirty = false

        init {
            runCatching {
                if (file.isFile) {
                    val root = org.json.JSONObject(file.readText())
                    for (key in root.keys()) {
                        val o = root.getJSONObject(key)
                        entries[key] = Triple(
                            o.getLong("size"),
                            o.getLong("mtime"),
                            if (o.isNull("ms")) null else o.getInt("ms"),
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to read duration cache, ignoring: ${it.message}") }
        }

        fun durationOf(f: File): Int? {
            val size = f.length()
            val mtime = f.lastModified()
            entries[f.name]?.let { (cachedSize, cachedMtime, ms) ->
                // Only treat **valid values** in the cache as authoritative: if detection fails and we cache null, we’ll wait until detection itself improves
                // (e.g., after adding MediaMetadataRetriever as a fallback), old clips will still show no duration,
                // unless the user reinstalls. Null isn’t cached, which means each scan will retry reading files that truly can’t be parsed.
                if (cachedSize == size && cachedMtime == mtime && ms != null) return ms
            }
            val ms = probe(f)
            if (ms != null) {
                entries[f.name] = Triple(size, mtime, ms)
                dirty = true
            }
            return ms
        }

        private fun probe(f: File): Int? = runCatching {
            val size = f.length()
            if (size > TAIL_BYTES) {
                val tail = ByteArray(TAIL_BYTES)
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(size - TAIL_BYTES)
                    raf.readFully(tail)
                }
                oggDurationMsFromTail(tail)?.let { return@runCatching it }
            }
            oggDurationMs(f.readBytes())
                // Failure to read Ogg duration doesn’t mean the file has no length: imported wav/m4a/mp3 files are handled
                // via MediaMetadataRetriever. Previously they always returned null, causing the UI to treat “no duration”
                // as “text‑only” (2026‑08‑12 test: playable wav files were labeled Text only).
                ?: retrieverDurationMs(f)
        }.getOrNull()

        // No need for `use{}`: MediaMetadataRetriever only became AutoCloseable in API 29, while minSdk is 24.
        private fun retrieverDurationMs(f: File): Int? = runCatching {
            val mr = android.media.MediaMetadataRetriever()
            try {
                mr.setDataSource(f.absolutePath)
                mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull()
            } finally {
                runCatching { mr.release() }
            }
        }.getOrNull()

        /** Also drop rows for deleted files to prevent the cache from growing indefinitely. */
        fun persist(liveNames: Set<String>) {
            val stale = entries.keys.filter { it !in liveNames }
            if (stale.isNotEmpty()) {
                stale.forEach { entries.remove(it) }
                dirty = true
            }
            if (!dirty) return
            runCatching {
                val root = org.json.JSONObject()
                entries.forEach { (name, v) ->
                    root.put(
                        name,
                        org.json.JSONObject()
                            .put("size", v.first)
                            .put("mtime", v.second)
                            .put("ms", v.third ?: org.json.JSONObject.NULL),
                    )
                }
                file.writeText(root.toString())
            }.onFailure { Log.w(TAG, "Failed to write duration cache, ignoring: ${it.message}") }
        }

        private companion object {
            /** Ogg page size limit is about 64 KB; 256 KB is guaranteed to contain the final page header. */
            const val TAIL_BYTES = 256 * 1024
        }
    }

    /**
     * Each of the three artifact types can be deleted independently — deleting audio leaves transcription and summary untouched. After deletion, scan once for orphaned titles.
     */
    fun deleteArtefact(clip: LocalClip, what: ClipArtefact) {
        val dir = File(getApplication<Application>().filesDir, "clips")
        if (clip.audio?.name == _ui.value.playing) stopPlayback()
        dir.listFiles()
            ?.filter { ClipArtefact.baseOf(it.name) == clip.base && ClipArtefact.of(it.name) == what }
            ?.forEach { it.delete() }
        refreshClips()
    }

    /** Base name format like `20260730155017`; returns null if parsing fails (imported external files may not follow this pattern). */
    private fun parseRecordedAt(stem: String): Date? {
        if (stem.length != 14 || stem.any { !it.isDigit() }) return null
        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(stem)
        }.getOrNull()
    }

    /** Abort any ongoing retrieval. The client also notifies the device to stop and flushes remaining packets. */
    /** Request navigation to a specific sub‑page in Settings (used together with switching the tab to Settings). */
    fun openSettingsAt(target: SettingsTarget) = set { it.copy(settingsTarget = target) }

    fun settingsTargetHandled() = set { it.copy(settingsTarget = null) }

    fun cancelFetch() {
        fetchJob?.cancel()
        fetchJob = null
        set { it.copy(busy = null, progress = null, downloadingName = null) }
    }

    /** Delete a recording on the device (0x91). Refresh the list after deletion. */
    fun deleteDeviceFile(file: DnoteBleClient.DeviceFile) {
        val c = client ?: return
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.encryption_progress_working), error = null) }
            try {
                withContext(Dispatchers.IO) { c.deleteFile(file.name) }
                set { it.copy(busy = null) }
                loadFiles()
            } catch (e: Exception) {
                set { it.copy(busy = null, error = failed(R.string.device_files_operation_failed, e)) }
            }
        }
    }

    /**
     * Play a local audio file. **If the same file is already loaded, don’t prepare again**, just resume playback — the player stays alive,
     * pausing or finishing leaves it at the same position (finishing doesn’t release, it resets to 0 and shows as paused).
     */
    fun play(f: File) {
        // Recreate the player only when switching to a different file; the same file uses resume (preserves position and saves a prepare call)
        if (player != null && _ui.value.playing == f.name) { resume(); return }
        stopPlayback()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnSeekCompleteListener { seeking = false }
                setOnCompletionListener {
                    // No release: returns to 0 and stays stopped, button changes back to “play”, tapping again starts from the beginning
                    runCatching { it.seekTo(0) }
                    abandonAudioFocus()
                    set { u -> u.copy(playPaused = true, playPositionMs = 0) }
                }
                prepare()
            }
            player = mp
            set { it.copy(playing = f.name, playPaused = false, playPositionMs = 0, playDurationMs = mp.duration) }
            if (!requestAudioFocus()) {   // If we can’t acquire focus, don’t force audio out; the UI also shouldn’t falsely report playback
                set { it.copy(playPaused = true) }
                return
            }
            mp.start()
            // Poll playback position for the progress bar. **Liveness is determined by `player != null` rather than “producing sound”** —
            // The player remains during pause, so polling must not be casually stopped, otherwise the progress bar won’t move after resume.
            progressJob = viewModelScope.launch {
                while (player != null) {
                    val p = player ?: break
                    // seek not completed / dragging: this tick reports the old position, writing it back causes a bounce
                    if (!seeking && !_ui.value.playPaused) {
                        val pos = runCatching { p.currentPosition }.getOrDefault(0)
                        set { it.copy(playPositionMs = pos) }
                    }
                    kotlinx.coroutines.delay(200)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            set { it.copy(error = failed(R.string.device_files_operation_failed, e)) }
        }
    }

    /** Pause (keep the player alive, retain position). Return focus — holding MEDIA focus while paused is abusive. */
    fun pause() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
        abandonAudioFocus()
        set { it.copy(playPaused = true) }
    }

    /** Resume playback. If stopped at the end (including after finishing or dragging to the end without pressing play), start from the beginning — tolerance 0.15 s. */
    fun resume() {
        val p = player ?: return
        if (!requestAudioFocus()) return
        val dur = _ui.value.playDurationMs
        if (dur > 0 && _ui.value.playPositionMs >= dur - 150) {
            seeking = true
            runCatching { p.seekTo(0) }
            set { it.copy(playPositionMs = 0) }
        }
        runCatching { p.start() }
        set { it.copy(playPaused = false) }
    }

    /** Play/pause toggle — the sole entry point for the UI button. */
    fun togglePlay(f: File) {
        if (player != null && _ui.value.playing == f.name && !_ui.value.playPaused) pause() else play(f)
    }

    /** Drag‑seek (P0 #6). While seek is pending, use [seeking] to block poll writes and eliminate progress‑bar bounce. */
    fun seekTo(ms: Int) {
        val p = player ?: return
        val target = ms.coerceIn(0, _ui.value.playDurationMs)
        seeking = true
        set { it.copy(playPositionMs = target) }
        runCatching { p.seekTo(target) }.onFailure { seeking = false }
    }

    /**
     * User holds the progress bar: during dragging the audio is paused — the bar freezes under the finger and the sound must freeze too,
     * otherwise the two sides get out of sync.
     */
    fun beginScrub() {
        val p = player ?: return
        resumeAfterScrub = !_ui.value.playPaused
        if (resumeAfterScrub) {
            runCatching { if (p.isPlaying) p.pause() }
            set { it.copy(playPaused = true) }
        }
    }

    /** Release: jump to the target position, **do not resume if it was originally paused**. */
    fun endScrub(ms: Int) {
        val shouldResume = resumeAfterScrub
        resumeAfterScrub = false
        seekTo(ms)
        if (shouldResume) {
            val p = player ?: return
            if (!requestAudioFocus()) return
            // Intentionally avoid calling resume(): releasing the finger at the very end should leave playback stopped there, not restart from the beginning
            runCatching { p.start() }
            set { it.copy(playPaused = false) }
        }
    }

    fun stopPlayback() {
        progressJob?.cancel(); progressJob = null
        runCatching { player?.stop(); player?.release() }
        player = null
        seeking = false
        resumeAfterScrub = false
        abandonAudioFocus()
        set { it.copy(playing = null, playPaused = false, playPositionMs = 0, playDurationMs = 0) }
    }

    /** Request audio focus. Starting with API 26 use AudioFocusRequest; earlier versions only have the deprecated three‑parameter overload. */
    private fun requestAudioFocus(): Boolean {
        val am = audioManager
        val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = (audioFocusRequest as? android.media.AudioFocusRequest)
                ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            // The recorder captures speech; label it as SPEECH so the system treats it as voice input
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                    .also { audioFocusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = audioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            (audioFocusRequest as? android.media.AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
    }

    fun clearError() = set { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        adapterStateReceiver?.let { r ->
            runCatching { getApplication<Application>().unregisterReceiver(r) }
        }
        adapterStateReceiver = null
        client?.close()
    }
}
