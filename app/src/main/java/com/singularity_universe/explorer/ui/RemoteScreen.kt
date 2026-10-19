package com.singularity_universe.explorer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.remote.PairingNotification
import com.singularity_universe.explorer.remote.RemoteConnection
import com.singularity_universe.explorer.remote.RemoteConnection.Status
import com.singularity_universe.explorer.remote.SshConnections
import kotlinx.coroutines.launch

/** The activity-owned [RemoteConnection], provided by MainActivity to every screen. */
val LocalRemoteConnection = staticCompositionLocalOf<RemoteConnection> {
    error("RemoteConnection is provided by MainActivity")
}

/** The activity-owned [SshConnections], provided by MainActivity to every screen. */
val LocalSshConnections = staticCompositionLocalOf<SshConnections> {
    error("SshConnections is provided by MainActivity")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: ExplorerViewModel) {
    val remote = LocalRemoteConnection.current
    val status = remote.status

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Remote access") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status)
            when (status) {
                Status.Connected -> ConnectedActions(remote)
                Status.Connecting -> Unit
                Status.Disconnected -> Button(onClick = remote::connectAsync) { Text("Connect") }
                Status.DebuggingOff -> DebuggingOffActions(remote)
                Status.NotPaired, is Status.Failed -> PairingSteps(remote)
            }
            PrivacyNote()
        }
    }
}

@Composable
private fun StatusCard(status: Status) {
    val (icon, title, text) = when (status) {
        Status.Connected -> Triple(
            Icons.Outlined.CheckCircle, "Connected",
            "Android/data and Android/obb are available. The connection is kept while Explorer is open.",
        )
        Status.Connecting -> Triple(Icons.Outlined.PhonelinkLock, "Connecting…", "Reaching Wireless debugging on this phone.")
        Status.Disconnected -> Triple(Icons.Outlined.PhonelinkLock, "Disconnected", "Paired with this phone. Connect to open protected folders.")
        Status.DebuggingOff -> Triple(
            Icons.Outlined.WifiOff, "Wireless debugging is off",
            "Android turns it off after a restart, and it needs Wi-Fi to be on (no internet needed).",
        )
        Status.NotPaired -> Triple(
            Icons.Outlined.Lock, "Not set up",
            "Android blocks Android/data and Android/obb for every app. Explorer can open them through " +
                "this phone's own Wireless debugging, without any other app.",
        )
        is Status.Failed -> Triple(Icons.Outlined.WifiOff, "Couldn't connect", status.message)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            if (status == Status.Connecting) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectedActions(remote: RemoteConnection) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = remote::disconnect) { Text("Disconnect") }
        OutlinedButton(onClick = remote::forget) { Text("Forget pairing") }
    }
}

@Composable
private fun DebuggingOffActions(remote: RemoteConnection) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { context.openWirelessDebugging() }) { Text("Open Wireless debugging") }
        OutlinedButton(onClick = remote::connectAsync) { Text("Try again") }
    }
    TextButton(onClick = remote::forget) { Text("Forget pairing") }
}

@Composable
private fun PairingSteps(remote: RemoteConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun startPairing() {
        remote.startPairingDiscovery()
        PairingNotification.show(context)
        context.openWirelessDebugging()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Without notifications the in-app code field below (in split screen) still works.
        startPairing()
    }

    Step(1, "Turn on Developer options", "Settings › About phone › tap \"Build number\" 7 times.") {
        TextButton(onClick = { context.startSettings(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }) {
            Text("Open About phone")
        }
    }
    Step(2, "Start pairing", "Turn on Wireless debugging (Wi-Fi must be on), then tap \"Pair device with pairing code\". Type the 6-digit code into Explorer's notification.") {
        Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Start pairing")
        }
    }
    Step(3, "Or enter the code here", "Useful in split screen, with the pairing dialog open next to Explorer.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            if (pairing) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Button(
                    enabled = code.length == 6,
                    onClick = {
                        pairing = true
                        result = null
                        scope.launch {
                            remote.pair(code).onFailure { result = it.message }
                            pairing = false
                        }
                    },
                ) { Text("Pair") }
            }
        }
        result?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Step(number: Int, title: String, text: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number. $title", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            "Explorer only connects to this phone. Its debugging key never leaves the device, and it " +
                "is only used to read and change files. Disconnect or forget the pairing at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opens Developer options with Wireless debugging highlighted. */
private fun Context.openWirelessDebugging() = startSettings(
    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
)

private fun Context.startSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
