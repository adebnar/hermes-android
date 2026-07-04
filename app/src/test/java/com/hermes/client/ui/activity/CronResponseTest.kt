package com.hermes.client.ui.activity

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(role: Role, text: String = "", tools: List<ToolCall> = emptyList()) =
    ChatMessage(id = "x", role = role, text = text, tools = tools)

class CronResponseTest {
    @Test fun returns_last_assistant_text() {
        val out = cronResponse(
            listOf(msg(Role.USER, "run it"), msg(Role.ASSISTANT, "first"), msg(Role.ASSISTANT, "final answer")),
        )
        assertEquals("final answer", out)
    }

    @Test fun trims_assistant_text() {
        assertEquals("hi", cronResponse(listOf(msg(Role.ASSISTANT, "  hi  "))))
    }

    @Test fun falls_back_to_last_tool_output_when_no_assistant_text() {
        val out = cronResponse(
            listOf(
                msg(Role.USER, "go"),
                msg(Role.ASSISTANT, "", tools = listOf(ToolCall("t1", "search", ToolStatus.DONE, "42 results"))),
            ),
        )
        assertEquals("search: 42 results", out)
    }

    @Test fun truncates_long_tool_output() {
        val long = "y".repeat(CRON_RESPONSE_MAX + 50)
        val out = cronResponse(listOf(msg(Role.ASSISTANT, "", tools = listOf(ToolCall("t", "run", ToolStatus.DONE, long)))))
        assertEquals(CRON_RESPONSE_MAX + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun empty_messages_returns_placeholder() {
        assertEquals("No text output.", cronResponse(emptyList()))
    }

    @Test fun blank_assistant_and_no_tools_returns_placeholder() {
        assertEquals("No text output.", cronResponse(listOf(msg(Role.ASSISTANT, "   "))))
    }
}
