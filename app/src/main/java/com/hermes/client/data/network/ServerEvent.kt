package com.hermes.client.data.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

// Reads a payload field as display text. The gateway sends some fields — notably tool
// results (e.g. a Gmail "read unread" tool returns a JSON object/array of messages) — as
// structured JSON, not string primitives. Reading those via jsonPrimitive THROWS, and the
// throw escapes the (uncaught) event collector and crashes the app mid-stream. So unwrap a
// primitive to its content and render any object/array as its raw JSON text; never throw.
internal fun ServerEvent.str(key: String): String? = when (val el = payload[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> el.content
    else -> el.toString()
}

internal fun JsonObject.objOrEmpty(key: String): JsonObject =
    (this[key] as? JsonObject) ?: JsonObject(emptyMap())
