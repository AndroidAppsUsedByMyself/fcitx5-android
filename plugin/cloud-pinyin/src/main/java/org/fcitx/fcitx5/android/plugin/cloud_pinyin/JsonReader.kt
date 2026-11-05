/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Minimal bounded JSON reader.
 *
 * Only what the cloud backends need: no library, no streaming, fixed nesting limit, and
 * strict typing. Any malformed input throws [JsonParseException] and the caller returns
 * an empty candidate.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Number(val raw: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

class JsonParseException(message: String) : Exception(message)

class JsonReader(
    private val text: String,
    private val maxDepth: Int = 8,
    private val maxLength: Int = 8 * 1024
) {

    private var pos = 0

    fun read(): JsonValue {
        if (text.length > maxLength) throw JsonParseException("input too large")
        val value = value(0)
        skipWhitespace()
        if (pos != text.length) throw JsonParseException("trailing data")
        return value
    }

    private fun value(depth: Int): JsonValue {
        if (depth > maxDepth) throw JsonParseException("nesting too deep")
        skipWhitespace()
        if (pos >= text.length) throw JsonParseException("unexpected end")
        return when (val c = text[pos]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> JsonValue.Str(string())
            't', 'f', 'n' -> literal()
            else -> if (c == '-' || c.isDigit()) number() else throw JsonParseException("unexpected '$c'")
        }
    }

    private fun obj(depth: Int): JsonValue {
        expect('{')
        val fields = mutableMapOf<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            fields[key] = value(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.Obj(fields)
                }
                else -> throw JsonParseException("expected ',' or '}'")
            }
        }
    }

    private fun arr(depth: Int): JsonValue {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(value(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.Arr(items)
                }
                else -> throw JsonParseException("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) throw JsonParseException("unterminated string")
            when (val c = text[pos++]) {
                '"' -> return out.toString()
                '\\' -> out.append(escape())
                else -> {
                    if (c < ' ') throw JsonParseException("control character in string")
                    out.append(c)
                }
            }
        }
    }

    private fun escape(): Char = when (val c = next()) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            if (pos + 4 > text.length) throw JsonParseException("truncated escape")
            val hex = text.substring(pos, pos + 4)
            pos += 4
            hex.toIntOrNull(16)?.toChar() ?: throw JsonParseException("bad escape")
        }
        else -> throw JsonParseException("bad escape '$c'")
    }

    private fun literal(): JsonValue = when {
        text.startsWith("true", pos) -> {
            pos += 4
            JsonValue.Bool(true)
        }
        text.startsWith("false", pos) -> {
            pos += 5
            JsonValue.Bool(false)
        }
        text.startsWith("null", pos) -> {
            pos += 4
            JsonValue.Null
        }
        else -> throw JsonParseException("bad literal")
    }

    private fun number(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        if (start == pos) throw JsonParseException("bad number")
        return JsonValue.Number(text.substring(start, pos))
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun next(): Char {
        if (pos >= text.length) throw JsonParseException("unexpected end")
        return text[pos++]
    }

    private fun expect(c: Char) {
        if (next() != c) throw JsonParseException("expected '$c'")
    }
}
