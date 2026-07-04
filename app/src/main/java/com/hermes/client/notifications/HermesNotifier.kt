package com.hermes.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hermes.client.MainActivity
import com.hermes.client.R

/** Owns notification channels and turns a [NotificationSpec] into a posted Android notification. */
class HermesNotifier(private val context: Context) {
    private val mgr = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, Notif.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes")
            .setContentText("Connected — watching for approvals & activity")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun post(spec: NotificationSpec) {
        val b = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setAutoCancel(true)
            .setGroup(spec.groupKey)
            .setContentIntent(openIntent(spec.route, spec.id))
        spec.actions.forEach { a ->
            b.addAction(0, a.label, actionIntent(a, spec.id))
        }
        if (mgr.areNotificationsEnabled()) {
            mgr.notify(spec.id, b.build())
        }
    }

    fun cancel(id: Int) = mgr.cancel(id)

    private fun openIntent(route: String?, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra("extra_route", it) }
        }
        return PendingIntent.getActivity(context, id, intent, pendingFlags())
    }

    private fun actionIntent(a: NotifAction, notifId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = a.action
            putExtra("session_id", a.sessionId)
            putExtra("notif_id", notifId)
        }
        return PendingIntent.getBroadcast(context, (a.action + a.sessionId).hashCode(), intent, pendingFlags())
    }

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
    }
}
