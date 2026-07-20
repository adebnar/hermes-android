package com.hermes.client.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.progress.reduce
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
    @Inject lateinit var profiles: com.hermes.client.data.repository.ProfileManager

    // Held separately (not just scope.coroutineContext[Job]) so onDestroy can register an
    // invokeOnCompletion callback on it directly — see onDestroy for why.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    // Latest notification prefs, kept current by a collector so the hot event loop never blocks on
    // DataStore. @Volatile for cross-thread visibility (scope has no single-thread dispatcher).
    @Volatile private var latestPrefs = NotificationPrefs()

    // Whether the app process is currently in the foreground, kept current by a
    // ProcessLifecycleOwner observer so the event loop can suppress notifications the user is
    // already looking at. @Volatile for cross-thread visibility (scope has no single-thread
    // dispatcher).
    @Volatile private var appInForeground = false

    // Live run state, folded from the same event stream. @Volatile for the same reason as the
    // fields above: the collector scope has no single-thread dispatcher.
    @Volatile private var runProgress = com.hermes.client.data.progress.RunProgress()

    // Last spec actually posted. message.delta fires many times per second and does not change
    // the spec, so re-posting on every event would burn cycles and visibly flicker the
    // notification. Only act when the derived spec actually changes.
    @Volatile private var lastRunSpec: RunProgressSpec? = null

    // ProcessLifecycleOwner is a process-lifetime singleton; hold the observer so onDestroy can
    // remove it — otherwise each stop/start of this service (e.g. toggling notifications) would
    // leak the retired Service instance (and its injected WS client) forever.
    private var lifecycleObserver: androidx.lifecycle.LifecycleEventObserver? = null

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification())
        client.connect()
        // Service.onCreate() runs on the main thread — where ProcessLifecycleOwner must be observed —
        // so register synchronously (addObserver replays the current state immediately, no race).
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> appInForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> {}
            }
        }
        lifecycleObserver = obs
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        // Track the latest prefs reactively so the event loop reads a cached value instead of
        // collecting DataStore per event. Started first so its replayed value is in place before
        // events arrive; also picks up mid-run toggles (e.g. approvals turned off).
        scope.launch { settings.prefs.collect { latestPrefs = it } }
        scope.launch {
            client.events.collect { event ->
                // One malformed/unexpected event must not crash the process — mirror the guard
                // ChatViewModel's reduce() uses around event handling.
                runCatching {
                    toNotificationSpec(event, latestPrefs, appInForeground)?.let { notifier.post(it) }
                    updateRunProgress(event)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    /** Folds the event into run state and posts/cancels the progress notification on change. */
    private fun updateRunProgress(event: com.hermes.client.data.network.ServerEvent) {
        runProgress = runProgress.reduce(event)
        // Single read: profiles.active.value is used for both the spec derivation and the post
        // call below, so a profile switch landing between two separate reads can't split a
        // notification's title/route from its accent colour across two different tenants.
        val activeProfile = profiles.active.value
        val spec = runProgress.toSpec(activeProfile, latestPrefs)
        if (spec == lastRunSpec) return
        lastRunSpec = spec
        if (spec != null) notifier.postRunProgress(spec, activeProfile)
        else notifier.cancelRunProgress()
    }

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
        // A stopped service must never strand an ongoing "running" notification. scope.cancel()
        // is cooperative: it does not preempt a collector iteration already executing
        // synchronously, so if the collector is mid-lambda when we get here it can still call
        // notifier.postRunProgress(...) after this function returns, with no ordering guarantee
        // against a cancelRunProgress() called from here directly. Hanging the final cancel off
        // the Job's actual completion — rather than off the cancel() call itself — guarantees it
        // runs strictly after every child coroutine (including any such in-flight iteration) has
        // finished, so no post can ever win the race.
        job.invokeOnCompletion { notifier.cancelRunProgress() }
        scope.cancel()
        // onDestroy() runs on the main thread — remove the observer synchronously so this Service
        // instance isn't retained (and no event can hit a defunct instance in a deferred window).
        lifecycleObserver?.let { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        lifecycleObserver = null
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
