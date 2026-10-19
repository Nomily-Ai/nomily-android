package com.nomily.app.jobs

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
import android.util.Log
import com.nomily.app.MainActivity
import com.nomily.app.R

/**
 * Foreground service held while background tasks run (transcription, summaries, translations, exports), so the
 * process survives the user switching away or locking the phone. The work itself runs in the ViewModel's queue.
 */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Not sticky: after the process dies the queue resumes from its ledger at the next launch.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.jobs_page_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.jobs_open_queue_footer))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WorkService"
        private const val CHANNEL_ID = "background-tasks"
        private const val NOTIFICATION_ID = 1002

        /** Safe to call repeatedly. Starting from the background is refused on Android 12+; the queue still runs while the app lives. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WorkService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Couldn't start the foreground service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkService::class.java))
        }
    }
}
