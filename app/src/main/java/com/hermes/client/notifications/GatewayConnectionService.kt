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
import kotlinx.coroutines.flow.first
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

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification())
        client.connect()
        scope.launch {
            client.events.collect { event ->
                // One malformed/unexpected event must not crash the process — mirror the guard
                // ChatViewModel's reduce() uses around event handling.
                runCatching {
                    val prefs = settings.prefs.first()
                    toNotificationSpec(event, prefs)?.let { notifier.post(it) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

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
