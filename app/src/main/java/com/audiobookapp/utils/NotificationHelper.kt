package com.audiobookapp.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CHANNEL_ID         = "readio_conversion"
    const val NOTIFICATION_ID    = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Readio Conversion",
            NotificationManager.IMPORTANCE_LOW   // silent — no sound/vibration
        ).apply {
            description = "Shows progress while converting documents to audio"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        message: String,
        progress: Int         // 0-100, or -1 for indeterminate
    ) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(message)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)          // not dismissible while converting
        .setOnlyAlertOnce(true)    // don't re-alert on each update
        .apply {
            if (progress < 0) {
                setProgress(0, 0, true)   // indeterminate
            } else {
                setProgress(100, progress, false)
            }
        }
        .build()
}
