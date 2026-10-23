package com.singularity_universe.explorer.data

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.json.JSONException

/** Text formats Android reports under an application/ type (scripts, data and markup). */
val TEXT_MIMES = setOf(
    "application/json",
    "application/ld+json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/x-javascript",
    "application/ecmascript",
    "application/x-sh",
    "application/x-shellscript",
    "application/x-csh",
    "application/x-python",
    "application/x-perl",
    "application/x-ruby",
    "application/x-httpd-php",
    "application/x-php",
    "application/sql",
    "application/x-sql",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
    "application/x-subrip",
    "application/x-tex",
    "application/x-latex",
    "application/x-troff",
    "application/rss+xml",
    "application/atom+xml",
    "application/x-wine-extension-ini",
)

/** Source, config and data files MimeTypeMap doesn't know; they're mapped to text/plain. */
val TEXT_EXTENSIONS = setOf(
    "kt", "kts", "gradle", "groovy", "java", "scala", "swift", "dart", "go", "rs", "c", "h", "cc", "cpp",
    "cxx", "hpp", "hh", "cs", "m", "mm", "py", "pyw", "rb", "php", "pl", "pm", "lua", "r", "jl", "ex", "exs",
    "erl", "hs", "clj", "elm", "vue", "svelte", "jsx", "tsx", "mjs", "cjs", "json5", "jsonc", "geojson",
    "sh", "bash", "zsh", "fish", "ksh", "bat", "cmd", "ps1", "psm1", "awk", "sed",
    "md", "markdown", "rst", "adoc", "org", "tex", "log", "nfo", "diff", "patch",
    "yml", "yaml", "toml", "ini", "cfg", "conf", "config", "properties", "prop", "env", "lock", "pro",
    "gitignore", "gitattributes", "gitmodules", "editorconfig", "dockerignore", "npmrc", "nvmrc",
    "sql", "graphql", "gql", "proto", "csv", "tsv", "srt", "vtt", "ass", "ssa", "sub", "plist", "rc",
)

/** Plain text, source code, scripts, and data/markup formats shown by the in-app text viewer. */
val FileItem.isText: Boolean
    get() = !isDirectory && (mimeType.startsWith("text/") || mimeType in TEXT_MIMES)

fun isJsonFile(name: String, mimeType: String): Boolean =
    mimeType == "application/json" || name.substringAfterLast('.', "").lowercase() in setOf("json", "geojson")

/**
 * For files whose type is unknown (no or an unusual extension, like README or Makefile): looks at
 * the start of the file. Text has no NUL bytes, few control characters, and decodes as UTF-8.
 */
fun looksLikeText(file: File): Boolean {
    val head = ByteArray(SNIFF_BYTES)
    val read = runCatching { file.inputStream().use { it.read(head) } }.getOrNull() ?: return false
    if (read <= 0) return true // Empty: open it as (empty) text.
    var control = 0
    for (i in 0 until read) {
        val b = head[i].toInt() and 0xFF
        if (b == 0) return false
        if (b < 0x20 && b != '\n'.code && b != '\r'.code && b != '\t'.code && b != 0x0C && b != 0x1B) control++
    }
    if (control * 100 > read) return false
    // The sample may end in the middle of a character: that alone doesn't make it binary.
    return decodeUtf8(head, 0, read, allowTruncatedEnd = true) != null
}

/** A text file as loaded for viewing or editing. Line endings are always "\n" in [text]. */
class TextContent(
    val text: String,
    /** The file uses Windows line endings; saving writes them back. */
    val crlf: Boolean,
    /** Only the first [MAX_PREVIEW_BYTES] were read. */
    val truncated: Boolean,
    /** False when the file isn't valid UTF-8: [text] then shows replacement characters. */
    val utf8: Boolean,
    /** The file starts with a UTF-8 byte order mark; saving keeps it. */
    val bom: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    /** Saving would lose nothing: the whole file was read and decoded exactly. */
    val editable: Boolean get() = utf8 && !truncated && size <= MAX_EDIT_BYTES
}

@Throws(IOException::class)
fun readTextFile(file: File, limit: Long = MAX_PREVIEW_BYTES): TextContent {
    val size = file.length()
    val lastModified = file.lastModified()
    val bytes = file.inputStream().use { input ->
        val wanted = minOf(size, limit).toInt()
        val buffer = ByteArray(wanted)
        var total = 0
        while (total < wanted) {
            val n = input.read(buffer, total, wanted - total)
            if (n < 0) break
            total += n
        }
        if (total == wanted) buffer else buffer.copyOf(total)
    }
    val truncated = size > bytes.size
    val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val start = if (bom) 3 else 0
    val strict = decodeUtf8(bytes, start, bytes.size - start, allowTruncatedEnd = truncated)
    val raw = strict ?: String(bytes, start, bytes.size - start, Charsets.UTF_8)
    val crlf = raw.contains("\r\n")
    return TextContent(
        text = if (crlf) raw.replace("\r\n", "\n") else raw,
        crlf = crlf,
        truncated = truncated,
        utf8 = strict != null,
        bom = bom,
        size = size,
        lastModified = lastModified,
    )
}

/**
 * Saves [text] as UTF-8, with "\r\n" line endings if [crlf] and a byte order mark if [bom]. Writes
 * a temporary file next to it and renames it over the original, so a crash mid-write never leaves a
 * half-written file.
 */
@Throws(IOException::class)
fun writeTextFile(file: File, text: String, crlf: Boolean, bom: Boolean) {
    val body = (if (crlf) text.replace("\n", "\r\n") else text).toByteArray(Charsets.UTF_8)
    val bytes = if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body
    val temp = File(file.parentFile, ".${file.name}.saving")
    try {
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (temp.renameTo(file)) return
    } catch (_: IOException) {
        // The folder may not allow new files; fall back to writing the file in place.
    } finally {
        temp.delete()
    }
    file.outputStream().use { it.write(bytes) }
}

