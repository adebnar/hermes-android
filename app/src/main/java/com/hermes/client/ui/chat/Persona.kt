package com.hermes.client.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A gateway-configured personality the user can apply to a session. */
data class Persona(val name: String, val description: String)

private fun JsonObject.objOrNull(key: String): JsonObject? = (this[key] as? JsonObject)
private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Personalities from `agent.personalities` (name → String | object). Sorted by name; never throws. */
fun parsePersonas(config: JsonObject): List<Persona> {
    val personalities = config.objOrNull("agent")?.objOrNull("personalities") ?: return emptyList()
    return personalities.entries.map { (name, value) ->
        val desc = (value as? JsonObject)?.let {
            it.strOrNull("description") ?: it.strOrNull("tone") ?: it.strOrNull("style") ?: ""
        }.orEmpty().trim()
        Persona(name, desc)
    }.sortedBy { it.name.lowercase() }
}

/** The active personality (`display.personality`); blank/"none" → null. */
fun activePersonaOf(config: JsonObject): String? =
    config.objOrNull("display")?.strOrNull("personality")
        ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
