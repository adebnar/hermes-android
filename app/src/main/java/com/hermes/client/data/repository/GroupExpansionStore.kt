package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.groupExpansionDataStore by preferencesDataStore(name = "session_group_expansion")

/**
 * Device-local store of which session-list groups are collapsed. Groups default to expanded, so
 * only the *collapsed* keys are persisted — an empty set means everything is open. Keys are built
 * by [profileKey]/[workspaceKey] so a profile group and a workspace group never collide.
 */
class GroupExpansionStore(private val context: Context) {
    private val key = stringSetPreferencesKey("collapsed")

    /** The set of currently-collapsed group keys. */
    val collapsed: Flow<Set<String>> = context.groupExpansionDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(groupKey: String) {
        context.groupExpansionDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (groupKey in cur) cur - groupKey else cur + groupKey
        }
    }

    companion object {
        /** Collapse key for a profile (top tier). */
        fun profileKey(profile: String?) = "p:${profile ?: "default"}"

        /** Collapse key for a workspace within a profile (sub tier). */
        fun workspaceKey(profile: String?, workspace: String) = "w:${profile ?: "default"}/$workspace"
    }
}
