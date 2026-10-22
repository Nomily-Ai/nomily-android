package com.nomily.app.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android [SpeechRecognizer] as the on-device caption engine for the phone microphone (iOS `PlaudLiveTranscriber`).
 *
 * The recogniser would normally open the microphone itself and fight [com.nomily.app.audio.PhoneMicRecorder] for it,
 * so the recording's own PCM is piped in through `EXTRA_AUDIO_SOURCE` — a platform feature of **API 33+**. Older
 * devices get [isSupported] = false and the caller shows the "captions unavailable" hint (the recording is unaffected).
 *
 * One recognizer session ends at the first pause (`onResults`) or error; a new one is started at once on a fresh pipe.
 * PCM is queued meanwhile (≤ [QUEUE_CAP] chunks ≈ 10 s) so the gap between sessions loses nothing. Repeated errors in
 * a row (no model for the language, busy service…) end the engine with [LiveAsrEvent.Closed].
 *
 * ⚠️ Needs a real device and a recogniser service to verify; the pipe/endpointing behaviour is service-dependent.
 */
class SpeechRecognizerLiveAsr(
    private val context: Context,
    private val lang: String,
) : LiveAsr {

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var events: ((LiveAsrEvent) -> Unit)? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0
    private var writer: Thread? = null

    override fun connect(onEvent: (LiveAsrEvent) -> Unit) {
        check(isSupported(context)) { "On-device speech recognition is unavailable." }
        this.events = onEvent
        writer = Thread(::pump, "caption-pcm-writer").apply { isDaemon = true; start() }
        main.post { startSession() }
    }

    override fun send(frames: ByteArray) {
        if (stopped) return
        if (queue.size >= QUEUE_CAP) queue.poll()
        queue.offer(frames.copyOf())
    }

    override fun close() {
        if (stopped) return
        stopped = true
        // Closing the pipe is the end-of-audio signal: the recogniser then returns what it still holds.
        closePipe()
        main.postDelayed({
            runCatching { recognizer?.destroy() }
            recognizer = null
            events?.invoke(LiveAsrEvent.Closed(null))
            events = null
        }, DRAIN_MS)
    }

    private fun pump() {
        while (!stopped) {
            val sink = out
            if (sink == null) {
                Thread.sleep(20)
                continue
            }
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            try {
                sink.write(chunk)
                sink.flush()
            } catch (_: IOException) {
                // The session just ended; this chunk is lost (≤100 ms).
            }
        }
    }

    private fun closePipe() {
        val sink = out
        out = null
        runCatching { sink?.close() }
    }

    private fun startSession() {
        if (stopped) return
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val rec = recognizer ?: createRecognizer().also { recognizer = it }
            rec.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (Build.VERSION.SDK_INT >= 33) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            }
            rec.startListening(intent)
            out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        } catch (e: Exception) {
            Log.w(TAG, "recognizer session failed to start: ${e.message}")
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun restart() {
        closePipe()
        if (stopped) return
        main.postDelayed({ startSession() }, RESTART_DELAY_MS)
    }

    private fun fail(message: String) {
        if (stopped) return
        stopped = true
        closePipe()
        runCatching { recognizer?.destroy() }
        recognizer = null
        events?.invoke(LiveAsrEvent.Closed(message))
        events = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { events?.invoke(LiveAsrEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            events?.invoke(LiveAsrEvent.Final(best(results).orEmpty()))
            restart()
        }

        override fun onError(error: Int) {
            if (stopped) return
            when (error) {
                // Silence / nothing heard: normal between sentences.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    events?.invoke(LiveAsrEvent.Final(""))
                    restart()
                }
                else -> {
                    if (++consecutiveErrors >= MAX_ERRORS || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        fail("SpeechRecognizer error $error")
                    } else {
                        restart()
                    }
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "SpeechRecognizerLiveAsr"
        private const val QUEUE_CAP = 100
        private const val DRAIN_MS = 900L
        private const val RESTART_DELAY_MS = 150L
        private const val MAX_ERRORS = 5

        /** Needs the audio-source extra (API 33) and an installed recognition service. */
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)
    }
}
