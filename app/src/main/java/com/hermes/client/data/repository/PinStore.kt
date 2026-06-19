package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pinDataStore by preferencesDataStore(name = "pinned_sessions")

/**
 * Device-local pinned-session store. The gateway has no pin API, so pins live on the phone
 * only (they do not sync to the desktop app). Keyed by "<profile>/<sessionId>" so a pin is
 * scoped to the profile it was made in.
 */
class PinStore(private val context: Context) {
    private val key = stringSetPreferencesKey("pinned")

    val pinned: Flow<Set<String>> = context.pinDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(token: String) {
        context.pinDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (token in cur) cur - token else cur + token
        }
    }

    companion object {
        fun token(profile: String?, sessionId: String) = "${profile ?: "default"}/$sessionId"
    }
}
