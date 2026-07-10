package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondApprovalTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client) // match the real ctor; add other relaxed deps if needed

    @Test fun once_sends_choice_once_and_approved_true() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.ONCE)
        assertEquals("s1", params.captured["session_id"]!!.jsonPrimitive.content)
        assertEquals("once", params.captured["choice"]!!.jsonPrimitive.content)
        assertTrue(params.captured["approved"]!!.jsonPrimitive.boolean)
    }

    @Test fun deny_sends_choice_deny_and_approved_false() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.DENY)
        assertEquals("deny", params.captured["choice"]!!.jsonPrimitive.content)
        assertFalse(params.captured["approved"]!!.jsonPrimitive.boolean)
    }
}
