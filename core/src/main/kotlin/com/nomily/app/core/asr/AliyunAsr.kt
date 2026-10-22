package com.nomily.app.core.asr

import com.nomily.app.core.config.AppConfig
import com.nomily.app.core.json.JsonParser
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Aliyun NLS file transcription — the pure half: request signing, URL building, result parsing and the
 * `asr_providers.cloud.aliyun` config shape. The networking lives in `:app`'s `AliyunFileAsr`.
 *
 * Two signature schemes, both HMAC-SHA1:
 *  - **OSS V1** signs headers: `VERB\n\n<content-type>\n<date>\n/<bucket>/<key>`, key = the AccessKey secret.
 *  - **POP RPC** signs the sorted, percent-encoded query: `<METHOD>&%2F&<encoded query>`, key = `secret + "&"`.
 */
object AliyunAsr {
    const val PROVIDER = "aliyun"
    const val DEFAULT_REGION = "cn-shanghai"
    const val DEFAULT_ENDPOINT = "https://nls-meta.cn-shanghai.aliyuncs.com"
    const val API_VERSION = "2018-08-17"
    const val POLL_INTERVAL_MS = 4_000L
    const val MAX_WAIT_MS = 15 * 60 * 1_000L
    const val PRESIGN_VALIDITY_SECONDS = 2 * 3_600L

    const val STATUS_DONE = 21000000
    private val STATUS_PENDING = setOf(21050000, 21050001)

    // ── Encoding and HMAC ──────────────────────────────────────────────

