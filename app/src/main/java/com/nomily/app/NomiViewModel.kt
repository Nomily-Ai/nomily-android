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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
        private val DOCUMENT_FORMATS = setOf("txt", "md", "pdf", "png")
        private const val CONNECT_ATTEMPTS = 3

        /** When tapping the connect capsule for a known device, give up after not finding it for up to 10 seconds. */
        private const val KNOWN_CONNECT_TIMEOUT_MS = 10_000L
        /** Pauses between reconnect attempts after an unexpected drop: three attempts, 1 s then 3 s apart, as on iOS. */
        private val DROP_RECONNECT_BACKOFF_MS = listOf(1_000L, 3_000L)

        /** 0x80 polling interval. 30 seconds — enough for battery/space to be reliable without wasting BLE. */
        private const val DEVICE_POLL_INTERVAL_MS = 30_000L

        /** Upper bound for waiting for the first frame of a real‑time session. */
        private const val LIVE_FIRST_FRAME_TIMEOUT_MS = 10_000L

        /**
         * How long a live session stays open after a BLE drop, waiting for the card to come back — as iOS: a
         * reconnect takes 2–5 s, 20 s covers one failed attempt plus its retry without looking like a hang.
         */
        private const val LIVE_STREAM_RECOVERY_MS = 20_000L
        private const val LIVE_STREAM_RECOVERY_POLL_MS = 500L

        /** Interval for reading 0x56 during recording. 1 second — timing must keep up with the second ticks. */
        private const val REC_ELAPSED_POLL_MS = 1_000L

        /** Probe recording length for passphrase verification — 2.5 seconds: enough to generate an encrypted fragment, and BLE download is fast. */
        private const val PROBE_RECORD_MS = 2_500L
        /** How long to wait before reconnecting after an ASR disconnection. */
        private const val LIVE_RECONNECT_DELAY_MS = 1_500L
        private const val LIVE_MAX_RECONNECTS = 5
        /** Partial lines are translated at most this often, always the latest — a debounce would never fire while someone talks. */
        private const val PARTIAL_TRANSLATE_MIN_INTERVAL_MS = 2_500L
        /** How long stopping waits for queued line translations before saving without them. */
        private const val TRANSLATION_FLUSH_TIMEOUT_MS = 8_000L
        private val liveLineIds = java.util.concurrent.atomic.AtomicLong()

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

    data class RotatePlan(val passphrase: String, val recordingsAtRisk: Int)

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
        /** The connected card is not paired with this app: ask before pairing ("Pair this Nomi?"). */
        val pendingPairing: Boolean = false,
        /** A connect the user started failed; shown as an alert with Retry. */
        val connectFailure: String? = null,
        /** The device panel is re-reading info, switches and pairing state. */
        val refreshingDevice: Boolean = false,
        /**
         * A transfer finished and the device copy is gone: show the library, opening this clip base when set
         * (an empty string means "library only", after a fast transfer).
         */
        val showInLibrary: String? = null,
        /** Device files waiting for the BLE link, in order. */
        val queuedDownloads: List<String> = emptyList(),
        /** The library search box has text; multi-select is off while results shift under the user's typing. */
        val librarySearching: Boolean = false,
        /** Phone Bluetooth adapter is on; with no saved device the pill says so instead of offering "Add Device". */
        val bluetoothOn: Boolean = true,
        /** Audio file name → why it couldn't be loaded for playback; the player shows this instead of a dead play button. */
        val playError: Pair<String, String>? = null,
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
        /** Bases of the clips being transcribed — drives the per-clip progress row and its Cancel button. */
        val transcribing: Set<String> = emptySet(),
        /** Clip base → last error, for transcriptions the queue gave up on (shown on the device file row). */
        val transcribeFailures: Map<String, String> = emptyMap(),
        /** Device file name → why its last download failed, shown on that row (cleared when it is fetched again). */
        val fetchFailures: Map<String, String> = emptyMap(),
        /** `"{base}:{artefact}"` keys of translations in flight, so the transcript and summary sections spin independently. */
        val translating: Set<String> = emptySet(),
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
        /** Playback speed (detail page's 0.75–2× menu); applied whenever the player starts. */
        val playRate: Float = 1f,
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
        /** Rotation waiting for confirmation: the new passphrase and how many device recordings the old key would orphan. */
        val rotatePlan: RotatePlan? = null,
        /** Automatically reconnecting — distinct from user‑initiated connection, with different UI wording. */
        val reconnecting: Boolean = false,
        /** Live transcription session. */
        val live: Live = Live(),
    ) {
        /** A Bluetooth download or a fast transfer (incl. waiting for a join retry with the AP on) is in progress. */
        val transferActive: Boolean
            get() = downloadingName != null ||
                fastTransfer?.stage?.let { it != FtStage.DONE && it != FtStage.FAILED } == true
    }

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
        /** Bytes received / total while [repairState] is DOWNLOADING. */
        val repairProgress: Pair<Long, Long>? = null,
        /** Why the replacement failed, while [repairState] is FAILED. */
        val repairError: String? = null,
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
        /**
         * Soft warning that the translation path failed or is unavailable; the transcript is unaffected,
         * so it is a gray banner rather than [error].
         */
        val translationNotice: String? = null,
        /**
         * No speech service could be opened: the session keeps recording and streaming, and the saved clip is
         * transcribed afterwards. A soft notice, not [error] — nothing is broken from the user's side.
         */
        val serviceNotice: String? = null,
        /** The automatic save after stopping failed and its audio is still held: the page offers "Save to Library" to retry it. */
        val canSaveToLibrary: Boolean = false,
        /**
         * The BLE link dropped and the session is held open while the card reconnects (the card keeps recording on its
         * own). Cleared when the stream is re-attached, or when the wait runs out and the session is saved as it stands.
         */
        val linkRecovering: Boolean = false,
        /** Recent loudness of the stream, 0..1, newest last — the level bars. Empty when nothing is decoded locally. */
        val audioLevels: List<Float> = emptyList(),
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
    enum class RepairState { IDLE, DOWNLOADING, FAILED, SUCCEEDED, NEEDS_MANUAL_TRANSFER }

    /** A segment in the transcription stream: original text + (translation when live translation is enabled). */
    /**
     * @param id Stable identity, so a translation that returns after more lines arrived lands on the right one.
     * @param at Seconds of audio captured when the line was finalized — the saved transcript's timestamps.
     */
    data class LiveLine(
        val text: String,
        val translation: String = "",
        val at: Double = 0.0,
        val id: Long = liveLineIds.incrementAndGet(),
    )

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
        /** The BLE link dropped mid-session and the stream was re-attached: the phone's copy misses the gap. */
        val linkDropped: Boolean = false,
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
        /** Where it came from (manifest); no entry = imported. */
        val source: com.nomily.app.core.clips.ClipManifest.Source =
            com.nomily.app.core.clips.ClipManifest.Source(com.nomily.app.core.clips.ClipManifest.Source.Kind.IMPORTED),
        /** Not opened since it arrived — a red dot in the list. */
        val unread: Boolean = false,
    ) {
        fun seen() = LocalClip(
            base, audio, transcript, transcriptJson, summary, transcriptTranslation, summaryTranslation,
            durationMs, recordedAt, title, source, unread = false,
        )
        val name: String get() = audio?.name ?: base
        val hasAudio: Boolean get() = audio != null
        val sizeBytes: Long get() = audio?.length() ?: 0L
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val scanner = DeviceScanner(app)
    private val pipeline = ClipPipeline(app)
    val library = com.nomily.app.data.LibraryStore(app) { localized() }
    /** Background tasks; declared before `init`, which registers the runners and resumes the ledger. */
    val jobs = com.nomily.app.jobs.JobQueue(File(app.filesDir, "jobs.json"), viewModelScope)
    val exports = com.nomily.app.audio.ExportCenter(app.filesDir)
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
    private fun str(@StringRes id: Int, vararg args: Any): String = localized().getString(id, *args)

    /**
     * Application context in the **in-app language**. Only the Activity's context is wrapped by [AppLocale.wrap];
     * the bare application context follows the system language, so messages built here would come out in the wrong
     * language whenever the two differ. Rebuilt when the language setting changes.
     */
    private var localizedCtx: Pair<String?, android.content.Context>? = null

    fun localized(): android.content.Context {
        val tag = configStore.config.value.appLanguage
        localizedCtx?.takeIf { it.first == tag }?.let { return it.second }
        return com.nomily.app.data.AppLocale.wrap(getApplication()).also { localizedCtx = tag to it }
    }

    /** "Header: Reason" (header is localized, reason comes from the exception). */
    private fun failed(@StringRes titleId: Int, e: Throwable): String = "${str(titleId)}: ${describe(e)}"

    /** Error text for the user: errors with a localized meaning use it, the rest keep their message. */
    private fun describe(e: Throwable): String = when (e) {
        is DnoteBleClient.DeviceRecordingException -> str(R.string.device_unavailable_while_recording)
        else -> e.message ?: e.javaClass.simpleName
    }

    private var elapsedJob: Job? = null
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

    private fun set(f: (Ui) -> Ui) = _ui.update(f)

    /**
     * Runtime permissions an action needs before it can run. Only an Activity can ask, so the gate is
     * published here, `MainActivity` launches the request and answers through [onPermissionResult].
     */
    data class PermissionRequest(val permissions: List<String>, val action: Action) {
        enum class Action { LIVE, FAST_TRANSFER, FIND_NOTIFY, FIND_LOCATION }
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
            PermissionRequest.Action.FIND_NOTIFY, PermissionRequest.Action.FIND_LOCATION ->
                com.nomily.app.ble.CardFinder.refreshPermissions()
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

    /** Find-card page: turning the alert on asks for the notification grant (API 33+) while the user is looking at the switch. */
    fun setFinderNotify(on: Boolean) {
        com.nomily.app.ble.CardFinder.setNotifyOnDisconnect(on)
        if (on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val needed = notGranted(listOf(android.Manifest.permission.POST_NOTIFICATIONS))
            if (needed.isNotEmpty()) _permissionRequest.value = PermissionRequest(needed, PermissionRequest.Action.FIND_NOTIFY)
        }
    }

    /** Find-card page: the user asked to record where the card was left, so ask for location now. */
    fun requestFinderLocation() {
        _permissionRequest.value = PermissionRequest(
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionRequest.Action.FIND_LOCATION,
        )
    }

    init {
        com.nomily.app.ble.CardFinder.init(app)
        viewModelScope.launch(Dispatchers.IO) { library.purgeExpired() }
        refreshClips()
        registerJobRunners()
        set { it.copy(autoReconnect = configStore.config.value.autoReconnectEnabled) }
        maybeAutoReconnect()
        watchRecordingElapsed()
        registerAdapterStateReceiver()
        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        set { it.copy(bluetoothOn = btManager?.adapter?.isEnabled == true) }
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
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                set { it.copy(bluetoothOn = state == BluetoothAdapter.STATE_ON) }
                when (state) {
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
        com.nomily.app.ble.CardFinder.onBluetoothOff(_ui.value.connectedName)
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
    fun maybeAutoReconnect(afterDrop: Boolean = false) {
        if (!configStore.config.value.autoReconnectEnabled) return
        if (client != null) return
        val (address, rec) = configStore.config.value.mostRecentDevice() ?: return

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            set { it.copy(reconnecting = true, error = null) }
            // After a drop the card may be mid power-cycle and not advertising yet: retry on a short, **bounded**
            // schedule, then stop rather than scan forever and drain the battery (the pill goes grey; a tap retries).
            // At launch or when Bluetooth comes back, one attempt.
            val pauses = if (afterDrop) DROP_RECONNECT_BACKOFF_MS else emptyList()
            try {
                for (attempt in 0..pauses.size) {
                    if (client != null) break
                    // Match by device_sid (survives MAC randomisation); records saved before the SID existed fall back to the address.
                    val target = kotlinx.coroutines.withTimeoutOrNull(KNOWN_CONNECT_TIMEOUT_MS) {
                        scanner.scan()
                            .mapNotNull { list ->
                                list.firstOrNull { d ->
                                    rec.deviceSid?.let { d.adv.deviceSidHex == it } ?: (d.address == address)
                                }
                            }
                            .first()
                    }
                    if (target != null) {
                        Log.i(TAG, "Auto-reconnect found ${target.name} (attempt ${attempt + 1})")
                        set { it.copy(reconnecting = false) }
                        connect(target, manual = false)
                        return@launch
                    }
                    pauses.getOrNull(attempt)?.let { kotlinx.coroutines.delay(it) }
                }
                Log.i(TAG, "Auto-reconnect gave up on ${rec.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Auto-reconnect abandoned: ${e.message}")
            }
            set { it.copy(reconnecting = false) }
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
                // Not advertising within the window almost always means the card is asleep: say how to wake it, offer Retry.
                lastManualTarget = null
                lastManualAddress = address
                set { it.copy(connectFailure = str(R.string.scanner_wake_and_retry)) }
                return@launch
            }
            connect(target)
        }
    }

    /** What the connect-failure alert's Retry reconnects to: the scanned device, else the known address the pill tried. */
    private var lastManualTarget: DeviceScanner.Discovered? = null
    private var lastManualAddress: String? = null

    fun retryConnect() {
        set { it.copy(connectFailure = null) }
        lastManualTarget?.let { connect(it); return }
        lastManualAddress?.let { connectKnown(it) }
    }

    fun dismissConnectFailure() = set { it.copy(connectFailure = null) }

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

    /** Change the colour theme ([AppTheme] tag). Written through at once like iOS `applyTheme`: one tap repaints the whole app. */
    fun setAppTheme(tag: String) {
        configStore.update { it.copy(appTheme = tag) }
        viewModelScope.launch { configStore.saveNow() }
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

    fun connect(d: DeviceScanner.Discovered, manual: Boolean = true) {
        stopScan()
        if (manual) lastManualTarget = d
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
                com.nomily.app.ble.CardFinder.register(d.address, d.name)

                // v1.47+: without a bond, the device refuses recording. Pairing is never silent: an unbound card raises the
                // "Pair this Nomi?" prompt, and the user's confirmation runs [pairDevice].
                val bound = runCatching { c.queryBondState().bound }
                    .onFailure { Log.w(TAG, "Bond state query after connect failed: ${it.message}") }
                    .getOrNull()

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
                        bound = bound,
                        pendingPairing = bound == false,
                        encryptionOn = enc,
                        hasLocalKey = hasKey,
                        busy = null,
                    )
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
                // A connect timeout almost always means the card is asleep and not advertising: say how to wake it.
                val timedOut = e is kotlinx.coroutines.TimeoutCancellationException
                // The manual alert is already titled "Connection failed"; its body carries only the reason.
                val msg = when {
                    timedOut -> str(R.string.scanner_wake_and_retry)
                    manual -> e.message ?: e.javaClass.simpleName
                    else -> failed(R.string.scanner_connection_failed, e)
                }
                set { if (manual) it.copy(busy = null, connectFailure = msg) else it.copy(busy = null, error = msg) }
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
        if (client != null) com.nomily.app.ble.CardFinder.onDisconnected(unexpected = false, cardName = null)
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
        com.nomily.app.ble.CardFinder.onDisconnected(unexpected = true, cardName = _ui.value.connectedName)
        pollJob?.cancel(); pollJob = null
        // Release the dead GATT; marking it closed also stops it reporting again.
        client?.let { old ->
            old.onUnexpectedDisconnect = null
            old.onRecordingStateChanged = null
            runCatching { old.close() }
        }
        client = null
        set {
            Ui(
                clips = it.clips,
                autoReconnect = it.autoReconnect,
                error = if (it.autoReconnect) null else str(R.string.device_disconnected),
                // A running live session outlives the drop: the stream task waits for the reconnect and re-attaches.
                live = if (it.live.running) it.live else Live(),
            )
        }
        maybeAutoReconnect(afterDrop = true)
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

    /** Re-read 0x80 + 0x81 + pairing state — the panel's “refresh”, also run each time the panel opens. */
    fun refreshDeviceState() {
        val c = client ?: return
        if (_ui.value.refreshingDevice) return
        set { it.copy(refreshingDevice = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val j = c.getDeviceInfo()
                    val sw = c.getSwitchInfo()
                    val bound = runCatching { c.queryBondState().bound }.getOrNull()
                    if (c === client) set {
                        it.copy(
                            info = com.nomily.app.ble.DeviceInfo(j),
                            switches = com.nomily.app.ble.SwitchInfo(sw),
                            bound = bound ?: it.bound,
                        )
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to refresh device state: ${e.message}")
                set { it.copy(error = failed(R.string.device_couldnt_update, e)) }
            }
            set { it.copy(refreshingDevice = false) }
        }
    }

    /**
     * Common skeleton for panel actions: set busy → send command → read back 0x81 → clear busy; failures go to the error branch.
     * `after` is for actions that need to “do and then disconnect” (shutdown / unbond).
     */
    private fun deviceOp(
        /** Busy-pill text; null for quick settings (switches, sliders) that apply silently — the control itself shows the change. */
        @StringRes labelId: Int?,
        after: (() -> Unit)? = null,
        op: suspend (DnoteBleClient) -> Unit,
    ) {
        val c = client ?: return
        viewModelScope.launch {
            set { it.copy(busy = labelId?.let(::str) ?: it.busy, error = null) }
            try {
                withContext(Dispatchers.IO) { op(c) }
                if (labelId != null) set { it.copy(busy = null) }
                if (after != null) after() else refreshSwitches()
            } catch (e: Exception) {
                Log.e(TAG, "Device command failed", e)
                set { it.copy(busy = if (labelId != null) null else it.busy, error = failed(R.string.device_couldnt_update, e)) }
            }
        }
    }

    fun setSwitch(name: String, on: Boolean) = deviceOp(null) { it.setSwitch(name, on) }

    fun setMicGain(level: Int) = deviceOp(null) { it.setMicGain(level) }

    fun setNrLevel(level: Int) = deviceOp(null) { it.setNrLevel(level) }

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
        // Firmware v1.50 turns encryption on when bonding, so the first thing after pairing is the passphrase:
        // anything recorded before it is set could never be decrypted on this phone (rv7my090). The user may dismiss it
        // and set it later — recording stays disabled until then, and the library banner leads back to this step.
        val enc = runCatching { c.getEncryptionState() }.getOrNull()
        set {
            it.copy(
                bound = true,
                pendingPairing = false,
                encryptionOn = enc ?: it.encryptionOn,
                needPassphrase = it.needPassphrase || (enc == true && !it.hasLocalKey),
                passphraseMode = if (enc == true && !it.hasLocalKey) PassphraseMode.SET else it.passphraseMode,
                passphraseFirstBind = it.passphraseFirstBind || (enc == true && !it.hasLocalKey),
            )
        }
    }

    fun dismissPairing() = set { it.copy(pendingPairing = false) }

    /** Unbond: the device returns to “unbonded”; after v1.47 it will reject recording until re‑bonded. */
    fun unbindDevice() = deviceOp(R.string.pairing_unpairing) {
        it.unbindDevice()
        set { ui -> ui.copy(bound = false) }
    }

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
        // An unpaired card refuses to record (v1.47+): offer pairing instead of sending a command that will fail.
        if (!recording && _ui.value.bound == false) { set { it.copy(pendingPairing = true) }; return }
        recordJob = viewModelScope.launch {
            set {
                it.copy(
                    busy = str(if (recording) R.string.fast_transfer_stage_stopping else R.string.recording_starting),
                    error = null,
                )
            }
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
        if (clip.audio == null) return
        if (jobs.jobs.value.any { it.id == "transcribe:${clip.base}" && it.finishedAt == null }) return
        jobs.enqueue(com.nomily.app.jobs.WorkJob.transcribe(clip.base, locales, enforceMinDuration))
    }

    fun cancelTranscribe(clip: LocalClip) = jobs.cancel("transcribe:${clip.base}")

    /**
     * The foreground service only runs while there is work **and** background keep-alive is on. Work is a runnable
     * job, or a Bluetooth download / fast transfer in progress — those die with the process just the same.
     */
    private fun syncWorkService(hasWork: Boolean = jobs.jobs.value.any { it.isRunnable }) {
        if ((hasWork || _ui.value.transferActive) && configStore.config.value.backgroundKeepAliveEnabled) {
            com.nomily.app.jobs.WorkService.start(getApplication())
        } else {
            com.nomily.app.jobs.WorkService.stop(getApplication())
        }
    }

    fun setBackgroundKeepAlive(on: Boolean) {
        updateConfig { it.copy(backgroundKeepAliveEnabled = on) }
        syncWorkService()
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

    /** The app went to the background mid fast transfer with keep-alive off: the run was cancelled for that (iOS). */
    private var ftInterruptedByBackground = false

    /**
     * The app moved to the background while the fast-transfer panel is open (iOS `cancelForBackground`).
     *
     * With background keep-alive on the foreground service carries the transfer and nothing happens. Off, nothing
     * protects the process, so the run is cancelled now — while the card is still reachable, the cleanup closes its
     * AP — the unfinished files are marked failed, and the panel offers Retry on return.
     */
    fun onAppBackgrounded() {
        if (configStore.config.value.backgroundKeepAliveEnabled) return
        val ft = _ui.value.fastTransfer ?: return
        if (ft.stage == FtStage.DONE || ft.stage == FtStage.FAILED || fetchJob?.isActive != true) return
        Log.i(TAG, "App backgrounded during fast transfer (${ft.stage}) without keep-alive — interrupting")
        ftInterruptedByBackground = true
        fetchJob?.cancel()
    }

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
        ftInterruptedByBackground = false
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
                        awaitFastTransferRetry(failed(R.string.device_files_operation_failed, e))
                        // Join failures are usually because the device reports wifiap=1 but isn’t actually broadcasting. Reusing the same dead AP yields identical failures; before retrying, have the device turn the hotspot off and on again.
                        cycleAp(c, ssid, psk)
                        continue
                    }

                    set { it.copy(busy = str(R.string.fast_transfer_stage_connecting)) }
                    ftStage(FtStage.CONNECTING)
                    val tcpError = connectTcpForWindow(wifi)
                    if (tcpError == null) break

                    // If the hotspot is joined but TCP is unreachable for 60 s: first unbind that network; on retry, request the hotspot network again, matching the behavior of unbinding then re‑requesting.
                    Log.w(TAG, "TCP unreachable after Wi-Fi join: ${tcpError.message}")
                    wifi.close()
                    joiner.release()
                    awaitFastTransferRetry(str(R.string.fast_transfer_tcp_unreachable))
                    // Join succeeds but TCP cannot connect for a minute, indicating the reported `wifiap=1` does not match the actual broadcast.
                    cycleAp(c, ssid, psk)
                }

                // List again over Wi-Fi, as iOS does: recordings made after the tap are included, and anything already
                // in the library is skipped. If the listing fails, the list taken over Bluetooth still stands.
                val files = listForFastTransfer(wifi, files)
                set { u -> u.copy(fastTransfer = u.fastTransfer?.copy(files = files.map { f -> FtFile(f.name, f.size) })) }

                var done = 0
                val saved = mutableListOf<String>()
                ftStage(FtStage.DOWNLOADING)
                // A failing file (timeout, undecryptable) is marked on its own row and the rest carry on, as on iOS.
                for (f in files) {
                    set {
                        it.copy(busy = "${str(R.string.fast_transfer_stage_downloading)} ${done + 1}/${files.size}")
                    }
                    ftFile(f.name) { it.copy(status = FtFileStatus.DOWNLOADING) }
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            // Include the size declared in the list: short reads or corruption must raise an error, not fall through to the "delete from device after transfer" branch below.
                            wifi.download(f.name, f.size) { got, total ->
                                set { ui -> ui.copy(progress = if (total > 0) got to total else null) }
                                ftFile(f.name) { it.copy(got = got) }
                            }
                        }
                        // Downloaded bytes are not a completed transfer until the encrypted payload has been decoded and
                        // durably written; an unreadable file keeps its original on the device so it can be retried.
                        val res = try {
                            withContext(Dispatchers.IO) { pipeline.ingestRaw(bytes, f.name, key) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Keeping ${f.name} on device — not playable: ${e.message}")
                            ftFile(f.name) { it.copy(status = FtFileStatus.FAILED, error = downloadFailureReason(f.name, key, e)) }
                            continue
                        }
                        library.manifest.recordDownload(
                            f.name, bytes.size.toLong(), "wifi", sn, _ui.value.connectedName, res.oggFile.name,
                        )
                        ftFile(f.name) { it.copy(status = FtFileStatus.DONE, got = it.size) }
                        saved += res.oggFile.name.substringBeforeLast('.')
                        // Same as recorder: after successful save, delete from the device over the same TCP channel.
                        if (configStore.config.value.autoDeleteAfterTransfer) {
                            runCatching { withContext(Dispatchers.IO) { wifi.deleteFile(f.name) } }
                                .onFailure { Log.w(TAG, "Failed to delete ${f.name} after fast transfer: ${it.message}") }
                        }
                        done++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Fast transfer of ${f.name} failed: ${e.message}")
                        ftFile(f.name) { it.copy(status = FtFileStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
                    }
                }
                set { it.copy(busy = null, progress = null) }
                // Finishing the loop is not success: summarise failed rows instead of a green "Done" over them.
                if (done < files.size) {
                    val msg = str(R.string.device_files_operation_failed)
                    set { it.copy(fastTransfer = it.fastTransfer?.copy(stage = FtStage.FAILED, error = msg)) }
                } else {
                    ftStage(FtStage.DONE)
                }
                refreshClips()
                // Auto-transcribe after download applies to fast transfer too (minimum-duration gate on).
                if (configStore.config.value.autoTranscribeAfterDownload && saved.isNotEmpty()) {
                    clipsJob?.join()
                    _ui.value.clips.filter { it.base in saved }.forEach { transcribe(it, enforceMinDuration = true) }
                }
                Log.i(TAG, "Fast transfer completed $done/${files.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (ftInterruptedByBackground) {
                    val msg = str(R.string.fast_transfer_background_interrupted)
                    set { ui ->
                        ui.copy(
                            busy = null,
                            progress = null,
                            fastTransfer = ui.fastTransfer?.let { ft ->
                                ft.copy(
                                    stage = FtStage.FAILED,
                                    error = msg,
                                    files = ft.files.map { f ->
                                        if (f.status == FtFileStatus.PENDING || f.status == FtFileStatus.DOWNLOADING) {
                                            f.copy(status = FtFileStatus.FAILED, error = msg)
                                        } else {
                                            f
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
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

    /**
     * Why a downloaded recording could not be saved, in words that say what to do — same wording for the
     * Bluetooth and Wi‑Fi paths, as on iOS.
     */
    private fun downloadFailureReason(name: String, key: ByteArray?, e: Exception): String = when {
        e is com.nomily.app.core.crypto.DecryptError ->
            str(R.string.encryption_download_wrong_key, name, e.message ?: e.kind.toString())
        // ClipPipeline.ingestRaw: encrypted envelope but no key on this phone.
        key == null && e is IllegalArgumentException -> str(R.string.encryption_download_key_missing, name)
        e is java.io.IOException -> failed(R.string.device_files_operation_failed, e)
        else -> str(R.string.fast_transfer_decode_failed, name)
    }

    /** Keep the device AP on, remain in the failure state awaiting user retry or cancellation. */
    private suspend fun awaitFastTransferRetry(message: String) {
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
    private suspend fun listForFastTransfer(
        wifi: com.nomily.app.wifi.WifiClient,
        fallback: List<DnoteBleClient.DeviceFile>,
    ): List<DnoteBleClient.DeviceFile> {
        val remote = runCatching { withContext(Dispatchers.IO) { wifi.listFiles() } }
            .onFailure { Log.w(TAG, "Wi-Fi listing failed, using the Bluetooth list: ${it.message}") }
            .getOrNull() ?: return fallback
        val inLibrary = _ui.value.clips.map { it.base }.toSet()
        return remote
            .filter { it.name.substringBeforeLast('.') !in inLibrary }
            .map { DnoteBleClient.DeviceFile(it.index, it.name, it.size) }
    }

    fun dismissFastTransfer(cancel: Boolean) {
        if (cancel) fetchJob?.cancel()
        // Closing a finished transfer goes to the library, where the clips now are.
        val done = _ui.value.fastTransfer?.stage == FtStage.DONE
        set { it.copy(fastTransfer = null, showInLibrary = if (done) "" else it.showInLibrary) }
    }

    /**
     * "Automatically delete device files after transfer" (settings page `rec_settings.delete_after_transfer`, enabled by default).
     *
     * ⚠️ This toggle **was previously not wired at all**: the value is read to render the switch, but nowhere is it used to actually delete.
     * The UI text says “removed from device after retrieval”, leading users to think device storage is freed, which is not the case.
     *
     * Deletion failure does not count as retrieval failure: the file is safely stored locally, but the original remains on the device; a simple note suffices ("saved locally, but the device kept the original").
     */
    private suspend fun maybeDeleteFromDevice(c: DnoteBleClient, deviceFileName: String): Boolean {
        if (!configStore.config.value.autoDeleteAfterTransfer) return false
        return try {
            withContext(Dispatchers.IO) { c.deleteFile(deviceFileName) }
            Log.i(TAG, "Deleted $deviceFileName from device after retrieval")
            loadFiles()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete $deviceFileName after retrieval", e)
            set { it.copy(error = str(R.string.device_files_kept_on_device, deviceFileName, describe(e))) }
            false
        }
    }

    fun showInLibraryHandled() = set { it.copy(showInLibrary = null) }

    fun setLibrarySearching(on: Boolean) = set { it.copy(librarySearching = on) }

    /** Switch to the library and open this clip's detail page; ignored when the clip no longer exists. */
    fun openInLibrary(base: String) {
        if (_ui.value.clips.any { it.base == base }) set { it.copy(showInLibrary = base) }
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

    /**
     * Step 1 of a rotation: count what is still on the device. Recordings made under the old key become unreadable
     * the moment the new key lands, so the user sees that number — and can download them first — before anything is written.
     */
    private fun prepareRotate(passphrase: String) {
        val c = client ?: run {
            set { it.copy(error = str(R.string.encryption_error_device_not_connected)) }
            return
        }
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.encryption_progress_checking_device), error = null) }
            val atRisk = runCatching { withContext(Dispatchers.IO) { c.getFileList() } }
                .getOrNull()?.count { it.name.isNotEmpty() } ?: 0
            set { it.copy(busy = null, rotatePlan = RotatePlan(passphrase, atRisk)) }
        }
    }

    /** Answer to the rotation confirmation: rotate now, or go back to the passphrase sheet. */
    fun resolveRotate(confirm: Boolean) {
        val plan = _ui.value.rotatePlan ?: return
        set { it.copy(rotatePlan = null) }
        if (confirm) submitPassphrase(plan.passphrase, rotateConfirmed = true)
    }

    fun submitPassphrase(passphrase: String, rotateConfirmed: Boolean = false) {
        val sn = _ui.value.deviceSn ?: return
        val rotate = _ui.value.passphraseMode == PassphraseMode.ROTATE
        if (rotate && !rotateConfirmed) {
            prepareRotate(passphrase)
            return
        }
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
        // Accepting the offer *is* the confirmation (the device holds no recordings to lose): write the key straight away.
        submitPassphrase(pending, rotateConfirmed = true)
    }

    /** User has acknowledged “data will be sent to a third party” — record once and don’t ask again. */
    fun consentCloudEgress() = updateConfig { it.copy(cloudEgressConsented = true) }

    /** Forget a paired device (delete from Settings → “Known Devices”). */
    fun forgetDevice(id: String) = updateConfig { it.copy(devices = it.devices - id) }

    /** Remove a saved device from the pill: drop the link first when it is the connected one. Recordings on the card stay. */
    fun removeKnownDevice(address: String) {
        if (address == connectedAddress) {
            if (_ui.value.info?.isRecording == true) {
                set { it.copy(error = str(R.string.device_unavailable_while_recording)) }
                return
            }
            disconnectNow()
        }
        forgetDevice(address)
    }

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
            // The trash and the bookkeeping dot-files aren't library content.
            if (f.isDirectory || f.name.startsWith(".")) return@forEach
            val hit = target?.let { ClipArtefact.of(f.name) == it } ?: true
            if (hit && !f.delete()) {
                failed++
                Log.w(TAG, "Failed to delete ${f.name} during cleanup")
            }
        }
        // The manifest tracks downloaded audio; with the audio gone, forget it so the device list offers it again.
        if (target == null || target == ClipArtefact.AUDIO) library.manifest.clear()
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
        // The BLE link carries one transfer at a time: further taps queue up and run in order, each row showing its own state.
        if (fetchJob?.isActive == true) {
            if (_ui.value.downloadingName != file.name && downloadQueue.none { it.name == file.name }) {
                downloadQueue += file
                publishDownloadQueue()
            }
            return
        }
        fetchJob = viewModelScope.launch {
            try {
                set { it.copy(downloadingName = file.name, fetchFailures = it.fetchFailures - file.name) }
                val res = withContext(Dispatchers.IO) {
                    pipeline.fetch(c, file, sn, key) { stage, got, total ->
                        set { it.copy(busy = stage.label, progress = if (total > 0) got to total else null) }
                    }
                }
                library.manifest.recordDownload(
                    file.name, file.size.toLong(), "ble", sn, _ui.value.connectedName, res.oggFile.name,
                )
                set { it.copy(busy = null, progress = null, downloadingName = null) }
                refreshClips()
                Log.i(TAG, "Retrieved ${res.oggFile.name}: ${res.durationMs / 1000.0}s, encrypted=${res.wasEncrypted}")
                // P3 “Automatically delete device file after transfer”. **Reaching this point guarantees a playable artefact** ——
                // Missing key or decryption failure throws an exception in the pipeline, never reaching this branch,
                // so the issue of “deleting an undecrypted original from the device” cannot occur.
                // The row just left the device list: take the user to the clip (its detail page) in the library.
                if (maybeDeleteFromDevice(c, file.name)) {
                    set { it.copy(showInLibrary = res.oggFile.name.substringBeforeLast(".")) }
                }
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
                // The reason stays on that file's row (like iOS), not in the page-wide error card.
                val reason = downloadFailureReason(file.name, key, e)
                set { it.copy(busy = null, progress = null, downloadingName = null, fetchFailures = it.fetchFailures + (file.name to reason)) }
            } finally {
                // Next queued file (cancelling one row moves on to the next; cancelFetch empties the queue first).
                downloadQueue.removeFirstOrNull()?.let { next ->
                    publishDownloadQueue()
                    fetchJob = null   // this job is ending; without this, fetch() would see it active and re-queue
                    fetch(next)
                }
            }
        }
    }

    private val downloadQueue = mutableListOf<DnoteBleClient.DeviceFile>()

    private fun publishDownloadQueue() = set { it.copy(queuedDownloads = downloadQueue.map { f -> f.name }) }

    /** Take a waiting file out of the download queue. */
    fun cancelQueued(name: String) {
        downloadQueue.removeAll { it.name == name }
        publishDownloadQueue()
    }

    /** Stop only the file transferring now; queued files carry on. */
    fun cancelCurrentDownload() {
        fetchJob?.cancel()
        set { it.copy(busy = null, progress = null, downloadingName = null) }
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
    /** The link dropped mid-session and the stream was re-attached: the saved copy has a gap. */
    private var liveLinkDropped = false
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

    /** `azure` / `remote` / `local` — the provider the open ASR link belongs to (badge and the saved transcript). */
    private var liveActiveProvider = ""
    /**
     * The **kind** of the open link, for dead-marking: `azuretranslate` vs `azure`. A dead translation link (e.g. a
     * target Azure translation rejects by closing at once) must not poison plain Azure transcription, and must not be
     * re-selected on every reconnect — that was a ~1 Hz connect/close loop with no text for the whole session.
     */
    private var liveActiveKind = ""
    private var liveReconnectAttempts = 0
    /** Exact bytes captured this session; [Live.bytes] is batched for the UI and too coarse for timestamps. */
    private var liveCapturedBytes = 0L
    private val liveCapturedSeconds: Double
        get() = (liveCapturedBytes / com.nomily.app.core.audio.OPUS_FRAME_SIZE) * 0.02

    // LLM live translation — used when the session wants a translation but Azure live translation is not available.
    // The queue is written from the ASR socket's callback thread and drained by the pump on the main thread.
    /** Target language name handed to the LLM; null = no LLM translation this session. */
    @Volatile private var liveTranslateTo: String? = null
    private var liveTranslatePump: Job? = null
    private val translateFinals = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    @Volatile private var translatePartial: String? = null
    @Volatile private var translateInFlight: Long? = null
    @Volatile private var lastPartialTranslated = ""
    @Volatile private var lastPartialTranslateAt = 0L

    /**
     * @param assumeIdle Only the branch “unknown state → user clicks start anyway” passes true,
     *   all others are false.
     */
    fun startLive(assumeIdle: Boolean = false) {
        val c = client ?: return
        if (_ui.value.live.running) return
        if (_ui.value.bound == false) { set { it.copy(pendingPairing = true) }; return }
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
        liveDeadProviders.clear()
        liveReconnectAttempts = 0
        liveCapturedBytes = 0
        endLiveTranslation()
        set { it.copy(live = it.live.copy(translationNotice = null)) }
        // A missing speech service must not stop the recording: without one the card still records and streams,
        // and the saved clip is transcribed afterwards.
        val serviceNotice = openLiveAsrOrRecordOnly(cfg)
        liveDidPause = false
        liveLinkDropped = false
        liveAttachedToExisting = false
        preSessionFiles = null
        pendingLiveSave = null
        // Write temporary files while receiving: after the session ends, package into Ogg and store in the library
        liveBufferFile = File(getApplication<Application>().cacheDir, "live-stream.opus.raw").also { f ->
            f.delete()
            liveBuffer = f.outputStream().buffered(1 shl 16)
        }
        set {
            it.copy(
                live = Live(
                    running = true,
                    provider = providerLabel(),
                    status = LiveStatus.CONNECTING,
                    translationNotice = it.live.translationNotice,
                    serviceNotice = serviceNotice,
                ),
            )
        }
        com.nomily.app.LiveSessionService.start(getApplication())

        liveJob = viewModelScope.launch {
            try {
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
                        liveIntentionalClose = true
                        liveAsr?.close()
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
                var pendingLevels = emptyList<Float>()
                val meter = com.nomily.app.audio.LiveLevelMeter()
                var source = c
                try {
                    while (true) {
                        try {
                            withContext(Dispatchers.IO) {
                                // First frame timeout 10 s: a streaming device emits a frame every 20 ms; the default
                                // 30 s is reserved for gaps during the session, not for the initial packet.
                                source.streamAudio(firstFrameTimeoutMs = LIVE_FIRST_FRAME_TIMEOUT_MS).collect { frames ->
                                    // Setting on every frame would overwhelm the UI (50 fps) — batch updates every half second;
                                    // the two counters in the control bar only display to the second, which is sufficient.
                                    pendingBytes += frames.size
                                    if (!_ui.value.live.paused) pendingLevels = pendingLevels + meter.feed(frames)
                                    if (pendingBytes >= OPUS_FRAME_SIZE * 25) {
                                        val add = pendingBytes
                                        val levels = pendingLevels
                                        pendingBytes = 0
                                        pendingLevels = emptyList()
                                        set {
                                            it.copy(
                                                live = it.live.copy(
                                                    bytes = it.live.bytes + add,
                                                    audioLevels = com.nomily.app.core.audio.LiveLevels.push(it.live.audioLevels, levels),
                                                ),
                                            )
                                        }
                                    }
                                    // During pause, **continue receiving and storing, just don't send to ASR** (disconnecting BLE might stop the device's stream).
                                    // Swallow write failures: stopLive closes the buffered stream, and we might be writing the last frame —
                                    // that isn't a session error; it shouldn't push the UI into FAILED.
                                    runCatching { liveBuffer?.write(frames) }
                                    liveCapturedBytes += frames.size
                                    if (!_ui.value.live.paused) liveAsr?.send(frames)
                                }
                            }
                            break
                        } catch (e: DnoteBleClient.DnoteLinkLostException) {
                            // The card keeps recording through a link drop: hold the session, re-attach after the
                            // reconnect, and keep appending to the same buffer. Given up → save what we have (below).
                            Log.w(TAG, "Real-time stream lost (link dropped) — holding the session for the reconnect")
                            source = recoverLiveStream(source) ?: break
                        }
                    }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { meter.release() }
                }
                // Stream ended: the device stopped recording, or the card didn't come back after a drop
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

    /**
     * Re-attach the live stream after the BLE link dropped (iOS `LiveModel.recoverStream`).
     *
     * Waits up to [LIVE_STREAM_RECOVERY_MS] for the automatic reconnect to hand over a fresh client, then re-arms the
     * stream on it. Not attempted while paused (same as iOS). The phone's copy now misses the gap, so the session is
     * flagged for the "replace with the complete recording" offer — and its device file is never auto-deleted.
     *
     * @return the client now streaming, or `null` when the card didn't come back in time.
     */
    private suspend fun recoverLiveStream(lost: DnoteBleClient): DnoteBleClient? {
        val live = _ui.value.live
        if (!live.running || live.paused) return null
        set { it.copy(live = it.live.copy(linkRecovering = true)) }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + LIVE_STREAM_RECOVERY_MS
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val fresh = client
                // connectedName is set at the end of connect(): the new link is set up and usable.
                if (fresh != null && fresh !== lost && _ui.value.connectedName != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            // Same check as the first attach: upload=0 means the firmware will never push frames.
                            val upload = runCatching { fresh.getRecordingStatus().optInt("upload", 1) }.getOrDefault(1)
                            if (upload == 0) throw IllegalStateException(str(R.string.live_attach_no_upload))
                            fresh.startStream()
                        }
                        liveLinkDropped = true
                        Log.i(TAG, "Real-time stream re-attached after the link came back")
                        set { it.copy(live = it.live.copy(error = null)) }
                        return fresh
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Re-attach failed (${e.message}) — retrying")
                    }
                }
                kotlinx.coroutines.delay(LIVE_STREAM_RECOVERY_POLL_MS)
            }
            Log.w(TAG, "Real-time stream not recovered within ${LIVE_STREAM_RECOVERY_MS / 1000} s — ending the session")
            return null
        } finally {
            set { it.copy(live = it.live.copy(linkRecovering = false)) }
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
        // No service → resume as record-only; the frames are stored either way.
        val notice = openLiveAsrOrRecordOnly(configStore.config.value)
        set {
            it.copy(
                live = it.live.copy(
                    paused = false, status = LiveStatus.STREAMING, error = null,
                    provider = providerLabel(), serviceNotice = notice,
                ),
            )
        }
    }

    /**
     * Consolidate unfinished temporary lines into a segment. With LLM translation on, an untranslated line is
     * queued too — partial translation is throttled, so otherwise the last sentence would never get one.
     */
    private fun commitLivePartial() {
        val live = _ui.value.live
        if (live.partial.isBlank()) return
        val line = LiveLine(live.partial, live.partialTranslation, at = liveCapturedSeconds)
        set { it.copy(live = it.live.copy(finals = it.live.finals + line, partial = "", partialTranslation = "")) }
        if (liveTranslateTo != null && line.translation.isEmpty()) translateFinals.addLast(line.id)
        translatePartial = null
    }

    /**
     * Reopen the ASR link with the current language settings — called after the user changes language
     * mid-session. Paused sessions pick the settings up on resume.
     */
    fun switchLiveAsr() {
        val live = _ui.value.live
        if (!live.running || live.paused) return
        commitLivePartial()
        liveIntentionalClose = true
        liveAsr?.close(); liveAsr = null
        liveReconnectAttempts = 0
        val notice = openLiveAsrOrRecordOnly(configStore.config.value)
        set {
            it.copy(
                live = it.live.copy(
                    status = if (liveAsr != null) LiveStatus.RECONNECTING else LiveStatus.STREAMING,
                    error = null, provider = providerLabel(), serviceNotice = notice,
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
        commitLivePartial()
        set { it.copy(live = it.live.copy(running = false, status = LiveStatus.IDLE, linkRecovering = false, audioLevels = emptyList())) }
        val hadPauses = liveDidPause
        liveDidPause = false
        val linkDropped = liveLinkDropped
        liveLinkDropped = false
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
                    // Clear `rec` as soon as the stop is acknowledged; otherwise the pill's red dot lingers until the next 30 s poll.
                    if (stopInfo != null) set { ui ->
                        val raw = ui.info?.raw ?: return@set ui
                        raw.put("rec", 0)
                        ui.copy(info = com.nomily.app.ble.DeviceInfo(raw))
                    }
                }
                flushLiveTranslation()
                saveLiveClip(c, buffer, stopInfo, hadPauses, linkDropped)
                endLiveTranslation()
                loadFiles()
            }
        } else {
            // The card is gone (dropped and never came back, or stopped while reconnecting): keep what was received.
            viewModelScope.launch {
                flushLiveTranslation()
                saveLiveClip(null, buffer, null, hadPauses, linkDropped)
                endLiveTranslation()
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
        c: DnoteBleClient?,
        buffer: File?,
        stopInfo: org.json.JSONObject?,
        hadPauses: Boolean,
        linkDropped: Boolean = false,
    ) {
        if (buffer == null || !buffer.isFile || buffer.length() < com.nomily.app.core.audio.OPUS_FRAME_SIZE) {
 Log.i(TAG, "Real-time session did not receive enough audio (${buffer?.length() ?: 0}B), not storing segment")
            buffer?.delete()
            return
        }
        // Without a client the device file can't be identified: no repair offer and no auto-delete, the card keeps it.
        val deviceName = stopInfo?.optString("name")?.takeIf { it.isNotEmpty() }
            ?: c?.let { resolveSessionDeviceFile(it) }
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
        if (saved == null) {
            // Keep the bytes: until the next session starts, "Save to Library" retries this exact save.
            pendingLiveSave = PendingLiveSave(c, buffer, stopInfo)
            set { it.copy(live = it.live.copy(canSaveToLibrary = true)) }
            return
        }
        buffer.delete()
        library.manifest.recordDownload(name, saved.length(), "stream", localName = saved.name)

        // Persist the transcription as well: live lines become the clip's transcript (txt + segmented json),
        // and their translations the clip's translation — the same files batch transcription writes.
        val lines = _ui.value.live.finals
        if (lines.isNotEmpty()) {
            val cfg = configStore.config.value
            val provider = liveActiveProvider.ifEmpty { "live" }
            runCatching {
                withContext(Dispatchers.IO) {
                    val base = saved.name.substringBeforeLast('.')
                    val dir = saved.parentFile
                    fun write(txt: String, json: String, items: List<Pair<String, Double>>, provider: String, locale: String) {
                        val text = items.joinToString("\n") { it.first }
                        File(dir, txt).writeText(text)
                        File(dir, json).writeText(
                            com.nomily.app.core.asr.AsrResult(text, liveSegments(items), provider, locale).toJsonString(),
                        )
                    }
                    val source = com.nomily.app.llm.LanguageService.liveSourceLang(cfg)
                    write("$base.txt", "$base.asr.json", lines.map { it.text to it.at }, provider, source)
                    val translated = lines.filter { it.translation.isNotEmpty() }
                    if (translated.isNotEmpty()) {
                        write(
                            "$base.translated.txt",
                            "$base.translated.json",
                            translated.map { it.translation to it.at },
                            "$provider-translate",
                            cfg.lastTargetLang ?: source,
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to write real-time transcription: ${it.message}") }
        }
        set { it.copy(live = it.live.copy(savedClip = saved.name)) }
        Log.i(TAG, "Real-time session saved as ${saved.name}")
        val savedBase = saved.name.substringBeforeLast('.')
        if (lines.isNotEmpty()) {
            // The live transcript was written straight to disk, so no transcription job fires its follow-ups.
            maybeAutoSummarize(savedBase)
        } else if (configStore.config.value.autoTranscribeAfterDownload) {
            // No live transcript (live ASR off or never ran): treat the clip like any other import.
            refreshClips()
            clipsJob?.join()
            _ui.value.clips.firstOrNull { it.base == savedBase }?.let { transcribe(it, enforceMinDuration = true) }
        }

        // If paused (or this session is attached to a segment the device is already recording) → the phone’s copy is incomplete,
        // the device’s copy is full; ask the user whether to replace. In attachment scenarios **there is no pause**, the wording is a single sentence.
        val attached = liveAttachedToExisting
        if ((hadPauses || attached || linkDropped) && deviceName != null) {
            val size = stopInfo?.optInt("size") ?: 0
            set {
                it.copy(
                    live = it.live.copy(
                        pendingRepair = PendingRepair(
                            deviceName, saved.name, size,
                            wasAttached = attached, linkDropped = linkDropped && !hadPauses,
                        ),
                    ),
                )
            }
        } else if (c != null && deviceName != null && configStore.config.value.autoDeleteAfterTransfer) {
            runCatching { withContext(Dispatchers.IO) { c.deleteFile(deviceName) } }
                .onFailure { Log.w(TAG, "Failed to delete device file after real-time session: ${it.message}") }
        }
    }

    private class PendingLiveSave(val client: DnoteBleClient?, val buffer: File, val stopInfo: org.json.JSONObject?)

    /** A live session whose automatic save failed; held until the user retries or a new session starts. */
    private var pendingLiveSave: PendingLiveSave? = null

    /**
     * Retry the failed automatic save of the last live session, unchanged. No pause-repair prompt on this path:
     * the user asked to keep what was captured, not to start another dialog.
     */
    fun saveLiveToLibrary() {
        val p = pendingLiveSave ?: return
        pendingLiveSave = null
        set { it.copy(live = it.live.copy(canSaveToLibrary = false)) }
        viewModelScope.launch {
            saveLiveClip(p.client, p.buffer, p.stopInfo, hadPauses = false)
            loadFiles()
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
            set {
                it.copy(
                    busy = str(R.string.live_repair_downloading),
                    error = null,
                    live = it.live.copy(repairState = RepairState.DOWNLOADING, repairProgress = null, repairError = null),
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val blob = c.downloadFile(repair.deviceFileName, repair.expectedSize) { got, total ->
                        val p = if (total > 0) got.toLong() to total.toLong() else null
                        set { it.copy(progress = if (total > 0) got to total else null, live = it.live.copy(repairProgress = p)) }
                    }
                    val res = pipeline.ingestRaw(blob, repair.deviceFileName, sn?.let { pipeline.cachedKeyFor(it) })
                    // “Fixed” must mean **a truly playable file was produced**. An empty artefact doesn’t count as success,
                    // and we must not delete the device’s sole complete recording afterward.
                    if (!res.oggFile.isFile || res.oggFile.length() <= 0L) {
                        throw IllegalStateException(str(R.string.live_repair_not_playable))
                    }
                    library.manifest.recordDownload(repair.deviceFileName, blob.size.toLong(), "ble", localName = res.oggFile.name)
                    res
                }
            }.onFailure { e ->
                Log.w(TAG, "Replacement failed — device file ${repair.deviceFileName} left unchanged", e)
                set {
                    it.copy(live = it.live.copy(repairState = RepairState.FAILED, repairError = e.message ?: ""))
                }
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
    fun dismissRepairState() = set {
        it.copy(live = it.live.copy(repairState = RepairState.IDLE, repairProgress = null, repairError = null))
    }

    private fun onLiveEvent(ev: com.nomily.app.asr.LiveAsrEvent) {
        when (ev) {
            is com.nomily.app.asr.LiveAsrEvent.Partial -> {
                noteLiveResult()
                val llm = liveTranslateTo != null
                // Only the latest partial is worth translating: it keeps rolling.
                if (llm && ev.text.isNotEmpty()) translatePartial = ev.text
                set {
                    it.copy(
                        live = it.live.copy(
                            partial = ev.text,
                            partialTranslation = if (llm) it.live.partialTranslation else ev.translation,
                            status = LiveStatus.STREAMING,
                        ),
                    )
                }
            }
            is com.nomily.app.asr.LiveAsrEvent.Final -> {
                noteLiveResult()
                val llm = liveTranslateTo != null
                if (ev.text.isNotEmpty() || ev.translation.isNotEmpty()) {
                    // LLM translation lags a line behind speech. If the partial just translated is the start of this
                    // final, show that translation now as a placeholder; the queued full translation replaces it.
                    // A non-matching prefix means the previous sentence is mixed in — better none than a wrong one.
                    val partialTr = _ui.value.live.partialTranslation
                    val seed = if (llm && partialTr.isNotEmpty() && lastPartialTranslated.isNotEmpty() &&
                        ev.text.startsWith(lastPartialTranslated)
                    ) partialTr else ev.translation
                    val line = LiveLine(ev.text, seed, at = liveCapturedSeconds)
                    set { it.copy(live = it.live.copy(finals = it.live.finals + line)) }
                    if (llm && ev.text.isNotEmpty()) translateFinals.addLast(line.id)
                }
                translatePartial = null
                lastPartialTranslated = ""
                set { it.copy(live = it.live.copy(partial = "", partialTranslation = "", status = LiveStatus.STREAMING)) }
            }
            is com.nomily.app.asr.LiveAsrEvent.Closed -> {
                if (liveIntentionalClose) {
                    liveIntentionalClose = false
                    return
                }
                if (!_ui.value.live.running || _ui.value.live.paused) return
                liveAsr = null
                // A service that was shut down without ever producing a result is a **dead end**: reconnecting to it only consumes reconnection attempts,
                // while the UI keeps showing it as active. Cross it off and fall through to the next provider in the chain right away.
                val delayMs = if (!liveSawResult && liveActiveProvider.isNotEmpty()) {
                    Log.w(TAG, "ASR provider $liveActiveProvider connected but produced no results — considered dead end, switching to next")
                    liveDeadProviders += liveActiveKind.ifEmpty { liveActiveProvider }
                    liveReconnectAttempts = 0
                    0L
                } else {
                    if (liveReconnectAttempts >= LIVE_MAX_RECONNECTS) {
                        set { it.copy(live = it.live.copy(status = LiveStatus.FAILED, error = ev.error ?: str(R.string.live_status_failed))) }
                        return
                    }
                    liveReconnectAttempts++
                    // Backoff 1.5 s, 3 s, 4.5 s, then flat.
                    LIVE_RECONNECT_DELAY_MS * minOf(liveReconnectAttempts, 3)
                }
                // Session still active but receives a close = disconnection; reconnect ASR (BLE remains unchanged)
                Log.w(TAG, "ASR connection dropped: ${ev.error ?: "closed by the server"}, reconnecting (attempt $liveReconnectAttempts)")
                set { it.copy(live = it.live.copy(status = LiveStatus.RECONNECTING, error = ev.error)) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(delayMs)
                    if (!_ui.value.live.running || _ui.value.live.paused || liveAsr != null) return@launch
                    val notice = openLiveAsrOrRecordOnly(configStore.config.value)
                    if (liveAsr != null) {
                        // Socket opened **does not mean** transcription is active — state stays at “reconnecting”,
                        // only after the first result arrives is it considered connected (Partial/Final branches set STREAMING).
                        set { it.copy(live = it.live.copy(error = null, provider = providerLabel(), serviceNotice = null)) }
                    } else {
                        // Losing the speech service mid-session degrades the session, it does not end it: the card is
                        // still recording, and the clip is transcribed from the saved file.
                        set { it.copy(live = it.live.copy(status = LiveStatus.STREAMING, provider = "", serviceNotice = notice)) }
                    }
                }
            }
        }
    }

    /**
     * Open and connect the ASR link for this session, mirroring iOS `LiveModel.openASR`:
     * - a translation is wanted, Azure is configured and its translation link has not died this session → Azure live
     *   translation (source and translation in one stream);
     * - otherwise walk the configured chain (`defaults.asr` primary → fallbacks), skipping providers that proved dead
     *   this session; a wanted translation is then produced line by line by the LLM.
     *
     * Each provider owns its failure: one whose `connect` throws (e.g. a remote server without a key) is marked dead
     * and the next one is tried. The link lands in [liveAsr] **before** connecting, so a close that arrives at once
     * is handled against the right link. Throws only when nothing is left.
     */
    private fun openLiveAsr(cfg: com.nomily.app.core.config.AppConfig) {
        liveIntentionalClose = false
        liveSawResult = false
        liveActiveProvider = ""
        liveActiveKind = ""
        val target = cfg.lastTargetLang
        val source = com.nomily.app.llm.LanguageService.liveSourceLang(cfg)
        // Only translate when the target language **differs** from the source; if identical (or not selected) only perform recognition.
        val wantTranslation = !target.isNullOrEmpty() &&
            target.substringBefore('-').lowercase() != source.substringBefore('-').lowercase()
        val azure = cfg.asrProviders.azure?.takeIf { it.key.isNotEmpty() && it.region.isNotEmpty() }

        fun attempt(kind: String, provider: String, build: () -> com.nomily.app.asr.LiveAsr?): Boolean =
            try {
                val asr = build() ?: return false
                liveActiveProvider = provider
                liveActiveKind = kind
                liveAsr = asr
                asr.connect { ev -> onLiveEvent(ev) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Live ASR $kind unavailable: ${e.message}")
                liveDeadProviders += kind
                liveAsr = null
                liveActiveProvider = ""
                liveActiveKind = ""
                false
            }

        if (wantTranslation && azure != null && "azuretranslate" !in liveDeadProviders) {
            endLiveTranslation()
            if (attempt("azuretranslate", "azure") {
                    com.nomily.app.asr.AzureLiveTranslation(azure.key, azure.region, source, target!!)
                }
            ) return
        }
        if (wantTranslation) beginLiveTranslation(cfg, target!!) else endLiveTranslation()

        val chain = (listOf(cfg.defaults.asrPrimary) + cfg.defaults.asrFallbacks).map { it.lowercase() }.distinct()
        for (name in chain) {
            if (name in liveDeadProviders) continue
            val opened = when (name) {
                "azure" -> attempt("azure", "azure") {
                    azure?.let { com.nomily.app.asr.AzureLiveAsr(it.key, it.region, source) }
                }
                // Remote secure server: wss:// enforced and the key in handshake headers only (see RemoteLiveAsr).
                // A failed handshake is classified and marked dead, falling through to `local` per the fallback order.
                "remote" -> attempt("remote", "remote") {
                    cfg.asrProviders.remoteLive?.takeIf { it.endpoint.isNotBlank() }?.let {
                        com.nomily.app.asr.RemoteLiveAsr(getApplication(), it.endpoint, it.apiKey.orEmpty())
                    }
                }
                "local" -> attempt("local", "local") {
                    cfg.asrProviders.local?.takeIf { it.host.isNotEmpty() && it.port > 0 }?.let {
                        com.nomily.app.asr.LocalLiveAsr(it.host, it.port)
                    }
                }
                // `custom` is file transcription only.
                else -> false
            }
            if (opened) return
        }
        throw IllegalStateException(
            str(if (liveDeadProviders.isEmpty()) R.string.asr_no_provider_configured else R.string.asr_all_providers_unreachable),
        )
    }

    /**
     * [openLiveAsr] into [liveAsr]; when nothing can be opened the session carries on record-only.
     * @return the record-only notice, or null when a link is open.
     */
    private fun openLiveAsrOrRecordOnly(cfg: com.nomily.app.core.config.AppConfig): String? = try {
        openLiveAsr(cfg)
        null
    } catch (e: Exception) {
        Log.w(TAG, "No live ASR — continuing record-only: ${e.message}")
        liveAsr = null
        str(R.string.asr_live_recording_without_asr)
    }

    private fun providerLabel(): String = when (liveActiveProvider) {
        "local" -> "Local"
        "remote" -> "Remote"
        "azure" -> "Azure"
        else -> ""
    }

    /** A delivered result proves the (possibly just-reconnected) link works: fresh reconnect budget. */
    private fun noteLiveResult() {
        liveSawResult = true
        liveReconnectAttempts = 0
    }

    // ── Live translation via the LLM ───────────────────────────────────────────

    private fun llmUsable(cfg: com.nomily.app.core.config.AppConfig): Boolean =
        com.nomily.app.core.llm.LlmChain.isConfigured(cfg)

    /**
     * Prepare LLM translation into [targetCode]. Idempotent: a reconnect calls it again, and restarting would drop
     * the translations in flight. Without an LLM the session transcribes only, with a soft notice saying why.
     */
    private fun beginLiveTranslation(cfg: com.nomily.app.core.config.AppConfig, targetCode: String) {
        val name = languageService.cachedName(targetCode) ?: targetCode
        if (liveTranslateTo == name && liveTranslatePump?.isActive == true) return
        resetLiveTranslationQueue()
        set { it.copy(live = it.live.copy(partialTranslation = "")) }
        if (!llmUsable(cfg)) {
            liveTranslateTo = null
            liveTranslatePump?.cancel(); liveTranslatePump = null
            set { it.copy(live = it.live.copy(translationNotice = str(R.string.live_translate_unavailable))) }
            Log.i(TAG, "Live translation requested but no LLM provider is configured")
            return
        }
        liveTranslateTo = name
        set { it.copy(live = it.live.copy(translationNotice = null)) }
        // Serial pump — one request at a time; a local model on CPU only slows down when run concurrently.
        // Finals first (finalized text), then the latest partial, throttled.
        liveTranslatePump = viewModelScope.launch {
            while (true) {
                val id = translateFinals.pollFirst()
                if (id != null) {
                    translateLiveLine(id)
                    continue
                }
                val partial = translatePartial
                if (partial != null && System.currentTimeMillis() - lastPartialTranslateAt >= PARTIAL_TRANSLATE_MIN_INTERVAL_MS) {
                    translatePartial = null
                    translateLivePartial(partial)
                    continue
                }
                kotlinx.coroutines.delay(200)
            }
        }
        Log.i(TAG, "Live translation via LLM into $name")
    }

    private fun endLiveTranslation() {
        liveTranslateTo = null
        resetLiveTranslationQueue()
        liveTranslatePump?.cancel(); liveTranslatePump = null
        val unavailable = str(R.string.live_translate_unavailable)
        if (_ui.value.live.translationNotice == unavailable) set { it.copy(live = it.live.copy(translationNotice = null)) }
    }

    private fun resetLiveTranslationQueue() {
        translateFinals.clear()
        translatePartial = null
        translateInFlight = null
        lastPartialTranslated = ""
        lastPartialTranslateAt = 0L
    }

    private suspend fun translateLiveLine(id: Long) {
        val target = liveTranslateTo ?: return
        val source = _ui.value.live.finals.firstOrNull { it.id == id }?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        translateInFlight = id
        try {
            val out = withContext(Dispatchers.IO) {
                com.nomily.app.llm.LlmClient.translate(
                    localized(), source, target, configStore.config.value, allowFallback = false,
                ).text.trim()
            }
            if (out.isEmpty()) return
            // Look the line up by id again: the session may have moved on while the LLM was working.
            set { ui ->
                ui.copy(
                    live = ui.live.copy(
                        finals = ui.live.finals.map { if (it.id == id) it.copy(translation = out) else it },
                        translationNotice = null,
                    ),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            noteLiveTranslationFailure(e)
        } finally {
            translateInFlight = null
        }
    }

    private suspend fun translateLivePartial(text: String) {
        val target = liveTranslateTo ?: return
        val source = text.trim()
        if (source.isEmpty() || source == lastPartialTranslated) return
        lastPartialTranslateAt = System.currentTimeMillis()
        try {
            val out = withContext(Dispatchers.IO) {
                com.nomily.app.llm.LlmClient.translate(
                    localized(), source, target, configStore.config.value, allowFallback = false,
                ).text.trim()
            }
            // Only if the partial is still the same text — otherwise the previous sentence's translation would sit under the next one.
            if (out.isEmpty() || _ui.value.live.partial != text) return
            lastPartialTranslated = source
            set { it.copy(live = it.live.copy(partialTranslation = out, translationNotice = null)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            noteLiveTranslationFailure(e)
        }
    }

    /** A translation failure never interrupts transcription: one soft notice, not repeated. */
    private fun noteLiveTranslationFailure(e: Exception) {
        Log.w(TAG, "Live translation failed: ${e.message}")
        if (_ui.value.live.translationNotice == null) {
            set { it.copy(live = it.live.copy(translationNotice = str(R.string.live_translate_unavailable))) }
        }
    }

    /**
     * Wait — bounded — for queued line translations before the transcript is saved, so lines the user saw
     * translated are not saved without it. Better to miss two translations than to hang the save on a slow model.
     */
    private suspend fun flushLiveTranslation() {
        if (liveTranslateTo == null) return
        val deadline = System.currentTimeMillis() + TRANSLATION_FLUSH_TIMEOUT_MS
        while ((translateFinals.isNotEmpty() || translateInFlight != null) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        if (translateFinals.isNotEmpty() || translateInFlight != null) {
            Log.w(TAG, "Live translation flush timed out with ${translateFinals.size} line(s) pending")
        }
    }

    /** Segments for a saved live transcript: each line spans from the previous line's end to its own. */
    private fun liveSegments(items: List<Pair<String, Double>>): List<com.nomily.app.core.asr.AsrSegment> =
        items.mapIndexed { i, (text, at) ->
            val start = if (i > 0) items[i - 1].second else 0.0
            fun r(v: Double) = Math.round(v * 10) / 10.0
            com.nomily.app.core.asr.AsrSegment(null, text, r(start), r(at), r(at - start))
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

    // ── Background tasks: transcription, summary, translation, export ─────────

    private fun registerJobRunners() {
        jobs.register(com.nomily.app.jobs.JobKind.TRANSCRIBE) { job, _ -> runTranscribe(job) }
        jobs.register(com.nomily.app.jobs.JobKind.SUMMARIZE) { job, r -> runSummarize(job, r) }
        jobs.register(com.nomily.app.jobs.JobKind.TRANSLATE) { job, r -> runTranslate(job, r) }
        jobs.register(com.nomily.app.jobs.JobKind.EXPORT) { job, _ -> runExport(job) }
        // Keep the process alive while anything is queued, refresh the library as jobs finish, and mirror
        // transcription / translation state into the UI fields the clip page already reads.
        // A download or fast transfer is protected by the same foreground service as the job queue.
        viewModelScope.launch {
            _ui.map { it.transferActive }.distinctUntilChanged().drop(1).collect { syncWorkService() }
        }
        viewModelScope.launch {
            var hadWork = false
            var finished = emptySet<String>()
            jobs.jobs.collect { list ->
                val active = list.filter { it.finishedAt == null }
                val hasWork = active.any { it.isRunnable }
                if (hasWork != hadWork) {
                    syncWorkService(hasWork)
                    hadWork = hasWork
                }
                val nowFinished = list.filter { it.finishedAt != null }.map { it.id }.toSet()
                if ((nowFinished - finished).isNotEmpty()) refreshClips()
                finished = nowFinished
                set { ui ->
                    ui.copy(
                        transcribing = active.filter { it.kind == com.nomily.app.jobs.JobKind.TRANSCRIBE }.map { it.clip }.toSet(),
                        transcribeFailures = active
                            .filter { it.kind == com.nomily.app.jobs.JobKind.TRANSCRIBE && !it.isRunnable }
                            .associate { it.clip to (it.lastError ?: "") },
                        translating = active.filter { it.kind == com.nomily.app.jobs.JobKind.TRANSLATE }.map {
                            "${it.clip}:${if (it.translateTarget == "summary") ClipArtefact.SUMMARY.name else ClipArtefact.TRANSCRIPT.name}"
                        }.toSet(),
                    )
                }
            }
        }
        // After the constructor finishes: runners touch properties declared further down this class.
        viewModelScope.launch { kotlinx.coroutines.yield(); jobs.resume() }
    }

    private fun clipFile(base: String, suffix: String) = File(library.dir, base + suffix)

    private fun clipAudio(base: String): File? =
        library.dir.listFiles()?.firstOrNull { ClipArtefact.of(it.name) == ClipArtefact.AUDIO && ClipArtefact.baseOf(it.name) == base }

    private suspend fun runTranscribe(job: com.nomily.app.jobs.WorkJob) {
        val audio = clipAudio(job.clip) ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_audio)))
        try {
            val outcome = withContext(Dispatchers.IO) {
                transcription.transcribe(audio, configStore.config.value, enforceMinDuration = job.enforceMinDuration, locales = job.locales)
            }
            Log.i(TAG, "Transcription completed ${job.clip}: ${outcome.result.segments.size} segments, provider=${outcome.result.provider}")
            // Manual, auto-after-download and resumed transcriptions all land here: one place for the follow-ups.
            withContext(Dispatchers.Main) {
                maybeAutoSummarize(job.clip)
                showTranscribedBanner(job.clip)
            }
            // A fallback provider isn't an error, but the user should know a non-preferred one did the work.
            outcome.warnings.joinToString("; ").ifEmpty { null }?.let { w -> set { it.copy(error = w) } }
        } catch (e: com.nomily.app.asr.TranscriptionService.TooShortException) {
            // Below the auto-transcribe threshold: nothing to retry.
            Log.i(TAG, "Skipping transcription (too short): ${e.message}")
            set { it.copy(error = str(R.string.asr_clip_too_short, e.duration, e.minimum.toDouble())) }
        } catch (e: com.nomily.app.asr.AsrException) {
            throw IllegalStateException(e.messageRes?.let { str(it) } ?: failed(R.string.device_files_operation_failed, e), e)
        }
    }

    private suspend fun runSummarize(job: com.nomily.app.jobs.WorkJob, reporter: com.nomily.app.jobs.JobQueue.Reporter) {
        val prompt = job.prompt ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_prompt)))
        val source = sourceText(job.clip)
        val cfg = configStore.config.value
        // Short sources must not be inflated by the template; the gate lives here so queue re-runs take the same path.
        val effectivePrompt = com.nomily.app.core.llm.ShortSource.adaptedPrompt(prompt, source)
        // Finished chunk sections are reused only on a retry; a first run has none and "generate again" wants new text.
        val outcome = com.nomily.app.llm.LlmClient.completeResilient(
            localized(), effectivePrompt, source, cfg, allowSectionReuse = job.attempts > 0,
        ) { done, total ->
            reporter.progress(done, total)
        }
        withContext(Dispatchers.IO) { clipFile(job.clip, ".summary.md").writeText(outcome.completion.text) }
        // The title is a nice-to-have: a failure leaves the summary standing. The provider that just worked goes first.
        runCatching {
            val title = com.nomily.app.llm.LlmClient.completeResilient(
                localized(), com.nomily.app.core.llm.TITLE_PROMPT, outcome.completion.text.take(1000), cfg,
                allowChunking = false, preferred = outcome.providerKey,
            ).completion.text.trim()
            if (title.isNotEmpty()) withContext(Dispatchers.IO) { clipFile(job.clip, ClipArtefact.TITLE_SUFFIX).writeText(title) }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "Title failed: ${it.message}") }
        val notes = com.nomily.app.llm.LlmClient.resultNotes(localized(), outcome, cfg) +
            if (effectivePrompt != prompt) listOf(str(R.string.summarize_short_source_note)) else emptyList()
        reporter.notes(notes, outcome.completion.truncated)
    }

    /**
     * The detail page's AI Q&A: one answer grounded on [material] (summary + timestamped transcript), through the
     * same fallback chain as summaries but never chunked — the user is waiting for an answer, not a merge pass.
     */
    suspend fun askAboutClip(material: String, messages: List<com.nomily.app.core.asr.ClipQa.Message>): String =
        com.nomily.app.llm.LlmClient.completeResilient(
            localized(),
            str(R.string.clip_detail_ai_ask_system),
            com.nomily.app.core.asr.ClipQa.userContent(material, messages),
            configStore.config.value,
            allowChunking = false,
        ).completion.text

    private suspend fun runTranslate(job: com.nomily.app.jobs.WorkJob, reporter: com.nomily.app.jobs.JobQueue.Reporter) {
        val language = job.languageName ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_language)))
        val summary = job.translateTarget == "summary"
        val source = if (summary) {
            clipFile(job.clip, ".summary.md").takeIf(File::isFile)?.readText()?.takeIf { it.isNotBlank() }
                ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_summary)))
        } else sourceText(job.clip)
        val out = com.nomily.app.llm.LlmClient.translate(localized(), source, language, configStore.config.value)
        withContext(Dispatchers.IO) {
            if (summary) {
                clipFile(job.clip, ".summary.translated.md").writeText(out.text)
            } else {
                clipFile(job.clip, ".translated.txt").writeText(out.text)
                // Live's `.translated.json` carries timestamps for one specific target language; a translation into
                // another language makes it stale.
                clipFile(job.clip, ".translated.json").delete()
            }
        }
        reporter.notes(if (out.truncated) listOf(str(R.string.summarize_truncated_warning)) else emptyList(), out.truncated)
    }

    private suspend fun runExport(job: com.nomily.app.jobs.WorkJob) {
        val format = job.exportFormat
        if (format in DOCUMENT_FORMATS) {
            withContext(Dispatchers.IO) {
                val dir = exports.beginNewStaging()
                val file = writeDocument(format!!, job.clip, dir)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                exports.publish(dir, file, job.clip, origin = job.clip)
            }
            return
        }
        val src = clipAudio(job.clip) ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_audio)))
        withContext(Dispatchers.IO) {
            val dir = exports.beginNewStaging()
            val file = when (format) {
                "wav" -> com.nomily.app.audio.AudioExporter.exportAsWav(source = src, outDir = dir, stem = job.clip)
                else -> com.nomily.app.audio.AudioExporter.exportAsM4a(source = src, outDir = dir, stem = job.clip)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            exports.publish(dir, file, job.clip, origin = job.clip)
        }
    }

    private fun missing(@StringRes what: Int) = com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(what)))

    private fun summaryForExport(base: String): String? =
        clipFile(base, ".summary.md").takeIf(File::isFile)?.readText()?.takeIf { it.isNotEmpty() }

    private fun transcriptForExport(base: String): String =
        com.nomily.app.core.export.DocumentText.transcript(
            clipFile(base, ".asr.json").takeIf(File::isFile)?.readText(),
            clipFile(base, ".txt").takeIf(File::isFile)?.readText(),
            speakerNames(base),
        ) ?: throw missing(R.string.jobs_source_transcript)

    /** Writes one document export (`txt` / `md` / `pdf` / `png` = mind map) of [base] into [dir]. */
    private fun writeDocument(format: String, base: String, dir: File): File {
        val stem = com.nomily.app.core.export.DocumentText.stem(base)
        val exporter = com.nomily.app.audio.DocumentExporter
        val summary = summaryForExport(base)
        return when (format) {
            "txt" -> exporter.writeText(dir, stem, "txt", transcriptForExport(base))
            "md" -> exporter.writeText(
                dir, stem, "md",
                com.nomily.app.core.export.DocumentText.markdown(
                    stem, summary, transcriptForExport(base), str(R.string.clip_detail_tab_summary), str(R.string.clip_detail_tab_transcript),
                ),
            )
            "pdf" -> runCatching {
                exporter.writePdf(dir, stem, summary, transcriptForExport(base), str(R.string.clip_detail_tab_summary), str(R.string.clip_detail_tab_transcript))
            }.getOrElse { if (it is com.nomily.app.jobs.JobSourceMissing) throw it else throw IllegalStateException(str(R.string.clip_detail_export_pdf_failed), it) }
            "png" -> exporter.writeMindMap(dir, stem, summary ?: throw missing(R.string.jobs_source_summary))
                ?: throw missing(R.string.jobs_source_summary)
            else -> throw missing(R.string.jobs_source_format)
        }
    }

    /**
     * Files for the mail composer, under `cache/exports/mail` (covered by the FileProvider paths): the summary as
     * `.md`, the transcript as `.txt`, the mind map as `-mindmap.png`, in that order. Throws when a chosen one cannot be made.
     */
    suspend fun prepareMailAttachments(base: String, summary: Boolean, transcript: Boolean, mindMap: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(getApplication<Application>().cacheDir, "exports/mail").apply { deleteRecursively(); mkdirs() }
            val stem = com.nomily.app.core.export.DocumentText.stem(base)
            val exporter = com.nomily.app.audio.DocumentExporter
            buildList {
                if (summary) add(exporter.writeText(dir, stem, "md", summaryForExport(base) ?: throw missing(R.string.jobs_source_summary)))
                if (transcript) add(exporter.writeText(dir, stem, "txt", transcriptForExport(base)))
                if (mindMap) add(writeDocument("png", base, dir))
            }
        }

    /** The user's speaker names for [base] (`{base}.speakers.json`), empty when none. */
    fun speakerNames(base: String): Map<String, String> = com.nomily.app.core.asr.SpeakerNames.read(library.dir, base)

    // shortcut: written on the calling thread per keystroke; a few hundred bytes, so fine until names carry more than that.
    fun saveSpeakerNames(base: String, names: Map<String, String>) =
        com.nomily.app.core.asr.SpeakerNames.write(library.dir, base, names)

    /** The transcript as the LLM should see it, or [JobSourceMissing] when there is none. Speaker names are read from disk, as the queue may run without the page. */
    private fun sourceText(base: String): String =
        clipFile(base, ".asr.json").takeIf(File::isFile)
            ?.let { f -> runCatching { com.nomily.app.core.asr.parseAsrArtefact(f.readText()) }.getOrNull()?.textForSummarization(speakerNames(base)) }
            ?.takeIf { it.isNotBlank() }
            ?: clipFile(base, ".txt").takeIf(File::isFile)?.readText()?.takeIf { it.isNotBlank() }
            ?: throw com.nomily.app.jobs.JobSourceMissing(str(R.string.jobs_source_missing, str(R.string.jobs_source_transcript)))

    /**
     * Queue a summary. The prompt is frozen now, language instruction included — the job may run after a restart,
     * when the template could have changed. Returns the job id for the sheet to follow.
     */
    fun enqueueSummary(clip: LocalClip, template: com.nomily.app.core.llm.SummarizeTemplate, languageName: String?): String {
        val job = com.nomily.app.jobs.WorkJob.summarize(
            clip.base, com.nomily.app.core.llm.withLanguageInstruction(template.prompt, languageName), template.name,
        )
        jobs.enqueue(job)
        return job.id
    }

    /**
     * "Summarize right after transcribing": queue a summary with the first built-in template once a transcript lands.
     * Skipped when the switch is off, when no AI service can run (the job would only fail and sit in the queue as
     * stuck), and when the clip already has a summary — a hand-edited result is never replaced by a machine one.
     */
    fun maybeAutoSummarize(base: String) {
        val cfg = configStore.config.value
        if (!cfg.autoSummarizeAfterTranscription) return
        if (com.nomily.app.core.llm.LlmChain.candidateChain(cfg).isEmpty()) return
        if (clipFile(base, ".summary.md").isFile) return
        val template = com.nomily.app.core.llm.DEFAULT_SUMMARIZE_TEMPLATES.first()
        val prompt = com.nomily.app.llm.localizedTemplatePrompt(localized(), template)
        jobs.enqueue(
            com.nomily.app.jobs.WorkJob.summarize(base, com.nomily.app.core.llm.withLanguageInstruction(prompt, null), template.name),
        )
    }

    // ── "Transcript ready" banner ─────────────────────────────────────────

    private val bannerQueue = com.nomily.app.core.clips.TranscriptionBanners()
    private val _transcribedBanner = MutableStateFlow<com.nomily.app.core.clips.TranscriptionBanners.Banner?>(null)
    /** The "transcript ready" toast on screen; tapping it opens the clip ([openInLibrary]). */
    val transcribedBanner: StateFlow<com.nomily.app.core.clips.TranscriptionBanners.Banner?> = _transcribedBanner.asStateFlow()

    private fun showTranscribedBanner(base: String) {
        val title = clipFile(base, ClipArtefact.TITLE_SUFFIX).takeIf(File::isFile)?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.nomily.app.ble.RecordingName.displayTitle(base)
        bannerQueue.enqueue(base, title, System.currentTimeMillis())
        _transcribedBanner.value = bannerQueue.current
    }

    fun dismissTranscribedBanner() {
        bannerQueue.dismissCurrent()
        _transcribedBanner.value = bannerQueue.current
    }

    /** Back in front: toasts for transcripts that finished a while ago would only confuse. */
    fun dropStaleBanners() {
        bannerQueue.dropStale(System.currentTimeMillis())
        _transcribedBanner.value = bannerQueue.current
    }

    /**
     * Translate the transcript or summary in the background, into `{base}.translated.txt` /
     * `{base}.summary.translated.md`. [languageCode] is remembered as `last_target_lang`, the same preference Live
     * translation starts from.
     */
    fun translateArtefact(clip: LocalClip, which: ClipArtefact, languageName: String, languageCode: String) {
        configStore.update { it.copy(lastTargetLang = languageCode) }
        jobs.enqueue(
            com.nomily.app.jobs.WorkJob.translate(clip.base, if (which == ClipArtefact.SUMMARY) "summary" else "transcript", languageName),
        )
    }

    suspend fun readSummary(base: String): String? = withContext(Dispatchers.IO) {
        clipFile(base, ".summary.md").takeIf(File::isFile)?.readText()?.takeIf { it.isNotBlank() }
    }

    fun hasTranscript(base: String): Boolean = runCatching { sourceText(base) }.isSuccess

    /** Convert the audio to M4A or WAV in the background; the result is offered for sharing on the clip page. */
    fun enqueueExport(clip: LocalClip, format: String) = jobs.enqueue(com.nomily.app.jobs.WorkJob.export(clip.base, format))

    // ── Library: knowledge search / batch export ───────────────────────────

    /** Body text per base, valid while the library listing stays the same (count + newest mtime). */
    private var knowledgeStamp: String? = null
    private val knowledgeDocs = HashMap<String, String>()

    /**
     * Search transcripts and summaries of [clips]; returns `base → snippet` for the matches. Bodies are read once per
     * library state and cached, so typing doesn't re-read every file.
     */
    suspend fun searchKnowledge(query: String, clips: List<LocalClip>): Map<String, String> = withContext(Dispatchers.IO) {
        val dir = library.dir
        val stamp = "${clips.size}@${clips.maxOfOrNull { (it.audio ?: it.transcript ?: it.summary)?.lastModified() ?: 0L }}"
        synchronized(knowledgeDocs) {
            if (stamp != knowledgeStamp) { knowledgeDocs.clear(); knowledgeStamp = stamp }
        }
        val hits = HashMap<String, String>()
        for (c in clips) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (c.transcript == null && c.summary == null) continue
            val text = synchronized(knowledgeDocs) { knowledgeDocs[c.base] } ?: com.nomily.app.core.text.KnowledgeSearch.SUFFIXES
                .mapNotNull { File(dir, c.base + it).takeIf(File::isFile)?.let { f -> runCatching { f.readText().trim() }.getOrNull() } }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .also { synchronized(knowledgeDocs) { knowledgeDocs[c.base] = it } }
            com.nomily.app.core.text.KnowledgeSearch.snippet(text, query)?.let { hits[c.base] = it }
        }
        hits
    }

    /**
     * Copy [clips] into the folder the user picked: one sub-folder per clip, named by its title, holding the audio
     * (original name) and readable text (`Transcript.txt`, `Summary.md`, …). Returns the failures as "title: reason".
     */
    suspend fun exportClips(clips: List<LocalClip>, tree: android.net.Uri): List<String> = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        val failures = mutableListOf<String>()
        val taken = mutableSetOf<String>()
        fun create(parent: android.net.Uri, mime: String, name: String) =
            android.provider.DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: throw java.io.IOException("Couldn't create $name")
        for (c in clips) {
            val title = clipTitle(c)
            try {
                val folder = create(root, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, exportFolderName(c, title, taken))
                val files = listOfNotNull(c.audio?.let { it to it.name }) + listOf(
                    ".txt" to "Transcript.txt",
                    ".translated.txt" to "Transcript (translated).txt",
                    ".summary.md" to "Summary.md",
                    ".summary.translated.md" to "Summary (translated).md",
                ).mapNotNull { (suffix, name) -> File(library.dir, c.base + suffix).takeIf(File::isFile)?.let { it to name } }
                for ((src, name) in files) {
                    val mime = if (src == c.audio) "audio/*" else "text/plain"
                    val out = create(folder, mime, name)
                    resolver.openOutputStream(out)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                        ?: throw java.io.IOException("Couldn't write $name")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Export of ${c.base} failed", e)
                failures += "$title: ${e.message}"
            }
        }
        failures
    }

    /** Folder name from the display title; path separators stripped, clashes become "Title (2)". */
    private fun exportFolderName(c: LocalClip, title: String, taken: MutableSet<String>): String {
        val base = title.replace('/', '-').replace(':', '-').trim().ifEmpty { c.base }.take(80)
        var candidate = base
        var n = 2
        while (!taken.add(candidate)) candidate = "$base (${n++})"
        return candidate
    }

    fun mergeClips(older: LocalClip, newer: LocalClip) {
        if (_ui.value.playing in listOf(older.audio?.name, newer.audio?.name)) stopPlayback()
        val dir = File(getApplication<Application>().filesDir, "clips")
        viewModelScope.launch {
            set { it.copy(busy = str(R.string.library_merging), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val a = older.audio ?: throw IllegalStateException("missing audio: ${older.name}")
                    val b = newer.audio ?: throw IllegalStateException("missing audio: ${newer.name}")
                    com.nomily.app.audio.AudioMerger.merge(a, b, dir)
                    // The sources go to the trash, not away: if the merged audio turns out wrong, both originals can be restored.
                    // No download record: the files are still on this phone, so the device list must not flip them back to "not downloaded".
                    library.moveToTrash(
                        listOf(older, newer).map {
                            com.nomily.app.data.LibraryStore.TrashRequest(
                                it.name, clipTitle(it),
                                com.nomily.app.core.clips.ClipTrash.wholeClipFiles(library.dir, it.base, it.audio),
                                takesAudio = false,
                            )
                        },
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Merge failed", e)
                set { it.copy(error = failed(R.string.library_merge_failed, e)) }
            }
            set { it.copy(busy = null) }
            refreshClips()
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
        val sources = library.manifest.sources()

        val list = byBase.map { (base, group) ->
            val audio = group.firstOrNull { ClipArtefact.of(it.name) == ClipArtefact.AUDIO }
            val name = audio?.name ?: base
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
                source = sources[name] ?: com.nomily.app.core.clips.ClipManifest.Source(
                    com.nomily.app.core.clips.ClipManifest.Source.Kind.IMPORTED,
                ),
                unread = library.reads.isUnread(name, (audio ?: group.first()).lastModified()),
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
        if (clip.audio?.name == _ui.value.playing) stopPlayback()
        val others = when (what) {
            ClipArtefact.AUDIO -> clip.transcript != null || clip.summary != null
            ClipArtefact.TRANSCRIPT -> clip.hasAudio || clip.summary != null
            ClipArtefact.SUMMARY -> clip.hasAudio || clip.transcript != null
        }
        val files = com.nomily.app.data.LibraryStore.artefactFiles(library.dir, clip.base, what, others)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                library.moveToTrash(
                    listOf(
                        com.nomily.app.data.LibraryStore.TrashRequest(
                            clip.name, clipTitle(clip), files, takesAudio = what == ClipArtefact.AUDIO,
                        ),
                    ),
                )
            }
            refreshClips()
        }
    }

    /**
     * Move whole clips (audio, text, sidecars) into the trash. Returns the titles of clips that couldn't be moved.
     */
    suspend fun trashClips(clips: List<LocalClip>): List<String> {
        if (clips.any { it.audio?.name == _ui.value.playing }) stopPlayback()
        val requests = clips.map {
            com.nomily.app.data.LibraryStore.TrashRequest(
                it.name, clipTitle(it),
                com.nomily.app.core.clips.ClipTrash.wholeClipFiles(library.dir, it.base, it.audio),
                takesAudio = true,
            )
        }
        withContext(Dispatchers.IO) { library.moveToTrash(requests) }
        refreshClips()
        return requests.filter { r -> r.files.any { it.exists() } }.map { it.title }
    }

    fun restoreFromTrash(items: List<com.nomily.app.core.clips.ClipTrash.Item>): Boolean {
        val ok = items.map { library.restore(it) }.all { it }
        refreshClips()
        return ok
    }

    fun purgeTrash(items: List<com.nomily.app.core.clips.ClipTrash.Item>) = items.forEach(library::purge)

    fun purgeAllTrash() = library.purgeAll()

    fun purgeExpiredTrash() = library.purgeExpired()

    fun markSeen(clip: LocalClip) {
        if (!clip.unread) return
        library.reads.markSeen(clip.name)
        set { ui -> ui.copy(clips = ui.clips.map { if (it.base == clip.base) it.seen() else it }) }
    }

    private fun clipTitle(c: LocalClip): String = c.title ?: com.nomily.app.ble.RecordingName.displayTitle(c.name)

    /** Base name format like `20260730155017` (or a merged `A+B`); returns null if parsing fails (imported external files may not follow this pattern). */
    private fun parseRecordedAt(stem: String): Date? = com.nomily.app.ble.RecordingName.date(stem)

    /** Abort any ongoing retrieval. The client also notifies the device to stop and flushes remaining packets. */
    /** Request navigation to a specific sub‑page in Settings (used together with switching the tab to Settings). */
    fun openSettingsAt(target: SettingsTarget) = set { it.copy(settingsTarget = target) }

    fun settingsTargetHandled() = set { it.copy(settingsTarget = null) }

    fun cancelFetch() {
        downloadQueue.clear()
        publishDownloadQueue()
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
        if (open(f, reportError = true)) resume()
    }

    /**
     * Load [f] paused at 0 so the detail page's inline player has a real duration before the first tap, as iOS
     * `AudioPlaybackController.load`. Does nothing when this file is already loaded, or when another file is
     * **playing** (opening a detail page must not cut off audio the user started elsewhere).
     */
    fun load(f: File) {
        if (player != null && (_ui.value.playing == f.name || !_ui.value.playPaused)) return
        open(f, reportError = false)
    }

    /** Jump [f] to [ms] and keep it playing — the transcript's tap-a-line gesture. Loads [f] first if needed. */
    fun seekAndPlay(f: File, ms: Int) {
        if (!(player != null && _ui.value.playing == f.name) && !open(f, reportError = true)) return
        seekTo(ms)
        if (_ui.value.playPaused) resume()
    }

    /** ±15 s buttons: relative seek that keeps the current play/pause state. */
    fun skipBy(f: File, deltaMs: Int) {
        if (!(player != null && _ui.value.playing == f.name) && !open(f, reportError = true)) return
        seekTo(_ui.value.playPositionMs + deltaMs)
    }

    /**
     * Sets the speed and keeps playing at it. `setPlaybackParams` on a paused player **starts** it, so while
     * paused only the value is stored; [startPlayer] applies it on the next start.
     */
    fun setPlaybackRate(rate: Float) {
        set { it.copy(playRate = rate) }
        val p = player ?: return
        if (!_ui.value.playPaused) runCatching { p.playbackParams = p.playbackParams.setSpeed(rate) }
    }

    /** `start()` + the user's speed. Every place that starts audio goes through here. */
    private fun startPlayer(p: MediaPlayer) {
        p.start()
        val rate = _ui.value.playRate
        runCatching { if (p.playbackParams.speed != rate) p.playbackParams = p.playbackParams.setSpeed(rate) }
    }

    /** Prepares [f] as the loaded file, paused at 0, with position polling running. False when it can't be opened. */
    private fun open(f: File, reportError: Boolean): Boolean {
        stopPlayback()
        set { it.copy(playError = null) }
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
            set { it.copy(playing = f.name, playPaused = true, playPositionMs = 0, playDurationMs = mp.duration) }
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
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            runCatching { player?.release() }
            player = null
            // Inline in the player; the library card covers playback started from a list row.
            set {
                it.copy(
                    playError = f.name to describe(e),
                    error = if (reportError) failed(R.string.device_files_operation_failed, e) else it.error,
                )
            }
            return false
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
        runCatching { startPlayer(p) }
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
            runCatching { startPlayer(p) }
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
