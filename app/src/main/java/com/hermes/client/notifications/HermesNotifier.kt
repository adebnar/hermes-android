package com.hermes.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.ui.theme.accentArgb

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
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_RUN_PROGRESS, "Run progress", NotificationManager.IMPORTANCE_LOW),
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

    /**
     * Posts (or updates) the single ongoing run-progress notification. On API 36+ this uses the
     * platform ProgressStyle so the system can promote it to a status-bar Live Update; below that
     * it falls back to an ordinary ongoing progress notification.
     *
     * androidx.core 1.16.0 has no NotificationCompat.ProgressStyle, so the API 36+ branch builds
     * with the platform Notification.Builder rather than upgrading the dependency.
     */
    fun postRunProgress(spec: RunProgressSpec, profile: String?) {
        if (!mgr.areNotificationsEnabled()) return
        val accent = accentFor(profile)
        val n = if (Build.VERSION.SDK_INT >= 36) buildPromoted(spec, accent) else buildCompat(spec, accent)
        mgr.notify(RUN_PROGRESS_NOTIFICATION_ID, n)
    }

    fun cancelRunProgress() = mgr.cancel(RUN_PROGRESS_NOTIFICATION_ID)

    /** Tenant accent, resolved against the system's current night mode. Chrome only. */
    private fun accentFor(profile: String?): Int {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return accentArgb(profile, dark)
    }

    @androidx.annotation.RequiresApi(36)
    private fun buildPromoted(spec: RunProgressSpec, accent: Int): Notification =
        Api36RunProgressBuilder.build(
            context = context,
            spec = spec,
            accent = accent,
            contentIntent = openIntent(spec.route, RUN_PROGRESS_NOTIFICATION_ID),
        )

    private fun buildCompat(spec: RunProgressSpec, accent: Int): Notification =
        NotificationCompat.Builder(context, Notif.CHANNEL_RUN_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setProgress(spec.total, spec.done, spec.indeterminate)
            .setOngoing(true)
            .setColor(accent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(spec.route, RUN_PROGRESS_NOTIFICATION_ID))
            .build()

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
            putExtra("request_id", a.requestId.orEmpty())
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

        // Distinct from SERVICE_NOTIFICATION_ID (1001) and from toNotificationSpec's 1002
        // collision fallback, so the ongoing progress notification can never clobber either.
        const val RUN_PROGRESS_NOTIFICATION_ID = 1003
    }
}

/**
 * Isolates the API-36-only `Notification.ProgressStyle` construction in its own class so that
 * [HermesNotifier]'s own method bodies never name an API-36 type. ART verifies dex classes
 * lazily and per-class, so this is defensive hardening against class-verification issues rather
 * than a behaviour change — [HermesNotifier.buildPromoted] only reaches this class from behind
 * the existing `Build.VERSION.SDK_INT >= 36` guard.
 */
@androidx.annotation.RequiresApi(36)
private object Api36RunProgressBuilder {
    fun build(context: Context, spec: RunProgressSpec, accent: Int, contentIntent: PendingIntent): Notification {
        val style = Notification.ProgressStyle().setProgressIndeterminate(spec.indeterminate)
        if (!spec.indeterminate) {
            // ProgressStyle has no setProgressMax(): the bar's maximum is the SUM of its segment
            // lengths, so one segment of `total` gives a bar of exactly that length.
            style.addProgressSegment(Notification.ProgressStyle.Segment(spec.total).setColor(accent))
            style.setProgress(spec.done)
        }
        val b = Notification.Builder(context, Notif.CHANNEL_RUN_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(style)
            .setOngoing(true)
            .setColor(accent)
            .setContentIntent(contentIntent)
        // Status-bar chip text on a promoted notification. The system decides promotion itself
        // (Notification.FLAG_PROMOTED_ONGOING); there is no request API to call.
        spec.shortText?.let { b.setShortCriticalText(it) }
        return b.build()
    }
}
