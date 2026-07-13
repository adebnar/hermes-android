package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.ui.sessions.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.viewModeDataStore by preferencesDataStore(name = "sessions_view_mode")

/** Device-local persisted Chats view mode. Defaults to [ViewMode.SESSIONS]. */
class ViewModeStore(private val context: Context) {
    private val key = stringPreferencesKey("view_mode")

    val mode: Flow<ViewMode> = context.viewModeDataStore.data.map { prefs ->
        when (prefs[key]) {
            ViewMode.PROJECTS.name -> ViewMode.PROJECTS
            else -> ViewMode.SESSIONS
        }
    }

    suspend fun set(mode: ViewMode) {
        context.viewModeDataStore.edit { it[key] = mode.name }
    }
}
