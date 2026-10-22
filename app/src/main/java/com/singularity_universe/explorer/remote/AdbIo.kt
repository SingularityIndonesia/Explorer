package com.singularity_universe.explorer.remote

import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Shared ADB stream helpers.
 *
 * libadb can miss the end-of-stream signal when adbd closes a stream that still has queued data
 * (the reader then blocks forever), so nothing here waits for EOF: shell output is read up to an
 * exit-code marker, and files move over ADB's own framed sync protocol.
 */

private const val EXIT_MARK = "__EXPLORER_EXIT__"
private val exitMarker = Regex("$EXIT_MARK(\\d+)\\r?\\n")

/** The `shell:` service string for [command]; its output ends with an exit-code marker. */
internal fun shellService(command: String) = "shell:$command; echo; echo $EXIT_MARK\$?"

/** Reads a stream opened with [shellService] and returns the command's output and exit code. */
internal fun AdbStream.readShellResult(): ShellResult {
    val output = ByteArrayOutputStream()
    val input = openInputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = try {
            input.read(buffer)
        } catch (_: IOException) {
            -1 // libadb reports a closed stream as "Stream closed." instead of EOF.
        }
        if (read < 0) break
        output.write(buffer, 0, read)
        if (exitMarker.containsMatchIn(output.tail())) break
    }
    val text = String(output.toByteArray())
    val match = exitMarker.findAll(text).lastOrNull() ?: return ShellResult(text, -1)
    return ShellResult(text.substring(0, match.range.first).trimEnd('\n', '\r'), match.groupValues[1].toInt())
}

private fun ByteArrayOutputStream.tail(): String {
    val bytes = toByteArray()
    return String(bytes, maxOf(0, bytes.size - 64), minOf(64, bytes.size))
}

// ---- Sync protocol (the one `adb pull` / `adb push` use), over a "sync:" stream ----

private const val SYNC_CHUNK = 64 * 1024

/** Downloads [path] into [destination]. */
internal fun AdbStream.syncRecv(path: String, destination: File) {
    val input = openInputStream()
    val output = openOutputStream()
    output.writeRequest("RECV", path.toByteArray())
    destination.parentFile?.mkdirs()
    destination.outputStream().buffered().use { file ->
        while (true) {
            val (id, length) = input.readHeader()
            when (id) {
                "DATA" -> file.write(input.readExactly(length))
                "DONE" -> break
                "FAIL" -> throw IOException(String(input.readExactly(length)))
                else -> throw IOException("Unexpected reply \"$id\" while downloading")
            }
        }
    }
    output.writeRequest("QUIT", ByteArray(0))
}

/** Uploads [source] to [path] (created, or replaced if it exists). */
internal fun AdbStream.syncSend(source: File, path: String) {
    val input = openInputStream()
    val output = openOutputStream()
    // Regular file, rw-r--r--.
    output.writeRequest("SEND", "$path,${0b1_000_000_110_100_100}".toByteArray())
    source.inputStream().buffered().use { file ->
        val buffer = ByteArray(SYNC_CHUNK)
        while (true) {
            val read = file.read(buffer)
            if (read < 0) break
            output.writeRequest("DATA", buffer, read)
        }
    }
    output.writeHeader("DONE", (source.lastModified() / 1000).toInt())
    output.flush()
    val (id, length) = input.readHeader()
    when (id) {
        "OKAY" -> Unit
        "FAIL" -> throw IOException(String(input.readExactly(length)))
        else -> throw IOException("Unexpected reply \"$id\" while uploading")
    }
    output.writeRequest("QUIT", ByteArray(0))
}

private fun OutputStream.writeHeader(id: String, value: Int) {
    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    header.put(id.toByteArray(Charsets.US_ASCII)).putInt(value)
    write(header.array())
}

private fun OutputStream.writeRequest(id: String, payload: ByteArray, length: Int = payload.size) {
    writeHeader(id, length)
    if (length > 0) write(payload, 0, length)
    flush()
}

private fun InputStream.readHeader(): Pair<String, Int> {
    val header = readExactly(8)
    val id = String(header, 0, 4, Charsets.US_ASCII)
    val value = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    return id to value
}

internal fun InputStream.readExactly(length: Int): ByteArray {
    val data = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val read = read(data, filled, length - filled)
        if (read < 0) throw IOException("Connection closed during transfer")
        filled += read
    }
    return data
}
