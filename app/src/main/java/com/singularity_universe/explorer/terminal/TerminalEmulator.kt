package com.singularity_universe.explorer.terminal

import kotlin.math.max
import kotlin.math.min

/**
 * An xterm-compatible terminal screen, enough for shells and full-screen programs (nano, vim,
 * less, htop, top): cursor addressing, scroll regions, the alternate screen, insert/delete,
 * 16/256/true colours, DEC line drawing, and the queries those programs send.
 *
 * Bytes from the server go in through [append]; answers to queries go out through [reply].
 * Not thread-safe: callers synchronize on the instance (the reader thread writes, the UI draws).
 */
class TerminalEmulator(columns: Int, rows: Int, private val reply: (ByteArray) -> Unit) {
    var columns = max(columns, 1)
        private set
    var rows = max(rows, 1)
        private set

    /** One row of cells: a code point and a packed [TextStyle] per column. */
    class Line(columns: Int) {
        var text = IntArray(columns)
            private set
        var style = LongArray(columns)
            private set

        /** The text continues on the next line (it was wrapped, not ended by a newline). */
        var wrapped = false

        val size: Int get() = text.size

        fun clear(from: Int, to: Int, blank: Long) {
            for (x in max(from, 0) until min(to, size)) {
                text[x] = 0
                style[x] = blank
            }
        }

        fun resized(columns: Int): Line {
            if (columns == size) return this
            val line = Line(columns)
            text.copyInto(line.text, 0, 0, min(size, columns))
            style.copyInto(line.style, 0, 0, min(size, columns))
            // A wide character cut in half by the new width is dropped.
            if (columns in 1..size && line.text[columns - 1] != WIDE_TAIL && isWideAt(columns - 1)) line.text[columns - 1] = 0
            line.wrapped = wrapped && columns >= size
            return line
        }

        private fun isWideAt(x: Int) = x + 1 < size && text[x + 1] == WIDE_TAIL
    }

    private class SavedCursor(
        val x: Int,
        val y: Int,
        val style: Long,
        val wrapPending: Boolean,
        val origin: Boolean,
        val g0Graphics: Boolean,
        val g1Graphics: Boolean,
        val useG1: Boolean,
    )

    private var main = MutableList(this.rows) { Line(this.columns) }
    private var alt = MutableList(this.rows) { Line(this.columns) }
    private var screen = main

    /** Lines that scrolled off the top of the main screen, oldest first. */
    private val history = ArrayDeque<Line>()

    val historySize: Int get() = if (isAltScreen) 0 else history.size
    val isAltScreen: Boolean get() = screen === alt

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var wrapPending = false
    private var style = 0L
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var tabStops = defaultTabStops(this.columns)
    private var savedMain: SavedCursor? = null
    private var savedAlt: SavedCursor? = null
    private var lastPrinted = ' '.code

    // Modes
    var cursorVisible = true
        private set
    /** DECCKM: arrow keys send ESC O A instead of ESC [ A. */
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    /** 0 = off, else the xterm mouse mode (1000 clicks, 1002 drags, 1003 all motion). */
    var mouseTracking = 0
        private set
    var sgrMouse = false
        private set
    /** DECSCNM: the whole screen is shown inverted. */
    var reverseVideo = false
        private set
    private var insertMode = false
    private var autowrap = true
    private var originMode = false
    private var newLineMode = false
    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false

    /** Set by the server with OSC 0/2, e.g. "steve@mac: ~". */
    var title: String? = null
        private set

    // ---- Reading the screen ----

    /** A visible row (0 until [rows]), or a scrollback row (-[historySize] until 0). */
    fun lineAt(row: Int): Line = if (row < 0) history[history.size + row] else screen[row]

    /** Visible text of a row, e.g. for copying. Trailing blanks are dropped. */
    fun textOf(row: Int): String {
        val line = lineAt(row)
        val out = StringBuilder()
        for (x in 0 until line.size) {
            val cp = line.text[x]
            if (cp == WIDE_TAIL) continue
            out.appendCodePoint(if (cp == 0) ' '.code else cp)
        }
        return out.trimEnd().toString()
    }

    // ---- Selecting and links ----
    //
    // Positions here count lines through the whole buffer: 0 is the oldest scrollback line, and
    // the screen's rows follow it, so they stay put while the view scrolls.

    /** Lines in the buffer: scrollback plus the screen. */
    val totalLines: Int get() = historySize + rows

    /** Line [index] of the whole buffer (0 = oldest scrollback line). */
    fun bufferLine(index: Int): Line = lineAt(index - historySize)

