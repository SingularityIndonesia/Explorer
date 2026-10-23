package com.singularity_universe.explorer.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.singularity_universe.explorer.R
import com.singularity_universe.explorer.SSHTerminalActivity

/**
 * Keeps SSH terminal sessions connected while their windows are in the background or the screen
 * is off: a foreground service (so Android doesn't stop the app), with a partial wake lock and a
 * Wi-Fi lock (so SSH keepalives still go out while the phone sleeps). It runs only while at least
 * one terminal is connecting or connected, and shows them in its notification.
 *
 * Doze still ignores wake locks unless the app is exempt from battery optimization, which the
 * terminal asks for (see [SSHTerminalActivity]).
 */
class TerminalService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            TerminalSessions.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val sessions = TerminalSessions.active()
        if (sessions.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(sessions), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        acquireLocks()
        // If Android ever stops the service, the sessions are gone with the process: don't restart empty.
        return START_NOT_STICKY
    }

    private fun notification(sessions: List<TerminalSessions.Entry>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH terminal", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a terminal session is kept connected"
                setShowBadge(false)
            }
        )
        val latest = sessions.last()
        val open = PendingIntent.getActivity(
            this,
            0,
            SSHTerminalActivity.intent(this, latest.hostId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val names = sessions.map { it.name }.distinct()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
            .setContentTitle(if (sessions.size == 1) "SSH terminal: ${latest.name}" else "${sessions.size} SSH terminals")
            .setContentText(if (sessions.size == 1) "Connected in the background" else names.joinToString(", "))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_folder_foreground),
                    if (sessions.size == 1) "Disconnect" else "Disconnect all",
                    disconnect,
                ).build()
            )
            .build()
    }

    @SuppressLint("WakelockTimeout") // Held exactly as long as a terminal is connected; released in onDestroy.
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Explorer:ssh-terminal")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION") // Still keeps Wi-Fi from sleeping; the low-latency mode needs the screen on.
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Explorer:ssh-terminal")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ssh_terminal"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_DISCONNECT = "com.singularity_universe.explorer.action.DISCONNECT_TERMINALS"
    }
}

/**
 * The terminals that should be kept connected, and the service that does it: it starts with the
 * first one and stops after the last.
 */
object TerminalSessions {
    class Entry(val terminal: SshTerminal, val hostId: String, val name: String)

    private val entries = LinkedHashMap<SshTerminal, Entry>()

    fun active(): List<Entry> = synchronized(entries) { entries.values.toList() }

    /** [terminal] is connecting or connected ([active]), or not any more. */
    fun update(context: Context, terminal: SshTerminal, name: String, active: Boolean) {
        val changed = synchronized(entries) {
            if (active) entries.put(terminal, Entry(terminal, terminal.hostId, name)) == null
            else entries.remove(terminal) != null
        }
        if (!changed) return
        val app = context.applicationContext
        val service = Intent(app, TerminalService::class.java)
        if (active().isEmpty()) {
            app.stopService(service)
            return
        }
        try {
            // Also refreshes the notification when the list of terminals changes.
            app.startForegroundService(service)
        } catch (e: Exception) {
            // Started from the background without a battery-optimization exemption: Android refuses.
            // The session still works while the app stays alive.
            Log.w("ExplorerTerminal", "Couldn't start the terminal service", e)
        }
    }

    /** "Disconnect" in the notification. */
    fun disconnectAll() {
        active().forEach { it.terminal.hangUp() }
    }
}
