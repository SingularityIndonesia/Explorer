package com.singularity_universe.explorer.remote

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.singularity_universe.explorer.terminal.TerminalEmulator
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An interactive shell on a saved SSH server, on its own connection: a PTY ("xterm-256color")
 * whose output feeds a [TerminalEmulator], so full-screen programs like nano, vim and htop work.
 *
 * The owner (the terminal activity's ViewModel) keeps it for as long as the terminal is open and
 * calls [close] at the end; until then the session stays connected, with SSH keepalives.
 */
class SshTerminal(private val ssh: SshConnections, val hostId: String) {

    sealed interface Status {
        data object Connecting : Status
        data object Connected : Status
        /** The shell exited (e.g. `exit`), or the connection dropped. */
        data class Ended(val message: String) : Status
        data class Failed(val message: String) : Status
    }

    var status by mutableStateOf<Status>(Status.Connecting)
        private set

    /** The window title the shell sets, e.g. "steve@mac: ~". */
    var title by mutableStateOf<String?>(null)
        private set

    /** The screen. Synchronize on it to read or change it. */
    val emulator = TerminalEmulator(DEFAULT_COLUMNS, DEFAULT_ROWS) { reply -> send(reply) }

    /** Called (on the reader thread) whenever the screen changed; the view redraws. */
    @Volatile
    var onScreenChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Writes and PTY resizes go out in order on one thread, never on the main thread. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ssh-terminal-writer") }
    private var job: Job? = null

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false

    /** The user disconnected (from the notification): the end isn't an error. */
    @Volatile private var hungUp = false

    /** Folder to start the shell in (set before connecting, e.g. "Open terminal here"). */
    @Volatile var startDirectory: String? = null

    /** The last size the view reported: columns, rows, width and height in pixels. */
    @Volatile private var size = intArrayOf(DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0)

    fun connect() {
        if (closed || job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Starts over on a new connection, with a cleared screen. */
    fun reconnect() {
        if (closed) return
        val old = job
        disconnect()
        synchronized(emulator) { emulator.reset() }
        onScreenChanged?.invoke()
        job = scope.launch {
            old?.join()
            run()
        }
    }

    private suspend fun run() {
        hungUp = false
        status = Status.Connecting
        try {
            val opened = ssh.openSession(hostId)
            session = opened
            if (closed) return
            if (hungUp) {
                status = Status.Ended("Disconnected")
                return
            }
            val (columns, rows, width, height) = size
            val shell = (opened.openChannel("shell") as ChannelShell).apply {
                setPtyType(TERM, columns, rows, width, height)
                // macOS and most Linux servers accept LANG, so programs print UTF-8 (box lines, accents).
                setEnv("LANG", "en_US.UTF-8")
            }
            val input = shell.inputStream
            output = shell.outputStream
            channel = shell
            shell.connect(CONNECT_TIMEOUT_MS)
            status = Status.Connected
            // Starting in a folder: change to it, then clear the screen so it opens at a fresh prompt.
            startDirectory?.let { send("cd -- ${shellQuote(it)} && clear\r") }
            // The view may have measured itself while the channel was opening.
            if (!size.contentEquals(intArrayOf(columns, rows, width, height))) resizePty()

            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val newTitle = synchronized(emulator) {
                    emulator.append(buffer, read)
                    emulator.title
                }
                if (newTitle != title) title = newTitle
                onScreenChanged?.invoke()
            }
            status = Status.Ended(if (hungUp) "Disconnected" else endMessage(shell))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (closed) return
            Log.w(TAG, "Terminal session failed", e)
            status = if (hungUp) Status.Ended("Disconnected")
            else if (status == Status.Connected) Status.Ended("The connection was lost")
            else Status.Failed(e.message ?: "Couldn't connect")
        } finally {
            disconnect()
        }
    }

    private fun endMessage(shell: ChannelShell): String {
        // The exit status arrives just before the channel closes.
        repeat(20) {
            if (shell.isClosed) return@repeat
            Thread.sleep(25)
        }
        return when (val code = shell.exitStatus) {
            -1 -> "The connection was closed"
            0 -> "Session ended"
            else -> "Session ended (exit code $code)"
        }
    }

    /** Sends what the user typed (or the emulator's answers to queries) to the shell. */
    fun send(bytes: ByteArray) {
        val out = output ?: return
        post {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Terminal write failed", e)
            }
        }
    }

    fun send(text: String) = send(text.toByteArray())

    /** The view's new size in cells (and pixels): resizes the screen and tells the server. */
    fun resize(columns: Int, rows: Int, width: Int, height: Int) {
        val changed = synchronized(emulator) {
            val before = emulator.columns to emulator.rows
            emulator.resize(columns, rows)
            before != (emulator.columns to emulator.rows)
        }
        size = intArrayOf(columns, rows, width, height)
        if (changed) {
            resizePty()
            onScreenChanged?.invoke()
        }
    }

    private fun resizePty() {
        val shell = channel ?: return
        val (columns, rows, width, height) = size
        post { runCatching { shell.setPtySize(columns, rows, width, height) } }
    }

    private fun post(block: () -> Unit) {
        if (closed) return
        try {
            writer.execute(block)
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    private fun disconnect() {
        val oldChannel = channel
        val oldSession = session
        channel = null
        session = null
        output = null
        if (oldChannel == null && oldSession == null) return
        // Closing sockets is network I/O: never on the main thread.
        thread(name = "ssh-terminal-close") {
            runCatching { oldChannel?.disconnect() }
            runCatching { oldSession?.disconnect() }
        }
    }

    /**
     * "Open terminal here" for an open terminal: changes the shell to [directory]. Returns false
     * when a full-screen program (nano, top...) is running, since typing into it would do harm.
     */
    fun openDirectory(directory: String): Boolean {
        if (status != Status.Connected) {
            startDirectory = directory
            if (status !is Status.Connecting) reconnect()
            return true
        }
        if (synchronized(emulator) { emulator.isAltScreen }) return false
        startDirectory = directory
        send("cd -- ${shellQuote(directory)}\r")
        return true
    }

    /** Disconnects, keeping the screen; [reconnect] starts a new session. */
    fun hangUp() {
        hungUp = true
        disconnect()
    }

    /** Ends the session for good. */
    fun close() {
        if (closed) return
        closed = true
        onScreenChanged = null
        disconnect()
        scope.cancel()
        writer.shutdown()
    }

    private companion object {
        /** Single-quotes a path for POSIX shells: 'it'\''s' for "it's". */
        fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        const val TAG = "ExplorerTerminal"
        const val TERM = "xterm-256color"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
