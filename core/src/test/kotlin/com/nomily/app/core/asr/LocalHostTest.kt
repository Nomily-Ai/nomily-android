package com.nomily.app.core.asr

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalHostTest {
    @Test
    fun pastedUrlsReduceToTheHost() {
        assertEquals("1.2.3.4", normalizedLocalHost(" http://1.2.3.4:12300/ "))
        assertEquals("asr.lan", normalizedLocalHost("HTTPS://asr.lan//"))
        assertEquals("1.2.3.4", normalizedLocalHost("1.2.3.4"))
        assertEquals("fe80::1", normalizedLocalHost("fe80::1"))
        assertEquals("host:abc", normalizedLocalHost("host:abc"))
    }
}
