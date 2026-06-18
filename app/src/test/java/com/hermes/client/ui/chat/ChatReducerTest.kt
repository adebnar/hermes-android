package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun ev(type: String, session: String = "s1", build: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {}) =
        ServerEvent(type, session, buildJsonObject { put("session_id", session); build() })

    @Test fun start_delta_complete_builds_one_assistant_message() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("message.start") { put("message_id", "a1") })
        s = reduce(s, ev("message.delta") { put("text","Hel") })
        s = reduce(s, ev("message.delta") { put("text","lo") })
        s = reduce(s, ev("message.complete") { put("text","Hello") })
        assertEquals(1, s.messages.size)
        assertEquals(Role.ASSISTANT, s.messages[0].role)
        assertEquals("Hello", s.messages[0].text)
        assertTrue(!s.messages[0].isStreaming)
        assertTrue(!s.isGenerating)
    }

    @Test fun tool_events_attach_to_current_turn() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("message.start") { put("message_id", "a1") })
        s = reduce(s, ev("tool.start") { put("tool_id", "t1"); put("name","search") })
        s = reduce(s, ev("tool.complete") { put("tool_id", "t1"); put("result", "found") })
        val tools = s.messages.last().tools
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
        assertEquals(ToolStatus.DONE, tools[0].status)
        assertEquals("found", tools[0].output)
    }

    @Test fun approval_request_sets_pending() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("approval.request") { put("prompt", "Run rm -rf?") })
        assertEquals("Run rm -rf?", s.pendingApproval?.prompt)
    }

    @Test fun thinking_delta_accumulates() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("message.start") { put("message_id", "a1") })
        s = reduce(s, ev("reasoning.delta") { put("text","hmm ") })
        s = reduce(s, ev("reasoning.delta") { put("text","ok") })
        assertEquals("hmm ok", s.messages.last().thinking)
    }

    // T8b: tool.complete arriving AFTER message.complete must still update the tool card.
    @Test fun late_tool_complete_after_message_complete_is_not_dropped() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("message.start") { put("message_id", "a1") })
        s = reduce(s, ev("tool.start") { put("tool_id", "t1"); put("name","search") })
        s = reduce(s, ev("message.complete") { put("text","done") })
        // At this point the assistant message is no longer streaming.
        s = reduce(s, ev("tool.complete") { put("tool_id", "t1"); put("result", "done") })
        val tools = s.messages.last().tools
        assertEquals(1, tools.size)
        assertEquals(ToolStatus.DONE, tools[0].status)
        assertEquals("done", tools[0].output)
    }

    // I3: markInterrupted closes the streaming message and clears isGenerating.
    @Test fun markInterrupted_with_streaming_message() {
        var s = ChatUiState.empty()
        s = reduce(s, ev("message.start") { put("message_id", "a1") })
        s = reduce(s, ev("message.delta") { put("text","partial") })
        assertTrue(s.isGenerating)
        assertTrue(s.messages.last().isStreaming)

        val interrupted = s.markInterrupted()
        assertFalse(interrupted.isGenerating)
        assertFalse(interrupted.messages.last().isStreaming)
        assertTrue(interrupted.messages.last().interrupted)
    }

    @Test fun markInterrupted_without_streaming_message_still_clears_isGenerating() {
        // Build a state with isGenerating=true but no streaming message.
        val s = ChatUiState.empty().copy(isGenerating = true)
        val result = s.markInterrupted()
        assertFalse(result.isGenerating)
        assertEquals(s.messages, result.messages) // nothing else changed
    }
}
