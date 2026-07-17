package com.hermes.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
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
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT),
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
            if (a.reply) {
                val remoteInput = RemoteInput.Builder(Notif.KEY_REPLY_TEXT).setLabel("Reply…").build()
                val action = NotificationCompat.Action.Builder(0, a.label, replyIntent(a, spec.id))
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
                b.addAction(action)
            } else {
                b.addAction(0, a.label, actionIntent(a, spec.id))
            }
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

    private fun replyIntent(a: NotifAction, notifId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = a.action
            putExtra("session_id", a.sessionId)
            putExtra("notif_id", notifId)
        }
        // Direct-reply requires FLAG_MUTABLE so the system can attach the RemoteInput results.
        // The intent is explicit (our own receiver), so it can't be redirected — mutability is safe.
        return PendingIntent.getBroadcast(
            context,
            // Distinct namespace from actionIntent()'s button request code so a reply (MUTABLE) and a
            // button (IMMUTABLE) can never share PendingIntent identity (would crash on Android 12+).
            ("reply:" + a.action + a.sessionId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
    }
}
