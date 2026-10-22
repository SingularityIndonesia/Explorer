package com.singularity_universe.explorer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.singularity_universe.explorer.remote.AdbDevices
import com.singularity_universe.explorer.remote.ScrcpySession
import com.singularity_universe.explorer.ui.LocalAdbDevices
import com.singularity_universe.explorer.ui.RemoteControlScreen
import com.singularity_universe.explorer.ui.theme.ExplorerTheme
import kotlinx.coroutines.launch

/**
 * Remote control of another Android device, in its own window (its own task), so Explorer stays
 * usable next to it: in split screen, or with this window shrunk to picture-in-picture.
 *
 * This activity owns the [ScrcpySession] (created in onCreate, closed in onDestroy) and holds the
 * device's ADB connection open while it runs, even if Explorer's main window closes meanwhile.
 */
class RemoteControlActivity : ComponentActivity() {
    private lateinit var devices: AdbDevices
    private lateinit var session: ScrcpySession
    private lateinit var deviceId: String

    private var allowed by mutableStateOf(false)
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        enableEdgeToEdge()
        devices = AdbDevices.get(this)
        devices.beginSession(deviceId)
        session = ScrcpySession(applicationContext, devices, deviceId)
        allowed = isAllowed(this, deviceId)

        // Keep the picture-in-picture window the same shape as the device's screen.
        updatePictureInPicture()
        lifecycleScope.launch {
            snapshotFlow { session.videoSize to allowed }.collect { updatePictureInPicture() }
        }

        title = "${devices.device(deviceId)?.name ?: "Device"} · Remote control"
        setContent {
            CompositionLocalProvider(LocalAdbDevices provides devices) {
                ExplorerTheme {
                    val device = devices.device(deviceId)
                    RemoteControlScreen(
                        name = device?.name ?: "Device",
                        session = session,
                        allowed = allowed,
                        inPictureInPicture = inPictureInPicture,
                        addresses = device?.let { d -> d.hosts.indices.map(d::addressOf) }.orEmpty(),
                        activeAddress = device?.activeHost ?: 0,
                        onSelectAddress = { index ->
                            // Reconnect through the chosen route and restart the stream on it.
                            devices.useAddress(deviceId, index)
                            session.restart()
                        },
                        onAllow = {
                            allow(this, deviceId)
                            allowed = true
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            session.close()
            devices.endSession(deviceId)
        }
        super.onDestroy()
    }

    /** Switching to another app (e.g. back to Explorer) shrinks this window instead of hiding it. */
    private fun updatePictureInPicture() {
        val (width, height) = session.videoSize ?: (9 to 16)
        // Android only allows picture-in-picture between 1:2.39 and 2.39:1.
        val ratio = (width.toFloat() / height).coerceIn(1 / 2.39f + 0.01f, 2.39f - 0.01f)
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                .setAutoEnterEnabled(allowed)
                .setSeamlessResizeEnabled(true)
                .build()
        )
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val PREFS = "explorer"
        private const val KEY_ALLOWED = "remote_control_allowed"

        /** Opens (or brings back) the remote control window for a device. */
        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, RemoteControlActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
                    // One window per device: the same device reuses its window, others get their own.
                    .setData(Uri.parse("explorer-remote://$deviceId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            )
        }

        /** Remote control is opt-in per device: the user confirms once before the first session. */
        private fun isAllowed(context: Context, deviceId: String): Boolean =
            deviceId in context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_ALLOWED, emptySet())!!

        private fun allow(context: Context, deviceId: String) {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_ALLOWED, prefs.getStringSet(KEY_ALLOWED, emptySet())!! + deviceId).apply()
        }
    }
}
