package com.hermes.client.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the gateway WebSocket connected and posts notifications for
 * notifiable events, using the current [NotificationSettings]. Started/stopped by the toggle.
 */
@AndroidEntryPoint
class GatewayConnectionService : Service() {
    @Inject lateinit var client: HermesGatewayClient
    @Inject lateinit var settings: NotificationSettings
    @Inject lateinit var notifier: HermesNotifier

    private val scope = CoroutineScope(SupervisorJob())

    // Latest notification prefs, kept current by a collector so the hot event loop never blocks on
    // DataStore. @Volatile for cross-thread visibility (scope has no single-thread dispatcher).
    @Volatile private var latestPrefs = NotificationPrefs()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification())
        client.connect()
        // Track the latest prefs reactively so the event loop reads a cached value instead of
        // collecting DataStore per event. Started first so its replayed value is in place before
        // events arrive; also picks up mid-run toggles (e.g. approvals turned off).
        scope.launch { settings.prefs.collect { latestPrefs = it } }
        scope.launch {
            client.events.collect { event ->
                // One malformed/unexpected event must not crash the process — mirror the guard
                // ChatViewModel's reduce() uses around event handling.
                runCatching {
                    toNotificationSpec(event, latestPrefs)?.let { notifier.post(it) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ (API 35) caps a dataSync foreground service at ~6h and calls this instead of
    // just killing the process; Android 16 (API 36) added a fgsType-aware overload. Implement
    // both so whichever the OS invokes stops the service cleanly rather than crashing/ANR-ing.
    // Deliberately no auto-restart here (deferred) — just let the OS stop us.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, GatewayConnectionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayConnectionService::class.java))
        }
    }
}
