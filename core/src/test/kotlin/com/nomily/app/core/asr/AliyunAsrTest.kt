package com.nomily.app.core.asr

import com.nomily.app.core.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Expected signatures were computed with `openssl dgst -sha1 -hmac` / Python hmac, independent of this code. */
class AliyunAsrTest {
    @Test
    fun `encode follows the Aliyun rules`() {
        assertEquals("t%201%2B%2A", AliyunAsr.encode("t 1+*"))
        assertEquals("a-b_c.d~e", AliyunAsr.encode("a-b_c.d~e"))
        assertEquals("%E4%B8%AD", AliyunAsr.encode("中"))
        assertEquals("2026-10-22T08%3A00%3A00Z", AliyunAsr.encode("2026-10-22T08:00:00Z"))
    }

    @Test
    fun `base64 matches reference for every padding length`() {
        assertEquals("", AliyunAsr.base64(byteArrayOf()))
        assertEquals("Zg==", AliyunAsr.base64("f".toByteArray()))
        assertEquals("Zm8=", AliyunAsr.base64("fo".toByteArray()))
        assertEquals("Zm9v", AliyunAsr.base64("foo".toByteArray()))
        assertEquals("Zm9vYg==", AliyunAsr.base64("foob".toByteArray()))
    }

    @Test
    fun `rpc signature for GetTaskResult`() {
        val params = AliyunAsr.rpcParams(
            action = "GetTaskResult", version = "2018-08-17", region = "cn-shanghai",
            accessKeyId = "LTAIexample", nonce = "n-1", timestamp = "2026-10-22T08:00:00Z",
            extra = listOf("TaskId" to "t 1+*"),
        )
        assertEquals(
            "AccessKeyId=LTAIexample&Action=GetTaskResult&Format=JSON&RegionId=cn-shanghai&SignatureMethod=HMAC-SHA1" +
                "&SignatureNonce=n-1&TaskId=t%201%2B%2A&Timestamp=2026-10-22T08%3A00%3A00Z&Version=2018-08-17",
            AliyunAsr.canonicalQuery(params),
        )
        assertEquals(
            "GET&%2F&AccessKeyId%3DLTAIexample%26Action%3DGetTaskResult%26Format%3DJSON%26RegionId%3Dcn-shanghai" +
                "%26SignatureMethod%3DHMAC-SHA1%26SignatureNonce%3Dn-1%26TaskId%3Dt%25201%252B%252A" +
                "%26Timestamp%3D2026-10-22T08%253A00%253A00Z%26Version%3D2018-08-17",
            AliyunAsr.rpcStringToSign("GET", params),
        )
        assertEquals("pR5hjAl99uqB6UtCVG+EqrqYAO0=", AliyunAsr.rpcSignature("GET", params, "exampleSecret"))
        assertTrue(AliyunAsr.signedQuery("GET", params, "exampleSecret").endsWith("&Signature=pR5hjAl99uqB6UtCVG%2BEqrqYAO0%3D"))
    }

    @Test
    fun `oss header and presigned signatures`() {
        val secret = "OtxrzxIsfpFjA7SwPzILwy8Bw21TLhquhboDYROV"
        assertEquals(
            "OSS AKID:/5a5J7gtQ2QEWSa6K2oDX7CdeRQ=",
            AliyunAsr.ossAuthorization("PUT", "text/html", "Thu, 17 Nov 2005 18:49:58 GMT", "oss-example", "nelson", "AKID", secret),
        )
        assertEquals(
            "https://oss-example.oss-cn-hangzhou.aliyuncs.com/oss-api.pdf?OSSAccessKeyId=AKID&Expires=1141889120" +
                "&Signature=EwaNTn1erJGkimiJ9WmXgwnANLc%3D",
            AliyunAsr.presignedGetUrl("oss-example", "cn-hangzhou", "oss-api.pdf", "AKID", secret, 1141889120),
        )
    }

