package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.notifications.NotificationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notificationDataStore by preferencesDataStore(name = "notifications")

/** Device-local notification preferences (master toggle + per-type flags). Off by default. */
class NotificationSettings(private val context: Context) {
    private val kEnabled = booleanPreferencesKey("enabled")
    private val kApprovals = booleanPreferencesKey("approvals")
    private val kCron = booleanPreferencesKey("cron")
    private val kMessaging = booleanPreferencesKey("messaging")

    val prefs: Flow<NotificationPrefs> = context.notificationDataStore.data.map { p ->
        NotificationPrefs(
            enabled = p[kEnabled] ?: false,
            approvals = p[kApprovals] ?: true,
            cron = p[kCron] ?: true,
            messaging = p[kMessaging] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = context.notificationDataStore.edit { it[kEnabled] = v }
    suspend fun setApprovals(v: Boolean) = context.notificationDataStore.edit { it[kApprovals] = v }
    suspend fun setCron(v: Boolean) = context.notificationDataStore.edit { it[kCron] = v }
    suspend fun setMessaging(v: Boolean) = context.notificationDataStore.edit { it[kMessaging] = v }
}
