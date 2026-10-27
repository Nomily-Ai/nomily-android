package com.nomily.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground Service during real‑time transcription and phone‑microphone recording: a foreground Service + persistent
 * notification, exempted from the "platform differences" rule.
 *
 * It does nothing itself; it merely keeps the process alive when the user leaves the app or locks the screen,
 * while BLE frame acquisition / microphone capture and the WebSocket continue running in the ViewModel.
 * The foreground type is chosen per start: `connectedDevice` for the BLE session, `microphone` for the phone mic
 * (which needs `RECORD_AUDIO` granted before the start). The type must be explicit — a start without one claims every
 * type declared in the manifest, and `microphone` would then fail on API 34 whenever the permission is missing.
 */
class LiveSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The microphone type exists from API 30; on 29 the manifest's connectedDevice type is the only one declared.
        val type = if (intent?.getBooleanExtra(EXTRA_MICROPHONE, false) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.tab_live), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.live_listening))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live-session"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_MICROPHONE = "microphone"

        fun start(context: Context, microphone: Boolean = false) {
            val intent = Intent(context, LiveSessionService::class.java).putExtra(EXTRA_MICROPHONE, microphone)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSessionService::class.java))
        }
    }
}
