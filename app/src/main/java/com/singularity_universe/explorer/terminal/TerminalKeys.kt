package com.singularity_universe.explorer.terminal

import android.view.KeyEvent

/** Turns keys into the bytes an xterm sends, so shells and full-screen programs understand them. */
object TerminalKeys {
    private const val ESC = "\u001b"

    /**
     * The sequence for [keyCode], or null if it isn't a terminal key. [unicode] is the character the
     * key types without Ctrl/Alt (0 for none). [appCursor] is the server's DECCKM mode.
     */
    fun encode(keyCode: Int, unicode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, appCursor: Boolean): String? {
        // xterm's modifier parameter: 1 + Shift + 2·Alt + 4·Ctrl.
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        special(keyCode, modifier, appCursor, shift)?.let { return it }

        val plain = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_DEL -> if (ctrl) "\b" else "\u007f" // Backspace
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_SPACE -> if (ctrl) "\u0000" else " "
            else -> {
                if (unicode == 0) return null
                val control = if (ctrl) controlCharacter(unicode) else null
                control?.toString() ?: String(Character.toChars(unicode))
            }
        }
        return if (alt) ESC + plain else plain
    }

    /** Ctrl+key: Ctrl+A..Z → 0x01..0x1A, plus the usual punctuation (Ctrl+[ is Esc, Ctrl+/ is 0x1F...). */
    fun controlCharacter(cp: Int): Char? = when (cp) {
        in 'a'.code..'z'.code -> (cp - 'a'.code + 1).toChar()
        in 'A'.code..'Z'.code -> (cp - 'A'.code + 1).toChar()
        ' '.code, '@'.code, '2'.code -> 0.toChar()
        '['.code, '3'.code -> 27.toChar()
        '\\'.code, '4'.code -> 28.toChar()
        ']'.code, '5'.code -> 29.toChar()
        '^'.code, '6'.code -> 30.toChar()
        '_'.code, '/'.code, '7'.code, '-'.code -> 31.toChar()
        '?'.code, '8'.code -> 127.toChar()
        else -> null
    }

    private fun special(keyCode: Int, modifier: Int, appCursor: Boolean, shift: Boolean): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> cursor('A', modifier, appCursor)
        KeyEvent.KEYCODE_DPAD_DOWN -> cursor('B', modifier, appCursor)
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursor('C', modifier, appCursor)
        KeyEvent.KEYCODE_DPAD_LEFT -> cursor('D', modifier, appCursor)
        KeyEvent.KEYCODE_MOVE_HOME -> cursor('H', modifier, appCursor)
        KeyEvent.KEYCODE_MOVE_END -> cursor('F', modifier, appCursor)
        KeyEvent.KEYCODE_INSERT -> tilde(2, modifier)
        KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3, modifier)
        KeyEvent.KEYCODE_PAGE_UP -> tilde(5, modifier)
        KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6, modifier)
        KeyEvent.KEYCODE_F1 -> function('P', modifier)
        KeyEvent.KEYCODE_F2 -> function('Q', modifier)
        KeyEvent.KEYCODE_F3 -> function('R', modifier)
        KeyEvent.KEYCODE_F4 -> function('S', modifier)
        KeyEvent.KEYCODE_F5 -> tilde(15, modifier)
        KeyEvent.KEYCODE_F6 -> tilde(17, modifier)
        KeyEvent.KEYCODE_F7 -> tilde(18, modifier)
        KeyEvent.KEYCODE_F8 -> tilde(19, modifier)
        KeyEvent.KEYCODE_F9 -> tilde(20, modifier)
        KeyEvent.KEYCODE_F10 -> tilde(21, modifier)
        KeyEvent.KEYCODE_F11 -> tilde(23, modifier)
        KeyEvent.KEYCODE_F12 -> tilde(24, modifier)
        KeyEvent.KEYCODE_TAB -> if (shift) "$ESC[Z" else null
        else -> null
    }

    private fun cursor(final: Char, modifier: Int, appCursor: Boolean) = when {
        modifier > 1 -> "$ESC[1;$modifier$final"
        appCursor -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    private fun tilde(number: Int, modifier: Int) = if (modifier > 1) "$ESC[$number;$modifier~" else "$ESC[$number~"

    private fun function(final: Char, modifier: Int) = if (modifier > 1) "$ESC[1;$modifier$final" else "${ESC}O$final"

    /** A mouse button press/release or wheel step at a cell, in the encoding the program asked for. */
    fun mouse(button: Int, column: Int, row: Int, press: Boolean, sgr: Boolean): String? {
        if (sgr) return "$ESC[<$button;${column + 1};${row + 1}${if (press) 'M' else 'm'}"
        // The old encoding can't express columns past 223, nor which button was released.
        if (column > 222 || row > 222) return null
        val code = if (press) button else 3
        return "$ESC[M" + (32 + code).toChar() + (33 + column).toChar() + (33 + row).toChar()
    }
}
