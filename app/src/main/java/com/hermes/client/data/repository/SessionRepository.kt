package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.network.SessionStatsDto
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain

class SessionRepository(private val rest: HermesRestApi) {
    suspend fun list(profile: String? = null): List<Session> =
        rest.sessions(limit = 50, offset = 0, profile = profile).map { it.toDomain() }
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

    suspend fun rename(sessionId: String, title: String) = rest.patchSession(sessionId, title = title)
    suspend fun archive(sessionId: String, archived: Boolean) =
        rest.patchSession(sessionId, archived = archived)
    suspend fun delete(sessionId: String) = rest.deleteSession(sessionId)
}
