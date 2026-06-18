package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.SessionDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test fun session_dto_maps_to_domain() {
        val s = SessionDto(sessionId = "s1", title = "Hi", model = "opus", messageCount = 2).toDomain()
        assertEquals("s1", s.id)
        assertEquals("Hi", s.title)
        assertEquals(2, s.messageCount)
    }

    @Test fun message_dto_maps_role_and_text() {
        val m = MessageDto(id = "m1", role = "assistant", content = "hello").toDomain()
        assertEquals(Role.ASSISTANT, m.role)
        assertEquals("hello", m.text)
        assertEquals(false, m.isStreaming)
    }
}
