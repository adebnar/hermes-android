package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageActionsTest {
    private fun m(id: String, role: Role, text: String) = ChatMessage(id = id, role = role, text = text)

    @Test fun returns_last_user_text_ignoring_trailing_assistant() {
        val msgs = listOf(
            m("u0", Role.USER, "first"),
            m("a0", Role.ASSISTANT, "answer 0"),
            m("u1", Role.USER, "second"),
            m("a1", Role.ASSISTANT, "answer 1"),
        )
        assertEquals("second", lastUserMessageText(msgs))
    }
    @Test fun null_when_no_user_message() {
        assertNull(lastUserMessageText(listOf(m("a0", Role.ASSISTANT, "hi"), m("s0", Role.SYSTEM, "sys"))))
        assertNull(lastUserMessageText(emptyList()))
    }
    @Test fun blank_last_user_returns_null() {
        assertNull(lastUserMessageText(listOf(m("u0", Role.USER, "   "))))
    }
    @Test fun picks_the_last_user_when_several() {
        val msgs = listOf(m("u0", Role.USER, "a"), m("u1", Role.USER, "b"), m("u2", Role.USER, "c"))
        assertEquals("c", lastUserMessageText(msgs))
    }
}
