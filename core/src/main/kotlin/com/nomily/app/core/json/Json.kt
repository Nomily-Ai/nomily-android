package com.nomily.app.core.json

/**
 * Minimal JSON — zero dependencies.
 *
 * Reads and writes the service `config.json` ([com.nomily.app.core.config.AppConfig]).
 *
 * Deliberately avoids third‑party libraries: `:core` must stay dependency‑free, so we don’t pull in kotlinx‑serialization just for two JSON uses. The parsed result uses the most primitive Kotlin types:
 * `Map<String, Any?>` / `List<Any?>` / `String` / `Long` / `Double` / `Boolean` / `null`.
 */

class JsonSyntaxException(message: String) : RuntimeException(message)

class JsonParser(private val src: String) {
    private var i = 0

    fun parse(): Any? {
        val v = parseValue()
        skipWs()
        if (i != src.length) fail("There is still extra content after parsing")
        return v
    }

    private fun parseValue(): Any? {
        skipWs()
        if (i >= src.length) fail("Content is empty")
        return when (val c = src[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("Unexpected character '$c'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            out[key] = parseValue()
            skipWs()
            when (val c = peek()) {
                ',' -> i++
                '}' -> { i++; return out }
                else -> fail("Unexpected character '$c' in object")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') { i++; return out }
        while (true) {
            out.add(parseValue())
            skipWs()
            when (val c = peek()) {
                ',' -> i++
                ']' -> { i++; return out }
                else -> fail("Unexpected character '$c' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) fail("String not closed")
            when (val c = src[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= src.length) fail("Escape incomplete")
                    when (val e = src[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > src.length) fail("\\u escape is incomplete")
                            sb.append(src.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("Unknown escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = i
        if (peek() == '-') i++
        while (i < src.length && src[i] in '0'..'9') i++
        var isFloat = false
        if (i < src.length && src[i] == '.') {
            isFloat = true; i++
            while (i < src.length && src[i] in '0'..'9') i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            isFloat = true; i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i] in '0'..'9') i++
        }
        val text = src.substring(start, i)
        if (text.isEmpty() || text == "-") fail("Invalid number format")
        return if (isFloat) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
    }

    private fun <T> parseLiteral(literal: String, value: T): T {
        if (!src.startsWith(literal, i)) fail("Expected $literal")
        i += literal.length
        return value
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun peek(): Char {
        if (i >= src.length) fail("Unexpected end of content")
        return src[i]
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("Expected '$c', got '${src[i]}'")
        i++
    }

    private fun fail(msg: String): Nothing =
        throw JsonSyntaxException("$msg (offset $i)")
}

/** Generate compact JSON. Non‑ASCII characters are emitted directly, not escaped. */
fun writeJson(value: Any?): String {
    val sb = StringBuilder()
    writeJson(value, sb)
    return sb.toString()
}

private fun writeJson(value: Any?, sb: StringBuilder) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(if (value) "true" else "false")
        is Int, is Long -> sb.append(value.toString())
        is Double -> sb.append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
        is String -> writeJsonString(value, sb)
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) sb.append(',')
                first = false
                writeJsonString(k.toString(), sb)
                sb.append(':')
                writeJson(v, sb)
            }
            sb.append('}')
        }
        is List<*> -> {
            sb.append('[')
            value.forEachIndexed { idx, v ->
                if (idx > 0) sb.append(',')
                writeJson(v, sb)
            }
            sb.append(']')
        }
        else -> writeJsonString(value.toString(), sb)
    }
}

private fun writeJsonString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}
