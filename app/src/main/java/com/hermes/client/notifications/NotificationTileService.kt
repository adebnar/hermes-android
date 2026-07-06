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
import javax.inject.Inject

/**
 * Quick Settings tile that shows and toggles the notification service — mirrors the
 * Settings > Notifications "Enable" switch. On/off state is [NotificationSettings.prefs].enabled.
 * Tile callbacks run reads/writes on a service-owned `Main.immediate` scope so DataStore I/O never blocks the main thread.
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
        scope.launch {
            val enabled = settings.prefs.first().enabled
            val canStart = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this@NotificationTileService, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            when (tileClickAction(enabled, canStart)) {
                TileAction.ENABLE -> {
                    settings.setEnabled(true)
                    GatewayConnectionService.start(this@NotificationTileService)
                    renderTile(true)
                }
                TileAction.DISABLE -> {
                    settings.setEnabled(false)
                    GatewayConnectionService.stop(this@NotificationTileService)
                    renderTile(false)
                }
                TileAction.OPEN_FOR_PERMISSION -> openNotificationSettings()
            }
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
