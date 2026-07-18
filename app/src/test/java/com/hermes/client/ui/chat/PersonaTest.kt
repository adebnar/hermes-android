package com.hermes.client.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonaTest {
    private fun obj(s: String): JsonObject = Json.decodeFromString(JsonObject.serializer(), s)

    @Test fun parses_string_and_object_personas_sorted() {
        val c = obj("""{"agent":{"personalities":{"witty":"Be witty","coach":{"description":"A coach","tone":"warm"}}}}""")
        val ps = parsePersonas(c)
        assertEquals(listOf("coach", "witty"), ps.map { it.name })          // sorted
        assertEquals("A coach", ps.first { it.name == "coach" }.description) // object → description
        assertEquals("", ps.first { it.name == "witty" }.description)        // string → no description
    }

    @Test fun missing_agent_or_personalities_is_empty() {
        assertEquals(emptyList<Persona>(), parsePersonas(obj("""{}""")))
        assertEquals(emptyList<Persona>(), parsePersonas(obj("""{"agent":{}}""")))
    }

    @Test fun active_persona_reads_display_and_maps_none_to_null() {
        assertEquals("coach", activePersonaOf(obj("""{"display":{"personality":"coach"}}""")))
        assertNull(activePersonaOf(obj("""{"display":{"personality":"none"}}""")))
        assertNull(activePersonaOf(obj("""{"display":{"personality":""}}""")))
        assertNull(activePersonaOf(obj("""{}""")))
    }

    @Test fun active_persona_maps_default_to_null() {
        assertNull(activePersonaOf(obj("""{"display":{"personality":"default"}}""")))
    }
}
