package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.network.SessionStatsDto
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain

/**
 * Mirror the desktop session list: show interactive, used sessions only. Cron-created sessions
 * live in the Cron view, and empty (0-message) sessions are scratch — both are hidden from the
 * session list so the mobile counts match the desktop dashboard.
 */
private fun Session.isInteractive(): Boolean = source != "cron" && messageCount > 0

class SessionRepository(private val rest: HermesRestApi) {
    suspend fun list(profile: String? = null): List<Session> =
        rest.sessions(limit = 50, offset = 0, profile = profile).map { it.toDomain() }

    /**
     * All non-archived sessions across every profile, each tagged with its true profile.
     * This is the desktop-mirror list source — it replaces the single-profile [list] for the
     * sessions screen. The endpoint already excludes archived; the filter is defensive.
     * [isInteractive] hides cron + empty sessions so the counts match the desktop dashboard.
     */
    suspend fun listAllProfiles(): List<Session> =
        rest.profileSessions().sessions.map { it.toDomain() }
            .filter { !it.archived && it.isInteractive() }

    /** All archived sessions across every profile (the cross-profile archived view). */
    suspend fun archivedAllProfiles(): List<Session> =
        rest.profileSessions(archivedOnly = true).sessions.map { it.toDomain() }
            .filter { it.archived && it.isInteractive() }
    suspend fun stats(profile: String? = null): SessionStatsDto = rest.sessionStats(profile)
    suspend fun search(query: String, profile: String? = null): List<SearchResultDto> =
        rest.searchSessions(query, profile)
    suspend fun archived(profile: String? = null): List<Session> =
        rest.archivedSessions(profile).map { it.toDomain() }
    // The gateway reuses the model name as a message id across a session's turns, so
    // it can't be a unique list key on its own. Prefix the position to guarantee
    // unique, stable ids for the loaded history (the id is display-only).
    suspend fun history(sessionId: String, profile: String? = null): List<ChatMessage> =
        rest.messages(sessionId, profile).mapIndexed { i, dto ->
            val m = dto.toDomain()
            m.copy(id = "h-$i-${m.id}")
        }

    // All mutations carry the session's profile so the gateway hits the right per-profile DB
    // (otherwise the call 404s and the change silently no-ops).
    suspend fun rename(sessionId: String, title: String, profile: String?) =
        rest.patchSession(sessionId, title = title, profile = profile)
    suspend fun archive(sessionId: String, archived: Boolean, profile: String?) =
        rest.patchSession(sessionId, archived = archived, profile = profile)
    suspend fun delete(sessionId: String, profile: String?) = rest.deleteSession(sessionId, profile)
}
