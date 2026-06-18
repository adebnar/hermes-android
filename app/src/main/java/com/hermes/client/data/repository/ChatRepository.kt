package com.hermes.client.data.repository

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ServerEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ChatRepository(private val client: HermesGatewayClient) {
    val events: SharedFlow<ServerEvent> get() = client.events
    val connectionState: StateFlow<ConnectionState> get() = client.connectionState

    fun connect() = client.connect()
    fun disconnect() = client.close()

    suspend fun createSession(): String {
        val result = client.call("session.create", buildJsonObject {})
        return result.jsonObject["session_id"]?.jsonPrimitive?.content
            ?: error("session.create returned no id")
    }

    suspend fun resume(sessionId: String) {
        client.call("session.resume", buildJsonObject { put("session_id", sessionId) })
    }

    suspend fun submit(sessionId: String, text: String) {
        client.call("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
        })
    }

    suspend fun interrupt(sessionId: String) {
        client.call("session.interrupt", buildJsonObject { put("session_id", sessionId) })
    }

    suspend fun respondApproval(sessionId: String, approve: Boolean) {
        client.call("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            put("approved", approve)
        })
    }

    suspend fun respondClarify(sessionId: String, answer: String) {
        client.call("clarify.respond", buildJsonObject {
            put("session_id", sessionId)
            put("answer", answer)
        })
    }
}