    /**
     * The text from ([startLine], [startColumn]) to ([endLine], [endColumn]), inclusive, in either
     * order. Lines that were wrapped are joined without a line break; trailing blanks are dropped.
     */
    fun textBetween(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        val (fromLine, fromColumn, toLine, toColumn) =
            if (startLine < endLine || (startLine == endLine && startColumn <= endColumn)) listOf(startLine, startColumn, endLine, endColumn)
            else listOf(endLine, endColumn, startLine, startColumn)
        val out = StringBuilder()
        for (index in fromLine.coerceAtLeast(0)..toLine.coerceAtMost(totalLines - 1)) {
            val line = bufferLine(index)
            val first = if (index == fromLine) fromColumn else 0
            val last = if (index == toLine) toColumn else line.size - 1
            val part = StringBuilder()
            for (x in first.coerceAtLeast(0)..last.coerceAtMost(line.size - 1)) {
                val cp = line.text[x]
                if (cp == WIDE_TAIL) continue
                part.appendCodePoint(if (cp == 0) ' '.code else cp)
            }
            out.append(if (line.wrapped && index != toLine) part else part.trimEnd())
            if (index != toLine && !line.wrapped) out.append('\n')
        }
        return out.toString()
    }

    /** The columns of the word (a run of non-blank characters) at [column] of buffer line [index]. */
    fun wordAt(index: Int, column: Int): IntRange {
        val line = bufferLine(index)
        fun blank(x: Int) = line.text[x].let { it == 0 || it == ' '.code }
        val x = column.coerceIn(0, line.size - 1)
        if (blank(x)) return x..x
        var start = x
        while (start > 0 && !blank(start - 1)) start--
        var end = x
        while (end < line.size - 1 && !blank(end + 1)) end++
        return start..end
    }

    /** A web address under [column] of buffer line [index] (following it onto wrapped lines), or null. */
    fun urlAt(index: Int, column: Int): String? {
        // The logical line: this line, plus the lines it wraps onto.
        var first = index
        while (first > 0 && bufferLine(first - 1).wrapped) first--
        var last = index
        while (last < totalLines - 1 && bufferLine(last).wrapped) last++
        val widths = (first..last).map { bufferLine(it).size }
        val text = (first..last).joinToString("") { lineText(bufferLine(it)) }
        val offset = widths.take(index - first).sum() + column
        for (match in URL.findAll(text)) {
            val url = trimUrl(match.value)
            if (offset >= match.range.first && offset < match.range.first + url.length) return url
        }
        return null
    }

    /** The columns of buffer line [index] covered by web addresses (to underline them). */
    fun urlColumns(index: Int): List<IntRange> {
        var first = index
        while (first > 0 && bufferLine(first - 1).wrapped) first--
        var last = index
        while (last < totalLines - 1 && bufferLine(last).wrapped) last++
        val widths = (first..last).map { bufferLine(it).size }
        val text = (first..last).joinToString("") { lineText(bufferLine(it)) }
        if (!text.contains("://") && !text.contains("www.")) return emptyList()
        val start = widths.take(index - first).sum()
        val end = start + bufferLine(index).size
        return URL.findAll(text).mapNotNull { match ->
            val from = match.range.first
            val to = from + trimUrl(match.value).length - 1
            if (to < start || from >= end) null else (maxOf(from, start) - start)..(minOf(to, end - 1) - start)
        }.toList()
    }

    /** A line's cells as text, one character per cell (blank for empty and wide-tail cells). */
    private fun lineText(line: Line): String {
        val sb = StringBuilder(line.size)
        for (x in 0 until line.size) {
            val cp = line.text[x]
            // Keep one char per cell so offsets match columns (wide characters become a placeholder).
            sb.append(if (cp == 0 || cp == WIDE_TAIL || cp > 0xFFFF) ' ' else cp.toChar())
        }
        return sb.toString()
    }

