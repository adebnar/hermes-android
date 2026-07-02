package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.profileAccentDataStore by preferencesDataStore(name = "profile_accent")

/**
 * Device-local per-profile accent-colour overrides. When a profile has an entry, its accent uses
 * that chosen colour instead of the auto-hashed hue; with no entry it falls back to the hash.
 * Stored as a set of "<profile>=<argb>" entries so the whole map reads back in one flow.
 */
class ProfileAccentStore(private val context: Context) {
    private val key = stringSetPreferencesKey("colors")

    /** profile name → chosen ARGB colour. */
    val overrides: Flow<Map<String, Int>> = context.profileAccentDataStore.data.map { prefs ->
        (prefs[key] ?: emptySet()).mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val argb = entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, i) to argb
        }.toMap()
    }

    suspend fun setColor(profile: String, argb: Int) {
        context.profileAccentDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = cur.filterNot { it.substringBeforeLast('=') == profile }.toSet() + "$profile=$argb"
        }
    }

    suspend fun clear(profile: String) {
        context.profileAccentDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = cur.filterNot { it.substringBeforeLast('=') == profile }.toSet()
        }
    }
}
