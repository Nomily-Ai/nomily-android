package com.nomily.app.asr

import android.util.Log
import com.nomily.app.core.audio.OPUS_FRAME_SIZE
import com.nomily.app.core.audio.OPUS_PRESKIP
import com.nomily.app.core.audio.OPUS_SAMPLES_PER_FRAME
import com.nomily.app.core.audio.DNOTE_OGG_SERIAL
import com.nomily.app.core.audio.oggAudioPage
import com.nomily.app.core.audio.oggHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Two WebSocket clients for real‑time transcription.
 *
 * Common interface: `connect()` opens the connection and emits events to [onEvent], `send()` streams raw OPUS frames, `close()` finalizes.
 * Three event types: `Partial` (interim result, overwrites the current line) / `Final` (server‑finalized segment) / `Closed`.
 *
 * ⚠️ This layer **cannot be unit‑tested** (requires a real server); correctness must be verified on a device with a real key.
 */
sealed interface LiveAsrEvent {
    /** [translation] exists only for real‑time translation: one line of source text and one line of translated text. */
    data class Partial(val text: String, val translation: String = "") : LiveAsrEvent
    data class Final(val text: String, val translation: String = "") : LiveAsrEvent
    data class Closed(val error: String?) : LiveAsrEvent
}

interface LiveAsr {
    fun connect(onEvent: (LiveAsrEvent) -> Unit)
    fun send(frames: ByteArray)
    fun close()
}

