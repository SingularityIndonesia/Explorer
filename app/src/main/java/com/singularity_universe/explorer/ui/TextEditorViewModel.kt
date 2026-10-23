package com.singularity_universe.explorer.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.singularity_universe.explorer.data.MAX_EDIT_BYTES
import com.singularity_universe.explorer.data.isJsonFile
import com.singularity_universe.explorer.data.mimeTypeOf
import com.singularity_universe.explorer.data.prettifyJson
import com.singularity_universe.explorer.data.readTextFile
import com.singularity_universe.explorer.data.writeTextFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/** State of [com.singularity_universe.explorer.TextEditorActivity]: the file, its edit history, and autosave. */
class TextEditorViewModel(app: Application, path: String) : AndroidViewModel(app) {
    val file = File(path)
    val isJson = isJsonFile(file.name, mimeTypeOf(file.name))

    sealed interface State {
        data object Loading : State
        class Ready(val history: EditHistory) : State
        class Failed(val reason: String) : State
    }

    sealed interface SaveStatus {
        data object Saved : SaveStatus
        data object Saving : SaveStatus
        class Failed(val reason: String) : SaveStatus
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    var saveStatus by mutableStateOf<SaveStatus>(SaveStatus.Saved)
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /** What the file on disk holds now; nothing is written while the text still matches it. */
    private var savedText = ""
    private var crlf = false
    private var bom = false
    private val saveLock = Mutex()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val content = try {
            withContext(Dispatchers.IO) { readTextFile(file, limit = MAX_EDIT_BYTES + 1) }
        } catch (e: Exception) {
            state = State.Failed(e.message ?: "Couldn't read this file")
            return
        }
        if (!content.editable) {
            state = State.Failed(if (content.utf8) "This file is too large to edit here" else "Only UTF-8 text can be edited")
            return
        }
        savedText = content.text
        crlf = content.crlf
        bom = content.bom
        val history = EditHistory(TextFieldValue(content.text))
        state = State.Ready(history)
        autosave(history)
    }

    /** Saves a moment after typing stops. */
    @OptIn(FlowPreview::class)
    private fun autosave(history: EditHistory) {
        viewModelScope.launch {
            snapshotFlow { history.value.text }
                .drop(1)
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { save() }
        }
        viewModelScope.launch {
            // Show "Saving…" from the first keystroke, so the status never claims unsaved text is saved.
            snapshotFlow { history.value.text }
                .collect { text -> if (text != savedText && saveStatus !is SaveStatus.Failed) saveStatus = SaveStatus.Saving }
        }
    }

    /** Writes the current text if it changed. Safe to call often; saves never overlap. */
    private suspend fun save() {
        val history = (state as? State.Ready)?.history ?: return
        saveLock.withLock {
            val text = history.value.text
            if (text == savedText) {
                if (saveStatus !is SaveStatus.Failed) saveStatus = SaveStatus.Saved
                return
            }
            saveStatus = SaveStatus.Saving
            try {
                withContext(Dispatchers.IO) { writeTextFile(file, text, crlf, bom) }
                savedText = text
                saveStatus = if (history.value.text == text) SaveStatus.Saved else SaveStatus.Saving
            } catch (e: Exception) {
                Log.w("Explorer", "Saving ${file.path} failed", e)
                saveStatus = SaveStatus.Failed(e.message ?: "Couldn't save")
            }
        }
    }

    /**
     * Saves right away, e.g. when the editor is left. Runs outside [viewModelScope] so it still
     * finishes when the editor closes; a failure is shown as a toast since the screen may be gone.
     */
    fun saveNow() {
        if ((state as? State.Ready)?.history?.value?.text == savedText) return
        leftoverSaves.launch {
            save()
            (saveStatus as? SaveStatus.Failed)?.let {
                Toast.makeText(getApplication(), "Couldn't save ${file.name}: ${it.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Retries after a failed save. */
    fun retrySave() {
        saveStatus = SaveStatus.Saving
        viewModelScope.launch { save() }
    }

    /** Re-indents JSON as one undoable step. Strings, numbers and key order are kept as written. */
    fun prettify() {
        val history = (state as? State.Ready)?.history ?: return
        val text = history.value.text
        viewModelScope.launch {
            try {
                val pretty = withContext(Dispatchers.Default) { prettifyJson(text) }
                    .let { if (text.endsWith('\n')) it + '\n' else it }
                // Don't overwrite anything typed while it was being formatted.
                if (history.value.text != text) return@launch
                if (pretty == text) messageChannel.trySend("Already formatted")
                else history.replaceText(pretty)
            } catch (e: JSONException) {
                messageChannel.trySend("Not valid JSON: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        saveNow()
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 1_000L

        /** Saves that must outlive the editor screen. */
        private val leftoverSaves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
