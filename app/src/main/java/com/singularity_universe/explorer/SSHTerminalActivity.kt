package com.singularity_universe.explorer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.singularity_universe.explorer.remote.Endpoint
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshHost
import com.singularity_universe.explorer.remote.SshTerminal
import com.singularity_universe.explorer.remote.TerminalSessions
import com.singularity_universe.explorer.ui.SshTerminalScreen
import com.singularity_universe.explorer.ui.theme.ExplorerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A terminal on a saved SSH server, in its own window (one per server).
 *
 * The activity owns its own SSH connection and shell (through [TerminalViewModel]): it's opened
 * when the terminal opens and kept, through rotation, the keyboard opening and switching apps,
 * until the activity is destroyed. Back only sends the window to the background, so the session
 * keeps running; the close button (or the shell exiting) ends it.
 *
 * While connected, [com.singularity_universe.explorer.remote.TerminalService] keeps the app running
 * and awake with a notification. For that to hold while the phone sleeps, the app must be exempt
 * from battery optimization; the terminal asks once (and offers it again in its menu).
 */
class SSHTerminalActivity : ComponentActivity() {
    private val hostId: String? get() = intent.getStringExtra(EXTRA_HOST_ID)

    private val vm: TerminalViewModel by viewModels {
        viewModelFactory { initializer { TerminalViewModel(application, hostId!!, intent.getStringExtra(EXTRA_DIRECTORY)) } }
    }

    private var askBatteryExemption by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(true)

