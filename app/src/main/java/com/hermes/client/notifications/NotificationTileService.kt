package com.hermes.client.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Quick Settings tile that shows and toggles the notification service — mirrors the
 * Settings > Notifications "Enable" switch. On/off state is [NotificationSettings.prefs].enabled.
 * The DataStore *write* runs on a service-owned scope so it never blocks the main thread; the
 * click's decision, the activity/foreground-service start, and the tile re-render stay synchronous
 * because a TileService's start-from-background token is only valid during onClick's synchronous
 * body. The synchronous read hits DataStore's in-memory cache (warmed in onStartListening).
 */
@AndroidEntryPoint
class NotificationTileService : TileService() {
    @Inject lateinit var settings: NotificationSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { renderTile(settings.prefs.first().enabled) }
    }

    override fun onClick() {
        super.onClick()
        // Decide and start synchronously: the start-from-background token is only valid during
        // onClick's synchronous body, so the activity/service start must not sit behind a suspend.
        // The read is a warm in-memory cache hit; only the DataStore write is deferred to the scope.
        val enabled = runBlocking { settings.prefs.first().enabled }
        val canStart = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        when (tileClickAction(enabled, canStart)) {
            TileAction.ENABLE -> {
                GatewayConnectionService.start(this)
                renderTile(true)
                scope.launch { settings.setEnabled(true) }
            }
            TileAction.DISABLE -> {
                GatewayConnectionService.stop(this)
                renderTile(false)
                scope.launch { settings.setEnabled(false) }
            }
            TileAction.OPEN_FOR_PERMISSION -> openNotificationSettings()
        }
    }

    private fun renderTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Hermes"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_hermes)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (enabled) "On" else "Off"
        tile.updateTile()
    }

    /** Open the app at Settings > Notifications so its Enable switch can run the permission flow. */
    private fun openNotificationSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_route", "settings_notifications")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
