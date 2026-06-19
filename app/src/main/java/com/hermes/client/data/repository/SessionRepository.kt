package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain

class SessionRepository(private val rest: HermesRestApi) {
    suspend fun list(): List<Session> = rest.sessions(limit = 50, offset = 0).map { it.toDomain() }
    suspend fun history(sessionId: String): List<ChatMessage> =
        rest.messages(sessionId).map { it.toDomain() }

    suspend fun rename(sessionId: String, title: String) = rest.patchSession(sessionId, title = title)
    suspend fun archive(sessionId: String, archived: Boolean) =
        rest.patchSession(sessionId, archived = archived)
    suspend fun delete(sessionId: String) = rest.deleteSession(sessionId)
}
