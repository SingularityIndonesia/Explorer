package com.singularity_universe.explorer.terminal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.singularity_universe.explorer.remote.SshTerminal
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [SshTerminal]'s screen and sends it the keyboard. A plain View rather than a Compose text
 * field: a terminal needs every key as typed (Ctrl, arrows, Esc), not an editable text buffer.
 *
 * Gestures: tap shows the keyboard (or clicks, when the program uses the mouse), drag scrolls back
 * through earlier output (or scrolls the program: arrow keys / mouse wheel), pinch changes the
 * text size.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, private val modifiers: StickyModifiers) : View(context) {

    var terminal: SshTerminal? = null
        set(value) {
            if (value === field) return
            clearSelection()
            field?.onScreenChanged = null
            field = value
            value?.onScreenChanged = { postInvalidateOnAnimation() }
            scrollback = 0
            updateGrid()
            invalidate()
        }

    /** Text size in sp; pinch changes it and reports the result through [onFontSizeChanged]. */
    var fontSize = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (clamped == field) return
            field = clamped
            measureCells()
            updateGrid()
            invalidate()
        }

    var onFontSizeChanged: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = terminalTypeface }

    /**
     * Whether every character of the font is one cell wide. Some phones and tablets replace the
     * system monospace font with a proportional one; then each character is placed in its own cell
     * so the text lines up with the cursor and with what the server thinks is on screen.
     */
    private var monospaced = true
    private val background = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1
    private var ascent = 0f
    private val padding = (4 * resources.displayMetrics.density).toInt()

    /** How many lines back into the scrollback the view shows (0 = following the output). */
    private var scrollback = 0

    /** Selected text, in buffer positions: start line, start column, end line, end column. */
    private var selection: IntArray? = null
    private var selecting = false
    private var actionMode: ActionMode? = null
    private val selectionPaint = Paint().apply { color = 0x664FC3F7 }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF64B5F6.toInt() }

    /** While selecting, the finger's height; at the top or bottom edge the view scrolls on its own. */
    private var edgeY = -1f
    private val edgeScroller = object : Runnable {
        override fun run() {
            val emulator = terminal?.emulator ?: return
            if (!selecting || edgeY < 0) return
            val direction = when {
                edgeY < padding + cellHeight -> 1 // Up into earlier output.
                edgeY > height - padding - cellHeight -> -1
                else -> return
            }
            synchronized(emulator) {
                scrollback = (scrollback + direction).coerceIn(0, emulator.historySize)
                val row = if (direction > 0) 0 else emulator.rows - 1
                selection?.let { it[2] = emulator.historySize - scrollback + row }
            }
            invalidate()
            actionMode?.invalidateContentRect()
            postDelayed(this, 60)
        }
    }
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCells()
    }

    // ---- Layout ----

    private fun measureCells() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, resources.displayMetrics)
        cellWidth = paint.measureText("M")
        monospaced = listOf("i", "l", "W", ".", "_").all { abs(paint.measureText(it) - cellWidth) < cellWidth * 0.02f }
        val metrics = paint.fontMetrics
        cellHeight = ceil(metrics.descent - metrics.ascent).toInt()
        ascent = -metrics.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateGrid()
    }

    private fun updateGrid() {
        val terminal = terminal ?: return
        if (width == 0 || height == 0) return
        val columns = max(((width - 2 * padding) / cellWidth).toInt(), 4)
        val rows = max((height - 2 * padding) / cellHeight, 2)
        terminal.resize(columns, rows, width, height)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        // Inside Compose the canvas isn't clipped to this view: drawColor would paint over the
        // keys panel beside it. Everything is kept within the view's own bounds.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(TerminalColors.BACKGROUND)
        val terminal = terminal ?: return
        val emulator = terminal.emulator
        synchronized(emulator) {
            scrollback = scrollback.coerceIn(0, emulator.historySize)
            val reverse = emulator.reverseVideo
            for (row in 0 until emulator.rows) {
                val line = emulator.lineAt(row - scrollback)
                val cursorColumn = if (
                    scrollback == 0 && row == emulator.cursorY && emulator.cursorVisible
                ) emulator.cursorX else -1
                drawLine(canvas, line, row, min(line.size, emulator.columns), cursorColumn, reverse)
                // Web addresses are underlined: tapping one opens it.
                for (range in emulator.urlColumns(emulator.historySize - scrollback + row)) {
                    val y = padding + row * cellHeight + ascent + cellHeight * 0.12f
                    canvas.drawRect(padding + range.first * cellWidth, y, padding + (range.last + 1) * cellWidth, y + maxOf(1f, cellHeight * 0.06f), linkPaint)
                }
            }
            selection?.let { drawSelection(canvas, emulator, it) }
        }
    }

    private fun drawSelection(canvas: Canvas, emulator: TerminalEmulator, sel: IntArray) {
        val (startLine, startColumn, endLine, endColumn) = ordered(sel)
        for (row in 0 until emulator.rows) {
            val line = emulator.historySize - scrollback + row
            if (line < startLine || line > endLine) continue
            val from = if (line == startLine) startColumn else 0
            val to = if (line == endLine) endColumn else emulator.columns - 1
            val top = padding + row * cellHeight.toFloat()
            canvas.drawRect(padding + from * cellWidth, top, padding + (to + 1) * cellWidth, top + cellHeight, selectionPaint)
        }
    }

    /** The selection with its start before its end. */
    private fun ordered(sel: IntArray): IntArray =
        if (sel[0] < sel[2] || (sel[0] == sel[2] && sel[1] <= sel[3])) sel else intArrayOf(sel[2], sel[3], sel[0], sel[1])

    private val run = StringBuilder()

    private fun drawLine(
        canvas: Canvas,
        line: TerminalEmulator.Line,
        row: Int,
        columns: Int,
        cursorColumn: Int,
        reverse: Boolean,
    ) {
        val top = padding + row * cellHeight.toFloat()
        val baseline = top + ascent
        var x = 0
        while (x < columns) {
            val cp = line.text[x]
            val style = line.style[x]
            if (cp == TerminalEmulator.WIDE_TAIL) {
                x++
                continue
            }
            val width = if (x + 1 < columns && line.text[x + 1] == TerminalEmulator.WIDE_TAIL) 2 else 1
            val isCursor = x == cursorColumn || (width == 2 && x + 1 == cursorColumn)
            var (fg, bg) = TerminalColors.resolve(style, reverse)
            if (isCursor) {
                if (hasFocus()) {
                    val swap = fg
                    fg = bg
                    bg = swap
                }
            }
            val left = padding + x * cellWidth

            // Batch plain ASCII cells with the same look into one draw call; others are drawn one by one.
            var end = x + width
            if (monospaced && width == 1 && cp in 0x20..0x7E && !isCursor) {
                while (end < columns && end != cursorColumn && line.style[end] == style &&
                    line.text[end].let { it in 0x20..0x7E || it == 0 }
                ) end++
            }
            if (bg != TerminalColors.BACKGROUND) {
                background.color = bg
                canvas.drawRect(left, top, padding + end * cellWidth, top + cellHeight, background)
            }
            if (isCursor && !hasFocus()) {
                background.color = TerminalColors.FOREGROUND
                background.style = Paint.Style.STROKE
                background.strokeWidth = 2f
                canvas.drawRect(left + 1, top + 1, left + width * cellWidth - 1, top + cellHeight - 1, background)
                background.style = Paint.Style.FILL
            }
            if ((style and TextStyle.INVISIBLE) == 0L) {
                paint.color = fg
                paint.isFakeBoldText = (style and TextStyle.BOLD) != 0L
                paint.textSkewX = if ((style and TextStyle.ITALIC) != 0L) -0.2f else 0f
                paint.isUnderlineText = (style and TextStyle.UNDERLINE) != 0L
                paint.isStrikeThruText = (style and TextStyle.STRIKE) != 0L
                if (end - x > 1) {
                    run.setLength(0)
                    for (i in x until end) run.append(if (line.text[i] == 0) ' ' else line.text[i].toChar())
                    canvas.drawText(run, 0, run.length, left, baseline, paint)
                } else if (cp != 0 && cp != ' '.code) {
                    val text = String(Character.toChars(cp))
                    // Glyphs from fallback fonts (box drawing, CJK, emoji) are fitted into their cells.
                    val measured = paint.measureText(text)
                    val room = width * cellWidth
                    if (measured > room * 1.05f) {
                        canvas.save()
                        canvas.scale(room / measured, 1f, left, baseline)
                        canvas.drawText(text, left, baseline, paint)
                        canvas.restore()
                    } else {
                        canvas.drawText(text, left + (room - measured) / 2, baseline, paint)
                    }
                } else if (paint.isUnderlineText || paint.isStrikeThruText) {
                    canvas.drawText(" ", left, baseline, paint)
                }
            }
            x = end
        }
    }

    // ---- Touch ----

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val terminal = terminal ?: return false
            if (selection != null) {
                clearSelection()
                return true
            }
            val (mouse, sgr) = synchronized(terminal.emulator) { terminal.emulator.mouseTracking to terminal.emulator.sgrMouse }
            // A tapped web address opens in the browser.
            val (tapColumn, tapRow) = cellAt(e)
            val url = synchronized(terminal.emulator) { terminal.emulator.urlAt(terminal.emulator.historySize - scrollback + tapRow, tapColumn) }
            if (url != null) {
                openUrl(url)
                return true
            }
            if (mouse != 0) {
                val (column, row) = cellAt(e)
                TerminalKeys.mouse(0, column, row, press = true, sgr = sgr)?.let(terminal::send)
                TerminalKeys.mouse(0, column, row, press = false, sgr = sgr)?.let(terminal::send)
            } else {
                showKeyboard()
            }
            return true
        }

        /** Long-press selects the word under the finger; dragging on extends the selection. */
        override fun onLongPress(e: MotionEvent) {
            val terminal = terminal ?: return
            val emulator = terminal.emulator
            val (column, row) = cellAt(e)
            synchronized(emulator) {
                val line = emulator.historySize - scrollback + row
                val word = emulator.wordAt(line, column)
                selection = intArrayOf(line, word.first, line, word.last)
            }
            selecting = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startSelectionMode()
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            scrollRemainder += distanceY
            val lines = (scrollRemainder / cellHeight).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * cellHeight
            scrollBy(lines, e2)
            return true
        }
    })

    private var scaling = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSize *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onFontSizeChanged?.invoke(fontSize)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Dragging after a long-press moves the end of the selection.
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val emulator = terminal?.emulator ?: return true
                    // Near the top or bottom edge: keep scrolling while the finger stays there.
                    val wasAtEdge = edgeY >= 0 && (edgeY < padding + cellHeight || edgeY > height - padding - cellHeight)
                    edgeY = event.y
                    val atEdge = edgeY < padding + cellHeight || edgeY > height - padding - cellHeight
                    if (atEdge && !wasAtEdge) post(edgeScroller)
                    val (column, row) = cellAt(event)
                    synchronized(emulator) {
                        selection?.let {
                            it[2] = emulator.historySize - scrollback + row
                            it[3] = column
                        }
                    }
                    invalidate()
                    actionMode?.invalidateContentRect()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    edgeY = -1f
                    removeCallbacks(edgeScroller)
                    actionMode?.invalidateContentRect()
                }
            }
        }
        scaler.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scaling = false
        gestures.onTouchEvent(event)
        return true
    }

    /** [lines] > 0: the finger moved up, towards newer output. */
    private fun scrollBy(lines: Int, event: MotionEvent) {
        val terminal = terminal ?: return
        val emulator = terminal.emulator
        val (mouse, sgr, alt, appCursor) = synchronized(emulator) {
            listOf(emulator.mouseTracking != 0, emulator.sgrMouse, emulator.isAltScreen, emulator.applicationCursorKeys)
        }
        when {
            // The program handles scrolling itself: send mouse wheel steps.
            mouse -> {
                val (column, row) = cellAt(event)
                repeat(abs(lines)) {
                    TerminalKeys.mouse(if (lines > 0) 65 else 64, column, row, press = true, sgr = sgr)?.let(terminal::send)
                }
            }
            // Full-screen programs without mouse support (less, man, nano): arrow keys, like most terminals.
            alt -> {
                val key = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                val sequence = TerminalKeys.encode(key, 0, ctrl = false, alt = false, shift = false, appCursor = appCursor)
                repeat(abs(lines)) { sequence?.let(terminal::send) }
            }
            else -> {
                scrollback = (scrollback - lines).coerceIn(0, synchronized(emulator) { emulator.historySize })
                invalidate()
            }
        }
    }

    private fun cellAt(event: MotionEvent): Pair<Int, Int> {
        val emulator = terminal?.emulator ?: return 0 to 0
        val column = ((event.x - padding) / cellWidth).toInt().coerceIn(0, emulator.columns - 1)
        val row = ((event.y - padding) / cellHeight).toInt().coerceIn(0, emulator.rows - 1)
        return column to row
    }

    private fun startSelectionMode() {
        if (actionMode != null) {
            actionMode?.invalidateContentRect()
            return
        }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_COPY, 0, android.R.string.copy)
                menu.add(0, MENU_SELECT_ALL, 1, android.R.string.selectAll)
                menu.add(0, MENU_PASTE, 2, android.R.string.paste)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> {
                        copySelection()
                        mode.finish()
                    }
                    MENU_SELECT_ALL -> {
                        terminal?.emulator?.let { emulator ->
                            synchronized(emulator) { selection = intArrayOf(0, 0, emulator.totalLines - 1, emulator.columns - 1) }
                        }
                        invalidate()
                        mode.invalidateContentRect()
                    }
                    MENU_PASTE -> {
                        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)
                            ?.coerceToText(context)?.toString()?.let(::paste)
                        mode.finish()
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selection = null
                selecting = false
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val emulator = terminal?.emulator
                val sel = selection
                if (emulator == null || sel == null) {
                    outRect.set(0, 0, width, height / 2)
                    return
                }
                val (startLine, _, endLine, _) = ordered(sel)
                val firstRow = (startLine - (emulator.historySize - scrollback)).coerceIn(0, emulator.rows - 1)
                val lastRow = (endLine - (emulator.historySize - scrollback)).coerceIn(0, emulator.rows - 1)
                outRect.set(0, padding + firstRow * cellHeight, width, padding + (lastRow + 1) * cellHeight)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    private fun copySelection() {
        val emulator = terminal?.emulator ?: return
        val sel = selection ?: return
        val text = synchronized(emulator) { emulator.textBetween(sel[0], sel[1], sel[2], sel[3]) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    private fun clearSelection() {
        actionMode?.finish() ?: run {
            selection = null
            invalidate()
        }
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(if (url.startsWith("www.")) "https://$url" else url)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
            imm.hideSoftInputFromWindow(windowToken, 0)
        } else {
            showKeyboard()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate() // The cursor is drawn filled only while focused.
    }

    // ---- Keyboard ----

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A plain text field, so keyboards offer everything they normally do (not their password
        // mode). Words the keyboard is still composing are sent as they're typed: see below.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    /**
     * Keyboards build a word up as "composing" text and may replace it (autocorrect, a tapped
     * suggestion) before committing it. A terminal can't wait for that, so every change to the
     * composing word is sent at once, as backspaces for what changed plus the new characters.
     */
    private inner class TerminalInputConnection : BaseInputConnection(this, true) {
        /** The composing word as already sent to the terminal. */
        private var sent = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val now = text?.toString().orEmpty()
            sendChange(sent, now)
            sent = now
            return super.setComposingText(text, newCursorPosition)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            sendChange(sent, text?.toString().orEmpty())
            reset()
            return true
        }

        override fun finishComposingText(): Boolean {
            // The composing word was already sent; it just stops being replaceable.
            reset()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Nothing outside the composing word is kept here, so this is Backspace / Delete.
            repeat(beforeLength.coerceAtMost(64)) { sendKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceAtMost(64)) { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        private fun reset() {
            sent = ""
            editable?.clear()
            super.finishComposingText()
        }

        /** Turns [before] into [after] on the terminal: erase where they differ, then type the rest. */
        private fun sendChange(before: String, after: String) {
            var common = 0
            while (common < before.length && common < after.length && before[common] == after[common]) common++
            val erase = before.codePointCount(common, before.length)
            repeat(erase) { sendKey(KeyEvent.KEYCODE_DEL) }
            if (common < after.length) typeText(after.substring(common))
        }
    }

    /** Text from the soft keyboard. Sticky Ctrl/Alt from the extra keys apply to the first character. */
    private fun typeText(text: String) {
        val terminal = terminal ?: return
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) {
                sendKey(KeyEvent.KEYCODE_ENTER)
                continue
            }
            val ctrl = modifiers.ctrl
            val alt = modifiers.alt
            if (ctrl || alt) {
                val control = if (ctrl) TerminalKeys.controlCharacter(cp)?.toString() else null
                val typed = control ?: String(Character.toChars(cp))
                modifiers.clear()
                terminal.send(if (alt) "\u001b$typed" else typed)
            } else {
                terminal.send(String(Character.toChars(cp)))
            }
        }
        followOutput()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(keyCode) || isSystemKey(keyCode)) {
            return super.onKeyDown(keyCode, event)
        }
        val terminal = terminal ?: return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed || modifiers.ctrl
        val alt = event.isAltPressed || modifiers.alt
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
        if ((unicode and KeyCharacterMap.COMBINING_ACCENT) != 0) return true
        val appCursor = synchronized(terminal.emulator) { terminal.emulator.applicationCursorKeys }
        val sequence = TerminalKeys.encode(keyCode, unicode, ctrl, alt, event.isShiftPressed, appCursor)
            ?: return super.onKeyDown(keyCode, event)
        modifiers.clear()
        terminal.send(sequence)
        followOutput()
        return true
    }

    private fun isSystemKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
        else -> false
    }

    /** A key from the extra keys row (or the input connection), with the sticky modifiers. */
    fun sendKey(keyCode: Int) {
        val terminal = terminal ?: return
        val appCursor = synchronized(terminal.emulator) { terminal.emulator.applicationCursorKeys }
        val sequence = TerminalKeys.encode(keyCode, 0, modifiers.ctrl, modifiers.alt, shift = false, appCursor = appCursor) ?: return
        modifiers.clear()
        terminal.send(sequence)
        followOutput()
    }

    /** Text from the extra keys row ("-", "/", "|", "~"), with the sticky modifiers. */
    fun sendText(text: String) = typeText(text)

    /** A Ctrl shortcut key (^C, ^D...), sent as its control character. */
    fun sendControl(letter: Char) {
        val terminal = terminal ?: return
        val control = TerminalKeys.controlCharacter(letter.code) ?: return
        val alt = modifiers.alt
        modifiers.clear()
        terminal.send(if (alt) "\u001b$control" else control.toString())
        followOutput()
    }

    /** Pastes [text]; programs that asked for bracketed paste get it marked, so it isn't run line by line. */
    fun paste(text: String) {
        val terminal = terminal ?: return
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = synchronized(terminal.emulator) { terminal.emulator.bracketedPaste }
        terminal.send(if (bracketed) "\u001b[200~$normalized\u001b[201~" else normalized)
        followOutput()
    }

    /** Typing jumps back to the live output. */
    private fun followOutput() {
        if (scrollback != 0) {
            scrollback = 0
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        terminal?.onScreenChanged = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        terminal?.onScreenChanged = { postInvalidateOnAnimation() }
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_SELECT_ALL = 2
        private const val MENU_PASTE = 3
        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 32f

        /**
         * A real monospace font. Typeface.MONOSPACE can be a manufacturer's proportional font, so
         * Android's own monospace font file is preferred when it's there.
         */
        private val terminalTypeface: Typeface by lazy {
            listOf("DroidSansMono.ttf", "NotoSansMono-Regular.ttf")
                .map { File("/system/fonts", it) }
                .firstOrNull { it.exists() }
                ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                ?: Typeface.MONOSPACE
        }
    }
}

