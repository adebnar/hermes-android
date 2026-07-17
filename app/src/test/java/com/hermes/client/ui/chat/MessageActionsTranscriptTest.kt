package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionsTranscriptTest {
    private fun msg(role: Role, text: String, isError: Boolean = false) =
        ChatMessage(id = "x", role = role, text = text, isError = isError)

    @Test fun empty_is_empty() {
        assertEquals("", transcriptText(emptyList()))
    }

    @Test fun labels_user_and_assistant() {
        val t = transcriptText(listOf(msg(Role.USER, "hi"), msg(Role.ASSISTANT, "hello")))
        assertEquals("You:\nhi\n\nAssistant:\nhello", t)
    }

    @Test fun skips_blank_turns() {
        val t = transcriptText(listOf(msg(Role.USER, "q"), msg(Role.ASSISTANT, "   "), msg(Role.ASSISTANT, "a")))
        assertEquals("You:\nq\n\nAssistant:\na", t)
    }

    @Test fun error_labelled_error_and_system_labelled_system() {
        assertTrue(transcriptText(listOf(msg(Role.ASSISTANT, "boom", isError = true))).startsWith("Error:"))
        assertTrue(transcriptText(listOf(msg(Role.SYSTEM, "note"))).startsWith("System:"))
        assertTrue(transcriptText(listOf(msg(Role.USER, "boom", isError = true))).startsWith("Error:"))
    }

    @Test fun markdown_preserved_verbatim() {
        val t = transcriptText(listOf(msg(Role.ASSISTANT, "```kotlin\nval x = 1\n```")))
        assertTrue(t.contains("```kotlin"))
        assertTrue(t.contains("val x = 1"))
    }
}
