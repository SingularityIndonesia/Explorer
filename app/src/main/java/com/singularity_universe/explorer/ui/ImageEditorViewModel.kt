package com.singularity_universe.explorer.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.singularity_universe.explorer.data.BalloonOp
import com.singularity_universe.explorer.data.CropOp
import com.singularity_universe.explorer.data.ImageEdits
import com.singularity_universe.explorer.data.ImageOp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the picture editor: the decoded picture, the list of edits with undo/redo, and the
 * "base": the picture with every edit up to the last crop already drawn in. Edits after the last
 * crop are drawn live on top of it, which keeps drawing fast and balloons editable.
 */
class ImageEditorViewModel(app: Application, path: String) : AndroidViewModel(app) {
    val file = File(path)

    sealed interface State {
        data object Loading : State
        data object Ready : State
        class Failed(val reason: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var source: Bitmap? = null

    /** The edits, oldest first. */
    var ops by mutableStateOf<List<ImageOp>>(emptyList())
        private set

    private val undoStack = ArrayList<List<ImageOp>>()
    private val redoStack = ArrayList<List<ImageOp>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** The picture with the first [Base.baked] edits drawn in; the rest of [ops] are drawn on top. */
    class Base(val bitmap: Bitmap, val baked: Int, val ops: List<ImageOp>)

    var base by mutableStateOf<Base?>(null)
        private set

    var saving by mutableStateOf(false)
        private set

    val edited: Boolean get() = ops.isNotEmpty()

    private var baseJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) { ImageEdits.decode(file) }
                source = decoded
                base = Base(decoded, 0, emptyList())
                state = State.Ready
            } catch (e: Exception) {
                Log.w("Explorer", "Couldn't open ${file.path} for editing", e)
                state = State.Failed("Couldn't open this picture")
            } catch (e: OutOfMemoryError) {
                state = State.Failed("This picture is too large to edit")
            }
        }
    }

    // ---- Edits and history ----

    /** Adds [op] as one undoable step. */
    fun add(op: ImageOp) = commit(ops + op)

    /** Starts a change made of many small updates (dragging a balloon): one undo step for all of them. */
    fun beginChange() {
        pushUndo(ops)
    }

    /** Replaces a balloon without a new undo step (see [beginChange]). */
    fun updateBalloon(balloon: BalloonOp) {
        ops = ops.map { if (it is BalloonOp && it.id == balloon.id) balloon else it }
    }

    /** Replaces (or, with null, removes) a balloon as one undoable step. */
    fun replaceBalloon(id: Long, balloon: BalloonOp?) =
        commit(ops.mapNotNull { if (it is BalloonOp && it.id == id) balloon else it })

    private fun commit(newOps: List<ImageOp>) {
        pushUndo(ops)
        ops = newOps
        refreshBase()
    }

    private fun pushUndo(snapshot: List<ImageOp>) {
        undoStack += snapshot
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        updateFlags()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack += ops
        ops = previous
        updateFlags()
        refreshBase()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack += ops
        ops = next
        updateFlags()
        refreshBase()
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Redraws the base when the edits up to the last crop changed (a crop was added or undone). */
    private fun refreshBase() {
        val source = source ?: return
        val baked = ops.indexOfLast { it is CropOp } + 1
        val prefix = ops.take(baked)
        val current = base
        if (current != null && current.baked == baked && current.ops == prefix) return
        baseJob?.cancel()
        baseJob = viewModelScope.launch {
            val bitmap = if (baked == 0) source else withContext(Dispatchers.Default) { ImageEdits.render(source, prefix) }
            base = Base(bitmap, baked, prefix)
        }
    }

    /** Edits drawn live over the base. */
    fun liveOps(): List<ImageOp> {
        val current = base ?: return emptyList()
        if (ops.take(current.baked) != current.ops) return emptyList()
        return ops.drop(current.baked)
    }

    // ---- Crop and rotate ----

    fun crop(rect: Rect) {
        val bitmap = base?.bitmap ?: return
        if (rect.left <= 0 && rect.top <= 0 && rect.right >= bitmap.width && rect.bottom >= bitmap.height) return
        add(CropOp(rect))
    }

    fun rotate() {
        val bitmap = base?.bitmap ?: return
        add(CropOp(Rect(0, 0, bitmap.width, bitmap.height), quarterTurns = 1))
    }

    // ---- Saving ----

    /**
     * Saves the edited picture as a copy next to the original, or over it when [replace].
     * Calls [done] with the saved file (on the main thread) or null after a failure.
     */
    fun save(replace: Boolean, done: (File?, String?) -> Unit) {
        val source = source ?: return
        if (saving) return
        saving = true
        val edits = ops
        viewModelScope.launch {
            try {
                val target = if (replace && ImageEdits.canReplace(file)) file else ImageEdits.copyTarget(file)
                withContext(Dispatchers.Default) {
                    val result = ImageEdits.render(source, edits)
                    try {
                        withContext(Dispatchers.IO) { ImageEdits.save(result, target, file) }
                    } finally {
                        result.recycle()
                    }
                }
                // Galleries and Explorer's categories read MediaStore: tell it about the file.
                MediaScannerConnection.scanFile(getApplication(), arrayOf(target.path), null, null)
                done(target, null)
            } catch (e: Exception) {
                Log.w("Explorer", "Saving the edited picture failed", e)
                done(null, e.message ?: "Couldn't save the picture")
            } catch (e: OutOfMemoryError) {
                done(null, "Not enough memory to save this picture")
            } finally {
                saving = false
            }
        }
    }

    private companion object {
        const val MAX_UNDO = 100
    }
}