private val wsClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        // Streaming receive: a long silence from the speaker must not cause the connection to be considered dead (silence of 30 s triggers a disconnect).
        // If it truly disconnects, onFailure is invoked and the upper layer will reconnect.
        .readTimeout(180, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

/**
 * Azure Speech streaming recognition — endpoint, handshake, and frame‑by‑frame processing.
 *
 * Key points:
 *  - `wss://{region}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1`
 *  - First send a `speech.config` text message, then send the **Ogg header** as the first binary audio message
 *  - Afterwards send an Ogg audio page every 10 frames (200 ms)
 *  - Responses are `speech.hypothesis` (interim) / `speech.phrase` (final) text messages
 */
class AzureLiveAsr(
    private val key: String,
    private val region: String,
    private val lang: String = "en-US",
) : LiveAsr {

    private val requestId = UUID.randomUUID().toString().replace("-", "").lowercase()
    private var ws: WebSocket? = null
    private var closed = false
    private var pageSeq = 2
    private var granule = OPUS_PRESKIP.toLong()
    private val frameBuf = ArrayList<ByteArray>(FRAMES_PER_PAGE)
    private var onEvent: ((LiveAsrEvent) -> Unit)? = null

    override fun connect(onEvent: (LiveAsrEvent) -> Unit) {
        require(key.isNotEmpty() && region.isNotEmpty()) { "Azure credentials are not configured." }
        this.onEvent = onEvent
        val url = "wss://$region.stt.speech.microsoft.com" +
            "/speech/recognition/conversation/cognitiveservices/v1" +
            "?language=$lang&format=detailed"
        val req = Request.Builder().url(url).addHeader("Ocp-Apim-Subscription-Key", key).build()
        ws = wsClient.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Azure WebSocket connected")
            webSocket.send(
                textMessage(
                    "speech.config",
                    JSONObject(
                        mapOf(
                            "context" to mapOf(
                                "system" to mapOf("name" to "dnote", "version" to "1.0"),
                                "os" to mapOf("platform" to "Android", "name" to "ARM"),
                                "audio" to mapOf("source" to mapOf("connectivity" to "Bluetooth")),
                            ),
                        ),
                    ).toString(),
                ),
            )
            webSocket.send(audioMessage(oggHeaders(SERIAL)).toByteString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parts = text.split("\r\n\r\n", limit = 2)
            if (parts.size < 2) return
            val headers = parts[0]
            val body = parts[1]
            when {
                headers.contains("speech.hypothesis") -> {
                    val t = runCatching { JSONObject(body).optString("Text") }.getOrNull()
                    if (!t.isNullOrEmpty()) onEvent?.invoke(LiveAsrEvent.Partial(t))
                }
                headers.contains("speech.phrase") -> {
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return
                    if (json.optString("RecognitionStatus") == "Success") {
                        val t = json.optString("DisplayText")
                        if (t.isNotEmpty()) onEvent?.invoke(LiveAsrEvent.Final(t))
                    }
                }
                headers.contains("turn.end") -> Log.i(TAG, "azure turn.end")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            reportClosed(t.message ?: t.javaClass.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            reportClosed(null)
        }
    }

    override fun send(frames: ByteArray) {
        if (closed) return
        var offset = 0
        while (offset < frames.size) {
            val end = minOf(offset + OPUS_FRAME_SIZE, frames.size)
            frameBuf.add(frames.copyOfRange(offset, end))
            if (frameBuf.size >= FRAMES_PER_PAGE) flush()
            offset = end
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flush()
        // Cancel immediately (not from the send callback) — otherwise the next connect would compete for resources with the previous teardown
        ws?.cancel()
        ws = null
        onEvent?.invoke(LiveAsrEvent.Closed(null))
        onEvent = null
    }

    private fun flush() {
        val socket = ws ?: return
        if (frameBuf.isEmpty()) return
        val frames = ArrayList(frameBuf)
        frameBuf.clear()
        granule += frames.size.toLong() * OPUS_SAMPLES_PER_FRAME
        val page = oggAudioPage(SERIAL, pageSeq++, granule, frames)
        socket.send(audioMessage(page).toByteString())
    }

    private fun reportClosed(error: String?) {
        if (closed) return
        closed = true
        onEvent?.invoke(LiveAsrEvent.Closed(error))
        onEvent = null
    }

    // ── Azure message envelope (Path/X-RequestId/X-Timestamp headers + body)──────────

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun textMessage(path: String, json: String): String =
        "Path: $path\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: ${timestamp()}\r\n" +
            "Content-Type: application/json\r\n" +
            "\r\n" + json

    /** Binary audio message: `[header length (2B big‑endian)][header][audio]`. */
    private fun audioMessage(audio: ByteArray): ByteArray {
        val header = (
            "Path: audio\r\n" +
                "X-RequestId: $requestId\r\n" +
                "X-Timestamp: ${timestamp()}\r\n" +
                "Content-Type: audio/ogg;codecs=opus"
            ).toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + header.size + audio.size)
        out[0] = ((header.size shr 8) and 0xFF).toByte()
        out[1] = (header.size and 0xFF).toByte()
        header.copyInto(out, 2)
        audio.copyInto(out, 2 + header.size)
        return out
    }

    private companion object {
        const val TAG = "AzureLiveAsr"
        const val FRAMES_PER_PAGE = 10          // 10 frames = 200 ms
        const val SERIAL = DNOTE_OGG_SERIAL
    }
}

/**
 * Streaming interface of the local faster‑whisper server:
 *
 *  - `ws://{host}:{port}/v1/listen` (**not** `port+1`, which is the batch transcription HTTP endpoint)
 *  - Send raw 40‑byte OPUS frames as binary messages; the server assembles them into 5‑second segments
 *  - Receives `{"text": "...", "is_final": bool}`
 *  - Close by sending `{"type": "CloseStream"}`, after which the server flushes the tail
 */
class LocalLiveAsr(private val host: String, private val port: Int) : LiveAsr {

    private var ws: WebSocket? = null
    private var closed = false
    private var onEvent: ((LiveAsrEvent) -> Unit)? = null

    override fun connect(onEvent: (LiveAsrEvent) -> Unit) {
        require(host.isNotEmpty() && port > 0) { "Local ASR server is not configured." }
        this.onEvent = onEvent
        val req = Request.Builder().url("ws://$host:$port/v1/listen").build()
        ws = wsClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val t = json.optString("text")
                    if (t.isEmpty()) return
                    onEvent(
                        if (json.optBoolean("is_final")) LiveAsrEvent.Final(t) else LiveAsrEvent.Partial(t),
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    reportClosed(t.message ?: t.javaClass.simpleName)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    reportClosed(null)
                }
            },
        )
    }

    override fun send(frames: ByteArray) {
        if (closed) return
        ws?.send(frames.toByteString())
    }

    override fun close() {
        if (closed) return
        closed = true
        ws?.send("""{"type":"CloseStream"}""")
        ws?.cancel()
        ws = null
        onEvent?.invoke(LiveAsrEvent.Closed(null))
        onEvent = null
    }

    private fun reportClosed(error: String?) {
        if (closed) return
        closed = true
        onEvent?.invoke(LiveAsrEvent.Closed(error))
        onEvent = null
    }
}

/**
 * Azure real‑time **translation**.
 *
 * Differs from [AzureLiveAsr] in three ways: endpoint is `/speech/translation/cognitiveservices/v1?from=&to=`,
 * an extra `X-ConnectionId` header, and the response includes both source and translated text (`Translation.Translations[0]`).
 * Audio framing, Ogg pagination, and message envelope are identical.
 */
class AzureLiveTranslation(
    private val key: String,
    private val region: String,
    private val fromLang: String = "en-US",
    private val toLang: String = "en",
) : LiveAsr {

    private val requestId = UUID.randomUUID().toString().replace("-", "").lowercase()
    private var ws: WebSocket? = null
    private var closed = false
    private var pageSeq = 2
    private var granule = OPUS_PRESKIP.toLong()
    private val frameBuf = ArrayList<ByteArray>(FRAMES_PER_PAGE)
    private var onEvent: ((LiveAsrEvent) -> Unit)? = null

    override fun connect(onEvent: (LiveAsrEvent) -> Unit) {
        require(key.isNotEmpty() && region.isNotEmpty()) { "Azure credentials are not configured." }
        this.onEvent = onEvent
        val url = "wss://$region.stt.speech.microsoft.com" +
            "/speech/translation/cognitiveservices/v1" +
            "?from=$fromLang&to=$toLang&api-version=1.0"
        val req = Request.Builder().url(url)
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .addHeader("X-ConnectionId", UUID.randomUUID().toString().lowercase())
            .build()
        ws = wsClient.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                azureTextMessage(
                    requestId, "speech.config",
                    JSONObject(
                        mapOf(
                            "context" to mapOf(
                                "system" to mapOf("name" to "dnote", "version" to "1.0"),
                                "os" to mapOf("platform" to "Android", "name" to "ARM"),
                                "audio" to mapOf("source" to mapOf("connectivity" to "Bluetooth")),
                            ),
                        ),
                    ).toString(),
                ),
            )
            webSocket.send(azureAudioMessage(requestId, oggHeaders(DNOTE_OGG_SERIAL)).toByteString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parts = text.split("\r\n\r\n", limit = 2)
            if (parts.size < 2) return
            val headers = parts[0]
            val json = runCatching { JSONObject(parts[1]) }.getOrNull() ?: return
            val bilingual = parseBilingual(json)
            if (bilingual.first.isEmpty() && bilingual.second.isEmpty()) return
            when {
                headers.contains("translation.hypothesis") || headers.contains("speech.hypothesis") ->
                    onEvent?.invoke(LiveAsrEvent.Partial(bilingual.first, bilingual.second))
                headers.contains("translation.phrase") || headers.contains("speech.phrase") -> {
                    if (json.optString("RecognitionStatus") == "Success") {
                        onEvent?.invoke(LiveAsrEvent.Final(bilingual.first, bilingual.second))
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            reportClosed(t.message ?: t.javaClass.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reportClosed(null)
    }

    /** Prefer `DisplayText` for the source; fall back to `Text`. Translation is taken from `Translation.Translations[0]`. */
    private fun parseBilingual(json: JSONObject): Pair<String, String> {
        val source = json.optString("DisplayText").ifEmpty { json.optString("Text") }
        val translations = json.optJSONObject("Translation")?.optJSONArray("Translations")
        val first = if (translations != null && translations.length() > 0) {
            translations.optJSONObject(0)
        } else {
            null
        }
        val translated = first?.let { it.optString("DisplayText").ifEmpty { it.optString("Text") } } ?: ""
        return source to translated
    }

    override fun send(frames: ByteArray) {
        if (closed) return
        var offset = 0
        while (offset < frames.size) {
            val end = minOf(offset + OPUS_FRAME_SIZE, frames.size)
            frameBuf.add(frames.copyOfRange(offset, end))
            if (frameBuf.size >= FRAMES_PER_PAGE) flush()
            offset = end
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flush()
        ws?.cancel()
        ws = null
        onEvent?.invoke(LiveAsrEvent.Closed(null))
        onEvent = null
    }

    private fun flush() {
        val socket = ws ?: return
        if (frameBuf.isEmpty()) return
        val frames = ArrayList(frameBuf)
        frameBuf.clear()
        granule += frames.size.toLong() * OPUS_SAMPLES_PER_FRAME
        socket.send(azureAudioMessage(requestId, oggAudioPage(DNOTE_OGG_SERIAL, pageSeq++, granule, frames)).toByteString())
    }

    private fun reportClosed(error: String?) {
        if (closed) return
        closed = true
        onEvent?.invoke(LiveAsrEvent.Closed(error))
        onEvent = null
    }

    private companion object {
        const val FRAMES_PER_PAGE = 10
    }
}

// ── Azure message envelope (shared by recognition and translation streams)────────────────────────────

private fun azureTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ss.SSS\'Z\'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

internal fun azureTextMessage(requestId: String, path: String, json: String): String =
    "Path: $path\r\n" +
        "X-RequestId: $requestId\r\n" +
        "X-Timestamp: ${azureTimestamp()}\r\n" +
        "Content-Type: application/json\r\n" +
        "\r\n" + json

internal fun azureAudioMessage(requestId: String, audio: ByteArray): ByteArray {
    val header = (
        "Path: audio\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: ${azureTimestamp()}\r\n" +
            "Content-Type: audio/ogg;codecs=opus"
        ).toByteArray(Charsets.UTF_8)
    val out = ByteArray(2 + header.size + audio.size)
    out[0] = ((header.size shr 8) and 0xFF).toByte()
    out[1] = (header.size and 0xFF).toByte()
    header.copyInto(out, 2)
    audio.copyInto(out, 2 + header.size)
    return out
}

private fun ByteArray.toByteString(): ByteString = toByteString(0, size)
