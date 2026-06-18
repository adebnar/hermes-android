package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(OkHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @Test fun sessions_parses_list_and_sends_token() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[{"session_id":"s1","title":"First","model":"opus","provider":"anthropic","message_count":3}]}"""
        ).build())

        val list = api(serverRule.server).sessions(limit = 20, offset = 0)
        assertEquals(1, list.size)
        assertEquals("First", list[0].title)

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/sessions"))
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
    }

    @Test fun status_returns_true_on_200() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertTrue(api(serverRule.server).status())
    }
}
