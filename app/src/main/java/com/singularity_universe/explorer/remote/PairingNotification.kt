package com.singularity_universe.explorer.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.singularity_universe.explorer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android's "Pair device with pairing code" dialog closes as soon as the user switches apps, so
 * the code is typed into this notification's reply field while Settings stays on screen.
 */
object PairingNotification {
    private const val CHANNEL_ID = "remote_pairing"
    private const val NOTIFICATION_ID = 4201
    internal const val KEY_CODE = "pairing_code"

    fun show(
        context: Context,
        text: String = "Tap \"Pair device with pairing code\", then enter the code here",
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote access pairing", NotificationManager.IMPORTANCE_HIGH)
        )
        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_launcher_folder_foreground),
            "Enter pairing code",
            reply,
        ).addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()).build()

        manager.notify(
            NOTIFICATION_ID,
            builder(context)
                .setContentTitle("Pair Explorer")
                .setContentText(text)
                .addAction(action)
                .build(),
        )
    }

    fun update(context: Context, text: String, ongoing: Boolean = false) {
        context.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            builder(context).setContentTitle("Explorer remote access").setContentText(text)
                .setOngoing(ongoing).setAutoCancel(!ongoing).build(),
        )
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun builder(context: Context) = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_folder_foreground)
        .setCategory(Notification.CATEGORY_STATUS)
}

/** Receives the code typed into [PairingNotification] and pairs with Wireless debugging. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.KEY_CODE)?.toString()?.trim()
        if (code.isNullOrEmpty()) return
        val app = context.applicationContext
        // Replaces the reply field's spinner right away.
        PairingNotification.update(app, "Pairing…", ongoing = true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                RemoteConnection.get(app).pair(code)
                    .onSuccess { PairingNotification.update(app, "Paired. You can go back to Explorer.") }
                    .onFailure { PairingNotification.show(app, "${it.message ?: "Pairing failed."} Enter the code again.") }
            } finally {
                pending.finish()
            }
        }
    }
}
