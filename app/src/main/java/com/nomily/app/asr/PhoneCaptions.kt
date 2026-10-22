package com.nomily.app.asr

import android.content.Context
import android.util.Log
import com.nomily.app.R
import com.nomily.app.core.asr.LiveCaptionState
import com.nomily.app.core.config.AppConfig
import com.nomily.app.llm.LanguageService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live captions for the phone-microphone recording (iOS `LiveCaptionController`).
 *
 * Engine chain: `defaults.asr` order, Azure only (the microphone produces PCM, so the Opus-only providers are
 * skipped), then the on-device [SpeechRecognizerLiveAsr]. An Azure link that drops mid-take is re-opened a few
 * times; after that the on-device engine takes over and the lines already shown stay ([LiveCaptionState] is
 * engine-independent). With no engine at all, [State.notice] says so and the recording carries on — captions never
 * decide whether audio is kept.
 */
class PhoneCaptions(private val context: Context) {

    data class State(
        val caption: LiveCaptionState = LiveCaptionState(),
        /** "azure" / "ondevice"; empty = no engine. */
        val provider: String = "",
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: LiveAsr? = null
    private var cfg: AppConfig? = null
    private var lang = "en-US"
    private var azureRetries = 0
    @Volatile private var stopping = false
    /** Seconds of audio fed so far — the timestamp of a caption line. */
    @Volatile private var fedSeconds = 0.0

    fun start(config: AppConfig) {
        shutdown()
        _state.value = State()
        cfg = config
        lang = LanguageService.liveSourceLang(config)
        azureRetries = 0
        fedSeconds = 0.0
        stopping = false
        if (!open(skipAzure = false)) {
            _state.update { it.copy(notice = context.getString(R.string.phone_caption_unavailable)) }
        }
    }

    /** PCM16 mono 16 kHz from the recorder; copies what it keeps. */
    fun feed(pcm: ByteArray, length: Int) {
        fedSeconds += length / 32_000.0
        engine?.send(pcm.copyOf(length))
    }

    /** Stop the engine, wait briefly for the trailing phrase, and return the committed caption text. */
    suspend fun stop(): LiveCaptionState {
        stopping = true
        val e = engine
        engine = null
        e?.close()
        if (e != null) delay(DRAIN_MS)
        _state.update { it.copy(caption = it.caption.committed(fedSeconds)) }
        return _state.value.caption
    }

    private fun shutdown() {
        stopping = true
        engine?.close()
        engine = null
    }

    /** Open the first engine of the chain that connects; false when none does. */
    private fun open(skipAzure: Boolean): Boolean {
        val c = cfg ?: return false
        val chain = (listOf(c.defaults.asrPrimary) + c.defaults.asrFallbacks).map { it.lowercase() }.distinct()
        val names = chain.filter { it == "azure" && !skipAzure } + "ondevice"
        for (name in names) {
            val candidate: Pair<String, LiveAsr>? = when (name) {
                "azure" -> c.asrProviders.azure?.takeIf { it.key.isNotEmpty() && it.region.isNotEmpty() }
                    ?.let { "azure" to AzurePcmLiveAsr(it.key, it.region, lang) }
                else -> if (SpeechRecognizerLiveAsr.isSupported(context)) {
                    "ondevice" to SpeechRecognizerLiveAsr(context, lang)
                } else {
                    null
                }
            }
            val (provider, asr) = candidate ?: continue
            try {
                engine = asr
                asr.connect { ev -> onEvent(asr, ev) }
                _state.update { it.copy(provider = provider) }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "caption engine $name unavailable: ${e.message}")
                engine = null
            }
        }
        _state.update { it.copy(provider = "") }
        return false
    }

    private fun onEvent(source: LiveAsr, ev: LiveAsrEvent) {
        if (source !== engine) return
        when (ev) {
            is LiveAsrEvent.Partial -> _state.update { it.copy(caption = it.caption.withPartial(ev.text)) }
            is LiveAsrEvent.Final -> _state.update { it.copy(caption = it.caption.withFinal(ev.text, fedSeconds)) }
            is LiveAsrEvent.Closed -> if (!stopping) onDropped(source, ev.error)
        }
    }

    private fun onDropped(source: LiveAsr, error: String?) {
        Log.w(TAG, "caption engine dropped: ${error ?: "closed by the server"}")
        val wasAzure = _state.value.provider == "azure"
        engine = null
        source.close()
        if (wasAzure && azureRetries < MAX_AZURE_RETRIES) {
            azureRetries++
            if (open(skipAzure = false)) return
        }
        // Lines already shown stay: the caption state is shared by every engine.
        _state.update { it.copy(caption = it.caption.withPartial("")) }
        if (open(skipAzure = true)) {
            if (wasAzure) _state.update { it.copy(notice = context.getString(R.string.phone_azure_fell_back)) }
        } else {
            _state.update { it.copy(provider = "", notice = context.getString(R.string.phone_caption_unavailable)) }
        }
    }

    private companion object {
        const val TAG = "PhoneCaptions"
        const val DRAIN_MS = 1_000L
        const val MAX_AZURE_RETRIES = 2
    }
}
