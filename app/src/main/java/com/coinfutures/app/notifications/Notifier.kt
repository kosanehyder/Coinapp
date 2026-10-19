package com.coinfutures.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.coinfutures.app.MainActivity
import com.coinfutures.app.R

/** Local notification delivery. "Sent to system" is recorded; actual delivery is not observable. */
class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SETUPS, context.getString(R.string.channel_setups), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_setups_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PAPER, context.getString(R.string.channel_paper), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_paper_desc) }
        )
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Returns false when the OS refused (permission missing or channel blocked). */
    fun post(eventId: Long, instrumentId: String, planKey: String?, title: String, body: String, paper: Boolean): Boolean {
        if (!hasPermission() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INSTRUMENT, instrumentId)
            putExtra(EXTRA_PLAN_KEY, planKey)
            putExtra(EXTRA_EVENT_ID, eventId)
        }
        val pi = PendingIntent.getActivity(
            context, eventId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (paper) CHANNEL_PAPER else CHANNEL_SETUPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(eventId.toInt(), n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_SETUPS = "setups"
        const val CHANNEL_PAPER = "paper"
        const val EXTRA_INSTRUMENT = "instrumentId"
        const val EXTRA_PLAN_KEY = "planKey"
        const val EXTRA_EVENT_ID = "eventId"
    }
}