    /** The service's notification needs this permission to be shown (Android 13+). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched from an ssh://user@host link (browser, QR, shortcut, another app): find the
        // matching saved server and continue as if that server's terminal was opened.
        if (intent.action == Intent.ACTION_VIEW && intent.getStringExtra(EXTRA_HOST_ID) == null) {
            val resolved = resolveSshUri(intent.data)
            if (resolved == null) {
                Toast.makeText(this, sshUriError(intent.data), Toast.LENGTH_LONG).show()
                return finish()
            }
            intent.putExtra(EXTRA_HOST_ID, resolved.hostId)
            resolved.directory?.let { intent.putExtra(EXTRA_DIRECTORY, it) }
        }
        val id = hostId ?: return finish()
        // The terminal is always dark: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val ssh = SshConnections.get(this)
        val name = ssh.host(id)?.name ?: "Server"
        title = "$name · Terminal"
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (savedInstanceState == null) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            batteryExempt = isBatteryExempt()
            if (!batteryExempt && !prefs.getBoolean(KEY_ASKED_BATTERY, false)) {
                askBatteryExemption = true
                prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
            }
        }
        setContent {
            ExplorerTheme {
                if (askBatteryExemption) {
                    AlertDialog(
                        onDismissRequest = { askBatteryExemption = false },
                        title = { Text("Stay connected in the background?") },
                        text = {
                            Text(
                                "To keep this terminal connected while Explorer is in the background or the " +
                                    "screen is off, allow it to run without battery restrictions. A notification " +
                                    "shows while a session is open, and the connection ends when you close it."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                askBatteryExemption = false
                                requestBatteryExemption()
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askBatteryExemption = false }) { Text("Not now") }
                        },
                    )
                }
                SshTerminalScreen(
                    name = name,
                    terminal = vm.terminal,
                    sessions = vm.sessions,
                    current = vm.current,
                    onSelectSession = vm::select,
                    onNewSession = { vm.newSession() },
                    onCloseSession = { index -> if (vm.closeSession(index)) finishAndRemoveTask() },
                    fontSize = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE),
                    onFontSizeChanged = { prefs.edit().putFloat(KEY_FONT_SIZE, it).apply() },
                    onBack = {
                        // A live session stays open in the background; an ended one just closes.
                        if (vm.anyConnected) moveTaskToBack(true) else finish()
                    },
                    // The close button ends the session on screen; the window goes with the last one.
                    onClose = { if (vm.closeSession(vm.current)) finishAndRemoveTask() },
                    onKeepConnected = if (batteryExempt) null else ::requestBatteryExemption,
                    addresses = ssh.host(id)?.let { host -> host.hosts.indices.map(host::addressOf) }.orEmpty(),
                    activeAddress = ssh.host(id)?.activeHost ?: 0,
                    onSelectAddress = { index ->
                        // Saved for the server (the file browser uses it too), then a new session through it.
                        ssh.useAddress(id, index)
                        vm.terminal.reconnect()
                    },
                )
            }
        }
    }

    /** "Open terminal here" for a server whose terminal is already open: change folder in it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)
            ?: intent.takeIf { it.action == Intent.ACTION_VIEW }?.let { resolveSshUri(it.data)?.directory }
            ?: return
        if (!vm.terminal.openDirectory(directory)) {
            Toast.makeText(this, "Close the running program first, then try again", Toast.LENGTH_LONG).show()
        }
    }

    private data class ResolvedServer(val hostId: String, val directory: String?)

    /**
     * Turns an `ssh://[user[:password]@]host[:port][/dir]` link into a server to open:
     *  - with a username and password, it connects ad-hoc to that host (nothing is saved);
     *  - otherwise it matches a saved server by host (and username/port when the link names them).
     * Returns null when neither is possible.
     */
    private fun resolveSshUri(uri: Uri?): ResolvedServer? {
        if (uri == null || !uri.scheme.equals("ssh", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        // userInfo is "user" or "user:password" (percent-encoded).
        val userInfo = uri.userInfo
        val user = userInfo?.substringBefore(':')?.let(Uri::decode)?.takeIf { it.isNotBlank() }
        val password = userInfo?.substringAfter(':', "")?.let(Uri::decode)?.takeIf { it.isNotEmpty() }
        val port = uri.port.takeIf { it > 0 }
        val directory = uri.path?.takeIf { it.isNotBlank() && it != "/" }
        val ssh = SshConnections.get(this)

        // Full credentials in the link: connect ad-hoc without saving the server.
        if (user != null && password != null) {
            val adHoc = ssh.addTransient(
                SshHost(
                    id = "",
                    name = host,
                    hosts = listOf(if (port != null) "$host:$port" else host),
                    port = port ?: 22,
                    username = user,
                    password = password,
                )
            )
            return ResolvedServer(adHoc.id, directory)
        }

        // Otherwise match a saved server (prefer one that also matches the given port).
        val candidates = ssh.hosts.filter { server ->
            server.hosts.any { Endpoint.parse(it)?.host.equals(host, ignoreCase = true) == true } &&
                (user == null || server.username.equals(user, ignoreCase = true))
        }
        val match = port?.let { p -> candidates.firstOrNull { it.connectPort == p } }
            ?: candidates.firstOrNull()
            ?: return null
        return ResolvedServer(match.id, directory)
    }

    private fun sshUriError(uri: Uri?): String {
        val host = uri?.host
        return if (host.isNullOrBlank()) "That isn't a valid ssh:// address"
        else "No saved server matches $host. Open ssh://user:password@$host, or add it in Explorer."
    }

    override fun onDestroy() {
        super.onDestroy()
        // An ad-hoc (ssh:// link) server only lives as long as its terminal window.
        if (isFinishing) hostId?.takeIf { it.startsWith("transient-") }?.let { SshConnections.get(this).removeTransient(it) }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from the battery settings.
        batteryExempt = isBatteryExempt()
    }

    private fun isBatteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Asks Android to exempt the app from battery optimization (falls back to the settings list). */
    @SuppressLint("BatteryLife") // An open SSH session is exactly what this exemption is for.
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    companion object {
        private const val EXTRA_HOST_ID = "host_id"
        private const val EXTRA_DIRECTORY = "directory"
        private const val PREFS = "explorer"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val KEY_ASKED_BATTERY = "terminal_asked_battery"
        private const val DEFAULT_FONT_SIZE = 13f

        /** Opens (or brings back) the terminal window for a saved SSH server, optionally in [directory]. */
        fun start(context: Context, hostId: String, directory: String? = null) =
            context.startActivity(intent(context, hostId, directory))

        fun intent(context: Context, hostId: String, directory: String? = null): Intent =
            Intent(context, SSHTerminalActivity::class.java)
                .putExtra(EXTRA_HOST_ID, hostId)
                .apply { if (directory != null) putExtra(EXTRA_DIRECTORY, directory) }
                // One window per server: the same server returns to its running session.
                .setData(Uri.parse("explorer-terminal://$hostId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}

/** Holds the terminal session for the activity's whole life; it survives rotation and ends with the activity. */
/**
 * Holds the window's terminal sessions (tabs) for the activity's whole life; they survive rotation
 * and end with the activity, or when their tab is closed.
 */
class TerminalViewModel(app: Application, private val hostId: String, directory: String?) : AndroidViewModel(app) {
    private val ssh = SshConnections.get(app)
    private val name = ssh.host(hostId)?.name ?: "Server"
    private val watchers = HashMap<SshTerminal, Job>()

    val sessions = mutableStateListOf<SshTerminal>()
    var current by mutableIntStateOf(0)
        private set

    /** The session on screen. */
    val terminal: SshTerminal get() = sessions[current.coerceIn(0, sessions.lastIndex)]

    init {
        newSession(directory)
    }

    /** Opens another session to the same server, on its own connection, and shows it. */
    fun newSession(directory: String? = null) {
        val session = SshTerminal(ssh, hostId).apply {
            startDirectory = directory
            connect()
        }
        sessions += session
        current = sessions.lastIndex
        // The background service runs while any session is connecting or connected.
        watchers[session] = viewModelScope.launch {
            snapshotFlow { session.status }.collect { status ->
                val active = status == SshTerminal.Status.Connecting || status == SshTerminal.Status.Connected
                TerminalSessions.update(getApplication(), session, name, active)
            }
        }
    }

    fun select(index: Int) {
        if (index in sessions.indices) current = index
    }

    /** Ends a session. Returns true when it was the last one. */
    fun closeSession(index: Int): Boolean {
        val session = sessions.getOrNull(index) ?: return sessions.isEmpty()
        end(session)
        sessions.removeAt(index)
        if (current >= sessions.size) current = (sessions.size - 1).coerceAtLeast(0)
        return sessions.isEmpty()
    }

    /** Some session is connected (so leaving should keep the window in the background). */
    val anyConnected: Boolean get() = sessions.any { it.status == SshTerminal.Status.Connected }

    private fun end(session: SshTerminal) {
        watchers.remove(session)?.cancel()
        TerminalSessions.update(getApplication(), session, name, active = false)
        session.close()
    }

    override fun onCleared() {
        sessions.forEach(::end)
    }
}