    /** RFC 3986 percent-encoding over UTF-8 with Aliyun's reading: only `A-Za-z0-9-_.~` stay literal (so `+` → `%20`, `*` → `%2A`). */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val literal = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (literal) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun hmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return base64(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    // `java.util.Base64` needs API 26 and minSdk is 24; `:core` can't use android.util.Base64.
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    // ── POP RPC ────────────────────────────────────────────────────────

    /** The common RPC parameters plus [extra], sorted by name — the order the signature is computed in. */
    fun rpcParams(
        action: String,
        version: String,
        region: String?,
        accessKeyId: String,
        nonce: String,
        timestamp: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): List<Pair<String, String>> = (
        listOfNotNull(
            "Action" to action,
            "AccessKeyId" to accessKeyId,
            "Format" to "JSON",
            region?.let { "RegionId" to it },
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureNonce" to nonce,
            "Timestamp" to timestamp,
            "Version" to version,
        ) + extra
        ).sortedBy { it.first }

    /** `k=v&k=v` with both sides [encode]d; [params] must already be sorted. */
    fun canonicalQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

    fun rpcStringToSign(method: String, params: List<Pair<String, String>>): String =
        "$method&${encode("/")}&${encode(canonicalQuery(params))}"

    fun rpcSignature(method: String, params: List<Pair<String, String>>, accessKeySecret: String): String =
        hmacSha1Base64("$accessKeySecret&", rpcStringToSign(method, params))

    /** The signed parameters as one form/query string: the canonical query plus `Signature`. Identical for GET (query) and POST (body). */
    fun signedQuery(method: String, params: List<Pair<String, String>>, accessKeySecret: String): String =
        canonicalQuery(params) + "&Signature=" + encode(rpcSignature(method, params, accessKeySecret))

    /** The `Task` JSON for `SubmitTask`. */
    fun submitTaskJson(appKey: String, fileLink: String): String = com.nomily.app.core.json.writeJson(
        linkedMapOf(
            "appkey" to appKey,
            "file_link" to fileLink,
            "version" to "4.0",
            "enable_words" to false,
            "enable_sample_rate_adaptive" to true,
            "auto_split" to false,
        ),
    )

    // ── OSS ────────────────────────────────────────────────────────────

    fun ossHost(bucket: String, region: String) = "$bucket.oss-$region.aliyuncs.com"

    fun ossObjectUrl(bucket: String, region: String, key: String) = "https://${ossHost(bucket, region)}/$key"

    fun ossStringToSign(verb: String, contentType: String, dateOrExpires: String, bucket: String, key: String) =
        "$verb\n\n$contentType\n$dateOrExpires\n/$bucket/$key"

    /** `Authorization` header value for a header-signed request. */
    fun ossAuthorization(
        verb: String, contentType: String, date: String, bucket: String, key: String,
        accessKeyId: String, accessKeySecret: String,
    ): String = "OSS $accessKeyId:" + hmacSha1Base64(accessKeySecret, ossStringToSign(verb, contentType, date, bucket, key))

    /** Private-object download link valid until [expiresEpochSeconds]. */
    fun presignedGetUrl(
        bucket: String, region: String, key: String,
        accessKeyId: String, accessKeySecret: String, expiresEpochSeconds: Long,
    ): String {
        val signature = hmacSha1Base64(accessKeySecret, ossStringToSign("GET", "", expiresEpochSeconds.toString(), bucket, key))
        return ossObjectUrl(bucket, region, key) +
            "?OSSAccessKeyId=${encode(accessKeyId)}&Expires=$expiresEpochSeconds&Signature=${encode(signature)}"
    }

    fun mimeType(ext: String): String = when (ext.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "wma" -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }

    // ── GetTaskResult ──────────────────────────────────────────────────

    sealed class Poll {
        /** Finished; [resultJson] is the `Result` string. */
        data class Done(val resultJson: String?) : Poll()
        object Running : Poll()
        data class Failed(val detail: String) : Poll()
    }

    fun parsePoll(body: String): Poll {
        val m = JsonParser(body).parse() as? Map<*, *> ?: return Poll.Failed("response is not a JSON object")
        val code = when (val c = m["StatusCode"]) {
            is Number -> c.toInt()
            is String -> c.toIntOrNull() ?: 0
            else -> 0
        }
        return when {
            code == STATUS_DONE -> Poll.Done(m["Result"] as? String)
            code in STATUS_PENDING -> Poll.Running
            else -> Poll.Failed("StatusCode=$code StatusText=${m["StatusText"] as? String ?: ""}")
        }
    }

    /** `TaskId` of a `SubmitTask` response, or the server's `StatusText` / raw body as the failure detail. */
    fun parseSubmit(body: String): Pair<String?, String> {
        val m = runCatching { JsonParser(body).parse() as? Map<*, *> }.getOrNull()
        val id = (m?.get("TaskId") as? String)?.takeIf { it.isNotEmpty() }
        return id to ((m?.get("StatusText") as? String) ?: body.take(200).ifEmpty { "no TaskId" })
    }

    /**
     * 4.0 result shape: `Result` is a JSON **string** holding `bands[].sentences[]` with millisecond timings and no speakers.
     * @throws AsrEmptyException when nothing was recognised
     */
    fun parseResult(resultJson: String?, locale: String): AsrResult {
        val root = resultJson?.let { runCatching { JsonParser(it).parse() as? Map<*, *> }.getOrNull() }
        val bands = root?.get("bands") as? List<*> ?: throw AsrEmptyException()
        val segments = mutableListOf<AsrSegment>()
        for (band in bands) {
            val sentences = (band as? Map<*, *>)?.get("sentences") as? List<*> ?: continue
            for (s in sentences) {
                val sentence = s as? Map<*, *> ?: continue
                val text = sentence["text"] as? String ?: continue
                if (text.isEmpty()) continue
                val begin = ((sentence["begin_time"] as? Number)?.toDouble() ?: 0.0) / 1000
                val end = maxOf(begin, ((sentence["end_time"] as? Number)?.toDouble() ?: 0.0) / 1000)
                segments += AsrSegment(speaker = null, text = text, start = begin, end = end, duration = end - begin)
            }
        }
        if (segments.isEmpty()) throw AsrEmptyException()
        return AsrResult(text = segments.joinToString("") { it.text }, segments = segments, provider = PROVIDER, locale = locale)
    }
}

/**
 * `asr_providers.cloud.aliyun`: `{endpoint, model, values{appKey, ossBucket, region}, secrets{accessKeyId, accessKeySecret}}`.
 * `secrets` is in-memory only (the platform store moves it out of the file as `asr.cloud.aliyun.<field>`).
 */
data class AliyunConfig(
    val accessKeyId: String = "",
    val accessKeySecret: String = "",
    val appKey: String = "",
    val ossBucket: String = "",
    val region: String = "",
) {
    /** Region with the default applied. */
    val effectiveRegion: String get() = region.trim().ifEmpty { AliyunAsr.DEFAULT_REGION }

    /** Everything file transcription needs; the credential probe only needs the AccessKey pair. */
    val isComplete: Boolean
        get() = accessKeyId.isNotBlank() && accessKeySecret.isNotBlank() && appKey.isNotBlank() && ossBucket.isNotBlank()

    val isBlank: Boolean
        get() = accessKeyId.isEmpty() && accessKeySecret.isEmpty() && appKey.isEmpty() && ossBucket.isEmpty() && region.isEmpty()
}

private const val CLOUD = "cloud"

/** The Aliyun entry out of the passthrough `cloud` map; `null` when absent. */
val AppConfig.AsrProviders.aliyun: AliyunConfig?
    get() {
        val entry = ((unknown[CLOUD] as? Map<*, *>)?.get(AliyunAsr.PROVIDER)) as? Map<*, *> ?: return null
        val values = entry["values"] as? Map<*, *>
        val secrets = entry["secrets"] as? Map<*, *>
        fun s(m: Map<*, *>?, k: String) = (m?.get(k) as? String).orEmpty()
        return AliyunConfig(s(secrets, "accessKeyId"), s(secrets, "accessKeySecret"), s(values, "appKey"), s(values, "ossBucket"), s(values, "region"))
    }

/**
 * Write [aliyun] into `cloud.aliyun`, keeping every other `cloud.*` entry and any fields of the Aliyun entry this
 * client doesn't edit (endpoint, model, unknown keys). A blank config removes the entry.
 */
fun AppConfig.AsrProviders.withAliyun(aliyun: AliyunConfig?): AppConfig.AsrProviders {
    val cloud = LinkedHashMap<String, Any?>((unknown[CLOUD] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value })
    if (aliyun == null || aliyun.isBlank) {
        cloud.remove(AliyunAsr.PROVIDER)
    } else {
        val old = (cloud[AliyunAsr.PROVIDER] as? Map<*, *>).orEmpty().entries.associateTo(LinkedHashMap<String, Any?>()) { it.key.toString() to it.value }
        fun merged(key: String, entries: List<Pair<String, String>>): Map<String, Any?> {
            val m = (old[key] as? Map<*, *>).orEmpty().entries.associateTo(LinkedHashMap<String, Any?>()) { it.key.toString() to it.value }
            entries.forEach { (k, v) -> if (v.isEmpty()) m.remove(k) else m[k] = v }
            return m
        }
        old.putIfAbsent("endpoint", AliyunAsr.DEFAULT_ENDPOINT)
        old["values"] = merged("values", listOf("appKey" to aliyun.appKey, "ossBucket" to aliyun.ossBucket, "region" to aliyun.region))
        old["secrets"] = merged("secrets", listOf("accessKeyId" to aliyun.accessKeyId, "accessKeySecret" to aliyun.accessKeySecret))
        cloud[AliyunAsr.PROVIDER] = old
    }
    val rest = LinkedHashMap(unknown)
    if (cloud.isEmpty()) rest.remove(CLOUD) else rest[CLOUD] = cloud
    return copy(unknown = rest)
}