    /** Drops punctuation that ends a sentence rather than the address, keeping balanced brackets. */
    private fun trimUrl(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val unbalanced = when (c) {
                ')' -> url.substring(0, end).count { it == '(' } < url.substring(0, end).count { it == ')' }
                ']' -> url.substring(0, end).count { it == '[' } < url.substring(0, end).count { it == ']' }
                else -> false
            }
            if (c in ".,;:!?'\"" || unbalanced) end-- else break
        }
        return url.substring(0, end)
    }

    // ---- Input from the server ----

    private enum class State { Ground, Escape, EscapeIntermediate, Csi, Osc, OscEscape, IgnoreString, IgnoreStringEscape }

    private var state = State.Ground
    private val params = IntArray(MAX_PARAMS)
    /** Whether a parameter followed a ':' (an SGR sub-parameter) rather than a ';'. */
    private val colon = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var prefix = 0
    private val intermediates = StringBuilder()
    private val osc = StringBuilder()
    private var utf8Remaining = 0
    private var utf8CodePoint = 0
    private var utf8Min = 0

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) decode(bytes[i].toInt() and 0xFF)
    }

    /** UTF-8 decoding, byte by byte, since a read can end in the middle of a character. */
    private fun decode(b: Int) {
        if (utf8Remaining > 0) {
            if ((b and 0xC0) == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) {
                    val cp = utf8CodePoint
                    process(if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) REPLACEMENT else cp)
                }
                return
            }
            utf8Remaining = 0
            process(REPLACEMENT)
        }
        when {
            b < 0x80 -> process(b)
            (b and 0xE0) == 0xC0 -> start(1, b and 0x1F, 0x80)
            (b and 0xF0) == 0xE0 -> start(2, b and 0x0F, 0x800)
            (b and 0xF8) == 0xF0 -> start(3, b and 0x07, 0x10000)
            else -> process(REPLACEMENT)
        }
    }

    private fun start(remaining: Int, bits: Int, min: Int) {
        utf8Remaining = remaining
        utf8CodePoint = bits
        utf8Min = min
    }

    private fun process(c: Int) {
        when (state) {
            State.Osc -> {
                when (c) {
                    BEL -> finishOsc()
                    ESC -> state = State.OscEscape
                    CAN, SUB -> state = State.Ground
                    else -> if (osc.length < MAX_OSC) osc.appendCodePoint(c)
                }
                return
            }
            State.OscEscape -> {
                finishOsc()
                if (c != '\\'.code) {
                    state = State.Escape
                    process(c)
                }
                return
            }
            State.IgnoreString -> {
                when (c) {
                    ESC -> state = State.IgnoreStringEscape
                    BEL, CAN, SUB -> state = State.Ground
                }
                return
            }
            State.IgnoreStringEscape -> {
                state = if (c == '\\'.code) State.Ground else State.IgnoreString
                return
            }
            else -> Unit
        }

        if (c < 0x20 || c == 0x7F) {
            when (c) {
                ESC -> {
                    state = State.Escape
                    intermediates.setLength(0)
                }
                CAN, SUB -> state = State.Ground
                0x7F -> Unit
                // C0 controls take effect even in the middle of an escape sequence.
                else -> control(c)
            }
            return
        }

        when (state) {
            State.Ground -> printCodePoint(c)
            State.Escape -> escape(c)
            State.EscapeIntermediate -> escapeIntermediate(c)
            State.Csi -> csi(c)
            else -> state = State.Ground
        }
    }

    private fun control(c: Int) {
        when (c) {
            BEL -> Unit
            0x08 -> { // BS
                if (cursorX > 0) cursorX--
                wrapPending = false
            }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> { // LF, VT, FF
                index()
                if (newLineMode) cursorX = 0
            }
            0x0D -> carriageReturn()
            0x0E -> useG1 = true // SO
            0x0F -> useG1 = false // SI
        }
    }

    private fun escape(c: Int) {
        when (c.toChar()) {
            '[' -> {
                paramCount = 0
                prefix = 0
                intermediates.setLength(0)
                state = State.Csi
                return
            }
            ']' -> {
                osc.setLength(0)
                state = State.Osc
                return
            }
            'P', 'X', '^', '_' -> { // DCS, SOS, PM, APC: not used, skipped up to ST.
                state = State.IgnoreString
                return
            }
            '(', ')', '*', '+', '#', ' ', '%' -> {
                intermediates.setLength(0)
                intermediates.append(c.toChar())
                state = State.EscapeIntermediate
                return
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> {
                carriageReturn()
                index()
            }
            'M' -> reverseIndex()
            'H' -> tabStops[cursorX] = true
            'c' -> reset()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
        }
        state = State.Ground
    }

    private fun escapeIntermediate(c: Int) {
        if (c in 0x20..0x2F) {
            intermediates.append(c.toChar())
            return
        }
        state = State.Ground
        when (intermediates.firstOrNull()) {
            '(' -> g0Graphics = c == '0'.code
            ')' -> g1Graphics = c == '0'.code
            '#' -> if (c == '8'.code) alignmentTest()
        }
    }

    private fun csi(c: Int) {
        when (c) {
            in '0'.code..'9'.code -> {
                if (paramCount == 0) newParam(false)
                val i = paramCount - 1
                val digit = c - '0'.code
                params[i] = if (params[i] < 0) digit else min(params[i] * 10 + digit, 99_999)
            }
            ';'.code, ':'.code -> {
                if (paramCount == 0) newParam(false)
                newParam(c == ':'.code)
            }
            in 0x3C..0x3F -> if (paramCount == 0 && intermediates.isEmpty()) prefix = c // < = > ?
            in 0x20..0x2F -> intermediates.append(c.toChar())
            in 0x40..0x7E -> {
                state = State.Ground
                dispatchCsi(c.toChar())
            }
            else -> state = State.Ground
        }
    }

    private fun newParam(afterColon: Boolean) {
        if (paramCount >= MAX_PARAMS) return
        params[paramCount] = -1
        colon[paramCount] = afterColon
        paramCount++
    }

    /** Parameter [i], or [default] when it was left out. */
    private fun param(i: Int, default: Int): Int {
        val value = if (i < paramCount) params[i] else -1
        return if (value < 0) default else value
    }

    /** A count or position, where both a missing parameter and 0 mean 1. */
    private fun count(i: Int): Int = max(param(i, 1), 1)

    private fun dispatchCsi(final: Char) {
        if (intermediates.isNotEmpty()) {
            if (intermediates.toString() == "!" && final == 'p') softReset()
            // Others (cursor shape, DECRQM, ...) don't change what's on screen.
            return
        }
        when (prefix) {
            0 -> standardCsi(final)
            '?'.code -> when (final) {
                'h' -> for (i in 0 until paramCount) setPrivateMode(param(i, 0), true)
                'l' -> for (i in 0 until paramCount) setPrivateMode(param(i, 0), false)
                'n' -> if (param(0, 0) == 6) reportCursor(private = true)
            }
            // Secondary device attributes: claim to be a VT220-class xterm.
            '>'.code -> if (final == 'c' && param(0, 0) == 0) respond("\u001b[>1;10;0c")
        }
    }

    private fun standardCsi(final: Char) {
        when (final) {
            '@' -> insertChars(count(0))
            'A' -> cursorUp(count(0))
            'B', 'e' -> cursorDown(count(0))
            'C', 'a' -> setCursor(cursorX + count(0), cursorY)
            'D' -> setCursor(cursorX - count(0), cursorY)
            'E' -> {
                cursorDown(count(0))
                cursorX = 0
            }
            'F' -> {
                cursorUp(count(0))
                cursorX = 0
            }
            'G', '`' -> setCursor(count(0) - 1, cursorY)
            'H', 'f' -> moveTo(count(1) - 1, count(0) - 1)
            'I' -> tab(count(0))
            'Z' -> backTab(count(0))
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'L' -> if (cursorY in scrollTop..scrollBottom) {
                scrollDown(cursorY, scrollBottom, count(0))
                cursorX = 0
                wrapPending = false
            }
            'M' -> if (cursorY in scrollTop..scrollBottom) {
                scrollUp(cursorY, scrollBottom, count(0), keepInHistory = false)
                cursorX = 0
                wrapPending = false
            }
            'P' -> deleteChars(count(0))
            'S' -> scrollUp(scrollTop, scrollBottom, count(0), keepInHistory = scrollTop == 0)
            'T' -> if (paramCount <= 1) scrollDown(scrollTop, scrollBottom, count(0))
            'X' -> {
                val line = screen[cursorY]
                line.clear(cursorX, cursorX + count(0), blank())
                wrapPending = false
            }
            'b' -> repeat(min(count(0), columns * rows)) { printCodePoint(lastPrinted) }
            'c' -> if (param(0, 0) == 0) respond("\u001b[?62;22c") // VT220 with ANSI colour
            'd' -> moveTo(cursorX, count(0) - 1)
            'g' -> when (param(0, 0)) {
                0 -> tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h', 'l' -> for (i in 0 until paramCount) when (param(i, 0)) {
                4 -> insertMode = final == 'h'
                20 -> newLineMode = final == 'h'
            }
            'm' -> selectGraphicRendition()
            'n' -> when (param(0, 0)) {
                5 -> respond("\u001b[0n")
                6 -> reportCursor(private = false)
            }
            'r' -> setScrollRegion()
            's' -> if (paramCount == 0) saveCursor()
            'u' -> if (paramCount == 0) restoreCursor()
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            5 -> reverseVideo = on
            6 -> {
                originMode = on
                moveTo(0, 0)
            }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47 -> useAltScreen(on, clear = false)
            1047 -> {
                if (!on && isAltScreen) clearScreen(alt)
                useAltScreen(on, clear = false)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                saveCursor()
                useAltScreen(true, clear = true)
            } else {
                useAltScreen(false, clear = false)
                restoreCursor()
            }
            9, 1000, 1002, 1003 -> mouseTracking = if (on) mode else 0
            1006 -> sgrMouse = on
            2004 -> bracketedPaste = on
        }
    }

    private fun selectGraphicRendition() {
        if (paramCount == 0) {
            style = 0L
            return
        }
        var i = 0
        while (i < paramCount) {
            val value = param(i, 0)
            var next = i + 1
            when (value) {
                0 -> style = 0L
                1 -> style = style or TextStyle.BOLD
                2 -> style = style or TextStyle.DIM
                3 -> style = style or TextStyle.ITALIC
                4 -> {
                    // "4:0" turns underline off; "4", "4:1".."4:5" are kinds of underline.
                    val off = next < paramCount && colon[next] && param(next, 1) == 0
                    style = if (off) style and TextStyle.UNDERLINE.inv() else style or TextStyle.UNDERLINE
                }
                5, 6 -> style = style or TextStyle.BLINK
                7 -> style = style or TextStyle.INVERSE
                8 -> style = style or TextStyle.INVISIBLE
                9 -> style = style or TextStyle.STRIKE
                21 -> style = style or TextStyle.UNDERLINE
                22 -> style = style and (TextStyle.BOLD or TextStyle.DIM).inv()
                23 -> style = style and TextStyle.ITALIC.inv()
                24 -> style = style and TextStyle.UNDERLINE.inv()
                25 -> style = style and TextStyle.BLINK.inv()
                27 -> style = style and TextStyle.INVERSE.inv()
                28 -> style = style and TextStyle.INVISIBLE.inv()
                29 -> style = style and TextStyle.STRIKE.inv()
                in 30..37 -> style = TextStyle.withForeground(style, TextStyle.indexed(value - 30))
                38 -> {
                    val (color, after) = extendedColor(i)
                    if (color >= 0) style = TextStyle.withForeground(style, color)
                    next = after
                }
                39 -> style = TextStyle.withForeground(style, TextStyle.DEFAULT_COLOR)
                in 40..47 -> style = TextStyle.withBackground(style, TextStyle.indexed(value - 40))
                48 -> {
                    val (color, after) = extendedColor(i)
                    if (color >= 0) style = TextStyle.withBackground(style, color)
                    next = after
                }
                49 -> style = TextStyle.withBackground(style, TextStyle.DEFAULT_COLOR)
                in 90..97 -> style = TextStyle.withForeground(style, TextStyle.indexed(value - 90 + 8))
                in 100..107 -> style = TextStyle.withBackground(style, TextStyle.indexed(value - 100 + 8))
            }
            // Skip sub-parameters ("4:3", "58:5:1") that weren't consumed above.
            while (next < paramCount && colon[next]) next++
            i = next
        }
    }

    /** "38;5;n", "38;2;r;g;b", or the colon forms "38:5:n" / "38:2::r:g:b". Returns (colour, next index). */
    private fun extendedColor(i: Int): Pair<Int, Int> {
        if (i + 1 < paramCount && colon[i + 1]) {
            var j = i + 1
            val sub = ArrayList<Int>()
            while (j < paramCount && colon[j]) sub += param(j++, 0)
            return when {
                sub.firstOrNull() == 5 && sub.size >= 2 -> TextStyle.indexed(sub[1].coerceIn(0, 255)) to j
                sub.firstOrNull() == 2 && sub.size >= 4 -> {
                    val n = sub.size
                    TextStyle.rgb(sub[n - 3], sub[n - 2], sub[n - 1]) to j
                }
                else -> -1 to j
            }
        }
        return when (param(i + 1, -1)) {
            5 -> TextStyle.indexed(param(i + 2, 0).coerceIn(0, 255)) to i + 3
            2 -> TextStyle.rgb(param(i + 2, 0), param(i + 3, 0), param(i + 4, 0)) to i + 5
            else -> -1 to i + 2
        }
    }

    private fun finishOsc() {
        state = State.Ground
        val text = osc.toString()
        val kind = text.substringBefore(';', "")
        if (kind == "0" || kind == "2") title = text.substringAfter(';').take(200)
    }

    // ---- Printing ----

    private fun printCodePoint(input: Int) {
        val graphics = if (useG1) g1Graphics else g0Graphics
        val cp = if (graphics && input in 0x5F..0x7E) DEC_GRAPHICS[input - 0x5F].code else input
        val width = charWidth(cp)
        if (width == 0) return // Combining marks and zero-width characters aren't drawn separately.
        lastPrinted = cp

        if (wrapPending) {
            wrapPending = false
            if (autowrap) {
                screen[cursorY].wrapped = true
                cursorX = 0
                index()
            }
        }
        if (width == 2 && cursorX == columns - 1) {
            // A wide character doesn't fit in the last column: it goes on the next line.
            if (autowrap) {
                screen[cursorY].clear(cursorX, columns, blank())
                screen[cursorY].wrapped = true
                cursorX = 0
                index()
            } else if (columns >= 2) {
                cursorX = columns - 2
            }
        }
        val line = screen[cursorY]
        if (insertMode) shiftRight(line, cursorX, width)
        put(line, cursorX, cp)
        if (width == 2 && cursorX + 1 < columns) put(line, cursorX + 1, WIDE_TAIL)

        val nextX = cursorX + width
        if (nextX >= columns) {
            cursorX = columns - 1
            wrapPending = autowrap
        } else {
            cursorX = nextX
        }
    }

    private fun put(line: Line, x: Int, cp: Int) {
        // Overwriting half of a wide character erases its other half.
        if (line.text[x] == WIDE_TAIL && x > 0 && cp != WIDE_TAIL) line.text[x - 1] = 0
        if (x + 1 < line.size && line.text[x + 1] == WIDE_TAIL && line.text[x] != WIDE_TAIL) line.text[x + 1] = 0
        line.text[x] = cp
        line.style[x] = style
    }

    // ---- Cursor ----

    private fun setCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        cursorY = y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    /** CUP/HVP: [row] counts from the scroll region's top in origin mode. */
    private fun moveTo(x: Int, row: Int) {
        if (originMode) setCursor(x, (row + scrollTop).coerceIn(scrollTop, scrollBottom))
        else setCursor(x, row)
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= scrollTop) scrollTop else 0
        setCursor(cursorX, max(cursorY - n, limit))
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY <= scrollBottom) scrollBottom else rows - 1
        setCursor(cursorX, min(cursorY + n, limit))
    }

    private fun carriageReturn() {
        cursorX = 0
        wrapPending = false
    }

    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < columns - 1 && !tabStops[x]) x++
            cursorX = min(x, columns - 1)
        }
        wrapPending = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabStops[x]) x--
            cursorX = max(x, 0)
        }
        wrapPending = false
    }

    /** Line feed: down one line, scrolling the region when at its bottom. */
    private fun index() {
        wrapPending = false
        when {
            cursorY == scrollBottom -> scrollUp(scrollTop, scrollBottom, 1, keepInHistory = scrollTop == 0)
            cursorY < rows - 1 -> cursorY++
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        when {
            cursorY == scrollTop -> scrollDown(scrollTop, scrollBottom, 1)
            cursorY > 0 -> cursorY--
        }
    }

    private fun saveCursor() {
        val saved = SavedCursor(cursorX, cursorY, style, wrapPending, originMode, g0Graphics, g1Graphics, useG1)
        if (isAltScreen) savedAlt = saved else savedMain = saved
    }

    private fun restoreCursor() {
        val saved = (if (isAltScreen) savedAlt else savedMain)
        if (saved == null) {
            setCursor(0, 0)
            return
        }
        setCursor(saved.x, saved.y)
        style = saved.style
        wrapPending = saved.wrapPending
        originMode = saved.origin
        g0Graphics = saved.g0Graphics
        g1Graphics = saved.g1Graphics
        useG1 = saved.useG1
    }

    private fun reportCursor(private: Boolean) {
        val row = if (originMode) cursorY - scrollTop else cursorY
        respond("\u001b[${if (private) "?" else ""}${row + 1};${cursorX + 1}R")
    }

    // ---- Editing ----

    /** Erased cells keep the current background colour (xterm's "bce"), but nothing else. */
    private fun blank(): Long = TextStyle.withBackground(0L, TextStyle.background(style))

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorY + 1 until rows) clearLine(screen[y])
            }
            1 -> {
                for (y in 0 until cursorY) clearLine(screen[y])
                eraseInLine(1)
            }
            2 -> for (y in 0 until rows) clearLine(screen[y])
            3 -> history.clear()
        }
        wrapPending = false
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorY]
        when (mode) {
            0 -> {
                line.clear(cursorX, columns, blank())
                line.wrapped = false
            }
            1 -> line.clear(0, cursorX + 1, blank())
            2 -> clearLine(line)
        }
        wrapPending = false
    }

    private fun clearLine(line: Line) {
        line.clear(0, line.size, blank())
        line.wrapped = false
    }

    private fun insertChars(n: Int) {
        shiftRight(screen[cursorY], cursorX, n)
        wrapPending = false
    }

    private fun shiftRight(line: Line, from: Int, n: Int) {
        val count = min(n, columns - from)
        for (x in columns - 1 downTo from + count) {
            line.text[x] = line.text[x - count]
            line.style[x] = line.style[x - count]
        }
        line.clear(from, from + count, blank())
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        val count = min(n, columns - cursorX)
        for (x in cursorX until columns - count) {
            line.text[x] = line.text[x + count]
            line.style[x] = line.style[x + count]
        }
        line.clear(columns - count, columns, blank())
        wrapPending = false
    }

    /** Moves lines [top]..[bottom] up by [n]; lines leaving the top of the main screen go to scrollback. */
    private fun scrollUp(top: Int, bottom: Int, n: Int, keepInHistory: Boolean) {
        repeat(min(n, bottom - top + 1)) {
            val removed = screen.removeAt(top)
            if (keepInHistory && !isAltScreen) addToHistory(removed)
            screen.add(bottom, Line(columns).also { it.clear(0, columns, blank()) })
        }
    }

    private fun scrollDown(top: Int, bottom: Int, n: Int) {
        repeat(min(n, bottom - top + 1)) {
            screen.removeAt(bottom)
            screen.add(top, Line(columns).also { it.clear(0, columns, blank()) })
        }
    }

    private fun addToHistory(line: Line) {
        history.addLast(line)
        if (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun setScrollRegion() {
        val top = param(0, 1).coerceAtLeast(1) - 1
        val bottom = param(1, 0).let { if (it == 0) rows else it }.coerceAtMost(rows) - 1
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            moveTo(0, 0)
        }
    }

    private fun useAltScreen(on: Boolean, clear: Boolean) {
        if (on == isAltScreen) return
        screen = if (on) alt else main
        if (on && clear) {
            clearScreen(alt)
            savedAlt = null
        }
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private fun clearScreen(lines: MutableList<Line>) {
        lines.forEach { it.clear(0, it.size, blank()); it.wrapped = false }
    }

    private fun alignmentTest() {
        screen.forEach { line ->
            for (x in 0 until line.size) {
                line.text[x] = 'E'.code
                line.style[x] = 0L
            }
        }
        setCursor(0, 0)
    }

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autowrap = true
        applicationCursorKeys = false
        applicationKeypad = false
        scrollTop = 0
        scrollBottom = rows - 1
        style = 0L
        g0Graphics = false
        g1Graphics = false
        useG1 = false
        savedMain = null
        savedAlt = null
        wrapPending = false
    }

    /** RIS, and a fresh screen for a new session. */
    fun reset() {
        softReset()
        screen = main
        clearScreen(main)
        clearScreen(alt)
        history.clear()
        bracketedPaste = false
        mouseTracking = 0
        sgrMouse = false
        reverseVideo = false
        newLineMode = false
        tabStops = defaultTabStops(columns)
        title = null
        state = State.Ground
        utf8Remaining = 0
        setCursor(0, 0)
    }

    // ---- Size ----

    /**
     * Changes the screen size. The main screen keeps the cursor's line visible: when it gets shorter,
     * lines above go into scrollback; when it gets taller, they come back from it. Full-screen
     * programs redraw themselves anyway once the server tells them the new size.
     */
    fun resize(newColumns: Int, newRows: Int) {
        val c = max(newColumns, 1)
        val r = max(newRows, 1)
        if (c == columns && r == rows) return

        val mainCursor = if (isAltScreen) savedMain?.y ?: 0 else cursorY
        val (newMain, mainY) = resizeLines(main, mainCursor, c, r, useHistory = true)
        val (newAlt, altY) = resizeLines(alt, if (isAltScreen) cursorY else 0, c, r, useHistory = false)
        val wasAlt = isAltScreen
        main = newMain
        alt = newAlt
        screen = if (wasAlt) alt else main
        columns = c
        rows = r

        if (wasAlt) {
            savedMain = savedMain?.let { SavedCursor(min(it.x, c - 1), mainY, it.style, false, it.origin, it.g0Graphics, it.g1Graphics, it.useG1) }
            setCursor(cursorX, altY)
        } else {
            setCursor(cursorX, mainY)
        }
        scrollTop = 0
        scrollBottom = r - 1
        tabStops = defaultTabStops(c)
    }

    private fun resizeLines(
        lines: MutableList<Line>,
        cursorRow: Int,
        newColumns: Int,
        newRows: Int,
        useHistory: Boolean,
    ): Pair<MutableList<Line>, Int> {
        val out = lines.mapTo(ArrayList(newRows)) { it.resized(newColumns) }
        var y = cursorRow.coerceIn(0, max(out.size - 1, 0))
        if (newRows < out.size) {
            // Drop blank lines below the cursor first, then lines above it.
            while (out.size > newRows && out.lastIndex > y && out.last().isBlank()) out.removeAt(out.lastIndex)
            val fromTop = max(0, y - (newRows - 1))
            repeat(min(fromTop, out.size)) {
                val line = out.removeAt(0)
                if (useHistory) addToHistory(line)
            }
            y -= fromTop
            while (out.size > newRows) out.removeAt(out.lastIndex)
        } else {
            while (out.size < newRows) {
                if (useHistory && history.isNotEmpty() && out.lastOrNull()?.isBlank() != true) {
                    out.add(0, history.removeLast().resized(newColumns))
                    y++
                } else {
                    out.add(Line(newColumns))
                }
            }
        }
        return out to y.coerceIn(0, newRows - 1)
    }

    private fun Line.isBlank(): Boolean = text.all { it == 0 || it == ' '.code }

    private fun respond(text: String) = reply(text.toByteArray())

    companion object {
        /** The right half of a wide (CJK, emoji) character, which uses two cells. */
        const val WIDE_TAIL = -1

        private const val MAX_PARAMS = 32
        private const val MAX_OSC = 4096
        private const val MAX_HISTORY = 5_000
        private const val REPLACEMENT = 0xFFFD
        private const val BEL = 0x07
        private const val ESC = 0x1B
        private const val CAN = 0x18
        private const val SUB = 0x1A

        private val URL = Regex("(?:https?|ftp)://[^\\s<>\"'`]+|www\\.[^\\s<>\"'`]+")

        /** DEC Special Graphics for '_'..'~': the box-drawing set used by `ESC ( 0`. */
        private const val DEC_GRAPHICS = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        private fun defaultTabStops(columns: Int) = BooleanArray(columns) { it % 8 == 0 && it != 0 }

        /** Cells a character takes: 0 for combining marks, 2 for East Asian wide characters and emoji. */
        fun charWidth(cp: Int): Int {
            if (cp < 0x300) return 1
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> return 0
            }
            if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return 0 // Variation selectors
            return if (isWide(cp)) 2 else 1
        }

        private fun isWide(cp: Int): Boolean =
            cp in 0x1100..0x115F || cp in 0x231A..0x231B || cp in 0x2329..0x232A || cp in 0x23E9..0x23EC ||
                cp == 0x23F0 || cp == 0x23F3 || cp in 0x25FD..0x25FE || cp in 0x2614..0x2615 ||
                cp in 0x2648..0x2653 || cp == 0x267F || cp == 0x2693 || cp == 0x26A1 || cp in 0x26AA..0x26AB ||
                cp in 0x26BD..0x26BE || cp in 0x26C4..0x26C5 || cp == 0x26CE || cp == 0x26D4 || cp == 0x26EA ||
                cp in 0x26F2..0x26F3 || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD || cp == 0x2705 ||
                cp in 0x270A..0x270B || cp == 0x2728 || cp == 0x274C || cp == 0x274E || cp in 0x2753..0x2755 ||
                cp == 0x2757 || cp in 0x2795..0x2797 || cp == 0x27B0 || cp == 0x27BF || cp in 0x2B1B..0x2B1C ||
                cp == 0x2B50 || cp == 0x2B55 || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
                cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xA960..0xA97F ||
                cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE10..0xFE19 || cp in 0xFE30..0xFE6F ||
                cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x16FE0..0x18AFF || cp in 0x1B000..0x1B2FF ||
                cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E || cp in 0x1F191..0x1F19A ||
                cp in 0x1F200..0x1F251 || cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF ||
                cp in 0x1F7E0..0x1F7EB || cp in 0x1F90C..0x1F9FF || cp in 0x1FA70..0x1FAFF ||
                cp in 0x20000..0x2FFFD || cp in 0x30000..0x3FFFD
    }
}