/** Ctrl and Alt from the extra keys row: they apply to the next key typed, then switch off. */
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun clear() {
        ctrl = false
        alt = false
    }
}

/** The terminal's colours: a dark theme with the familiar 16-colour palette, plus xterm's 256 colours. */
object TerminalColors {
    val BACKGROUND = Color.rgb(0x1E, 0x1E, 0x1E)
    val FOREGROUND = Color.rgb(0xD4, 0xD4, 0xD4)

    private val ANSI = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )

    private val palette = IntArray(256) { i ->
        val rgb = when {
            i < 16 -> ANSI[i]
            i < 232 -> {
                val n = i - 16
                val levels = intArrayOf(0, 95, 135, 175, 215, 255)
                (levels[n / 36] shl 16) or (levels[(n / 6) % 6] shl 8) or levels[n % 6]
            }
            else -> (8 + (i - 232) * 10).let { (it shl 16) or (it shl 8) or it }
        }
        Color.rgb(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }

    /** Foreground and background for a cell, after inverse video and dim. */
    fun resolve(style: Long, reverseScreen: Boolean): Pair<Int, Int> {
        var fg = color(TextStyle.foreground(style), FOREGROUND)
        var bg = color(TextStyle.background(style), BACKGROUND)
        if (((style and TextStyle.INVERSE) != 0L) != reverseScreen) {
            val swap = fg
            fg = bg
            bg = swap
        }
        if ((style and TextStyle.DIM) != 0L) fg = Color.argb(0xFF, Color.red(fg) * 2 / 3, Color.green(fg) * 2 / 3, Color.blue(fg) * 2 / 3)
        return fg to bg
    }

    private fun color(value: Int, default: Int): Int = when {
        TextStyle.isIndexed(value) -> palette[TextStyle.value(value) and 0xFF]
        TextStyle.isRgb(value) -> TextStyle.value(value) or (0xFF shl 24)
        else -> default
    }
}
