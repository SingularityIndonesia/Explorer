package com.singularity_universe.explorer.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays output recorded from real programs (nano, vi, less, a shell) running in an 80×24
 * xterm-256color PTY, and checks the emulator's screen against a reference emulator's at each
 * recorded checkpoint. Recordings are `<name>.bin` with `<name>.expected` next to them.
 *
 * The expected screens come from pyte, corrected where pyte is wrong: it has no alternate screen
 * (so screens after nano/vi exit aren't compared) and doesn't draw DEC line-drawing characters.
 */
class TerminalEmulatorTest {
    private val fixtures = File(System.getProperty("terminal.fixtures") ?: "src/test/resources/terminal")

    @Test fun nano() = replay("nano")
    @Test fun vi() = replay("vi")
    @Test fun less() = replay("less")
    @Test fun shell() = replay("shell")

    @Test
    fun answersCursorPositionQuery() {
        val replies = StringBuilder()
        val emulator = TerminalEmulator(80, 24) { replies.append(String(it)) }
        emulator.append("\u001b[5;10H\u001b[6n".toByteArray())
        assertEquals("\u001b[5;10R", replies.toString())
    }

    @Test
    fun resizeKeepsCursorLineVisible() {
        val emulator = TerminalEmulator(20, 10) {}
        emulator.append((1..9).joinToString("\r\n") { "line $it" }.toByteArray())
        emulator.resize(20, 4)
        assertEquals("line 9", emulator.textOf(emulator.cursorY))
        assertEquals(3, emulator.cursorY)
        assertEquals("line 5", emulator.textOf(-1))
        emulator.resize(20, 10)
        assertEquals("line 1", emulator.textOf(0))
        assertEquals("line 9", emulator.textOf(emulator.cursorY))
    }

    @Test
    fun splitUtf8AcrossReads() {
        val emulator = TerminalEmulator(20, 2) {}
        val bytes = "é✓".toByteArray()
        bytes.forEach { emulator.append(byteArrayOf(it)) }
        assertEquals("é✓", emulator.textOf(0))
    }

    @Test
    fun urlsUnderTheFinger() {
        val emulator = TerminalEmulator(40, 5) {}
        emulator.append("see https://example.com/a_(b). ok\r\n".toByteArray())
        assertEquals("https://example.com/a_(b)", emulator.urlAt(0, 10))
        assertEquals("the closing bracket belongs to the link", "https://example.com/a_(b)", emulator.urlAt(0, 28))
        assertEquals("the full stop after it doesn't", null, emulator.urlAt(0, 29))
        assertEquals(null, emulator.urlAt(0, 1))
        // 41 characters in 40 columns: the address wraps, and both parts are the same link.
        emulator.append("go https://example.org/a/very/long/path/x".toByteArray())
        assertEquals("https://example.org/a/very/long/path/x", emulator.urlAt(1, 3))
        assertEquals("https://example.org/a/very/long/path/x", emulator.urlAt(2, 0))
    }

    @Test
    fun wordsAndCopiedText() {
        val emulator = TerminalEmulator(10, 3) {}
        // "0123456789abc" wraps after 10 columns; the 3-row screen scrolls "one two" into scrollback.
        emulator.append("one two\r\n0123456789abc\r\nlast".toByteArray())
        assertEquals(1, emulator.historySize)
        assertEquals(4..6, emulator.wordAt(0, 5))
        assertEquals("a wrapped line copies as one line", "0123456789abc", emulator.textBetween(1, 0, 2, 2))
        assertEquals("two\n0123456789abc\nlast", emulator.textBetween(3, 3, 0, 4))
        // More scrolling doesn't move buffer positions.
        emulator.append("\r\nmore".toByteArray())
        assertEquals(2, emulator.historySize)
        assertEquals("one two", emulator.textBetween(0, 0, 0, 9))
    }

    private fun replay(name: String) {
        val recording = File(fixtures, "$name.bin")
        assumeTrue("No recording at $recording", recording.exists())
        val bytes = recording.readBytes()
        val checkpoints = parseExpected(File(fixtures, "$name.expected"))
        val emulator = TerminalEmulator(80, 24) {}
        var fed = 0
        checkpoints.forEachIndexed { index, checkpoint ->
            emulator.append(bytes.copyOfRange(fed, checkpoint.offset))
            fed = checkpoint.offset
            val actual = (0 until 24).map { emulator.textOf(it) }
            assertEquals(
                "$name checkpoint ${index + 1} (byte ${checkpoint.offset}): screen",
                checkpoint.lines.joinToString("\n"),
                actual.joinToString("\n"),
            )
            assertEquals(
                "$name checkpoint ${index + 1}: cursor",
                checkpoint.cursor,
                emulator.cursorX to emulator.cursorY,
            )
        }
    }

    private class Checkpoint(val offset: Int, val cursor: Pair<Int, Int>, val lines: List<String>)

    private fun parseExpected(file: File): List<Checkpoint> {
        val result = ArrayList<Checkpoint>()
        val lines = file.readLines()
        var i = 0
        while (i < lines.size) {
            val header = lines[i].split(' ')
            require(header[0] == "@checkpoint") { "Bad fixture line: ${lines[i]}" }
            val screen = lines.subList(i + 1, i + 25)
            result += Checkpoint(header[1].toInt(), header[2].toInt() to header[3].toInt(), screen)
            i += 25
        }
        return result
    }
}