/**
 * A cell's look packed into a Long: attribute bits, then a 26-bit foreground and background colour.
 * A colour is [DEFAULT_COLOR], an indexed palette colour, or 24-bit RGB.
 */
object TextStyle {
    const val BOLD = 1L
    const val DIM = 2L
    const val ITALIC = 4L
    const val UNDERLINE = 8L
    const val BLINK = 16L
    const val INVERSE = 32L
    const val INVISIBLE = 64L
    const val STRIKE = 128L

    const val DEFAULT_COLOR = 0
    private const val INDEXED = 1 shl 24
    private const val RGB = 2 shl 24
    private const val COLOR_MASK = 0x3FFFFFFL
    private const val FG_SHIFT = 9
    private const val BG_SHIFT = 35

    fun indexed(index: Int) = INDEXED or index
    fun rgb(r: Int, g: Int, b: Int) = RGB or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun isIndexed(color: Int) = (color and (3 shl 24)) == INDEXED
    fun isRgb(color: Int) = (color and (3 shl 24)) == RGB
    fun value(color: Int) = color and 0xFFFFFF

    fun foreground(style: Long) = ((style ushr FG_SHIFT) and COLOR_MASK).toInt()
    fun background(style: Long) = ((style ushr BG_SHIFT) and COLOR_MASK).toInt()

    fun withForeground(style: Long, color: Int) =
        (style and (COLOR_MASK shl FG_SHIFT).inv()) or (color.toLong() shl FG_SHIFT)

    fun withBackground(style: Long, color: Int) =
        (style and (COLOR_MASK shl BG_SHIFT).inv()) or (color.toLong() shl BG_SHIFT)
}
