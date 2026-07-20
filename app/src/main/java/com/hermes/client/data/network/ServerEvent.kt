package com.hermes.client.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

internal fun ServerEvent.bool(key: String): Boolean? =
    (payload[key] as? JsonPrimitive)?.booleanOrNull

internal fun ServerEvent.strList(key: String): List<String> =
    (payload[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/**
 * Counts todo items from a `tool.complete` payload's `todos` array (gateway sends the full list
 * as `{id, content, status}` objects). `done` counts `completed`; `total` counts every item that
 * is NOT `cancelled` — a cancelled task never completes, so including it would stall the progress
 * bar below 100% forever. Defensive like [str]: a malformed or absent payload yields 0 to 0
 * rather than throwing, because a throw here would escape the event collector.
 */
internal fun ServerEvent.todoCounts(): Pair<Int, Int> {
    val arr = payload["todos"] as? JsonArray ?: return 0 to 0
    var done = 0
    var total = 0
    for (el in arr) {
        val status = ((el as? JsonObject)?.get("status") as? JsonPrimitive)?.content?.lowercase()
        if (status == "cancelled") continue
        total++
        if (status == "completed") done++
    }
    return done to total
}
