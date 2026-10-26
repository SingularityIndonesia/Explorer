package com.singularity_universe.explorer.profiler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.singularity_universe.explorer.R

/** Starts and stops recording, and keeps a notification to do so without leaving the app. */
object Profiler {
    private lateinit var app: Context

    private val prefs get() = app.getSharedPreferences("profiler", Context.MODE_PRIVATE)

    /** Start recording as soon as the app process starts, to see what launching does. */
    var recordAtLaunch: Boolean
        get() = prefs.getBoolean(KEY_AT_LAUNCH, false)
        set(value) = prefs.edit().putBoolean(KEY_AT_LAUNCH, value).apply()

    /** Nothing runs until recording starts or the profiler is opened, unless [recordAtLaunch] is on. */
    fun init(context: Context) {
        app = context.applicationContext
        if (recordAtLaunch) start()
    }

    fun start() {
        MemorySampler.start()
        CallTrace.start()
        showNotification()
    }

    fun stop() {
        CallTrace.stop()
        showNotification()
    }

    fun clear() {
        CallTrace.clear()
        if (!CallTrace.recording) app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Profiler", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val recording = CallTrace.recording
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, ProfilerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getBroadcast(
            app, 1,
            Intent(app, ProfilerReceiver::class.java).setAction(if (recording) ACTION_STOP else ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
            .setContentTitle(if (recording) "Recording function calls" else "Recording paused")
            .setContentText("Tap to see calls, timings and memory")
            .setContentIntent(open)
            .setOngoing(recording)
            .addAction(Notification.Action.Builder(null, if (recording) "Stop" else "Resume", toggle).build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    const val ACTION_START = "com.singularity_universe.explorer.profiler.START"
    const val ACTION_STOP = "com.singularity_universe.explorer.profiler.STOP"
    private const val CHANNEL_ID = "profiler"
    private const val NOTIFICATION_ID = 0x9f01
    private const val KEY_AT_LAUNCH = "record_at_launch"
}

/** The notification's Stop / Resume button. */
class ProfilerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Profiler.ACTION_START -> Profiler.start()
            Profiler.ACTION_STOP -> Profiler.stop()
        }
    }
}

/** Runs before the app's own code when the process starts, so launch can be recorded too. */
class ProfilerStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        Profiler.init(context!!)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