/**
 * Re-indents JSON with two spaces. Only whitespace between tokens changes: strings, numbers and key
 * order stay exactly as written. Throws [JSONException] when [json] isn't valid JSON.
 */
@Throws(JSONException::class)
fun prettifyJson(json: String): String {
    validateJson(json)
    val out = StringBuilder(json.length + json.length / 4)
    var depth = 0
    var i = 0
    fun newline() {
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }
    while (i < json.length) {
        val c = json[i]
        when {
            c == '"' -> {
                val end = stringEnd(json, i)
                out.append(json, i, end)
                i = end
                continue
            }
            c == '{' || c == '[' -> {
                val close = if (c == '{') '}' else ']'
                val next = nextNonSpace(json, i + 1)
                if (next < json.length && json[next] == close) {
                    out.append(c).append(close) // Empty: keep "{}" / "[]" on one line.
                    i = next + 1
                    continue
                }
                out.append(c)
                depth++
                newline()
            }
            c == '}' || c == ']' -> {
                depth--
                newline()
                out.append(c)
            }
            c == ',' -> {
                out.append(c)
                newline()
            }
            c == ':' -> out.append(": ")
            c.isWhitespace() -> Unit
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/**
 * Strict JSON (RFC 8259) check. org.json's own parser also accepts comments, single quotes and
 * unquoted names, which the formatter above would mangle, so those must be rejected here.
 */
private fun validateJson(json: String) {
    var i = 0
    fun fail(what: String): Nothing {
        val before = json.substring(0, minOf(i, json.length))
        val line = before.count { it == '\n' } + 1
        val column = i - before.lastIndexOf('\n')
        throw JSONException("$what at line $line, column $column")
    }
    fun skipSpace() {
        while (i < json.length && json[i] in " \t\n\r") i++
    }
    fun expect(c: Char) {
        if (i >= json.length || json[i] != c) fail(if (i >= json.length) "Unexpected end" else "Expected '$c'")
        i++
    }
    fun string() {
        expect('"')
        while (true) {
            if (i >= json.length) fail("Unterminated string")
            val c = json[i++]
            when {
                c == '"' -> return
                c < ' ' -> { i--; fail("Control character in string") }
                c == '\\' -> {
                    if (i >= json.length) fail("Unterminated string")
                    when (json[i++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (i >= json.length || json[i].digitToIntOrNull(16) == null) fail("Bad \\u escape")
                            i++
                        }
                        else -> { i--; fail("Bad escape") }
                    }
                }
            }
        }
    }
    fun digits() {
        val from = i
        while (i < json.length && json[i] in '0'..'9') i++
        if (i == from) fail("Expected a digit")
    }
    fun number() {
        if (json[i] == '-') i++
        if (i < json.length && json[i] == '0') i++ else digits()
        if (i < json.length && json[i] == '.') { i++; digits() }
        if (i < json.length && (json[i] == 'e' || json[i] == 'E')) {
            i++
            if (i < json.length && (json[i] == '+' || json[i] == '-')) i++
            digits()
        }
    }
    fun value(depth: Int) {
        if (depth > 512) fail("Nested too deeply")
        skipSpace()
        if (i >= json.length) fail("Unexpected end")
        when (val c = json[i]) {
            '{' -> {
                i++
                skipSpace()
                if (i < json.length && json[i] == '}') { i++; return }
                while (true) {
                    skipSpace()
                    string()
                    skipSpace()
                    expect(':')
                    value(depth + 1)
                    skipSpace()
                    if (i < json.length && json[i] == ',') { i++; continue }
                    expect('}')
                    return
                }
            }
            '[' -> {
                i++
                skipSpace()
                if (i < json.length && json[i] == ']') { i++; return }
                while (true) {
                    value(depth + 1)
                    skipSpace()
                    if (i < json.length && json[i] == ',') { i++; continue }
                    expect(']')
                    return
                }
            }
            '"' -> string()
            '-', in '0'..'9' -> number()
            else -> {
                val word = listOf("true", "false", "null").firstOrNull { json.startsWith(it, i) }
                    ?: fail("Unexpected '$c'")
                i += word.length
            }
        }
    }
    value(0)
    skipSpace()
    if (i < json.length) fail("Unexpected text after the JSON value")
}

/** Index just past the closing quote of the string starting at [start]. */
private fun stringEnd(json: String, start: Int): Int {
    var i = start + 1
    while (i < json.length) {
        when (json[i]) {
            '\\' -> i++
            '"' -> return i + 1
        }
        i++
    }
    return json.length
}

private fun nextNonSpace(json: String, from: Int): Int {
    var i = from
    while (i < json.length && json[i].isWhitespace()) i++
    return i
}

private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int, allowTruncatedEnd: Boolean): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    // A cut-off final character is at most 3 bytes; retry without them when that's allowed.
    val attempts = if (allowTruncatedEnd) (0..minOf(3, length)) else (0..0)
    for (drop in attempts) {
        try {
            return decoder.reset().decode(ByteBuffer.wrap(bytes, offset, length - drop)).toString()
        } catch (_: CharacterCodingException) {
        }
    }
    return null
}

private const val SNIFF_BYTES = 8 * 1024

/** The viewer reads at most this much; bigger files show their beginning. */
const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024

/** Larger files are too slow to edit comfortably in a text field. */
const val MAX_EDIT_BYTES = 1L * 1024 * 1024
