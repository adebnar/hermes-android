package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    @Test fun submit_sends_prompt_submit_with_text_and_session() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns JsonPrimitive("ok")
        val repo = ChatRepository(client)

        repo.submit(sessionId = "s1", text = "hello")

        coVerify {
            client.call("prompt.submit", match { it["text"]!!.toString().contains("hello") })
        }
    }

    // Regression: a new chat created with no profile is bound to the gateway's DEFAULT profile,
    // so its messages land in a db the (active-profile-scoped) session list never scans → the chat
    // is invisible in both the Android and Desktop apps. session.create MUST carry the active profile.
    @Test fun createSession_passes_active_profile() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        val id = repo.createSession(profile = "acme")

        assertEquals("abc", id)
        coVerify { client.call("session.create", match { it["profile"]?.jsonPrimitive?.content == "acme" }) }
    }

    @Test fun createSession_omits_blank_profile() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns buildJsonObject { put("session_id", "abc") }
        val repo = ChatRepository(client)

        repo.createSession(profile = null)

        coVerify { client.call("session.create", match { !it.containsKey("profile") }) }
    }
}
