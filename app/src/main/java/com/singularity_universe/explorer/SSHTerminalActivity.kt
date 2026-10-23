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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshTerminal
import com.singularity_universe.explorer.remote.TerminalSessions
import com.singularity_universe.explorer.ui.SshTerminalScreen
import com.singularity_universe.explorer.ui.theme.ExplorerTheme
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
                    fontSize = prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE),
                    onFontSizeChanged = { prefs.edit().putFloat(KEY_FONT_SIZE, it).apply() },
                    onBack = {
                        // A live session stays open in the background; an ended one just closes.
                        if (vm.terminal.status == SshTerminal.Status.Connected) moveTaskToBack(true) else finish()
                    },
                    onClose = ::finishAndRemoveTask,
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
        val directory = intent.getStringExtra(EXTRA_DIRECTORY) ?: return
        if (!vm.terminal.openDirectory(directory)) {
            Toast.makeText(this, "Close the running program first, then try again", Toast.LENGTH_LONG).show()
        }
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
class TerminalViewModel(app: Application, hostId: String, directory: String?) : AndroidViewModel(app) {
    private val ssh = SshConnections.get(app)
    private val name = ssh.host(hostId)?.name ?: "Server"
    val terminal = SshTerminal(ssh, hostId).apply {
        startDirectory = directory
        connect()
    }

    init {
        // The background service runs while this session is connecting or connected.
        viewModelScope.launch {
            snapshotFlow { terminal.status }.collect { status ->
                val active = status == SshTerminal.Status.Connecting || status == SshTerminal.Status.Connected
                TerminalSessions.update(getApplication(), terminal, name, active)
            }
        }
    }

    override fun onCleared() {
        TerminalSessions.update(getApplication(), terminal, name, active = false)
        terminal.close()
    }
}
