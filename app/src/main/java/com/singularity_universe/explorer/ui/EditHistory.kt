package com.singularity_universe.explorer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The text being edited, with undo and redo. Only the changed part of each edit is kept (not a copy
 * of the whole text), so long files keep a long history. Typing and backspacing within a word are
 * grouped into one step, like in most editors.
 */
class EditHistory(initial: TextFieldValue) {
    var value by mutableStateOf(initial)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<Edit>()
    private val redoStack = ArrayList<Edit>()
    private var lastEditAt = 0L

    /** False right after an undo, redo or replacement, so the next keystroke starts a new step. */
    private var merging = false

    /** Every change from the text field, including cursor moves and IME composing updates. */
    fun onValueChange(new: TextFieldValue) {
        val old = value
        value = new
        if (new.text != old.text) record(diff(old, new), mergeable = true)
    }

    /** Replaces the whole text as a single undoable step (e.g. Prettify). */
    fun replaceText(text: String) {
        val old = value
        if (text == old.text) return
        val new = TextFieldValue(text, TextRange(old.selection.min.coerceAtMost(text.length)))
        value = new
        record(Edit(0, old.text, text, old.selection, new.selection), mergeable = false)
    }

    fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        value = TextFieldValue(
            value.text.replaceRange(edit.start, edit.start + edit.inserted.length, edit.removed),
            edit.selectionBefore,
        )
        redoStack += edit
        merging = false
        updateFlags()
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        value = TextFieldValue(
            value.text.replaceRange(edit.start, edit.start + edit.removed.length, edit.inserted),
            edit.selectionAfter,
        )
        undoStack += edit
        merging = false
        updateFlags()
    }

    private fun record(edit: Edit, mergeable: Boolean) {
        val now = SystemClock.uptimeMillis()
        val last = undoStack.lastOrNull()
        val merged = if (mergeable && merging && last != null && now - lastEditAt < GROUP_WINDOW_MS) last.mergedWith(edit) else null
        if (merged != null) {
            undoStack[undoStack.lastIndex] = merged
        } else {
            undoStack += edit
            if (undoStack.size > MAX_STEPS) undoStack.removeAt(0)
        }
        merging = mergeable
        lastEditAt = now
        redoStack.clear()
        updateFlags()
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** At [start], [removed] was replaced by [inserted]. */
    private data class Edit(
        val start: Int,
        val removed: String,
        val inserted: String,
        val selectionBefore: TextRange,
        val selectionAfter: TextRange,
    ) {
        /** This edit and [next] as one step, or null when [next] should be a step of its own. */
        fun mergedWith(next: Edit): Edit? = when {
            // Typing on: one more character right after the last, within the same word or line.
            removed.isEmpty() && next.removed.isEmpty() && next.inserted.length == 1 &&
                next.start == start + inserted.length && !startsNewWord(inserted.last(), next.inserted[0]) ->
                copy(inserted = inserted + next.inserted, selectionAfter = next.selectionAfter)
            // Backspacing on: one more character just before the last one removed.
            inserted.isEmpty() && next.inserted.isEmpty() && next.removed.length == 1 &&
                next.start + 1 == start ->
                copy(start = next.start, removed = next.removed + removed, selectionAfter = next.selectionAfter)
            else -> null
        }

        private fun startsNewWord(previous: Char, typed: Char) =
            previous == '\n' || typed == '\n' || (typed.isWhitespace() && !previous.isWhitespace())
    }

    private companion object {
        /** A pause longer than this ends the current step. */
        const val GROUP_WINDOW_MS = 1_000L
        const val MAX_STEPS = 1_000

        /** The smallest edit turning [old] into [new]: everything between their common start and end. */
        fun diff(old: TextFieldValue, new: TextFieldValue): Edit {
            val a = old.text
            val b = new.text
            val shorter = minOf(a.length, b.length)
            var prefix = 0
            while (prefix < shorter && a[prefix] == b[prefix]) prefix++
            var suffix = 0
            while (suffix < shorter - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++
            return Edit(
                start = prefix,
                removed = a.substring(prefix, a.length - suffix),
                inserted = b.substring(prefix, b.length - suffix),
                selectionBefore = old.selection,
                selectionAfter = new.selection,
            )
        }
    }
}
