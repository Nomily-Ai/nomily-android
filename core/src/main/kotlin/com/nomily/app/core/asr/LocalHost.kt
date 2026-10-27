package com.nomily.app.core.asr

/**
 * Accept a pasted URL as a host: `http://1.2.3.4:12300/` → `1.2.3.4`.
 *
 * Without this, a pasted URL builds `http://http://1.2.3.4:12300/health`, which can only fail, and the user sees
 * "cannot connect" with no hint that extra characters came along. A trailing port is stripped only when there is
 * exactly one colon — IPv6 addresses contain several.
 */
fun normalizedLocalHost(raw: String): String {
    var host = raw.trim()
    for (scheme in listOf("http://", "https://")) {
        if (host.lowercase().startsWith(scheme)) host = host.drop(scheme.length)
    }
    host = host.trimEnd('/')
    if (host.count { it == ':' } == 1) {
        val port = host.substringAfterLast(':')
        if (port.isNotEmpty() && port.all { it.isDigit() }) host = host.substringBeforeLast(':')
    }
    return host
}
