package com.hermes.client.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ServerEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonObject,
) {
    companion object {
        fun from(params: JsonObject): ServerEvent {
            val type = params["type"]?.jsonPrimitive?.content ?: "unknown"
            val payload = (params["payload"] as? JsonObject) ?: JsonObject(emptyMap())
            val sessionId = payload["session_id"]?.jsonPrimitive?.content
                ?: params["session_id"]?.jsonPrimitive?.content
            return ServerEvent(type, sessionId, payload)
        }
    }
}

internal fun JsonObject.objOrEmpty(key: String): JsonObject =
    (this[key] as? JsonObject) ?: JsonObject(emptyMap())
