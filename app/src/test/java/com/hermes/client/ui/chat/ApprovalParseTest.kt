package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalParseTest {
    private fun approvalEvent(payload: JsonObject) = ServerEvent("approval.request", "s1", payload)

    @Test fun parses_command_description_patterns_and_allow_permanent() {
        val e = approvalEvent(buildJsonObject {
            put("session_id", "s1")
            put("command", "rm -rf /tmp/x")
            put("description", "recursive delete")
            putJsonArray("pattern_keys") { add("recursive delete"); add("force") }
            put("allow_permanent", true)
        })
        val state = ChatUiState().reduce(e)
        val req = state.pendingApproval!!
        assertEquals("rm -rf /tmp/x", req.command)
        assertEquals("recursive delete", req.description)
        assertEquals(listOf("recursive delete", "force"), req.patternKeys)
        assertTrue(req.allowPermanent)
    }

    @Test fun missing_fields_degrade_safely() {
        val req = ChatUiState().reduce(approvalEvent(buildJsonObject { put("session_id", "s1") })).pendingApproval!!
        assertEquals("", req.command)
        assertEquals("", req.description)
        assertEquals(emptyList<String>(), req.patternKeys)
        assertFalse(req.allowPermanent) // safe default: no "Always"
    }

    @Test fun single_pattern_key_is_used_when_array_absent() {
        val req = ChatUiState().reduce(approvalEvent(buildJsonObject {
            put("session_id", "s1"); put("pattern_key", "force push")
        })).pendingApproval!!
        assertEquals(listOf("force push"), req.patternKeys)
    }
}
