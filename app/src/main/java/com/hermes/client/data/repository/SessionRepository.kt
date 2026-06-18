package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Session
import com.hermes.client.domain.toDomain

class SessionRepository(private val rest: HermesRestApi) {
    suspend fun list(): List<Session> = rest.sessions(limit = 50, offset = 0).map { it.toDomain() }
    suspend fun history(sessionId: String): List<ChatMessage> =
        rest.messages(sessionId).map { it.toDomain() }
}