    @Test
    fun `poll states`() {
        assertEquals(AliyunAsr.Poll.Running, AliyunAsr.parsePoll("""{"StatusCode":21050001,"StatusText":"RUNNING"}"""))
        assertEquals(AliyunAsr.Poll.Running, AliyunAsr.parsePoll("""{"StatusCode":21050000}"""))
        assertEquals(AliyunAsr.Poll.Done("{}"), AliyunAsr.parsePoll("""{"StatusCode":21000000,"Result":"{}"}"""))
        val failed = AliyunAsr.parsePoll("""{"StatusCode":41050002,"StatusText":"FILE_DOWNLOAD_FAILED"}""")
        assertEquals("StatusCode=41050002 StatusText=FILE_DOWNLOAD_FAILED", assertIs<AliyunAsr.Poll.Failed>(failed).detail)
    }

    @Test
    fun `submit response`() {
        assertEquals("abc" to "ok", AliyunAsr.parseSubmit("""{"TaskId":"abc","StatusText":"ok"}"""))
        val (id, why) = AliyunAsr.parseSubmit("""{"StatusText":"INVALID_FILE_LINK"}""")
        assertNull(id)
        assertEquals("INVALID_FILE_LINK", why)
    }

    @Test
    fun `result bands become segments in seconds`() {
        val raw = """{"bands":[{"sentences":[
            {"text":"你好","begin_time":1500,"end_time":3200},
            {"text":"","begin_time":3300,"end_time":3400},
            {"text":"世界","begin_time":4000,"end_time":3900}]}]}"""
        val r = AliyunAsr.parseResult(raw, "zh-CN")
        assertEquals("你好世界", r.text)
        assertEquals(2, r.segments.size)
        assertEquals(1.5, r.segments[0].start)
        assertEquals(3.2, r.segments[0].end)
        assertEquals(0.0, r.segments[1].duration)   // end before begin is clamped
        assertEquals("aliyun", r.provider)
    }

    @Test
    fun `empty or malformed result is the empty-transcript error`() {
        assertFailsWith<AsrEmptyException> { AliyunAsr.parseResult(null, "") }
        assertFailsWith<AsrEmptyException> { AliyunAsr.parseResult("not json", "") }
        assertFailsWith<AsrEmptyException> { AliyunAsr.parseResult("""{"bands":[{"sentences":[]}]}""", "") }
    }

    @Test
    fun `config round trips and leaves other cloud entries alone`() {
        val json = """{"asr_providers":{"cloud":{
            "baidu":{"endpoint":"","values":{"x":"1"}},
            "aliyun":{"endpoint":"https://nls-meta.cn-shanghai.aliyuncs.com","model":"m","extra":7,
                      "values":{"appKey":"AK","ossBucket":"bkt","other":"keep"},
                      "secrets":{"accessKeyId":"id","accessKeySecret":"sec"}}}}}"""
        val cfg = AppConfig.parse(json)
        val a = assertIs<AliyunConfig>(cfg.asrProviders.aliyun)
        assertEquals(AliyunConfig("id", "sec", "AK", "bkt", ""), a)
        assertEquals("cn-shanghai", a.effectiveRegion)
        assertTrue(a.isComplete)

        val edited = cfg.asrProviders.withAliyun(a.copy(region = "cn-beijing", accessKeySecret = ""))
        val again = AppConfig.parse(cfg.copy(asrProviders = edited).toJson())
        assertEquals(AliyunConfig("id", "", "AK", "bkt", "cn-beijing"), again.asrProviders.aliyun)
        val cloud = again.asrProviders.unknown["cloud"] as Map<*, *>
        assertEquals(setOf("baidu", "aliyun"), cloud.keys)
        assertEquals(mapOf("endpoint" to "", "values" to mapOf("x" to "1")), cloud["baidu"])
        val entry = cloud["aliyun"] as Map<*, *>
        assertEquals(7L, entry["extra"])
        assertEquals("keep", (entry["values"] as Map<*, *>)["other"])
    }

    @Test
    fun `blank config removes only the aliyun entry`() {
        val base = AppConfig().asrProviders.withAliyun(AliyunConfig(appKey = "a"))
        assertEquals("a", base.aliyun?.appKey)
        assertEquals("https://nls-meta.cn-shanghai.aliyuncs.com", ((base.unknown["cloud"] as Map<*, *>)["aliyun"] as Map<*, *>)["endpoint"])
        val cleared = base.withAliyun(AliyunConfig())
        assertNull(cleared.aliyun)
        assertNull(cleared.unknown["cloud"])
    }
}
